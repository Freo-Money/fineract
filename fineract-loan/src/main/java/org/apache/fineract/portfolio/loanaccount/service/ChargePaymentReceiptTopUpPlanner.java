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
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * receipt back within what its charge can still absorb, in effective-due-date order, makes the receipts sum to the
 * amount again; the service's checks after the reprocess decide whether the replay agrees. Room on a charge that
 * another short charge payment also has receipts on could belong to either payment, so such a payment is reported
 * rather than topped up. Receipts are only ever raised, never lowered or moved.
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

    private ChargePaymentReceiptTopUpPlanner() {}

    /** Whether the receipts of this transaction settle the charges they name. */
    private static boolean settlesCharges(final LoanTransaction transaction) {
        return transaction.isNotReversed() && !NON_SETTLING_TYPES.contains(transaction.getTypeOf());
    }

    /** What the last replay absorbed into the transaction's fee and penalty portions. */
    public static Money chargesPortion(final LoanTransaction transaction, final MonetaryCurrency currency) {
        return transaction.getFeeChargesPortion(currency).plus(transaction.getPenaltyChargesPortion(currency));
    }

    /**
     * The transaction's receipts, summed. The one definition of a short charge payment (receipts below its amount) for
     * both {@link #shortPaymentIdsByCharge} and {@link #plan}, which must agree on it.
     */
    private static Money receiptsTotal(final LoanTransaction transaction, final MonetaryCurrency currency) {
        Money total = Money.zero(currency);
        for (final LoanChargePaidBy receipt : transaction.getLoanChargesPaid()) {
            total = total.plus(Money.of(currency, receipt.getAmount()));
        }
        return total;
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
     * Ids of the charge payments among {@code chargePayments} whose receipts sum below their amount, by the charges
     * those receipts name. Room on such a charge could belong to any of them.
     */
    public static Map<Long, Set<Long>> shortPaymentIdsByCharge(final Collection<LoanTransaction> chargePayments,
            final MonetaryCurrency currency) {
        final Map<Long, Set<Long>> byCharge = new HashMap<>();
        for (final LoanTransaction payment : chargePayments) {
            if (!receiptsTotal(payment, currency).isLessThan(payment.getAmount(currency))) {
                continue;
            }
            for (final LoanChargePaidBy receipt : payment.getLoanChargesPaid()) {
                if (receipt.getLoanCharge() != null) {
                    byCharge.computeIfAbsent(receipt.getLoanCharge().getId(), chargeId -> new HashSet<>()).add(payment.getId());
                }
            }
        }
        return byCharge;
    }

    /**
     * @param settledByCharge
     *            {@link #settledByCharge} over the loan's live transactions, this one included, with the top-ups
     *            applied to earlier charge payments merged in; it is not modified
     * @param shortPaymentIdsByCharge
     *            {@link #shortPaymentIdsByCharge} over the loan's live charge payments before any top-up
     */
    public static ChargePaymentReceiptTopUpPlan plan(final LoanTransaction transaction, final MonetaryCurrency currency,
            final Map<Long, Money> settledByCharge, final Map<Long, Set<Long>> shortPaymentIdsByCharge) {
        final Money amount = transaction.getAmount(currency);
        final Money charged = chargesPortion(transaction, currency);
        final Money receiptsTotal = receiptsTotal(transaction, currency);

        final List<LoanChargePaidBy> receipts = new ArrayList<>(transaction.getLoanChargesPaid());
        receipts.sort(RECEIPT_ORDER);
        boolean anyInactiveCharge = false;
        boolean anyUnsupportedCharge = false;
        boolean anyFee = false;
        boolean anyPenalty = false;
        for (final LoanChargePaidBy receipt : receipts) {
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
            final Map<Long, Money> roomByCharge = roomByCharge(receipts, currency, settledByCharge);
            if (roomByCharge.isEmpty()) {
                classification = ChargePaymentReceiptClassification.SURPLUS;
            } else if (Money.zero(currency).plus(roomByCharge.values()).isLessThan(gap)) {
                // A partial raise is never applied: the residual is left for refund. Decided before the shared-room
                // test, so this room may also be claimed by another short payment (see CLIPPED_RESIDUAL).
                classification = ChargePaymentReceiptClassification.CLIPPED_RESIDUAL;
            } else if (sharesRoomWithAnotherShortPayment(transaction, roomByCharge, shortPaymentIdsByCharge)) {
                classification = ChargePaymentReceiptClassification.AMBIGUOUS_ROOM;
            } else if (charged.isLessThan(receiptsTotal)) {
                // Replay parks what a receipt's installment cannot absorb, so raising receipts would never reconcile.
                classification = ChargePaymentReceiptClassification.INSTALLMENT_ROOM;
            } else {
                classification = ChargePaymentReceiptClassification.CLIPPED;
                topUps = computeTopUps(receipts, gap, currency, roomByCharge);
            }
        }
        return new ChargePaymentReceiptTopUpPlan(transaction, classification, amount.getAmount(), receiptsTotal.getAmount(),
                transaction.getOverPaymentPortion(currency).getAmount(), topUps);
    }

    /**
     * Room left on each of the receipts' charges, in {@link #RECEIPT_ORDER}, charges without room left out. The room is
     * the charge amount net of waivers, write-offs and every settling receipt on it (this transaction's included), so
     * at currency precision the receipts of one charge never exceed the charge. Room is measured at that precision, so
     * a stored receipt with extra decimals can end up above it by less than one minor unit.
     */
    private static Map<Long, Money> roomByCharge(final List<LoanChargePaidBy> receipts, final MonetaryCurrency currency,
            final Map<Long, Money> settledByCharge) {
        final Map<Long, Money> rooms = new LinkedHashMap<>();
        for (final LoanChargePaidBy receipt : receipts) {
            final LoanCharge charge = receipt.getLoanCharge();
            final Money room = charge.getAmount(currency).minus(charge.getAmountWaived(currency))
                    .minus(charge.getAmountWrittenOff(currency)).minus(settledByCharge.getOrDefault(charge.getId(), Money.zero(currency)));
            if (room.isGreaterThanZero()) {
                rooms.putIfAbsent(charge.getId(), room);
            }
        }
        return rooms;
    }

    private static boolean sharesRoomWithAnotherShortPayment(final LoanTransaction transaction, final Map<Long, Money> roomByCharge,
            final Map<Long, Set<Long>> shortPaymentIdsByCharge) {
        for (final Long chargeId : roomByCharge.keySet()) {
            for (final Long paymentId : shortPaymentIdsByCharge.getOrDefault(chargeId, Set.of())) {
                if (!paymentId.equals(transaction.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Walks the receipts in {@link #RECEIPT_ORDER} and raises each by its charge's room until the gap is used up; a
     * charge's room goes to its first receipt, so later receipts on the same charge are left as they are.
     */
    private static List<ChargePaymentReceiptTopUp> computeTopUps(final List<LoanChargePaidBy> receipts, final Money gap,
            final MonetaryCurrency currency, final Map<Long, Money> roomByCharge) {
        final Map<Long, Money> unclaimedRoom = new HashMap<>(roomByCharge);
        final List<ChargePaymentReceiptTopUp> topUps = new ArrayList<>();
        Money remaining = gap;
        for (final LoanChargePaidBy receipt : receipts) {
            if (!remaining.isGreaterThanZero()) {
                break;
            }
            // Null when the charge has no room, or an earlier receipt on it already took the room.
            final Money room = unclaimedRoom.remove(receipt.getLoanCharge().getId());
            if (room == null) {
                continue;
            }
            final Money increment = room.isGreaterThan(remaining) ? remaining : room;
            topUps.add(new ChargePaymentReceiptTopUp(receipt, receipt.getAmount(), raised(receipt.getAmount(), increment, currency)));
            remaining = remaining.minus(increment);
        }
        return List.copyOf(topUps);
    }

    /**
     * The stored amount plus the increment, never re-rounded. A result with no more decimals than the currency takes
     * the currency scale, which is what the receipt's GST split is computed at on creation; one with more keeps them,
     * unless it would round to another minor unit than planned (HALF_EVEN on an exact half unit is not
     * shift-invariant). The replay reads each receipt through {@link Money#of}, so such a receipt takes the planned
     * currency-precision amount instead, which is still above the stored one: the increment is at least one minor unit.
     */
    private static BigDecimal raised(final BigDecimal stored, final Money increment, final MonetaryCurrency currency) {
        final BigDecimal raised = stored.add(increment.getAmount());
        final int digits = currency.getDigitsAfterDecimal();
        if (raised.stripTrailingZeros().scale() <= digits) {
            return raised.setScale(digits);
        }
        final Money planned = Money.of(currency, stored).plus(increment);
        return Money.of(currency, raised).isEqualTo(planned) ? raised : planned.getAmount();
    }
}
