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
package org.apache.fineract.portfolio.loanaccount.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;

/**
 * The allocation rules a multi-receipt charge payment is replayed and repaired by, shared so that the replay
 * ({@code AbstractLoanRepaymentScheduleTransactionProcessor}) and the repair planner
 * ({@code ChargePaymentReceiptRepairPlanner}) can never drift apart: a repair is only correct if the planner predicts
 * exactly what the replay then does.
 */
public final class ChargePaymentReceiptAllocation {

    private ChargePaymentReceiptAllocation() {}

    /**
     * Orders receipts the way the charge-definition payment API allocated them, by the date the charge was due, with
     * the charge id and then the receipt id as tiebreaks so the order is total and deterministic.
     * <p>
     * The date deliberately does NOT come from {@link LoanCharge#getEffectiveDueDate()}: for an instalment fee that
     * returns the first UNPAID installment's due date, which moves as the replay marks installments paid, so the replay
     * and the planner would sort the same receipts differently. The receipt's stored installment number is immutable,
     * so an instalment-fee receipt is ordered by its own installment's due date instead.
     */
    public static List<LoanChargePaidBy> sortedForAllocation(final Collection<LoanChargePaidBy> receipts,
            final List<LoanRepaymentScheduleInstallment> installments) {
        final List<LoanChargePaidBy> sorted = new ArrayList<>(receipts);
        sorted.sort(Comparator
                .comparing((final LoanChargePaidBy receipt) -> allocationDate(receipt, installments),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(receipt -> receipt.getLoanCharge() == null ? null : receipt.getLoanCharge().getId(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LoanChargePaidBy::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        return sorted;
    }

    private static LocalDate allocationDate(final LoanChargePaidBy receipt, final List<LoanRepaymentScheduleInstallment> installments) {
        final LoanCharge loanCharge = receipt.getLoanCharge();
        if (loanCharge == null) {
            return null;
        }
        if (loanCharge.isInstalmentFee()) {
            final LoanInstallmentCharge installmentCharge = installmentCharge(receipt);
            if (installmentCharge != null && installmentCharge.getRepaymentInstallment() != null) {
                return installmentCharge.getRepaymentInstallment().getDueDate();
            }
            final LoanRepaymentScheduleInstallment installment = ChargePaymentReceiptInstallmentResolver.byInstallmentNumber(installments,
                    receipt.getInstallmentNumber());
            return installment == null ? null : installment.getDueDate();
        }
        return loanCharge.getDueLocalDate();
    }

    /**
     * Raises each receipt towards its cap, in order, until {@code gap} is consumed, and returns the increment per
     * receipt. This is the ONE sizing rule: the repair planner proposes with it and the replay applies it, so a dry run
     * always describes what the replay then does.
     * <p>
     * A receipt is only ever raised, never lowered and never re-split. That is what keeps a payment whose receipts
     * already sum to its amount untouched - its gap is zero, so every increment is zero - and what stops one payment
     * claiming room a later payment's receipts account for: each may only reach for the money it is itself missing.
     *
     * @param capOf
     *            what the balance behind a receipt may hold in total, net of waivers, write-offs and whatever other
     *            transactions have settled on it. Callers differ in how they measure "other transactions": the planner
     *            reads their stored receipts, the replay reads what the charges have been marked so far.
     */
    public static Map<LoanChargePaidBy, Money> topUps(final List<LoanChargePaidBy> sortedReceipts, final Money gap,
            final MonetaryCurrency currency, final Function<LoanChargePaidBy, Money> capOf) {
        final Map<LoanChargePaidBy, Money> increments = new LinkedHashMap<>();
        if (!gap.isGreaterThanZero()) {
            return increments;
        }
        final Map<String, Money> carried = new HashMap<>();
        for (final LoanChargePaidBy receipt : sortedReceipts) {
            carried.merge(allocationKey(receipt), Money.of(currency, receipt.getAmount()), Money::plus);
        }
        Money remaining = gap;
        for (final LoanChargePaidBy receipt : sortedReceipts) {
            if (!remaining.isGreaterThanZero()) {
                break;
            }
            final String key = allocationKey(receipt);
            final Money room = capOf.apply(receipt).minus(carried.get(key));
            if (!room.isGreaterThanZero()) {
                continue;
            }
            final Money increment = room.isGreaterThan(remaining) ? remaining : room;
            increments.put(receipt, increment);
            carried.merge(key, increment, Money::plus);
            remaining = remaining.minus(increment);
        }
        return increments;
    }

    /**
     * What every live transaction has settled with money, per balance a receipt can pay. The single input both the
     * planner and the replay measure "other transactions" from, so neither can size a top-up the other would not.
     */
    public static Map<String, Money> settledByKey(final Collection<LoanTransaction> transactions, final MonetaryCurrency currency) {
        final Map<String, Money> settled = new HashMap<>();
        if (transactions == null) {
            return settled;
        }
        for (final LoanTransaction transaction : transactions) {
            if (!isCashSettlementOfCharge(transaction)) {
                continue;
            }
            for (final LoanChargePaidBy receipt : transaction.getLoanChargesPaid()) {
                settled.merge(settlementKey(receipt), Money.of(currency, receipt.getAmount()), Money::plus);
            }
        }
        return settled;
    }

    /** What one transaction's own receipts carry, per balance, so it is not counted against itself. */
    public static Map<String, Money> carriedByKey(final LoanTransaction transaction, final MonetaryCurrency currency) {
        final Map<String, Money> carried = new HashMap<>();
        for (final LoanChargePaidBy receipt : transaction.getLoanChargesPaid()) {
            carried.merge(settlementKey(receipt), Money.of(currency, receipt.getAmount()), Money::plus);
        }
        return carried;
    }

    /**
     * The cap a receipt's top-up is bounded by: the balance it pays, net of waivers and write-offs and of what every
     * OTHER live transaction has settled on it. Excludes what this transaction's own receipts carry, which
     * {@link #topUps} subtracts itself.
     */
    public static Money capOf(final LoanChargePaidBy receipt, final MonetaryCurrency currency, final Map<String, Money> settledByKey,
            final Map<String, Money> carriedByThisTransaction) {
        final LoanCharge loanCharge = receipt.getLoanCharge();
        if (loanCharge == null || !loanCharge.isActive()) {
            return Money.zero(currency);
        }
        Money base;
        if (loanCharge.isInstalmentFee()) {
            final LoanInstallmentCharge installmentCharge = installmentCharge(receipt);
            if (installmentCharge == null) {
                return Money.zero(currency);
            }
            base = installmentCharge.getAmount(currency).minus(installmentCharge.getAmountWaived(currency))
                    .minus(installmentCharge.getAmountWrittenOff(currency));
        } else {
            base = Money.of(currency, loanCharge.amount()).minus(loanCharge.getAmountWaived(currency))
                    .minus(loanCharge.getAmountWrittenOff(currency));
        }
        final String key = settlementKey(receipt);
        Money elsewhere = settledByKey.getOrDefault(key, Money.zero(currency))
                .minus(carriedByThisTransaction.getOrDefault(key, Money.zero(currency)));
        if (elsewhere.isLessThanZero()) {
            elsewhere = Money.zero(currency);
        }
        if (loanCharge.isInstalmentFee()) {
            // Another transaction settles an instalment fee without naming an installment, so that money cannot be
            // attributed to one share. Deducting it from every share can only make the cap too small, which refuses a
            // repair, never too large, which would raise a receipt past what its installment can absorb.
            final String unattributed = unattributedKey(loanCharge);
            elsewhere = elsewhere.plus(settledByKey.getOrDefault(unattributed, Money.zero(currency))
                    .minus(carriedByThisTransaction.getOrDefault(unattributed, Money.zero(currency))));
        }
        return base.minus(elsewhere);
    }

    /** Where a receipt's settled amount is recorded: its own balance, or the charge when no installment is named. */
    private static String settlementKey(final LoanChargePaidBy receipt) {
        final LoanCharge loanCharge = receipt.getLoanCharge();
        if (loanCharge != null && loanCharge.isInstalmentFee() && receipt.getInstallmentNumber() == null) {
            return unattributedKey(loanCharge);
        }
        return allocationKey(receipt);
    }

    /** Money settled on an instalment fee without naming an installment. */
    public static String unattributedKey(final LoanCharge charge) {
        return charge.getId() + "/unattributed";
    }

    /**
     * Whether a receipt set mixes fee and penalty charges, which the installment allocation cannot route per receipt.
     */
    public static boolean hasMixedComponents(final Collection<LoanChargePaidBy> receipts) {
        Boolean fee = null;
        for (final LoanChargePaidBy receipt : receipts) {
            final LoanCharge loanCharge = receipt.getLoanCharge();
            if (loanCharge == null) {
                continue;
            }
            if (fee == null) {
                fee = loanCharge.isFeeCharge();
            } else if (fee != loanCharge.isFeeCharge()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The balance a receipt competes for: a charge, or one installment of an instalment fee. Two receipts sharing a key
     * share one balance; receipts with different keys never contend.
     */
    public static String allocationKey(final LoanChargePaidBy receipt) {
        final LoanCharge loanCharge = receipt.getLoanCharge();
        final Long chargeId = loanCharge == null ? null : loanCharge.getId();
        if (loanCharge != null && loanCharge.isInstalmentFee()) {
            return chargeId + "/" + receipt.getInstallmentNumber();
        }
        return String.valueOf(chargeId);
    }

    /** The per-installment row an instalment-fee receipt pays, or null when the receipt maps to none. */
    public static LoanInstallmentCharge installmentCharge(final LoanChargePaidBy receipt) {
        final LoanCharge loanCharge = receipt.getLoanCharge();
        if (loanCharge == null || !loanCharge.isInstalmentFee() || receipt.getInstallmentNumber() == null) {
            return null;
        }
        return loanCharge.getInstallmentLoanCharge(receipt.getInstallmentNumber());
    }

    /**
     * Whether a transaction's receipts represent money that actually settled the charge, and therefore reduce what
     * another transaction may claim on it.
     * <p>
     * Accruals, accrual adjustments and the NPA suspense pair all book receipts to record what was accrued against a
     * charge, not paid. A waiver or write-off relieves the charge, which the charge's own waived and written-off totals
     * already account for. A charge refund and a charge adjustment move money back out. Counting any of these as paid
     * pushes the room towards zero and makes a genuinely clipped payment look like a surplus.
     */
    public static boolean isCashSettlementOfCharge(final LoanTransaction transaction) {
        if (transaction == null || transaction.isReversed()) {
            return false;
        }
        if (transaction.isAccrual() || transaction.isAccrualActivity() || transaction.isAccrualAdjustment()
                || transaction.isAccrualSuspense() || transaction.isAccrualSuspenseReverse()) {
            return false;
        }
        if (transaction.isWaiver() || transaction.isChargesWaiver() || transaction.isWriteOff()) {
            return false;
        }
        // A charge refund and a charge adjustment move money back out, so they are not payments - but the replay marks
        // charges for them all the same, because both are repayment-like and go through the strategy. What the planner
        // must mirror is the room the replay consumes, not what is conceptually a payment, so they are counted here.
        return transaction.isChargePayment() || transaction.isRepaymentLikeType() || transaction.isRepaymentAtDisbursement();
    }

    /**
     * Whether this receipt's amount can be re-derived at all. A receipt whose charge is inactive or missing, or an
     * instalment-fee receipt with no per-installment row to size it against, has no balance to claim: deriving the
     * transaction around it would either move its money onto a sibling charge (so the stored receipts would sum above
     * the transaction amount) or send the whole payment to overpayment. Such a transaction is replayed verbatim and
     * reported instead, which is also how the repair planner classifies it.
     */
    public static boolean isDerivable(final LoanChargePaidBy receipt) {
        final LoanCharge loanCharge = receipt.getLoanCharge();
        if (loanCharge == null || !loanCharge.isActive()) {
            return false;
        }
        if (loanCharge.isDueAtDisbursement()) {
            return false;
        }
        return !loanCharge.isInstalmentFee() || installmentCharge(receipt) != null;
    }

}
