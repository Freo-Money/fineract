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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeEffectiveDueDateComparator;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;

/**
 * Classifies a charge payment's stored receipts and plans the top-ups that make them sum to its amount again.
 * <p>
 * Before multi-receipt charge payments were replayed from their receipts, reprocessing lowered those receipts in place.
 * Now that the receipts are authoritative, the lowered part parks as overpayment on every reprocess. Raising each
 * receipt back within what its charge can still absorb, in effective-due-date order, restores the original allocation.
 * Receipts are only ever raised, never lowered or moved.
 */
public final class ChargePaymentReceiptTopUpPlanner {

    /**
     * Transaction types whose receipts do not use up a charge's room: waivers and write-offs are netted through the
     * charge's own waived and written-off totals, and the accrual family books income, not payment.
     */
    private static final Set<LoanTransactionType> NON_SETTLING_TYPES = EnumSet.of(LoanTransactionType.WAIVE_INTEREST,
            LoanTransactionType.WAIVE_CHARGES, LoanTransactionType.WRITEOFF, LoanTransactionType.ACCRUAL,
            LoanTransactionType.ACCRUAL_ACTIVITY, LoanTransactionType.ACCRUAL_ADJUSTMENT, LoanTransactionType.ACCRUAL_SUSPENSE,
            LoanTransactionType.ACCRUAL_WRITEOFF, LoanTransactionType.ACCRUAL_SUSPENSE_REVERSE);

    /**
     * The order the charge-definition payment API allocated receipts in: charge effective due date, then receipt id.
     */
    private static final Comparator<LoanChargePaidBy> RECEIPT_ORDER = Comparator
            .comparing(LoanChargePaidBy::getLoanCharge, Comparator.nullsLast(LoanChargeEffectiveDueDateComparator.INSTANCE))
            .thenComparing(LoanChargePaidBy::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    /** Whether the receipts of this transaction settle the charges they name. */
    public static boolean settlesCharges(final LoanTransaction transaction) {
        return transaction.isNotReversed() && !NON_SETTLING_TYPES.contains(transaction.getTypeOf());
    }

    /**
     * Positive receipts of the settling transactions among {@code transactions}, summed by charge id. Negative receipts
     * are left out: a refund for an active loan appends a fresh negative set on every replay, and a charge refund's
     * negative link receipt never lowers the charge's paid amount, so counting them would overstate a charge's room.
     */
    public static Map<Long, Money> settledByCharge(final Collection<LoanTransaction> transactions, final MonetaryCurrency currency) {
        final Map<Long, Money> byCharge = new HashMap<>();
        for (final LoanTransaction transaction : transactions) {
            if (!settlesCharges(transaction)) {
                continue;
            }
            for (final LoanChargePaidBy receipt : transaction.getLoanChargesPaid()) {
                if (receipt.getLoanCharge() != null && receipt.getAmount() != null && receipt.getAmount().signum() > 0) {
                    byCharge.merge(receipt.getLoanCharge().getId(), Money.of(currency, receipt.getAmount()), Money::plus);
                }
            }
        }
        return byCharge;
    }

    /**
     * @param settledByCharge
     *            {@link #settledByCharge} over the loan's live transactions, this one included, with the top-ups
     *            applied to earlier charge payments merged in; it is not modified
     */
    public ChargePaymentReceiptTopUpPlan plan(final LoanTransaction transaction, final MonetaryCurrency currency,
            final Map<Long, Money> settledByCharge) {
        final Money amount = transaction.getAmount(currency);
        final Money charged = transaction.getFeeChargesPortion(currency).plus(transaction.getPenaltyChargesPortion(currency));

        final List<LoanChargePaidBy> receipts = new ArrayList<>(transaction.getLoanChargesPaid());
        receipts.sort(RECEIPT_ORDER);
        Money receiptsTotal = Money.zero(currency);
        boolean anyInactiveCharge = false;
        boolean anyUnsupportedCharge = false;
        boolean anyFee = false;
        boolean anyPenalty = false;
        for (final LoanChargePaidBy receipt : receipts) {
            receiptsTotal = receiptsTotal.plus(Money.of(currency, receipt.getAmount()));
            final LoanCharge charge = receipt.getLoanCharge();
            if (charge == null || !charge.isActive()) {
                anyInactiveCharge = true;
                continue;
            }
            anyUnsupportedCharge |= charge.isInstalmentFee() || charge.isDueAtDisbursement();
            if (charge.isFeeCharge()) {
                anyFee = true;
            } else {
                anyPenalty = true;
            }
        }

        final Money gap = amount.minus(receiptsTotal);
        ChargePaymentReceiptClassification classification;
        List<ChargePaymentReceiptTopUp> topUps = List.of();
        if (receipts.isEmpty()) {
            classification = ChargePaymentReceiptClassification.NO_RECEIPTS;
        } else if (anyInactiveCharge) {
            // Before the single-receipt test: replay leaves a deactivated charge out, so its receipt parks.
            classification = ChargePaymentReceiptClassification.RECEIPT_ON_INACTIVE_CHARGE;
        } else if (receipts.size() == 1) {
            classification = ChargePaymentReceiptClassification.SINGLE_RECEIPT;
        } else if (anyUnsupportedCharge) {
            classification = ChargePaymentReceiptClassification.UNSUPPORTED_CHARGE;
        } else if (anyFee && anyPenalty) {
            classification = ChargePaymentReceiptClassification.MIXED;
        } else if (gap.isLessThanZero()) {
            classification = ChargePaymentReceiptClassification.OVER;
        } else if (gap.isZero()) {
            classification = charged.isLessThan(receiptsTotal) ? ChargePaymentReceiptClassification.INSTALLMENT_ROOM
                    : ChargePaymentReceiptClassification.CONSISTENT;
        } else {
            final List<ChargePaymentReceiptTopUp> candidates = computeTopUps(receipts, gap, currency, settledByCharge);
            Money raised = Money.zero(currency);
            for (final ChargePaymentReceiptTopUp topUp : candidates) {
                raised = raised.plus(Money.of(currency, topUp.to()).minus(Money.of(currency, topUp.from())));
            }
            if (candidates.isEmpty()) {
                classification = ChargePaymentReceiptClassification.SURPLUS;
            } else if (!raised.isEqualTo(gap)) {
                // A partial raise is never applied: the residual is left for refund.
                classification = ChargePaymentReceiptClassification.CLIPPED_RESIDUAL;
            } else if (charged.isLessThan(receiptsTotal)) {
                // Replay parks what a receipt's installment cannot absorb, so raising receipts would never reconcile.
                classification = ChargePaymentReceiptClassification.INSTALLMENT_ROOM;
            } else {
                classification = ChargePaymentReceiptClassification.CLIPPED;
                topUps = candidates;
            }
        }
        return new ChargePaymentReceiptTopUpPlan(transaction, classification, amount.getAmount(), receiptsTotal.getAmount(),
                transaction.getOverPaymentPortion(currency).getAmount(), topUps);
    }

    /**
     * Walks the receipts in {@link #RECEIPT_ORDER} and raises each towards its charge's room until the gap is used up.
     * The room is the charge amount net of waivers, write-offs and every settling receipt on it (this transaction's
     * included), so the receipts of one charge never exceed the charge.
     */
    private static List<ChargePaymentReceiptTopUp> computeTopUps(final List<LoanChargePaidBy> receipts, final Money gap,
            final MonetaryCurrency currency, final Map<Long, Money> settledByCharge) {
        final Map<Long, Money> settled = new HashMap<>(settledByCharge);
        final List<ChargePaymentReceiptTopUp> topUps = new ArrayList<>();
        Money remaining = gap;
        for (final LoanChargePaidBy receipt : receipts) {
            if (!remaining.isGreaterThanZero()) {
                break;
            }
            final LoanCharge charge = receipt.getLoanCharge();
            final Money room = charge.getAmount(currency).minus(charge.getAmountWaived(currency))
                    .minus(charge.getAmountWrittenOff(currency)).minus(settled.getOrDefault(charge.getId(), Money.zero(currency)));
            if (!room.isGreaterThanZero()) {
                continue;
            }
            final Money increment = room.isGreaterThan(remaining) ? remaining : room;
            // Money keeps the currency scale, which is what the receipt's GST split is computed at on creation.
            topUps.add(new ChargePaymentReceiptTopUp(receipt, receipt.getAmount(),
                    Money.of(currency, receipt.getAmount()).plus(increment).getAmount()));
            settled.merge(charge.getId(), increment, Money::plus);
            remaining = remaining.minus(increment);
        }
        return List.copyOf(topUps);
    }
}
