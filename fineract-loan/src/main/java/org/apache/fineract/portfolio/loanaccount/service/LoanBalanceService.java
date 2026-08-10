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

import jakarta.persistence.FlushModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.persistence.FlushModeHandler;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.common.domain.DaysInMonthType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanDisbursementDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanInstallmentCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleProcessingWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionComparator;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.helper.ForeclosureChargeHelper;
import org.apache.fineract.portfolio.loanproduct.domain.CreditAllocationTransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LoanBalanceService {

    private static final Logger LOG = LoggerFactory.getLogger(LoanBalanceService.class);

    // calculateTotalOverpayment runs on every summary refresh, so a loan whose mismatch is baked into data would
    // re-fire the alert-grade suppression log on every touch and flood log-based alerting. Remember which loans
    // (per tenant) have already been reported this JVM and log follow-ups at DEBUG. Only defective loans ever enter
    // this set, so it stays tiny.
    private static final Set<String> SUPPRESSION_ALERTED_LOANS = ConcurrentHashMap.newKeySet();

    private final CapitalizedIncomeBalanceService capitalizedIncomeBalanceService;
    private final FlushModeHandler flushModeHandler;
    private final LoanTransactionRepository loanTransactionRepository;
    private final ForeclosureChargeHelper foreclosureChargeHelper;

    public Money calculateTotalOverpayment(final Loan loan) {
        Money totalPaidInRepayments = loan.getTotalPaidInRepayments();

        final MonetaryCurrency currency = loan.getCurrency();
        Money cumulativeTotalPaidOnInstallments = Money.zero(currency);
        List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        for (final LoanRepaymentScheduleInstallment scheduledRepayment : installments) {
            cumulativeTotalPaidOnInstallments = cumulativeTotalPaidOnInstallments
                    .plus(scheduledRepayment.getPrincipalCompleted(currency).plus(scheduledRepayment.getInterestPaid(currency)))
                    .plus(scheduledRepayment.getFeeChargesPaid(currency)).plus(scheduledRepayment.getPenaltyChargesPaid(currency));
        }

        for (final LoanTransaction loanTransaction : loan.getLoanTransactions()) {
            if (loanTransaction.isReversed()) {
                continue;
            }
            if (loanTransaction.isRefund() || loanTransaction.isRefundForActiveLoan()) {
                totalPaidInRepayments = totalPaidInRepayments.minus(loanTransaction.getAmount(currency));
            } else if (loanTransaction.isCreditBalanceRefund()) {
                if (loanTransaction.getPrincipalPortion(currency).isZero()) {
                    totalPaidInRepayments = totalPaidInRepayments.minus(loanTransaction.getOverPaymentPortion(currency));
                }
            } else if (loanTransaction.isChargeback()) {
                if (loanTransaction.getPrincipalPortion(currency).isZero() && loan.getCreditAllocationRules().stream() //
                        .filter(car -> car.getTransactionType().equals(CreditAllocationTransactionType.CHARGEBACK)) //
                        .findAny() //
                        .isEmpty()) {
                    totalPaidInRepayments = totalPaidInRepayments.minus(loanTransaction.getOverPaymentPortion(currency));
                }
            }
        }

        // if total paid in transactions doesn't match repayment schedule then there's
        // an overpayment.
        final Money overpayment = totalPaidInRepayments.minus(cumulativeTotalPaidOnInstallments);

        // A fully-settled foreclosure normally has no overpayment, so suppress any spurious computed difference.
        // But when a same-day repayment (or any extra payment) genuinely overpays the loan, the transaction
        // processor stamps a real overpayment portion on a transaction - in that case keep the overpayment so the
        // loan can transition to OVERPAID instead of silently swallowing it.
        if (!overpayment.isZero() && loan.isForeclosure() && loan.getSummary() != null
                && loan.getSummary().getTotalOutstanding(currency).isZero() && !hasActualOverpaymentPortion(loan, currency)) {
            // Alert-grade, not noise: this state means the transaction ledger and the repayment schedule disagree
            // with no allocation trail - a bookkeeping defect (the double-attached foreclosure payment was one such
            // source), never genuine customer money. It should NEVER fire in a healthy system, so wire log-based
            // alerting to the stable FORECLOSURE_OVERPAYMENT_SUPPRESSED marker and investigate every occurrence.
            // Both ledger totals are logged so the mismatch is diagnosable without replaying the loan.
            if (isFirstSuppressionAlertForLoan(loan)) {
                LOG.error("FORECLOSURE_OVERPAYMENT_SUPPRESSED loan {}: computed overpayment {} zeroed (transactions net {} vs schedule"
                        + " absorbed {}). Schedule is settled and no transaction carries an overpayment portion, so the difference"
                        + " is a transaction/schedule mismatch, not customer money. Investigate this loan's transaction" + " bookkeeping.",
                        loan.getId(), overpayment.getAmount(), totalPaidInRepayments.getAmount(),
                        cumulativeTotalPaidOnInstallments.getAmount());
            } else {
                LOG.debug(
                        "FORECLOSURE_OVERPAYMENT_SUPPRESSED (repeat) loan {}: computed overpayment {} zeroed (transactions net {} vs"
                                + " schedule absorbed {}).",
                        loan.getId(), overpayment.getAmount(), totalPaidInRepayments.getAmount(),
                        cumulativeTotalPaidOnInstallments.getAmount());
            }
            return Money.zero(currency);
        }
        return overpayment;
    }

    private boolean isFirstSuppressionAlertForLoan(final Loan loan) {
        if (loan.getId() == null) {
            return true;
        }
        final FineractPlatformTenant tenant = ThreadLocalContextUtil.getTenant();
        final String key = (tenant != null ? tenant.getTenantIdentifier() : "") + ":" + loan.getId();
        return SUPPRESSION_ALERTED_LOANS.add(key);
    }

    private boolean hasActualOverpaymentPortion(final Loan loan, final MonetaryCurrency currency) {
        for (final LoanTransaction loanTransaction : loan.getLoanTransactions()) {
            if (loanTransaction.isNotReversed() && loanTransaction.getOverPaymentPortion(currency).isGreaterThanZero()) {
                return true;
            }
        }
        return false;
    }

    public boolean isOverPaid(final Loan loan) {
        return calculateTotalOverpayment(loan).isGreaterThanZero();
    }

    public void updateLoanSummaryDerivedFields(final Loan loan) {
        flushModeHandler.withFlushMode(FlushModeType.COMMIT, () -> {
            if (loan.isNotDisbursed()) {
                if (loan.getSummary() != null) {
                    loan.getSummary().zeroFields();
                }
                loan.setTotalOverpaid(null);
            } else {
                refreshSummaryAndBalancesForDisbursedLoan(loan);
            }
        });
    }

    public void refreshSummaryAndBalancesForDisbursedLoan(final Loan loan) {
        // Summary FIRST, then total_overpaid. calculateTotalOverpayment's foreclosure suppression asks the summary
        // whether the schedule is settled, so the summary must reflect the installments as they are now - with the
        // previous ordering that question was answered with the prior refresh's snapshot, and the first refresh after
        // any schedule change decided suppression on stale data. Safe to reorder: updateSummary derives everything
        // from the installments, charges and capitalized income passed to it and never reads total_overpaid.
        final Money principal = loan.getLoanRepaymentScheduleDetail().getPrincipal();
        final Money capitalizedIncome = capitalizedIncomeBalanceService.calculateCapitalizedIncome(loan);
        final Money capitalizedIncomeAdjustment = capitalizedIncomeBalanceService.calculateCapitalizedIncomeAdjustment(loan);
        loan.getSummary().updateSummary(loan.getCurrency(), principal, loan.getRepaymentScheduleInstallments(), loan.getLoanCharges(),
                capitalizedIncome, capitalizedIncomeAdjustment);

        final Money overpaidBy = calculateTotalOverpayment(loan);
        loan.setTotalOverpaid(null);
        if (!overpaidBy.isLessThanZero()) {
            loan.setTotalOverpaid(overpaidBy.getAmountDefaultedToNullIfZero());
        }

        final Money recoveredAmount = calculateTotalRecoveredPayments(loan);
        loan.setTotalRecovered(recoveredAmount.getAmountDefaultedToNullIfZero());

        updateLoanOutstandingBalances(loan);
    }

    private Money calculateTotalRecoveredPayments(Loan loan) {
        // in case logic for reversing recovered payment is implemented handle
        // subtraction from totalRecoveredPayments
        final BigDecimal totalRecoveryAmount = loanTransactionRepository.calculateTotalRecoveryPaymentAmount(loan);
        return Money.of(loan.getCurrency(), totalRecoveryAmount);
    }

    public void updateLoanOutstandingBalances(Loan loan) {
        Money outstanding = Money.zero(loan.getCurrency());
        final List<LoanTransaction> loanTransactions = new ArrayList<>();
        for (final LoanTransaction transaction : loan.getLoanTransactions()) {
            if (transaction.isNotReversed() && !transaction.isNonMonetaryTransaction()) {
                loanTransactions.add(transaction);
            }
        }
        loanTransactions.sort(LoanTransactionComparator.INSTANCE);

        for (LoanTransaction loanTransaction : loanTransactions) {
            if (loanTransaction.isDisbursement() || loanTransaction.isIncomePosting() || loanTransaction.isCapitalizedIncome()) {
                outstanding = outstanding.plus(loanTransaction.getAmount(loan.getCurrency()))
                        .minus(loanTransaction.getOverPaymentPortion(loan.getCurrency()));
                loanTransaction.updateOutstandingLoanBalance(MathUtil.negativeToZero(outstanding.getAmount()));
            } else if (loanTransaction.isChargeback() || loanTransaction.isCreditBalanceRefund()) {
                Money transactionOutstanding = loanTransaction.getPrincipalPortion(loan.getCurrency());
                if (loanTransaction.isOverPaid()) {
                    // in case of advanced payment strategy and creditAllocations the full amount is
                    // recognized first
                    if (loan.getCreditAllocationRules() != null && !loan.getCreditAllocationRules().isEmpty()) {
                        Money payedPrincipal = loanTransaction.getLoanTransactionToRepaymentScheduleMappings().stream() //
                                .map(mapping -> mapping.getPrincipalPortion(loan.getCurrency())) //
                                .reduce(Money.zero(loan.getCurrency()), Money::plus);
                        transactionOutstanding = loanTransaction.getPrincipalPortion(loan.getCurrency()).minus(payedPrincipal);
                    } else {
                        // in case legacy payment strategy
                        transactionOutstanding = loanTransaction.getAmount(loan.getCurrency())
                                .minus(loanTransaction.getOverPaymentPortion(loan.getCurrency()));
                    }
                    if (transactionOutstanding.isLessThanZero()) {
                        transactionOutstanding = Money.zero(loan.getCurrency());
                    }
                }
                outstanding = outstanding.plus(transactionOutstanding);
                loanTransaction.updateOutstandingLoanBalance(MathUtil.negativeToZero(outstanding.getAmount()));
            } else if (!loanTransaction.isAccrualActivity()) {
                if (loan.getLoanInterestRecalculationDetails() != null
                        && loan.getLoanInterestRecalculationDetails().isCompoundingToBePostedAsTransaction()
                        && !loanTransaction.isRepaymentAtDisbursement()) {
                    outstanding = outstanding.minus(loanTransaction.getAmount(loan.getCurrency()));
                } else {
                    outstanding = outstanding.minus(loanTransaction.getPrincipalPortion(loan.getCurrency()));
                }
                loanTransaction.updateOutstandingLoanBalance(MathUtil.negativeToZero(outstanding.getAmount()));
            }
        }
    }

    public void updateLoanToLastDisbursalState(final Loan loan, final LoanDisbursementDetails disbursementDetail) {
        for (final LoanCharge charge : loan.getActiveCharges()) {
            if (charge.isOverdueInstallmentCharge()) {
                charge.setActive(false);
            } else if (charge.isTrancheDisbursementCharge() && disbursementDetail.getDisbursementDate()
                    .equals(charge.getTrancheDisbursementCharge().getloanDisbursementDetails().actualDisbursementDate())) {
                charge.resetToOriginal(loan.getCurrency());
            }
        }
        loan.getLoanRepaymentScheduleDetail().setPrincipal(loan.getDisbursedAmount().subtract(disbursementDetail.principal()));
        disbursementDetail.updateActualDisbursementDate(null);
        disbursementDetail.reverse();
        updateLoanSummaryDerivedFields(loan);
    }

    public Money getReceivableInterest(final Loan loan, final LocalDate tillDate) {
        Money receivableInterest = Money.zero(loan.getCurrency());
        for (final LoanTransaction transaction : loan.getLoanTransactions()) {
            if (transaction.isNotReversed() && !transaction.isRepaymentAtDisbursement() && !transaction.isDisbursement()
                    && !DateUtils.isAfter(transaction.getTransactionDate(), tillDate)) {
                if (transaction.isAccrual()) {
                    receivableInterest = receivableInterest.plus(transaction.getInterestPortion(loan.getCurrency()));
                } else if (transaction.isRepaymentLikeType() || transaction.isInterestWaiver() || transaction.isAccrualAdjustment()) {
                    receivableInterest = receivableInterest.minus(transaction.getInterestPortion(loan.getCurrency()));
                }
            }
            if (receivableInterest.isLessThanZero()) {
                receivableInterest = receivableInterest.zero();
            }
        }
        return receivableInterest;
    }

    public LoanRepaymentScheduleInstallment fetchLoanForeclosureDetail(final Loan loan, final LocalDate closureDate,
            final Map<Long, BigDecimal> mergedChargePercentages, final boolean updateCharges) {
        return fetchLoanForeclosureDetail(loan, closureDate, mergedChargePercentages, updateCharges, null);
    }

    /**
     * {@code principalBeforeReconcile}, when non-null, is the principal outstanding snapshotted before the
     * pre-foreclosure reconcile ran; percent-of-principal-outstanding foreclosure charges are computed on it so the
     * created charge equals the one the read-only template quoted (see ForeclosureChargeHelper).
     */
    public LoanRepaymentScheduleInstallment fetchLoanForeclosureDetail(final Loan loan, final LocalDate closureDate,
            final Map<Long, BigDecimal> mergedChargePercentages, final boolean updateCharges, final Money principalBeforeReconcile) {
        if (updateCharges) {
            foreclosureChargeHelper.updateForeclosureCharges(loan, mergedChargePercentages, closureDate, principalBeforeReconcile);
            refreshSummaryAndBalancesForDisbursedLoan(loan);
        }
        final MonetaryCurrency currency = loan.getCurrency();
        Money[] receivables = retrieveIncomeOutstandingTillDate(loan, closureDate);
        Money totalPrincipal = Money.of(currency, loan.getSummary().getTotalPrincipalOutstanding()).minus(receivables[3]);
        final LocalDate currentDate = DateUtils.getBusinessLocalDate();

        final LoanRepaymentScheduleInstallment foreclosureDetail = new LoanRepaymentScheduleInstallment(null, 0, currentDate, currentDate,
                totalPrincipal.getAmount(), receivables[0].getAmount(), receivables[1].getAmount(), receivables[2].getAmount(), false,
                null);

        return foreclosureDetail;
    }

    /**
     * Resolves the multiplesOf used for foreclosure rounding, or {@code null} when the loan's product does not round
     * foreclosure amounts. Single source of the eligibility rule for the template quote (applyForeclosureRounding) and
     * the actual foreclosure (applyForeclosureRoundingToLoan), so both round under identical conditions - products
     * relying on adjustInterestForRounding must not get a rounded foreclosure amount but an unrounded quote.
     */
    private Integer resolveForeclosureRoundingMultiplesOf(final Loan loan) {
        final Integer installmentAmountInMultiplesOf = loan.getLoanProductRelatedDetail().getInstallmentAmountInMultiplesOf();
        if ((installmentAmountInMultiplesOf == null || installmentAmountInMultiplesOf < 0)
                && !loan.getLoanProduct().isAdjustInterestForRounding()) {
            return null;
        }
        return LoanRoundingUtils.resolveMultiplesOfOrDefault(loan.getCurrency(), installmentAmountInMultiplesOf);
    }

    /**
     * The payoff amount foreclosure rounding would turn the given payoff into: unchanged when the product does not
     * round foreclosure amounts, otherwise rounded (or ceiled, per precloseEmiRounding) to the resolved multiplesOf.
     * Lets callers decide on the ROUNDED payoff (e.g. the zero-payoff early exit in foreCloseLoan) using exactly the
     * eligibility and rounding the actual foreclosure applies.
     */
    public Money projectForeclosureRoundedPayoff(final Loan loan, final Money payoff) {
        final Integer multiplesOf = resolveForeclosureRoundingMultiplesOf(loan);
        if (multiplesOf == null) {
            return payoff;
        }
        return loan.getLoanProduct().isPrecloseEmiRounding() ? Money.ceilToMultiplesOf(payoff, multiplesOf)
                : Money.roundToMultiplesOf(payoff, multiplesOf);
    }

    public void applyForeclosureRounding(final Loan loan, final LoanRepaymentScheduleInstallment foreclosureDetail, final Money extraFees) {
        final MonetaryCurrency currency = loan.getCurrency();
        final Integer installmentAmountInMultiplesOf = resolveForeclosureRoundingMultiplesOf(loan);
        if (installmentAmountInMultiplesOf == null) {
            return;
        }

        final Money outstandingAmount = foreclosureDetail.getPrincipalOutstanding(currency)
                .plus(foreclosureDetail.getInterestOutstanding(currency)).plus(foreclosureDetail.getFeeChargesOutstanding(currency))
                .plus(foreclosureDetail.getPenaltyChargesOutstanding(currency)).plus(extraFees == null ? Money.zero(currency) : extraFees);

        if (!outstandingAmount.isGreaterThanZero()) {
            // Over-settled quote (freed reversals meet or exceed the payoff, extraFees can be negative): rounding a
            // non-positive base would fabricate an adjustedInterest artifact; the quote is zero and the execution
            // takes the zero-payoff exit.
            return;
        }

        final boolean precloseEmiRounding = loan.getLoanProduct().isPrecloseEmiRounding();
        final Money roundedOutstandingAmount = precloseEmiRounding
                ? Money.ceilToMultiplesOf(outstandingAmount, installmentAmountInMultiplesOf)
                : Money.roundToMultiplesOf(outstandingAmount, installmentAmountInMultiplesOf);
        final BigDecimal adjustedInterestAmount = roundedOutstandingAmount.getAmount().subtract(outstandingAmount.getAmount());
        foreclosureDetail.setInterestCharged(foreclosureDetail.getInterestCharged(currency).getAmount());
        foreclosureDetail.setAdjustedInterestAmount(adjustedInterestAmount);
    }

    public void applyForeclosureRoundingToLoan(final Loan loan, final LoanRepaymentScheduleInstallment foreclosureDetail) {
        final Integer installmentAmountInMultiplesOf = resolveForeclosureRoundingMultiplesOf(loan);
        if (installmentAmountInMultiplesOf == null) {
            return;
        }
        applyForeclosureRoundingWithMultiples(loan, foreclosureDetail, loan.getCurrency(), installmentAmountInMultiplesOf);
    }

    private void applyForeclosureRoundingWithMultiples(final Loan loan, final LoanRepaymentScheduleInstallment foreclosureDetail,
            final MonetaryCurrency currency, final Integer multiplesOf) {

        final LoanRepaymentScheduleInstallment earliestUnpaidInstallment = loan.getRepaymentScheduleInstallments().stream()
                .filter(LoanRepaymentScheduleInstallment::isNotFullyPaidOff).min((i1, i2) -> i1.getDueDate().compareTo(i2.getDueDate()))
                .orElse(null);

        final boolean precloseEmiRounding = loan.getLoanProduct().isPrecloseEmiRounding();
        // Round the foreclosure payoff itself - the same base the foreclosure template rounds. The summary
        // outstanding cannot be used here: updateInstallmentsPostDate has just rebuilt the tail installment with
        // paid amounts zeroed, and same-day repayments are only re-applied later in handleForeClosureTransactions,
        // so the summary computes a different (often zero) adjustment than the one quoted to the customer.
        final Money totalOutstanding = foreclosureDetail != null ? foreclosureDetail.getPrincipalOutstanding(currency)
                .plus(foreclosureDetail.getInterestOutstanding(currency)).plus(foreclosureDetail.getFeeChargesOutstanding(currency))
                .plus(foreclosureDetail.getPenaltyChargesOutstanding(currency)) : loan.getSummary().getTotalOutstanding(currency);
        final Money roundedTotalOutstanding = precloseEmiRounding ? Money.ceilToMultiplesOf(totalOutstanding, multiplesOf)
                : Money.roundToMultiplesOf(totalOutstanding, multiplesOf);
        final BigDecimal adjustedInterestAmount = roundedTotalOutstanding.getAmount().subtract(totalOutstanding.getAmount());

        if (adjustedInterestAmount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        loan.updateAdjustedInterestAmount(adjustedInterestAmount);

        if (earliestUnpaidInstallment != null) {
            final BigDecimal currentInterestCharged = earliestUnpaidInstallment.getInterestCharged(currency).getAmount();
            earliestUnpaidInstallment.setInterestCharged(currentInterestCharged.add(adjustedInterestAmount));
            earliestUnpaidInstallment.setAdjustedInterestAmount(adjustedInterestAmount);
        }

        if (foreclosureDetail != null) {
            final BigDecimal currentForeclosureInterestCharged = foreclosureDetail.getInterestCharged(currency).getAmount();
            foreclosureDetail.setInterestCharged(currentForeclosureInterestCharged.add(adjustedInterestAmount));
            foreclosureDetail.setAdjustedInterestAmount(adjustedInterestAmount);
        }

        // The adjustment is already carried by the earliest unpaid installment, so refreshing the summary from the
        // installments is all that is needed. Deliberately NOT re-rounding the summary total here: the adjustment is
        // derived from the (net) foreclosure payoff while the summary aggregates the rebuilt (gross) schedule, and
        // when the two bases differ - same-day repayments not yet re-applied - force-rounding the summary would write
        // a total inconsistent with the installments, which the overpayment suppression check then reads stale.
        refreshSummaryAndBalancesForDisbursedLoan(loan);
    }

    public Money[] retrieveIncomeForOverlappingPeriod(final Loan loan, final LocalDate paymentDate) {
        Money[] balances = new Money[3];
        final MonetaryCurrency currency = loan.getCurrency();
        balances[0] = balances[1] = balances[2] = Money.zero(currency);
        int firstNormalInstallmentNumber = LoanRepaymentScheduleProcessingWrapper
                .fetchFirstNormalInstallmentNumber(loan.getRepaymentScheduleInstallments());
        for (final LoanRepaymentScheduleInstallment installment : loan.getRepaymentScheduleInstallments()) {
            boolean isFirstNormalInstallment = installment.getInstallmentNumber().equals(firstNormalInstallmentNumber);
            if (!installment.isDownPayment() && DateUtils.isEqual(paymentDate, installment.getDueDate())) {
                Money interest = installment.getInterestCharged(currency);
                Money fee = installment.getFeeChargesCharged(currency);
                Money penalty = installment.getPenaltyChargesCharged(currency);
                balances[0] = interest;
                balances[1] = fee;
                balances[2] = penalty;
                break;
            } else if (DateUtils.isDateInRangeExclusive(paymentDate, installment.getFromDate(), installment.getDueDate())
                    || (isFirstNormalInstallment && DateUtils.isEqual(paymentDate, installment.getFromDate()))) {
                balances = fetchInterestFeeAndPenaltyTillDate(loan, paymentDate, currency, installment, isFirstNormalInstallment);
                break;
            }
        }

        return balances;
    }

    private Money[] retrieveIncomeOutstandingTillDate(final Loan loan, final LocalDate paymentDate) {
        Money[] balances = new Money[4];
        final MonetaryCurrency currency = loan.getCurrency();
        Money interest = Money.zero(currency);
        Money paidFromFutureInstallments = Money.zero(currency);
        Money fee = Money.zero(currency);
        Money penalty = Money.zero(currency);
        int firstNormalInstallmentNumber = LoanRepaymentScheduleProcessingWrapper
                .fetchFirstNormalInstallmentNumber(loan.getRepaymentScheduleInstallments());

        for (final LoanRepaymentScheduleInstallment installment : loan.getRepaymentScheduleInstallments()) {
            boolean isFirstNormalInstallment = installment.getInstallmentNumber().equals(firstNormalInstallmentNumber);
            if (!DateUtils.isBefore(paymentDate, installment.getDueDate())) {
                interest = interest.plus(installment.getInterestOutstanding(currency));
                penalty = penalty.plus(installment.getPenaltyChargesOutstanding(currency));
                fee = fee.plus(installment.getFeeChargesOutstanding(currency));
            } else if (DateUtils.isAfter(paymentDate, installment.getFromDate())
                    || (isFirstNormalInstallment && DateUtils.isEqual(paymentDate, installment.getFromDate()))) {
                Money[] balancesForCurrentPeriod = fetchInterestFeeAndPenaltyTillDate(loan, paymentDate, currency, installment,
                        isFirstNormalInstallment);
                if (balancesForCurrentPeriod[0].isGreaterThan(balancesForCurrentPeriod[5])) {
                    interest = interest.plus(balancesForCurrentPeriod[0]).minus(balancesForCurrentPeriod[5]);
                } else {
                    paidFromFutureInstallments = paidFromFutureInstallments.plus(balancesForCurrentPeriod[5])
                            .minus(balancesForCurrentPeriod[0]);
                }
                if (balancesForCurrentPeriod[1].isGreaterThan(balancesForCurrentPeriod[3])) {
                    fee = fee.plus(balancesForCurrentPeriod[1].minus(balancesForCurrentPeriod[3]));
                } else {
                    paidFromFutureInstallments = paidFromFutureInstallments
                            .plus(balancesForCurrentPeriod[3].minus(balancesForCurrentPeriod[1]));
                }
                if (balancesForCurrentPeriod[2].isGreaterThan(balancesForCurrentPeriod[4])) {
                    penalty = penalty.plus(balancesForCurrentPeriod[2].minus(balancesForCurrentPeriod[4]));
                } else {
                    paidFromFutureInstallments = paidFromFutureInstallments.plus(balancesForCurrentPeriod[4])
                            .minus(balancesForCurrentPeriod[2]);
                }
            } else {
                paidFromFutureInstallments = paidFromFutureInstallments.plus(installment.getInterestPaid(currency))
                        .plus(installment.getPenaltyChargesPaid(currency)).plus(installment.getFeeChargesPaid(currency));
            }

        }
        balances[0] = interest;
        balances[1] = fee;
        balances[2] = penalty;
        balances[3] = paidFromFutureInstallments;
        return balances;
    }

    private Money[] fetchInterestFeeAndPenaltyTillDate(final Loan loan, final LocalDate paymentDate, final MonetaryCurrency currency,
            final LoanRepaymentScheduleInstallment installment, final boolean isFirstNormalInstallment) {
        Money penaltyForCurrentPeriod = Money.zero(loan.getCurrency());
        Money penaltyAccoutedForCurrentPeriod = Money.zero(loan.getCurrency());
        Money feeForCurrentPeriod = Money.zero(loan.getCurrency());
        Money feeAccountedForCurrentPeriod = Money.zero(loan.getCurrency());
        final DaysInMonthType daysInMonthType = loan.getLoanProductRelatedDetail().fetchDaysInMonthType();
        final int totalPeriodDays = daysInMonthType.calculateDaysInPeriod(installment.getFromDate(), installment.getDueDate());
        final int tillDays = daysInMonthType.calculateDaysInPeriod(installment.getFromDate(), paymentDate);
        Money interestForCurrentPeriod = Money.of(loan.getCurrency(), BigDecimal.valueOf(
                calculateInterestForDays(totalPeriodDays, installment.getInterestCharged(loan.getCurrency()).getAmount(), tillDays)));
        Money interestAccountedForCurrentPeriod = installment.getInterestWaived(loan.getCurrency())
                .plus(installment.getInterestPaid(loan.getCurrency()));
        for (LoanCharge loanCharge : loan.getLoanCharges()) {
            if (loanCharge.isActive() && !loanCharge.isDueAtDisbursement()) {
                boolean isDue = loanCharge.isDueInPeriod(installment.getFromDate(), paymentDate, isFirstNormalInstallment);
                if (isDue) {
                    if (loanCharge.isPenaltyCharge()) {
                        penaltyForCurrentPeriod = penaltyForCurrentPeriod.plus(loanCharge.getAmount(loan.getCurrency()));
                        penaltyAccoutedForCurrentPeriod = penaltyAccoutedForCurrentPeriod
                                .plus(loanCharge.getAmountWaived(loan.getCurrency()).plus(loanCharge.getAmountPaid(loan.getCurrency())));
                    } else {
                        feeForCurrentPeriod = feeForCurrentPeriod.plus(loanCharge.getAmount(currency));
                        feeAccountedForCurrentPeriod = feeAccountedForCurrentPeriod
                                .plus(loanCharge.getAmountWaived(loan.getCurrency()).plus(

                                        loanCharge.getAmountPaid(loan.getCurrency())));
                    }
                } else if (loanCharge.isInstalmentFee()) {
                    LoanInstallmentCharge loanInstallmentCharge = loanCharge.getInstallmentLoanCharge(installment.getInstallmentNumber());
                    if (loanCharge.isPenaltyCharge()) {
                        penaltyAccoutedForCurrentPeriod = penaltyAccoutedForCurrentPeriod
                                .plus(loanInstallmentCharge.getAmountPaid(currency));
                    } else {
                        feeAccountedForCurrentPeriod = feeAccountedForCurrentPeriod.plus(loanInstallmentCharge.getAmountPaid(currency));
                    }
                }
            }
        }

        Money[] balances = new Money[6];
        balances[0] = interestForCurrentPeriod;
        balances[1] = feeForCurrentPeriod;
        balances[2] = penaltyForCurrentPeriod;
        balances[3] = feeAccountedForCurrentPeriod;
        balances[4] = penaltyAccoutedForCurrentPeriod;
        balances[5] = interestAccountedForCurrentPeriod;
        return balances;
    }

    private double calculateInterestForDays(final int daysInPeriod, final BigDecimal interest, final int days) {
        if (interest == null || interest.doubleValue() == 0 || interest.compareTo(BigDecimal.ZERO) == 0 || daysInPeriod <= 0 || days <= 0) {
            return 0;
        }
        return interest.doubleValue() / daysInPeriod * days;
    }

}
