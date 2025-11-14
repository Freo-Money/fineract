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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.persistence.FlushModeHandler;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanDisbursementDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanInstallmentCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleProcessingWrapper;
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
    private final ForeclosureChargeHelper foreclosureChargeHelper;

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

        if (loan.isForeclosure() && loan.getSummary() != null
                && loan.getSummary().getTotalOutstanding(currency).isZero()) {
            return Money.zero(currency);
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

        // if total paid in transactions doesn't match repayment schedule then there's an overpayment.
        return totalPaidInRepayments.minus(cumulativeTotalPaidOnInstallments);
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
        return fetchLoanForeclosureDetail(loan, closureDate, null, false);
    }

    public LoanRepaymentScheduleInstallment fetchLoanForeclosureDetail(final Loan loan, final LocalDate closureDate,
            final BigDecimal foreclosureChargePercentage) {
        return fetchLoanForeclosureDetail(loan, closureDate, foreclosureChargePercentage, false);
    }

    public LoanRepaymentScheduleInstallment fetchLoanForeclosureDetail(final Loan loan, final LocalDate closureDate,
            final BigDecimal foreclosureChargePercentage, final boolean updateLoanCharges) {
        Money[] receivables = retrieveIncomeOutstandingTillDate(loan, closureDate);
        MonetaryCurrency currency = loan.getCurrency();
        Money totalPrincipal = Money.of(currency, loan.getSummary().getTotalPrincipalOutstanding());
        totalPrincipal = totalPrincipal.minus(receivables[3]);
        Money principalBaseForCharges = determineForeclosureChargePrincipalBase(loan, closureDate, receivables);

        BigDecimal effectiveForeclosureChargePercentage = foreclosureChargePercentage != null ? foreclosureChargePercentage
                : determineForeclosureChargePercentage(loan);

        if (updateLoanCharges) {
            updateForeclosureCharges(loan, currency, closureDate, principalBaseForCharges, effectiveForeclosureChargePercentage);
            receivables = retrieveIncomeOutstandingTillDate(loan, closureDate);
        }

        final LocalDate currentDate = DateUtils.getBusinessLocalDate();
        return new LoanRepaymentScheduleInstallment(null, 0, currentDate, currentDate, totalPrincipal.getAmount(),
                receivables[0].getAmount(), receivables[1].getAmount(), receivables[2].getAmount(), false, null);
    }

    public Money calculateForeclosureChargeAmount(final Loan loan, final LocalDate closureDate,
            final BigDecimal foreclosureChargePercentage) {
        Money[] receivables = retrieveIncomeOutstandingTillDate(loan, closureDate);
        Money principalBaseForCharges = determineForeclosureChargePrincipalBase(loan, closureDate, receivables);
        BigDecimal effectiveForeclosureChargePercentage = foreclosureChargePercentage != null ? foreclosureChargePercentage
                : determineForeclosureChargePercentage(loan);
        return calculateForeclosureChargeAmount(loan, principalBaseForCharges, effectiveForeclosureChargePercentage);
    }

    public BigDecimal determineForeclosureChargePercentage(final Loan loan) {
        BigDecimal loanLevelPercentage = getActiveForeclosureCharges(loan).stream()
                .map(foreclosureChargeHelper::extractForeclosurePercentage).filter(Objects::nonNull).findFirst().orElse(null);
        if (loanLevelPercentage != null) {
            return loanLevelPercentage;
        }
        return foreclosureChargeHelper.determineForeclosureChargePercentageFromProduct(loan);
    }

    private Money calculateForeclosureChargeAmount(final Loan loan, final Money principalOutstanding,
            final BigDecimal foreclosureChargePercentage) {
        MonetaryCurrency currency = loan.getCurrency();
        Money total = Money.zero(currency);
        List<LoanCharge> foreclosureCharges = getActiveForeclosureCharges(loan);
        if (!foreclosureCharges.isEmpty()) {
            for (LoanCharge charge : foreclosureCharges) {
                Money chargeAmount = calculateForeclosureChargeAmountForCharge(charge, currency, principalOutstanding,
                        foreclosureChargePercentage);
                total = total.plus(chargeAmount);
            }
            return total;
        }

        for (Charge chargeDefinition : foreclosureChargeHelper.getForeclosureChargeDefinitions(loan)) {
            Money chargeAmount = foreclosureChargeHelper.calculateForeclosureChargeAmountForDefinition(loan, chargeDefinition, currency,
                    principalOutstanding, foreclosureChargePercentage);
            total = total.plus(chargeAmount);
        }

        return total;
    }

    private void updateForeclosureCharges(final Loan loan, final MonetaryCurrency currency, final LocalDate closureDate,
            final Money principalOutstanding, final BigDecimal foreclosureChargePercentage) {
        List<LoanCharge> foreclosureCharges = getActiveForeclosureCharges(loan);
        if (foreclosureCharges.isEmpty()) {
            foreclosureCharges = foreclosureChargeHelper.createForeclosureChargesForLoan(loan);
            if (foreclosureCharges.isEmpty()) {
                return;
            }
        }

        Money totalForeclosureChargeAmount = Money.zero(currency);
        for (LoanCharge charge : foreclosureCharges) {
            Money chargeAmount = calculateForeclosureChargeAmountForCharge(charge, currency, principalOutstanding,
                    foreclosureChargePercentage);
            totalForeclosureChargeAmount = totalForeclosureChargeAmount.plus(chargeAmount);
            applyForeclosureChargeUpdate(charge, chargeAmount, principalOutstanding, closureDate, foreclosureChargePercentage);
        }
        applyForeclosureChargeOnRepaymentSchedule(loan, currency, totalForeclosureChargeAmount);
        refreshSummaryAndBalancesForDisbursedLoan(loan);
    }

    private Money calculateForeclosureChargeAmountForCharge(final LoanCharge charge, final MonetaryCurrency currency,
            final Money principalOutstanding, final BigDecimal foreclosureChargePercentage) {
        if (charge.getChargeCalculation().isPercentageBased()) {
            BigDecimal percentageToUse = foreclosureChargePercentage;
            if (percentageToUse == null) {
                percentageToUse = charge.getPercentage();
            }
            if (percentageToUse == null) {
                percentageToUse = charge.amountOrPercentage();
            }
            if (percentageToUse == null) {
                return Money.zero(currency);
            }
            return Money.of(currency, LoanCharge.percentageOf(principalOutstanding.getAmount(), percentageToUse));
        }
        Money outstanding = charge.getAmountOutstanding(currency);
        if (outstanding.isGreaterThanZero()) {
            return outstanding;
        }
        BigDecimal baseAmount = charge.amountOrPercentage();
        if (baseAmount == null) {
            return Money.zero(currency);
        }
        return Money.of(currency, baseAmount);
    }

    private void applyForeclosureChargeOnRepaymentSchedule(final Loan loan, final MonetaryCurrency currency, final Money foreclosureAmount) {
        if (!foreclosureAmount.isGreaterThanZero()) {
            return;
        }
        List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        if (installments == null || installments.isEmpty()) {
            return;
        }
        LoanRepaymentScheduleInstallment lastInstallment = LoanRepaymentScheduleInstallment.getLastNonDownPaymentInstallment(installments);
        Money existingFeeCharges = lastInstallment.getFeeChargesCharged(currency);
        if (existingFeeCharges.isEqualTo(foreclosureAmount)) {
            return;
        }
        lastInstallment.setFeeChargesCharged(foreclosureAmount.getAmount());
        lastInstallment.setFeeChargesPaid(Money.zero(currency).getAmount());
        lastInstallment.setFeeChargesWaived(Money.zero(currency).getAmount());
        lastInstallment.setFeeChargesWrittenOff(Money.zero(currency).getAmount());
    }

    public void syncForeclosureFeeOnRepaymentSchedule(final Loan loan, final Money foreclosureFee) {
        MonetaryCurrency currency = loan.getCurrency();
        if (!foreclosureFee.isGreaterThanZero()) {
            return;
        }
        List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        if (installments == null || installments.isEmpty()) {
            return;
        }
        LoanRepaymentScheduleInstallment lastInstallment = LoanRepaymentScheduleInstallment.getLastNonDownPaymentInstallment(installments);
        // Update to match the actual foreclosure fee that was charged and paid
        lastInstallment.setFeeChargesCharged(foreclosureFee.getAmount());
        lastInstallment.setFeeChargesPaid(foreclosureFee.getAmount());
        lastInstallment.setFeeChargesWaived(Money.zero(currency).getAmount());
        lastInstallment.setFeeChargesWrittenOff(Money.zero(currency).getAmount());
    }

    private Money determineForeclosureChargePrincipalBase(final Loan loan, final LocalDate closureDate, Money[] receivables) {
        MonetaryCurrency currency = loan.getCurrency();
        Money principalOverdue = calculatePrincipalOverdueTillDate(loan, closureDate);
        if (principalOverdue.isGreaterThanZero()) {
            return principalOverdue;
        }
        Money principalOutstanding = Money.of(currency, loan.getSummary().getTotalPrincipalOutstanding());
        if (receivables != null && receivables.length > 3) {
            principalOutstanding = principalOutstanding.minus(receivables[3]);
        }
        return principalOutstanding.isGreaterThanZero() ? principalOutstanding : Money.zero(currency);
    }

    private Money calculatePrincipalOverdueTillDate(final Loan loan, final LocalDate tillDate) {
        MonetaryCurrency currency = loan.getCurrency();
        Money total = Money.zero(currency);
        for (LoanRepaymentScheduleInstallment installment : loan.getRepaymentScheduleInstallments()) {
            if (installment.isOverdueOn(tillDate)) {
                Money principalOutstanding = installment.getPrincipalOutstanding(currency);
                if (principalOutstanding.isGreaterThanZero()) {
                    total = total.plus(principalOutstanding);
                }
            }
        }
        return total;
    }

    private void applyForeclosureChargeUpdate(final LoanCharge charge, final Money amount, final Money principalOutstanding,
            final LocalDate closureDate, final BigDecimal foreclosureChargePercentage) {
        charge.setDueDate(closureDate);
        charge.setAmount(amount.getAmount());
        charge.setAmountOutstanding(amount.getAmount());
        charge.setAmountPaid(BigDecimal.ZERO);
        charge.setAmountWaived(BigDecimal.ZERO);
        charge.setAmountWrittenOff(BigDecimal.ZERO);
        charge.setPaid(false);
        charge.setWaived(false);
        charge.setAmountPercentageAppliedTo(principalOutstanding.getAmount());
        if (foreclosureChargePercentage != null) {
            charge.setPercentage(foreclosureChargePercentage);
            charge.setAmountOrPercentage(foreclosureChargePercentage);
        }
    }

    private List<LoanCharge> getActiveForeclosureCharges(final Loan loan) {
        if (loan.getLoanCharges() == null) {
            return Collections.emptyList();
        }
        return loan.getLoanCharges().stream().filter(LoanCharge::isActive).filter(LoanCharge::isDueAtForeclosure)
                .collect(Collectors.toList());
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
        if (interest.doubleValue() == 0) {
            return 0;
        }
        return interest.doubleValue() / daysInPeriod * days;
    }

}
