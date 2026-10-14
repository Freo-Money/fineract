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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptAllocation;
import org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptInstallmentResolver;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleProcessingWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;

/**
 * Pure (no persistence, no Spring) classification of a charge payment's stored receipts against its amount, and the
 * top-ups that would make them consistent.
 * <p>
 * Background: since the receipts of a multi-receipt charge payment became the authoritative input of reprocessing, a
 * transaction whose receipts were lowered in place by the earlier destructive re-derivation parks the difference as
 * overpayment on every reprocess. The earlier code only ever lowered receipts, so raising each clipped receipt back up
 * to what its charge can still absorb, in effective-due-date order and bounded by the gap, reconstructs the original
 * allocation.
 */
public final class ChargePaymentReceiptRepairPlanner {

    /**
     * @param liveTransactions
     *            every non-reversed transaction of the loan (the one under review may be included). Their receipts on
     *            the same charges reduce the room a top-up may use, so the receipts of one charge never exceed the
     *            charge across the whole loan.
     */
    public ChargePaymentReceiptRepairPlan plan(final LoanTransaction transaction, final MonetaryCurrency currency,
            final List<LoanRepaymentScheduleInstallment> installments, final Collection<LoanTransaction> liveTransactions) {
        final Money amount = transaction.getAmount(currency);
        final Money feePortion = transaction.getFeeChargesPortion(currency);
        final Money penaltyPortion = transaction.getPenaltyChargesPortion(currency);
        final Money overpaymentPortion = transaction.getOverPaymentPortion(currency);
        final Money receiptsTotal = transaction.getLoanChargesPaidTotal(currency);

        final List<LoanChargePaidBy> receipts = ChargePaymentReceiptAllocation.sortedForAllocation(transaction.getLoanChargesPaid(),
                installments);
        final Map<String, Money> settledByKey = ChargePaymentReceiptAllocation.settledByKey(liveTransactions, currency);
        final Map<String, Money> carried = ChargePaymentReceiptAllocation.carriedByKey(transaction, currency);
        final int firstNormalInstallmentNumber = LoanRepaymentScheduleProcessingWrapper.fetchFirstNormalInstallmentNumber(installments);

        final List<ChargePaymentReceiptView> views = new ArrayList<>(receipts.size());
        boolean anyInactiveCharge = false;
        boolean anyUnmapped = false;
        boolean mixedComponents = false;
        Boolean feeComponent = null;
        for (final LoanChargePaidBy receipt : receipts) {
            final LoanCharge charge = receipt.getLoanCharge();
            final boolean chargeActive = charge != null && charge.isActive();
            anyInactiveCharge |= !chargeActive;

            final Boolean feeCharge = charge == null ? null : charge.isFeeCharge();
            if (feeCharge != null) {
                if (feeComponent == null) {
                    feeComponent = feeCharge;
                } else if (!feeComponent.equals(feeCharge)) {
                    mixedComponents = true;
                }
            }

            LoanRepaymentScheduleInstallment installment = ChargePaymentReceiptInstallmentResolver.byInstallmentNumber(installments,
                    receipt.getInstallmentNumber());
            String mappedBy = ChargePaymentReceiptView.UNMAPPED;
            if (installment != null) {
                mappedBy = ChargePaymentReceiptView.MAPPED_BY_NUMBER;
            } else if (charge != null) {
                installment = ChargePaymentReceiptInstallmentResolver.byDuePeriod(installments, firstNormalInstallmentNumber, charge);
                if (installment != null) {
                    mappedBy = ChargePaymentReceiptView.MAPPED_BY_DUE_PERIOD;
                }
            }
            anyUnmapped |= installment == null;

            BigDecimal bucketAmount = null;
            BigDecimal bucketCompleted = null;
            if (installment != null && feeCharge != null) {
                bucketAmount = (feeCharge ? installment.getFeeChargesCharged(currency) : installment.getPenaltyChargesCharged(currency))
                        .getAmount();
                bucketCompleted = (feeCharge ? installment.getFeeChargesPaid(currency) : installment.getPenaltyChargesPaid(currency))
                        .getAmount();
            }
            views.add(new ChargePaymentReceiptView(receipt.getId(), charge == null ? null : charge.getId(), receipt.getAmount(),
                    charge == null ? null : ChargePaymentReceiptAllocation.capOf(receipt, currency, settledByKey, carried).getAmount(),
                    chargeActive, feeCharge, installment == null ? null : installment.getInstallmentNumber(), mappedBy, bucketAmount,
                    bucketCompleted));
        }

        final Money gap = amount.minus(receiptsTotal);
        ChargePaymentReceiptRepairClassification classification;
        List<ChargePaymentReceiptTopUp> topUps = List.of();
        if (receipts.size() <= 1) {
            classification = ChargePaymentReceiptRepairClassification.SINGLE_RECEIPT;
        } else if (anyInactiveCharge) {
            classification = ChargePaymentReceiptRepairClassification.RECEIPT_ON_INACTIVE_CHARGE;
        } else if (anyUnmapped) {
            classification = ChargePaymentReceiptRepairClassification.UNMAPPED_RECEIPT;
        } else if (mixedComponents) {
            classification = ChargePaymentReceiptRepairClassification.MIXED;
        } else if (gap.isLessThanZero()) {
            classification = ChargePaymentReceiptRepairClassification.OVER;
        } else if (gap.isZero()) {
            classification = feePortion.plus(penaltyPortion).isLessThan(amount) ? ChargePaymentReceiptRepairClassification.INSTALLMENT_ROOM
                    : ChargePaymentReceiptRepairClassification.CONSISTENT;
        } else {
            topUps = computeTopUps(receipts, gap, currency, settledByKey, carried);
            if (topUps.isEmpty()) {
                classification = ChargePaymentReceiptRepairClassification.SURPLUS;
            } else {
                Money repaired = Money.zero(currency);
                for (final ChargePaymentReceiptTopUp topUp : topUps) {
                    repaired = repaired.plus(Money.of(currency, topUp.increment()));
                }
                classification = repaired.isEqualTo(gap) ? ChargePaymentReceiptRepairClassification.CLIPPED
                        : ChargePaymentReceiptRepairClassification.CLIPPED_RESIDUAL;
            }
        }
        return new ChargePaymentReceiptRepairPlan(transaction, classification, amount.getAmount(), receiptsTotal.getAmount(),
                gap.getAmount(), feePortion.getAmount(), penaltyPortion.getAmount(), overpaymentPortion.getAmount(), List.copyOf(views),
                topUps);
    }

    /**
     * Delegates to the one sizing rule the replay also uses ({@link ChargePaymentReceiptAllocation#topUps}), so the
     * plan this reports is exactly what the replay will do. Only the cap differs by context: here it is measured from
     * the stored receipts of other live transactions, because the charge's own paid amount may have drifted away from
     * them - which is the defect being repaired.
     */
    private static List<ChargePaymentReceiptTopUp> computeTopUps(final List<LoanChargePaidBy> receipts, final Money gap,
            final MonetaryCurrency currency, final Map<String, Money> settledByKey, final Map<String, Money> carried) {
        final List<ChargePaymentReceiptTopUp> topUps = new ArrayList<>();
        ChargePaymentReceiptAllocation
                .topUps(receipts, gap, currency, receipt -> ChargePaymentReceiptAllocation.capOf(receipt, currency, settledByKey, carried))
                .forEach((receipt, increment) -> topUps
                        .add(new ChargePaymentReceiptTopUp(receipt, receipt.getAmount(), receipt.getAmount().add(increment.getAmount()))));
        return List.copyOf(topUps);
    }

}
