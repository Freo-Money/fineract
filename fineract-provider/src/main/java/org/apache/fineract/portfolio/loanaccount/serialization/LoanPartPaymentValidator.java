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
package org.apache.fineract.portfolio.loanaccount.serialization;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanSummary;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.exception.LoanPartPaymentException;
import org.apache.fineract.portfolio.loanproduct.domain.InterestMethod;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoanPartPaymentValidator {

    public static final String ERROR_AMOUNT_EXCEEDS_TOTAL_OUTSTANDING = "error.msg.loan.part.payment.amount.cannot.exceed.total.outstanding";
    public static final String ERROR_EMI_DUE_TODAY_NOT_CLEARED = "error.msg.loan.partpayment.emidue.today.not.allowed";
    public static final String ERROR_PART_PAYMENT_ALREADY_MADE_ON_DATE = "error.msg.loan.partpayment.already.exists.on.date";

    public void validateNotOverdue(final Loan loan, final LocalDate transactionDate) {
        List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        for (LoanRepaymentScheduleInstallment installment : installments) {
            if (installment.isOverdueOn(transactionDate) && installment.isNotFullyPaidOff()) {
                throw new LoanPartPaymentException("error.msg.loan.partpayment.overdue.not.allowed",
                        "Part-payment is not allowed on overdue loans. Loan: " + loan.getId() + " has overdue installment: "
                                + installment.getInstallmentNumber());
            }
        }
    }

    /**
     * Refuses a part-payment while the installment falling due on the payment date itself is still unpaid.
     * <p>
     * {@link #validateNotOverdue} only catches installments whose due date has already <em>passed</em>
     * ({@link LoanRepaymentScheduleInstallment#isOverdueOn} is a strict comparison), so the EMI that falls due on the
     * very day of the payment slips past it: it is due, but not yet overdue. Between the two rules every installment
     * due up to <em>and including</em> the payment date has to be settled before a part-payment is accepted.
     * <p>
     * That gap matters because the re-amortiser starts from the first installment falling due strictly after the
     * payment date ({@code PartPaymentScheduleReamortizer#indexOfCurrentInstallment}), so an EMI due that same day sits
     * outside the balance it re-amortises. Letting the payment through would have the repayment strategy settle the due
     * EMI out of it while the rewritten schedule was built as though the whole payment had come off principal, leaving
     * the two disagreeing about what was actually paid. The customer is asked to clear the EMI first, which is what the
     * ordinary repayment command is for.
     * <p>
     * The reference date is the transaction date rather than the current business date. A part-payment cannot be dated
     * in the future ({@link #validateNotInTheFuture}), so for a same-day payment - the normal case - the two are the
     * same day, and for a backdated one the transaction date is the date the schedule is actually rewritten around.
     *
     * @throws LoanPartPaymentException
     *             when an installment falls due on the transaction date and is not fully paid off
     */
    public void validateNoUnclearedEmiDueOn(final Loan loan, final LocalDate transactionDate) {
        for (LoanRepaymentScheduleInstallment installment : loan.getRepaymentScheduleInstallments()) {
            if (DateUtils.isEqual(installment.getDueDate(), transactionDate) && installment.isNotFullyPaidOff()) {
                throw new LoanPartPaymentException(ERROR_EMI_DUE_TODAY_NOT_CLEARED,
                        "Part-payment is not allowed when an EMI is due today. Please clear the EMI due for today first.", loan.getId(),
                        installment.getInstallmentNumber(), transactionDate);
            }
        }
    }

    /**
     * Refuses a second part-payment on a date that already carries one.
     * <p>
     * A part-payment closes the current period on its payment date and re-amortises the rest of the loan around the
     * principal prepaid ({@code PartPaymentScheduleReamortizer}). A second payment dated the same day would re-amortise
     * a schedule the first one has already rewritten, over a period of zero elapsed days: no interest has accrued
     * between the two, so the second rewrite starts from a balance the first one has already moved. A single
     * part-payment for the combined amount is the supported way to pay that much on one day.
     * <p>
     * Reversed part-payments are ignored - their re-amortisation has been undone, so the day is free again.
     * <p>
     * The rule is applied on the transaction date rather than the current business date, matching the rest of this
     * validator: a backdated part-payment is rewritten around its own date, so that is the day that can only carry one.
     *
     * @throws LoanPartPaymentException
     *             when the loan already has an unreversed part-payment on the transaction date
     */
    public void validateNoExistingPartPaymentOn(final Loan loan, final LocalDate transactionDate) {
        for (final LoanTransaction transaction : loan.getLoanTransactions()) {
            if (transaction.isPartPayment() && transaction.isNotReversed()
                    && DateUtils.isEqual(transaction.getTransactionDate(), transactionDate)) {
                throw new LoanPartPaymentException(ERROR_PART_PAYMENT_ALREADY_MADE_ON_DATE,
                        "Only one part-payment is allowed per day. Loan: " + loan.getId() + " already has a part-payment on "
                                + transactionDate + ".",
                        loan.getId(), transactionDate);
            }
        }
    }

    public void validateNotFlatInterest(final Loan loan) {
        InterestMethod interestMethod = loan.getLoanProductRelatedDetail().getInterestMethod();
        if (interestMethod != null && interestMethod.isFlat()) {
            throw new LoanPartPaymentException("error.msg.loan.partpayment.flat.interest.not.supported",
                    "Part-payment is not supported for flat interest loans. Loan: " + loan.getId());
        }
    }

    /**
     * A part-payment rewrites the schedule around the payment date: it closes the current period on that date, charges
     * the interest accrued up to it and re-amortises the rest of the loan. Dating one in the future would do all of
     * that for a period that has not elapsed, so it is refused - unlike a plain repayment, there is nothing sensible to
     * fall back on.
     */
    public void validateNotInTheFuture(final LocalDate transactionDate) {
        if (DateUtils.isDateInTheFuture(transactionDate)) {
            throw new LoanPartPaymentException("error.msg.loan.partpayment.transaction.date.in.future",
                    "Part-payment transaction date " + transactionDate + " cannot be in the future.", transactionDate);
        }
    }

    /**
     * Caps a part-payment at the loan's total outstanding amount - the principal, interest and charges the loan summary
     * still shows as owed. A payment that reaches that figure is not a part-payment at all but a payoff, which belongs
     * to the {@code foreclosure} command: that one charges interest to the settlement date, applies the foreclosure
     * charges and runs the closure handling, none of which a part-payment does. Anything above it would leave the
     * customer in credit on a loan that was never closed.
     * <p>
     * The ceiling is read straight off {@link LoanSummary#getTotalOutstanding(MonetaryCurrency)} rather than recomputed
     * here, so it is the same balance the loan account and its API representation report.
     * <p>
     * Note that this is the outstanding of the whole remaining schedule, so it still includes the interest of periods
     * that have not elapsed - it is a deliberately generous upper bound whose job is to refuse plainly excessive
     * amounts before any work is done. The exact payoff threshold as at the transaction date is narrower and is applied
     * further in, by {@code PartPaymentScheduleReamortizer}, which knows what interest has actually accrued by then.
     *
     * @throws LoanPartPaymentException
     *             when the payment is greater than or equal to the total outstanding amount
     */
    public void validateAmountWithinTotalOutstanding(final Loan loan, final BigDecimal transactionAmount) {
        final LoanSummary summary = loan.getSummary();
        if (summary == null || transactionAmount == null) {
            // Nothing has been disbursed yet, or the amount is missing - the payload validator already rejects the
            // latter, and a loan with no summary has no balance to part-pay against.
            return;
        }
        final MonetaryCurrency currency = loan.getCurrency();
        final Money totalOutstanding = summary.getTotalOutstanding(currency);
        final Money partPayment = Money.of(currency, transactionAmount);
        if (partPayment.isLessThan(totalOutstanding)) {
            return;
        }
        throw new LoanPartPaymentException(ERROR_AMOUNT_EXCEEDS_TOTAL_OUTSTANDING,
                "Part-payment of " + partPayment.getAmount() + " on loan " + loan.getId()
                        + " must be less than the total outstanding amount of " + totalOutstanding.getAmount()
                        + ". Use the foreclosure command to settle the loan in full.",
                partPayment.getAmount(), totalOutstanding.getAmount(), loan.getId());
    }

    public void validatePreConditions(final Loan loan, final LocalDate transactionDate, final BigDecimal transactionAmount) {
        validateNotInTheFuture(transactionDate);
        validateNoExistingPartPaymentOn(loan, transactionDate);
        validateNotOverdue(loan, transactionDate);
        validateNoUnclearedEmiDueOn(loan, transactionDate);
        validateNotFlatInterest(loan);
        validateAmountWithinTotalOutstanding(loan, transactionAmount);
    }
}
