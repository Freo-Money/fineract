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
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.persistence.FlushModeHandler;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanDisbursementDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanInstallmentCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleProcessingWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanSummary;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionComparator;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanproduct.domain.CreditAllocationTransactionType;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LoanBalanceService {

    private final CapitalizedIncomeBalanceService capitalizedIncomeBalanceService;
    private final FlushModeHandler flushModeHandler;
    private final LoanTransactionRepository loanTransactionRepository;
    private final LoanChargeRepository loanChargeRepository;

    public Money calculateTotalOverpayment(final Loan loan) {
        Money totalPaidInRepayments = loan.getTotalPaidInRepayments();

        final MonetaryCurrency currency = loan.getCurrency();
        Money cumulativeTotalPaidOnInstallments = Money.zero(currency);
        Money cumulativeTotalWaivedOnInstallments = Money.zero(currency);
        List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        for (final LoanRepaymentScheduleInstallment scheduledRepayment : installments) {
            cumulativeTotalPaidOnInstallments = cumulativeTotalPaidOnInstallments
                    .plus(scheduledRepayment.getPrincipalCompleted(currency).plus(scheduledRepayment.getInterestPaid(currency)))
                    .plus(scheduledRepayment.getFeeChargesPaid(currency)).plus(scheduledRepayment.getPenaltyChargesPaid(currency));

            cumulativeTotalWaivedOnInstallments = cumulativeTotalWaivedOnInstallments.plus(scheduledRepayment.getInterestWaived(currency));
        }

        Money foreclosureChargesPaid = Money.zero(currency);

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

            for (LoanChargePaidBy chargePaidBy : loanTransaction.getLoanChargesPaid()) {
                LoanCharge loanCharge = chargePaidBy.getLoanCharge();
                if (loanCharge.isDueAtForeclosure()) {
                    foreclosureChargesPaid = foreclosureChargesPaid.plus(Money.of(currency, chargePaidBy.getAmount()));
                }
            }
        }

        // if total paid in transactions doesn't match repayment schedule then there's an overpayment.
        return totalPaidInRepayments.minus(cumulativeTotalPaidOnInstallments).minus(foreclosureChargesPaid);
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
        final Money overpaidBy = calculateTotalOverpayment(loan);
        loan.setTotalOverpaid(null);
        if (!overpaidBy.isLessThanZero()) {
            loan.setTotalOverpaid(overpaidBy.getAmountDefaultedToNullIfZero());
        }

        final Money recoveredAmount = calculateTotalRecoveredPayments(loan);
        loan.setTotalRecovered(recoveredAmount.getAmountDefaultedToNullIfZero());

        final Money principal = loan.getLoanRepaymentScheduleDetail().getPrincipal();
        final Money capitalizedIncome = capitalizedIncomeBalanceService.calculateCapitalizedIncome(loan);
        final Money capitalizedIncomeAdjustment = capitalizedIncomeBalanceService.calculateCapitalizedIncomeAdjustment(loan);
        loan.getSummary().updateSummary(loan.getCurrency(), principal, loan.getRepaymentScheduleInstallments(), loan.getLoanCharges(),
                capitalizedIncome, capitalizedIncomeAdjustment);
        updateLoanOutstandingBalances(loan);
    }

    private Money calculateTotalRecoveredPayments(Loan loan) {
        // in case logic for reversing recovered payment is implemented handle subtraction from totalRecoveredPayments
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
                    // in case of advanced payment strategy and creditAllocations the full amount is recognized first
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

    public LoanRepaymentScheduleInstallment fetchLoanForeclosureDetail(final Loan loan, final LocalDate closureDate) {
        return fetchLoanForeclosureDetail(loan, closureDate, true);
    }

    public LoanRepaymentScheduleInstallment fetchLoanForeclosureDetail(final Loan loan, final LocalDate closureDate,
            final boolean updateLoanCharges) {
        MonetaryCurrency currency = loan.getCurrency();
        final LocalDate currentDate = DateUtils.getBusinessLocalDate();
        Money foreclosureOutstandingBefore = loan.getActiveCharges().stream().filter(charge -> charge.isDueAtForeclosure())
                .map(charge -> charge.getAmountOutstanding(currency)).reduce(Money.zero(currency), Money::plus);
        Money foreclosurePrincipalBase = calculateForeclosurePrincipalOutstanding(loan, currency);
        if (foreclosurePrincipalBase.isLessThanZero()) {
            foreclosurePrincipalBase = foreclosurePrincipalBase.zero();
        }

        Money foreclosureCharges = calculateForeclosureCharges(loan, foreclosurePrincipalBase, currency, closureDate, updateLoanCharges);

        Money[] receivables = retrieveIncomeOutstandingTillDate(loan, closureDate);
        Money totalPrincipal = foreclosurePrincipalBase;
        if (totalPrincipal.isLessThanZero()) {
            totalPrincipal = totalPrincipal.zero();
        }

        Money interestOutstanding = receivables[0];
        if (interestOutstanding.isLessThanZero()) {
            interestOutstanding = interestOutstanding.zero();
        }

        Money feeOutstanding = calculateForeclosureFeeOutstanding(receivables[1], foreclosureOutstandingBefore, foreclosureCharges);
        if (feeOutstanding.isLessThanZero()) {
            feeOutstanding = feeOutstanding.zero();
        }

        Money penaltyOutstanding = receivables[2];
        if (penaltyOutstanding.isLessThanZero()) {
            penaltyOutstanding = penaltyOutstanding.zero();
        }

        return new LoanRepaymentScheduleInstallment(null, 0, currentDate, currentDate, totalPrincipal.getAmount(),
                interestOutstanding.getAmount(), feeOutstanding.getAmount(), penaltyOutstanding.getAmount(), false, null);
    }

    private Money calculateForeclosureCharges(final Loan loan, final Money foreclosurePrincipalBase, final MonetaryCurrency currency,
            final LocalDate foreclosureDate, final boolean updateLoanCharges) {
        List<LoanCharge> foreCloseChargesToBeSaved = new ArrayList<>();
        Money foreclosureCharges = Money.zero(currency);
        Money totalOriginalForeclosureCharges = Money.zero(currency);
        Money totalUpdatedForeclosureCharges = Money.zero(currency);
        BigDecimal foreclosurePrincipalBaseAmount = foreclosurePrincipalBase.getAmount();
        for (LoanCharge loanCharge : loan.getActiveCharges()) {
            if (!loanCharge.isDueAtForeclosure()) {
                continue;
            }

            Money originalAmount = loanCharge.getAmount(currency);
            Money updatedAmount = originalAmount;
            Money originalOutstanding = loanCharge.getAmountOutstanding(currency);
            Money updatedOutstanding = originalOutstanding;
            ChargeCalculationType chargeCalculationType = loanCharge.getChargeCalculation();

            if (chargeCalculationType != null && chargeCalculationType.isPercentageOfAmount()) {
                BigDecimal percentage = loanCharge.getPercentage() != null ? loanCharge.getPercentage() : loanCharge.amountOrPercentage();
                BigDecimal recalculatedAmount = LoanCharge.percentageOf(foreclosurePrincipalBaseAmount, percentage);
                BigDecimal cappedAmount = loanCharge.minimumAndMaximumCap(recalculatedAmount);

                BigDecimal recalculatedOutstanding = cappedAmount.subtract(MathUtil.nullToZero(loanCharge.getAmountPaid()))
                        .subtract(MathUtil.nullToZero(loanCharge.getAmountWaived()))
                        .subtract(MathUtil.nullToZero(loanCharge.getAmountWrittenOff()));
                BigDecimal adjustedOutstanding = MathUtil.negativeToZero(recalculatedOutstanding);

                if (updateLoanCharges) {
                    loanCharge.setAmountPercentageAppliedTo(foreclosurePrincipalBaseAmount);
                    loanCharge.setAmount(cappedAmount);
                    loanCharge.setAmountOutstanding(adjustedOutstanding);
                    updatedAmount = Money.of(currency, cappedAmount);
                    updatedOutstanding = Money.of(currency, adjustedOutstanding);
                } else {
                    updatedAmount = Money.of(currency, cappedAmount);
                    updatedOutstanding = Money.of(currency, adjustedOutstanding);
                }
            } else {
                BigDecimal baseAmount = loanCharge.amount();
                if (baseAmount != null) {
                    BigDecimal recalculatedOutstanding = baseAmount.subtract(MathUtil.nullToZero(loanCharge.getAmountPaid()))
                            .subtract(MathUtil.nullToZero(loanCharge.getAmountWaived()))
                            .subtract(MathUtil.nullToZero(loanCharge.getAmountWrittenOff()));
                    BigDecimal adjustedOutstanding = MathUtil.negativeToZero(recalculatedOutstanding);
                    if (updateLoanCharges) {
                        loanCharge.setAmountOutstanding(adjustedOutstanding);
                        updatedOutstanding = Money.of(currency, adjustedOutstanding);
                    } else {
                        updatedOutstanding = Money.of(currency, adjustedOutstanding);
                    }
                }
            }
            foreCloseChargesToBeSaved.add(loanCharge);
            if (updateLoanCharges && loanCharge.getDueLocalDate() == null && foreclosureDate != null) {
                loanCharge.setDueDate(foreclosureDate);
            }

            if (updateLoanCharges) {
                adjustLoanSummaryForForeclosureCharge(loan, originalAmount, updatedAmount, originalOutstanding, updatedOutstanding);
            }

            totalOriginalForeclosureCharges = totalOriginalForeclosureCharges.plus(originalAmount);
            totalUpdatedForeclosureCharges = totalUpdatedForeclosureCharges.plus(updatedAmount);
            foreclosureCharges = foreclosureCharges.plus(updatedOutstanding);
        }

        if (updateLoanCharges) {
            this.loanChargeRepository.saveAll(foreCloseChargesToBeSaved);
            updateForeclosureScheduleInstallment(loan, currency, foreclosureDate, totalOriginalForeclosureCharges,
                    totalUpdatedForeclosureCharges);
        }

        return foreclosureCharges;
    }

    private void adjustLoanSummaryForForeclosureCharge(final Loan loan, final Money originalAmount, final Money updatedAmount,
            final Money originalOutstanding, final Money updatedOutstanding) {
        if (loan.getSummary() == null) {
            return;
        }
        Money amountDifference = updatedAmount.minus(originalAmount);
        Money outstandingDifference = updatedOutstanding.minus(originalOutstanding);
        LoanSummary summary = loan.getSummary();
        BigDecimal currentFeeOutstanding = MathUtil.nullToZero(summary.getTotalFeeChargesOutstanding());
        BigDecimal currentTotalOutstanding = MathUtil.nullToZero(summary.getTotalOutstanding());
        BigDecimal currentTotalFeeChargesCharged = MathUtil.nullToZero(summary.getTotalFeeChargesCharged());
        BigDecimal currentTotalExpectedRepayment = MathUtil.nullToZero(summary.getTotalExpectedRepayment());
        BigDecimal currentTotalExpectedCostOfLoan = MathUtil.nullToZero(summary.getTotalExpectedCostOfLoan());

        BigDecimal newFeeOutstanding = currentFeeOutstanding.add(outstandingDifference.getAmount());
        BigDecimal newTotalOutstanding = currentTotalOutstanding.add(outstandingDifference.getAmount());
        BigDecimal newTotalFeeChargesCharged = currentTotalFeeChargesCharged.add(amountDifference.getAmount());
        BigDecimal newTotalExpectedRepayment = currentTotalExpectedRepayment.add(amountDifference.getAmount());
        BigDecimal newTotalExpectedCostOfLoan = currentTotalExpectedCostOfLoan.add(amountDifference.getAmount());

        summary.updateFeeChargeOutstanding(newFeeOutstanding);
        summary.updateTotalOutstanding(newTotalOutstanding);
        summary.updateTotalFeeChargesCharged(newTotalFeeChargesCharged);
        summary.updateTotalExpectedRepayment(newTotalExpectedRepayment);
        summary.updateTotalExpectedCostOfLoan(newTotalExpectedCostOfLoan);
    }

    public void updateForeclosureScheduleInstallment(final Loan loan, final MonetaryCurrency currency, final LocalDate foreclosureDate,
            final Money originalForeclosureCharges, final Money updatedForeclosureCharges) {
        LoanRepaymentScheduleInstallment foreclosureInstallment = loan.getRepaymentScheduleInstallments().stream()
                .filter(installment -> installment.getDueDate() != null && DateUtils.isEqual(installment.getDueDate(), foreclosureDate))
                .findFirst().orElseGet(() -> loan.getRepaymentScheduleInstallments().stream()
                        .max(Comparator.comparing(LoanRepaymentScheduleInstallment::getDueDate)).orElse(null));

        if (foreclosureInstallment == null) {
            return;
        }

        foreclosureInstallment.setFeeChargesCharged(updatedForeclosureCharges.getAmount());
        Money feePaid = foreclosureInstallment.getFeeChargesPaid(currency);
        if (feePaid.isGreaterThan(updatedForeclosureCharges)) {
            foreclosureInstallment.setFeeChargesPaid(updatedForeclosureCharges.getAmount());
        }
    }

    public void synchronizeForeclosureScheduleAndSummary(final Loan loan, final LocalDate foreclosureDate) {
        MonetaryCurrency currency = loan.getCurrency();
        Money foreclosureFeeTotal = loan.getCharges().stream().filter(LoanCharge::isDueAtForeclosure)
                .map(charge -> charge.getAmount(currency)).reduce(Money.zero(currency), Money::plus);

        LoanRepaymentScheduleInstallment foreclosureInstallment = loan.getRepaymentScheduleInstallments().stream()
                .filter(installment -> installment.getDueDate() != null && DateUtils.isEqual(installment.getDueDate(), foreclosureDate))
                .findFirst().orElseGet(() -> loan.getRepaymentScheduleInstallments().stream()
                        .max(Comparator.comparing(LoanRepaymentScheduleInstallment::getDueDate)).orElse(null));

        if (foreclosureInstallment != null && foreclosureFeeTotal.isGreaterThanZero()
                && !foreclosureInstallment.getFeeChargesCharged(currency).isEqualTo(foreclosureFeeTotal)) {
            foreclosureInstallment.setFeeChargesCharged(foreclosureFeeTotal.getAmount());
            Money feePaid = foreclosureInstallment.getFeeChargesPaid(currency);
            if (feePaid.isGreaterThan(foreclosureFeeTotal)) {
                foreclosureInstallment.setFeeChargesPaid(foreclosureFeeTotal.getAmount());
            }
        }

        updateLoanSummaryDerivedFields(loan);
    }

    Money calculateForeclosureFeeOutstanding(final Money receivableFeeOutstanding, final Money foreclosureOutstandingBefore,
            final Money foreclosureCharges) {
        Money feeOutstanding = receivableFeeOutstanding;
        Money foreclosureOutstandingOverlap = foreclosureOutstandingBefore;
        if (foreclosureOutstandingBefore.isGreaterThan(feeOutstanding)) {
            foreclosureOutstandingOverlap = feeOutstanding;
        }
        return feeOutstanding.minus(foreclosureOutstandingOverlap).plus(foreclosureCharges);
    }

    private Money calculateForeclosurePrincipalOutstanding(final Loan loan, final MonetaryCurrency currency) {
        Money outstandingPrincipal = loan.getSummary() != null && loan.getSummary().getTotalPrincipalOutstanding() != null
                ? Money.of(currency, loan.getSummary().getTotalPrincipalOutstanding())
                : Money.zero(currency);

        if (outstandingPrincipal.isZero()) {
            outstandingPrincipal = loan.getRepaymentScheduleInstallments().stream().filter(installment -> !installment.isAdditional())
                    .map(installment -> installment.getPrincipalOutstanding(currency)).reduce(Money.zero(currency), Money::plus);

            for (LoanDisbursementDetails disbursementDetails : loan.getDisbursementDetails()) {
                if (disbursementDetails.actualDisbursementDate() == null) {
                    outstandingPrincipal = outstandingPrincipal.minus(Money.of(currency, disbursementDetails.principal()));
                }
            }
        }

        if (outstandingPrincipal.isLessThanZero()) {
            outstandingPrincipal = outstandingPrincipal.zero();
        }

        return outstandingPrincipal;
    }

    public Money[] retrieveIncomeForOverlappingPeriod(final Loan loan, final LocalDate paymentDate) {
        Money[] balances = new Money[3];
        final MonetaryCurrency currency = loan.getCurrency();
        balances[0] = balances[1] = balances[2] = Money.zero(currency);
        int firstNormalInstallmentNumber = LoanRepaymentScheduleProcessingWrapper
                .fetchFirstNormalInstallmentNumber(loan.getRepaymentScheduleInstallments());
        for (final LoanRepaymentScheduleInstallment installment : loan.getRepaymentScheduleInstallments()) {
            boolean isFirstNormalInstallment = installment.getInstallmentNumber().equals(firstNormalInstallmentNumber);
            if (DateUtils.isEqual(paymentDate, installment.getDueDate())) {
                Money interest = installment.getInterestCharged(currency);
                Money fee = installment.getFeeChargesCharged(currency);
                Money penalty = installment.getPenaltyChargesCharged(currency);
                balances[0] = interest;
                balances[1] = fee;
                balances[2] = penalty;
                break;
            } else if (DateUtils.isDateInRangeExclusive(paymentDate, installment.getFromDate(), installment.getDueDate())) {
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
            } else if (DateUtils.isAfter(paymentDate, installment.getFromDate())) {
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
        int totalPeriodDays = DateUtils.getExactDifferenceInDays(installment.getFromDate(), installment.getDueDate());
        int tillDays = DateUtils.getExactDifferenceInDays(installment.getFromDate(), paymentDate);
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
                                .plus(loanCharge.getAmountWaived(loan.getCurrency()).plus(loanCharge.getAmountPaid(loan.getCurrency())));
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
        if (interest.doubleValue() == 0) {
            return 0;
        }
        return interest.doubleValue() / daysInPeriod * days;
    }

}
