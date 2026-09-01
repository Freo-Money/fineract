
/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.loanaccount.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.loanaccount.data.ScheduleGeneratorDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.exception.LoanPartPaymentException;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanApplicationTerms;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.PaymentPeriodsInOneYearCalculator;
import org.apache.fineract.portfolio.loanaccount.mapper.LoanTermVariationsMapper;
import org.apache.fineract.portfolio.loanproduct.domain.PartPaymentRecalculationStrategy;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductRoundingModeService;

/**
 * Re-amortises the repayment schedule of a cumulative loan after a part-payment.
 * <p>
 * This deliberately does <b>not</b> go through the interest-recalculation machinery
 * ({@code LoanScheduleService#regenerateRepaymentScheduleWithInterestRecalculation}). That path is unusable here for
 * two independent reasons:
 * <ol>
 * <li>it is a no-op unless the product has {@code interest_recalculation_enabled}, which is switched off for every
 * product we run;</li>
 * <li>even with the flag on, {@code REDUCE_EMI_AMOUNT} / {@code REDUCE_NUMBER_OF_INSTALLMENTS} only fire on the portion
 * of a payment that exceeds everything currently due (the {@code unprocessed} amount in
 * {@code AbstractCumulativeLoanScheduleGenerator#handleRecalculationForTransactions}). A part-payment that is smaller
 * than the current installment is fully absorbed as an in-advance payment, so the strategy never runs and the schedule
 * is left untouched.</li>
 * </ol>
 * Instead the remaining schedule is re-amortised in place, at the loan's own interest rate and day-count convention, by
 * reusing the loan's own primitives ({@link LoanApplicationTerms#calculateTotalInterestForPeriod} and
 * {@link LoanApplicationTerms#pmtForInstallment}) so the arithmetic stays consistent with how the schedule was
 * originally generated.
 * <p>
 * The rules applied are:
 * <ul>
 * <li>Installments due on or before the part-payment date are left alone.</li>
 * <li>The period the part-payment falls into is <em>split at the payment date</em>. A new installment is inserted that
 * runs from the period's start to the part-payment date and carries only the interest that actually accrued over those
 * days on the balance outstanding at the time, plus the principal the payment leaves over. The customer therefore pays
 * interest up to the day they paid, not up to the next due date.</li>
 * <li>Because that installment falls due exactly on the part-payment date, the repayment strategy treats the payment as
 * an on-time payment of it rather than as an advance payment, so the transaction is split into an interest portion and
 * a principal portion instead of going entirely to principal.</li>
 * <li>The rest of the period (part-payment date -&gt; original due date) and every installment after it are
 * re-amortised over the reduced balance, either keeping the due dates and lowering the instalment amount
 * ({@code REDUCED_EMI}) or keeping the instalment amount and dropping trailing installments
 * ({@code REDUCED_TENURE}).</li>
 * <li>A further part-payment on a date an earlier one already closed a period on gets an installment of its own too,
 * spanning no days and carrying no interest - the interest to that date has already been charged. Skipping it would
 * both lose the row and drop that payment's principal out of the schedule.</li>
 * <li>A payment that does not exceed the interest accrued to the payment date repays no principal at all, so there is
 * nothing to re-amortise and the schedule would be rewritten to say the same thing it already said. It is refused
 * ({@value #ERROR_AMOUNT_NOT_ABOVE_ACCRUED_INTEREST}) rather than silently falling through as an advance payment.</li>
 * <li>A payment that leaves no principal outstanding once that accrued interest is settled is a payoff, not a
 * part-payment, and is refused ({@value #ERROR_AMOUNT_CLEARS_OUTSTANDING_PRINCIPAL}) in favour of the foreclosure
 * command. Both bounds are exposed as {@link #validatePartPaymentAmount} so a caller can apply them before any of the
 * payment is executed.</li>
 * <li>The split conserves principal: the inserted installment plus the re-amortised ones still add up to the principal
 * that was outstanding, so the schedule total still equals the disbursed principal, which the loan summary relies
 * on.</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class PartPaymentScheduleReamortizer {

    public static final String ERROR_AMOUNT_NOT_ABOVE_ACCRUED_INTEREST = "error.msg.loan.part.payment.amount.must.be.greater.than.accrued.interest";
    public static final String ERROR_AMOUNT_CLEARS_OUTSTANDING_PRINCIPAL = "error.msg.loan.partpayment.exceeds.outstanding.principal";

    private final LoanTermVariationsMapper loanTermVariationsMapper;
    private final LoanProductRoundingModeService loanProductRoundingModeService;
    private final PaymentPeriodsInOneYearCalculator paymentPeriodsInOneYearCalculator;

    /**
     * The interest accrued on the outstanding principal from the start of the period the part-payment falls into up to
     * the part-payment date - the amount the payment has to clear before any of it can come off principal.
     * <p>
     * Side-effect free: nothing on the loan or its schedule is touched, so this can be used as a pre-flight check
     * before any part of the payment is executed. It is the same arithmetic {@link #reamortize} charges on the
     * installment it inserts at the payment date, so the figure a caller validates against is exactly the figure the
     * rewritten schedule would carry.
     *
     * @return zero when the payment date is on or after the final due date - there is no open period left for interest
     *         to accrue over, and {@link #reamortize} leaves the schedule alone in that case
     */
    public Money accruedInterestTo(final Loan loan, final ScheduleGeneratorDTO generatorDTO, final LocalDate partPaymentDate) {
        final List<LoanRepaymentScheduleInstallment> schedule = orderedInstallments(loan);
        final int currentIdx = indexOfCurrentInstallment(schedule, partPaymentDate);
        if (currentIdx < 0) {
            return Money.zero(loan.getCurrency());
        }
        return currentPeriod(loan, generatorDTO, schedule, currentIdx, partPaymentDate).accruedInterest();
    }

    /**
     * Applies both bounds a part-payment has to sit between, as at the payment date:
     * <ul>
     * <li>it has to exceed the interest accrued to that date, otherwise it repays no principal at all;</li>
     * <li>it has to leave principal still outstanding, otherwise it is a payoff and belongs to the foreclosure
     * command.</li>
     * </ul>
     * Meant to be called before the payment is executed - it neither reads nor writes anything the execution depends
     * on, so a rejected request leaves no transaction, no rewritten schedule, no updated summary and no accounting
     * entries behind, rather than relying on the surrounding transaction to roll half-applied work back.
     * {@link #reamortize} applies both rules again on the figures it has already computed, so neither guard can be
     * bypassed by a caller that skips this method.
     *
     * @throws LoanPartPaymentException
     *             when the payment is less than or equal to the interest accrued to the payment date, or when it would
     *             leave no principal outstanding
     */
    public void validatePartPaymentAmount(final Loan loan, final ScheduleGeneratorDTO generatorDTO, final LocalDate partPaymentDate,
            final BigDecimal partPaymentAmount) {
        final Money partPayment = Money.of(loan.getCurrency(), partPaymentAmount);
        final List<LoanRepaymentScheduleInstallment> schedule = orderedInstallments(loan);
        final int currentIdx = indexOfCurrentInstallment(schedule, partPaymentDate);
        if (currentIdx < 0) {
            // The payment date is on or after the final due date, so there is no open period and nothing to
            // re-amortise; {@link #reamortize} leaves the schedule alone and the payment is processed as an ordinary
            // repayment, which has its own overpayment handling. Neither rule has anything to bite on here.
            return;
        }
        final CurrentPeriod period = currentPeriod(loan, generatorDTO, schedule, currentIdx, partPaymentDate);
        validateAmountExceedsAccruedInterest(loan, partPaymentDate, partPayment, period.accruedInterest());
        validateLeavesPrincipalOutstanding(loan, partPayment, period.balanceAtPeriodStart(), period.accruedInterest());
    }

    /**
     * @return {@code true} when the schedule was actually re-amortised
     * @throws LoanPartPaymentException
     *             when the payment does not exceed the interest accrued to the payment date, or when it would leave no
     *             principal outstanding - that is a payoff and belongs to the foreclosure command, which charges
     *             interest to the date and applies the foreclosure charges
     */
    public boolean reamortize(final Loan loan, final ScheduleGeneratorDTO generatorDTO, final LocalDate partPaymentDate,
            final BigDecimal partPaymentAmount, final PartPaymentRecalculationStrategy strategy) {

        final MonetaryCurrency currency = loan.getCurrency();
        final Money partPayment = Money.of(currency, partPaymentAmount);
        if (!partPayment.isGreaterThanZero()) {
            return false;
        }

        final List<LoanRepaymentScheduleInstallment> schedule = orderedInstallments(loan);
        final int currentIdx = indexOfCurrentInstallment(schedule, partPaymentDate);
        if (currentIdx < 0) {
            // The part-payment lands on or after the final due date, so there is nothing left to re-amortise. The
            // payment still reduces the balance through normal transaction processing.
            log.debug("Loan {}: part-payment on {} leaves no future installments to re-amortise", loan.getId(), partPaymentDate);
            return false;
        }

        // The period the part-payment falls into, and everything after it. A part-payment is rejected while any
        // installment is overdue, so the principal still scheduled from here on is the balance interest accrues on.
        final LoanRepaymentScheduleInstallment current = schedule.get(currentIdx);
        final List<LoanRepaymentScheduleInstallment> remaining = schedule.subList(currentIdx, schedule.size());

        final CurrentPeriod period = currentPeriod(loan, generatorDTO, schedule, currentIdx, partPaymentDate);
        final LoanApplicationTerms terms = period.terms();
        final MathContext mc = period.mc();
        final Money balanceAtPeriodStart = period.balanceAtPeriodStart();
        final Money accruedInterest = period.accruedInterest();

        // Both bounds again on the figures just computed. Normally already refused by validatePartPaymentAmount before
        // the transaction was built; repeated here so the rules hold for any caller of the re-amortiser.
        validateAmountExceedsAccruedInterest(loan, partPaymentDate, partPayment, accruedInterest);
        validateLeavesPrincipalOutstanding(loan, partPayment, balanceAtPeriodStart, accruedInterest);

        final Money principalPrepaid = partPayment.minus(accruedInterest);
        Money balance = balanceAtPeriodStart.minus(principalPrepaid);

        // What the customer was billed for the period they are in - the instalment REDUCED_TENURE keeps them on.
        final Money billedInstalmentAmount = current.getPrincipal(currency).plus(current.getInterestCharged(currency));
        final Money instalmentAmount = resolveInstalmentAmount(terms, strategy, balance, remaining.size(), billedInstalmentAmount, mc);

        // Inserting the stub pushes every following installment one number along. Keeping the period numbers in step
        // matters because the interest calculation treats period 1 specially (broken first period, grace) - but only
        // when the stub actually charged that interest, otherwise the special treatment still belongs to the remainder.
        final int firstPeriodNumber = current.getInstallmentNumber() + (period.accruesInterest() ? 1 : 0);

        // The new amounts are worked out in full before anything is written, so a schedule that cannot be amortised
        // leaves the loan exactly as it was rather than half-rewritten.
        final List<ReamortisedPeriod> reamortised = new ArrayList<>();
        LocalDate fromDate = partPaymentDate;
        for (int i = 0; i < remaining.size(); i++) {
            final LoanRepaymentScheduleInstallment installment = remaining.get(i);
            final Money interest = interestForPeriod(terms, balance, firstPeriodNumber + i, fromDate, installment.getDueDate(), mc);
            Money principal = instalmentAmount.minus(interest);
            if (i == remaining.size() - 1 || principal.isGreaterThan(balance)) {
                // The closing installment settles whatever is left, which also absorbs the rounding residue left by
                // the annuity calculation.
                principal = balance;
            }
            if (principal.isLessThanZero()) {
                // The instalment does not even cover the interest for this period; re-amortising would produce
                // negative amortisation, so the schedule is left untouched.
                log.warn("Loan {}: part-payment re-amortisation skipped, instalment {} does not cover its interest", loan.getId(),
                        installment.getInstallmentNumber());
                return false;
            }

            reamortised.add(new ReamortisedPeriod(installment, fromDate, principal, interest));
            balance = balance.minus(principal);
            if (!balance.isGreaterThanZero()) {
                // REDUCED_TENURE: the balance is cleared early, so the remaining installments fall away.
                break;
            }
            fromDate = installment.getDueDate();
        }

        final List<LoanRepaymentScheduleInstallment> retained = new ArrayList<>(schedule.subList(0, currentIdx));
        // Falls due on the part-payment date itself, which is what makes the payment an on-time payment of this
        // installment: the repayment strategy then settles its interest before its principal, so the transaction
        // carries an interest portion instead of being absorbed as an advance payment of principal. Every part-payment
        // gets one, including same-day repeats whose period is zero-length and whose interest is therefore zero - the
        // stub is what holds their principal in the schedule.
        final LoanRepaymentScheduleInstallment stub = new LoanRepaymentScheduleInstallment(loan, current.getInstallmentNumber(),
                period.periodStart(), partPaymentDate, principalPrepaid.getAmount(), accruedInterest.getAmount(), BigDecimal.ZERO,
                BigDecimal.ZERO, false, null);
        loan.addLoanRepaymentScheduleInstallment(stub);
        retained.add(stub);
        reamortised.forEach(p -> {
            p.installment().updateFromDate(p.fromDate());
            p.installment().updatePrincipal(p.principal().getAmount());
            p.installment().updateInterestCharged(p.interest().getAmount());
            retained.add(p.installment());
        });

        // Anything the re-amortisation did not reach is dropped (REDUCED_TENURE). Matched by identity, because the
        // installment numbers are only rewritten afterwards and the stub temporarily shares one.
        final Set<LoanRepaymentScheduleInstallment> retainedInstallments = Collections.newSetFromMap(new IdentityHashMap<>());
        retainedInstallments.addAll(retained);
        loan.getRepaymentScheduleInstallments().removeIf(i -> !retainedInstallments.contains(i));
        renumber(retained);
        // The stub was appended to the loan's collection, so the in-memory list is no longer in schedule order.
        // Transaction reprocessing walks that list as-is when it allocates a payment across installments, so it has to
        // be put back in order here rather than relying on the @OrderBy that only applies when the loan is re-read.
        loan.getRepaymentScheduleInstallments().sort(Comparator.comparing(LoanRepaymentScheduleInstallment::getInstallmentNumber));
        loan.updateLoanScheduleDependentDerivedFields();
        return true;
    }

    private Money resolveInstalmentAmount(final LoanApplicationTerms terms, final PartPaymentRecalculationStrategy strategy,
            final Money balance, final int remainingPeriods, final Money billedInstalmentAmount, final MathContext mc) {
        // A null strategy means the caller had nothing configured to pass, so it resolves the same way
        // LoanAccountDomainServiceJpa#resolvePartPaymentStrategy resolves it - to the product-wide default.
        final PartPaymentRecalculationStrategy effectiveStrategy = strategy == null ? PartPaymentRecalculationStrategy.DEFAULT : strategy;
        if (effectiveStrategy.isReducedTenure()) {
            // Keep paying the instalment the customer is used to; the tenure absorbs the part-payment instead.
            return billedInstalmentAmount;
        }
        // Keep the tenure and solve for the instalment that amortises the reduced balance over the remaining periods.
        // pmtForInstallment short-circuits on a preset EMI, and derives the number of periods from the terms.
        terms.setFixedEmiAmount(null);
        terms.updateNumberOfRepayments(remainingPeriods);
        return terms.pmtForInstallment(paymentPeriodsInOneYearCalculator, balance, 1, mc);
    }

    /**
     * The single place the accrual for the open period is worked out, so the amount validated up-front and the amount
     * the inserted installment is built with can never drift apart.
     */
    private CurrentPeriod currentPeriod(final Loan loan, final ScheduleGeneratorDTO generatorDTO,
            final List<LoanRepaymentScheduleInstallment> schedule, final int currentIdx, final LocalDate partPaymentDate) {

        final MonetaryCurrency currency = loan.getCurrency();
        // The period the part-payment falls into, and everything after it. A part-payment is rejected while any
        // installment is overdue, so the principal still scheduled from here on is the balance interest accrues on.
        final LoanRepaymentScheduleInstallment current = schedule.get(currentIdx);
        final Money balanceAtPeriodStart = schedule.subList(currentIdx, schedule.size()).stream().map(i -> i.getPrincipal(currency))
                .reduce(Money.zero(currency), Money::plus);

        final MathContext mc = loanProductRoundingModeService.resolveMathContext(loan.loanProduct().getId());
        final LoanApplicationTerms terms = loanTermVariationsMapper.constructLoanApplicationTerms(generatorDTO, loan);
        terms.setRoundingModeConfig(loanProductRoundingModeService.resolveAll(loan.loanProduct().getId()));

        final LocalDate periodStart = current.getFromDate();
        // A further part-payment on the same date as an earlier one starts from a period that has not run for a day
        // yet: the interest up to that date was already charged by the installment the earlier payment closed, so this
        // one is pure principal. It still gets its own (zero-length) installment - without it the payment would drift
        // into the next period as an advance payment and its principal would drop out of the schedule.
        final boolean accruesInterest = DateUtils.isAfter(partPaymentDate, periodStart);
        final Money accruedInterest = accruesInterest
                ? interestForPeriod(terms, balanceAtPeriodStart, current.getInstallmentNumber(), periodStart, partPaymentDate, mc)
                : Money.zero(currency);

        return new CurrentPeriod(terms, mc, periodStart, accruesInterest, balanceAtPeriodStart, accruedInterest);
    }

    private void validateAmountExceedsAccruedInterest(final Loan loan, final LocalDate partPaymentDate, final Money partPayment,
            final Money accruedInterest) {
        if (partPayment.isGreaterThan(accruedInterest)) {
            return;
        }
        throw new LoanPartPaymentException(ERROR_AMOUNT_NOT_ABOVE_ACCRUED_INTEREST,
                "Part-payment of " + partPayment.getAmount() + " on loan " + loan.getId() + " must be greater than the interest of "
                        + accruedInterest.getAmount() + " accrued up to " + partPaymentDate + ".",
                partPayment.getAmount(), accruedInterest.getAmount(), loan.getId(), partPaymentDate);
    }

    /**
     * Refuses a part-payment that, once the interest accrued to the payment date is settled, leaves no principal
     * outstanding. That is a payoff rather than a part-payment: letting it through would close the loan without the
     * foreclosure charges and closure handling the foreclosure command applies, and would overpay the customer by
     * whatever future interest the quoted balance included.
     * <p>
     * This is the precise threshold as at the payment date. The coarser ceiling applied up-front by
     * {@code LoanPartPaymentValidator#validateAmountWithinTotalOutstanding} is the whole schedule's outstanding, which
     * still carries the interest of periods that have not elapsed and so sits above this one.
     */
    private void validateLeavesPrincipalOutstanding(final Loan loan, final Money partPayment, final Money balanceAtPeriodStart,
            final Money accruedInterest) {
        if (balanceAtPeriodStart.minus(partPayment.minus(accruedInterest)).isGreaterThanZero()) {
            return;
        }
        throw new LoanPartPaymentException(ERROR_AMOUNT_CLEARS_OUTSTANDING_PRINCIPAL,
                "Part-payment of " + partPayment.getAmount() + " clears the entire outstanding principal of "
                        + balanceAtPeriodStart.getAmount() + " on loan " + loan.getId()
                        + ". Use the foreclosure command to close the loan.",
                partPayment.getAmount(), balanceAtPeriodStart.getAmount(), loan.getId());
    }

    private Money interestForPeriod(final LoanApplicationTerms terms, final Money balance, final int periodNumber,
            final LocalDate periodStart, final LocalDate periodEnd, final MathContext mc) {
        return terms.calculateTotalInterestForPeriod(paymentPeriodsInOneYearCalculator, BigDecimal.ZERO, periodNumber, mc, balance.zero(),
                balance, periodStart, periodEnd).interest();
    }

    private List<LoanRepaymentScheduleInstallment> orderedInstallments(final Loan loan) {
        // Everything is kept, including down-payment and additional installments: they are all part of the schedule
        // that has to stay contiguously numbered, and they always fall before the part-payment date anyway.
        return loan.getRepaymentScheduleInstallments().stream()
                .sorted(Comparator.comparing(LoanRepaymentScheduleInstallment::getInstallmentNumber)).toList();
    }

    /**
     * The installment the customer is currently within - the first one falling due after the part-payment date.
     */
    private int indexOfCurrentInstallment(final List<LoanRepaymentScheduleInstallment> schedule, final LocalDate partPaymentDate) {
        for (int i = 0; i < schedule.size(); i++) {
            if (DateUtils.isAfter(schedule.get(i).getDueDate(), partPaymentDate)) {
                return i;
            }
        }
        return -1;
    }

    private void renumber(final List<LoanRepaymentScheduleInstallment> installments) {
        for (int i = 0; i < installments.size(); i++) {
            installments.get(i).updateInstallmentNumber(i + 1);
        }
    }

    private record ReamortisedPeriod(LoanRepaymentScheduleInstallment installment, LocalDate fromDate, Money principal, Money interest) {
    }

    /**
     * The open period the part-payment lands in, and the terms the rest of the schedule is re-amortised with.
     */
    private record CurrentPeriod(LoanApplicationTerms terms, MathContext mc, LocalDate periodStart, boolean accruesInterest,
            Money balanceAtPeriodStart, Money accruedInterest) {
    }
}
