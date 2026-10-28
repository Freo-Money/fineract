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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductRoundingModeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises the loan's clipped charge-payment receipts (GST rows are rebuilt with them), runs the standard
 * {@code reprocessTransactions} in the same transaction and on the same loan instance, then verifies the outcome. Any
 * failed check throws, which rolls back the top-ups and the reprocess together.
 * <p>
 * Refused up front: loans that are not active, closed (obligations met) or overpaid, progressive-schedule loans,
 * charged-off loans and loans with a live credit balance refund. Products with accounting enabled are allowed:
 * reprocessing corrects a charge payment in place and never re-posts its journal entry, which already carries the full
 * amount when, as in this defect, the receipts were clipped after the payment was booked.
 * <p>
 * Always rolled back when, after the reprocess, the overpayment parked across the loan did not fall by what the
 * topped-up payments had parked, the overpayment parked outside charge payments rose, or the loan newly became
 * overpaid. When something was topped up, also when a topped-up or consistent charge payment still carries overpayment,
 * another charge payment carries more than before, or a topped-up charge's paid amount differs from its receipts. The
 * before-figures are the stored ones, so on a loan not reprocessed since multi-receipt charge payments started
 * replaying from their receipts, a payment that is not topped up and parks its clipped part for the first time fails
 * these checks too.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargePaymentReceiptTopUpServiceImpl implements ChargePaymentReceiptTopUpService {

    private static final String ACTION_TOPPED_UP = "TOPPED_UP";
    private static final String ACTION_NO_TOP_UP = "NO_TOP_UP";

    private final LoanAssembler loanAssembler;
    private final LoanWritePlatformService loanWritePlatformService;
    private final LoanProductRoundingModeService loanProductRoundingModeService;
    private final LoanTransactionRepository loanTransactionRepository;

    @Override
    public boolean isRequested(final JsonCommand command) {
        // The command has always ignored its body, so a body that is not a JSON object must keep working without the
        // flag.
        if (!(command.parsedJson() instanceof JsonObject body) || !body.has(TOP_UP_CLIPPED_CHARGE_RECEIPTS_PARAM)) {
            return false;
        }
        // Validated rather than read leniently: a lenient read turns "yes", 1 or null into false, which would silently
        // run a plain reprocess instead of the requested top-up.
        final JsonElement element = body.get(TOP_UP_CLIPPED_CHARGE_RECEIPTS_PARAM);
        final String value = element.isJsonPrimitive() ? element.getAsString().trim() : null;
        final DataValidatorBuilder validator = new DataValidatorBuilder().resource("loan").parameter(TOP_UP_CLIPPED_CHARGE_RECEIPTS_PARAM)
                .value(value).notNull();
        if (value != null) {
            validator.validateForBooleanValue();
        }
        validator.throwValidationErrors();
        return Boolean.parseBoolean(value);
    }

    @Transactional
    @Override
    public CommandProcessingResult reprocessWithTopUp(final Long loanId, final JsonCommand command) {
        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        validateLoanInScope(loan);

        final MonetaryCurrency currency = loan.getCurrency();
        final LoanStatus statusBefore = loan.getStatus();
        final List<LoanTransaction> liveTransactions = loan.getLoanTransactions(LoanTransaction::isNotReversed);
        final Parked parkedBefore = Parked.on(liveTransactions, currency);
        // Nothing has changed the loan yet, so its live transactions are the database's; after the reprocess the ids
        // are read back from the database.
        final Set<Long> liveIdsBefore = liveTransactions.stream().map(LoanTransaction::getId).collect(Collectors.toSet());
        final Money repaymentChargesBefore = chargesPaidByRepayments(liveTransactions, currency);

        final List<ChargePaymentReceiptTopUpPlan> plans = applyTopUps(loan, currency, liveTransactions);
        final boolean toppedUp = plans.stream().anyMatch(ChargePaymentReceiptTopUpPlan::hasTopUps);

        // Re-assembles the loan by id inside this persistence context, so it reprocesses the receipts raised above.
        this.loanWritePlatformService.reprocessLoanTransactions(loanId, command);

        if (toppedUp) {
            assertChargePaymentsReconciled(loan, plans, currency);
            assertChargesMatchReceipts(loan, plans, currency);
        }
        final Parked parkedAfter = Parked.on(loan.getLoanTransactions(LoanTransaction::isNotReversed), currency);
        assertOverpaymentReleased(loan, plans, currency, statusBefore, parkedBefore, parkedAfter);
        logTopUps(loan, plans);

        // Read back from the database: the reprocess saves some replacements (accrual activity) without adding them to
        // the loan's in-memory transactions.
        final Set<Long> liveIdsAfter = liveTransactionIds(loan);
        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put(TOP_UP_CLIPPED_CHARGE_RECEIPTS_PARAM, true);
        changes.put("action", toppedUp ? ACTION_TOPPED_UP : ACTION_NO_TOP_UP);
        changes.put("statusBefore", statusBefore.name());
        changes.put("statusAfter", loan.getStatus().name());
        changes.put("parkedOverpaymentBefore", parkedBefore.total().getAmount());
        changes.put("parkedOverpaymentAfter", parkedAfter.total().getAmount());
        // A fall means a repayment had paid dues a top-up claims and its money moved on to other dues (principal or
        // interest on an active loan) instead of parking, which the overpayment check above cannot see.
        changes.put("repaymentChargesBefore", repaymentChargesBefore.getAmount());
        changes.put("repaymentChargesAfter", chargesPaidByRepayments(loan.getLoanTransactions(), currency).getAmount());
        changes.put("reversedTransactionIds", liveIdsBefore.stream().filter(id -> !liveIdsAfter.contains(id)).sorted().toList());
        changes.put("newTransactionIds", liveIdsAfter.stream().filter(id -> !liveIdsBefore.contains(id)).sorted().toList());
        changes.put("chargePaymentCounts", countByClassification(plans));
        // Consistent and single-receipt payments are only counted: this result is stored in
        // m_portfolio_command_source.result (64 KB on MySQL), which a long penalty history would overflow.
        changes.put("chargePayments", plans.stream().filter(ChargePaymentReceiptTopUpServiceImpl::needsAttention)
                .map(ChargePaymentReceiptTopUpServiceImpl::describe).toList());

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(loan.getId()) //
                .withEntityExternalId(loan.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loan.getId()) //
                .with(changes) //
                .build();
    }

    private static void validateLoanInScope(final Loan loan) {
        final LoanStatus status = loan.getStatus();
        if (!status.isActive() && !status.isClosedObligationsMet() && !status.isOverpaid()) {
            throw refusal(loan, "status",
                    "its status is " + status.name() + "; only active, closed (obligations met) and overpaid loans are supported");
        }
        if (loan.isProgressiveSchedule()) {
            throw refusal(loan, "progressive.schedule", "it uses a progressive schedule");
        }
        if (loan.isChargedOff()) {
            throw refusal(loan, "charged.off", "it is charged off");
        }
        if (loan.getLoanTransactions().stream().anyMatch(LoanTransaction::isCreditBalanceRefund)) {
            throw refusal(loan, "credit.balance.refund", "a credit balance refund exists and may have used the overpayment this removes");
        }
    }

    private static GeneralPlatformDomainRuleException refusal(final Loan loan, final String code, final String reason) {
        return new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.receipt.topup." + code,
                "Charge payment receipts of loan " + loan.getId() + " cannot be topped up: " + reason + ".", loan.getId());
    }

    /**
     * Plans and applies the charge payments one at a time in (transaction date, id) order. Which payments are short is
     * taken once, before any top-up, and a payment is only topped up when no other short payment has receipts on any of
     * its charges that still have room. Two topped-up payments therefore never share a charge, so merging each top-up
     * into {@code settledByCharge} changes no later plan today; it keeps the room right should that rule be relaxed.
     */
    private List<ChargePaymentReceiptTopUpPlan> applyTopUps(final Loan loan, final MonetaryCurrency currency,
            final List<LoanTransaction> liveTransactions) {
        final Map<Long, Money> settledByCharge = ChargePaymentReceiptTopUpPlanner.settledByCharge(liveTransactions, currency);
        final List<LoanTransaction> chargePayments = liveTransactions.stream().filter(LoanTransaction::isChargePayment)
                .sorted(Comparator.comparing(LoanTransaction::getTransactionDate).thenComparing(LoanTransaction::getId)).toList();
        final Map<Long, Set<Long>> shortPaymentIdsByCharge = ChargePaymentReceiptTopUpPlanner.shortPaymentIdsByCharge(chargePayments,
                currency);
        // Resolved on the first top-up only: a loan with nothing to top up never needs it.
        RoundingMode taxRoundingMode = null;
        final List<ChargePaymentReceiptTopUpPlan> plans = new ArrayList<>(chargePayments.size());
        for (final LoanTransaction chargePayment : chargePayments) {
            final ChargePaymentReceiptTopUpPlan plan = ChargePaymentReceiptTopUpPlanner.plan(chargePayment, currency, settledByCharge,
                    shortPaymentIdsByCharge);
            for (final ChargePaymentReceiptTopUp topUp : plan.topUps()) {
                if (taxRoundingMode == null) {
                    taxRoundingMode = this.loanProductRoundingModeService.resolveTaxRoundingMode(loan.getLoanProduct().getId());
                }
                // Rebuilds the receipt's GST rows for the new amount.
                topUp.receipt().setAmount(topUp.to(), taxRoundingMode);
                settledByCharge.merge(topUp.receipt().getLoanCharge().getId(),
                        Money.of(currency, topUp.to()).minus(Money.of(currency, topUp.from())), Money::plus);
            }
            plans.add(plan);
        }
        return plans;
    }

    /**
     * Logged only once every check has passed, so a top-up that a failed check rolls back is never logged as applied.
     */
    private static void logTopUps(final Loan loan, final List<ChargePaymentReceiptTopUpPlan> plans) {
        for (final ChargePaymentReceiptTopUpPlan plan : plans) {
            for (final ChargePaymentReceiptTopUp topUp : plan.topUps()) {
                log.info("Charge payment receipt top-up: loan [id={}] transaction [id={}] receipt [id={}] charge [id={}] {} -> {}",
                        loan.getId(), plan.transaction().getId(), topUp.receipt().getId(), topUp.receipt().getLoanCharge().getId(),
                        topUp.from(), topUp.to());
            }
        }
    }

    /**
     * A topped-up or consistent charge payment must be fully absorbed by fee/penalty after the reprocess; any other
     * must not carry more overpayment than before.
     */
    private static void assertChargePaymentsReconciled(final Loan loan, final List<ChargePaymentReceiptTopUpPlan> plans,
            final MonetaryCurrency currency) {
        for (final ChargePaymentReceiptTopUpPlan plan : plans) {
            final LoanTransaction transaction = plan.transaction();
            final Money amount = transaction.getAmount(currency);
            final Money overpayment = transaction.getOverPaymentPortion(currency);
            final Money overpaymentBefore = Money.of(currency, plan.overpaymentPortion());
            final Money charged = ChargePaymentReceiptTopUpPlanner.chargesPortion(transaction, currency);
            final boolean mustReconcile = plan.hasTopUps() || plan.classification() == ChargePaymentReceiptClassification.CONSISTENT;
            final boolean failed = mustReconcile ? !overpayment.isZero() || !charged.isEqualTo(amount)
                    : overpayment.isGreaterThan(overpaymentBefore);
            if (failed) {
                throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.receipt.topup.not.reconciled",
                        "Charge payment " + transaction.getId() + " (" + plan.classification().name() + ") of loan " + loan.getId()
                                + " did not reconcile after reprocessing (amount " + amount + ", fee+penalty " + charged + ", overpayment "
                                + overpayment + ", stored before " + overpaymentBefore + "); the loan was rolled back.",
                        loan.getId(), transaction.getId());
            }
        }
    }

    /** Each topped-up charge must be marked paid by exactly what the live receipts on it carry. */
    private static void assertChargesMatchReceipts(final Loan loan, final List<ChargePaymentReceiptTopUpPlan> plans,
            final MonetaryCurrency currency) {
        final Map<Long, LoanCharge> toppedUpCharges = new HashMap<>();
        for (final ChargePaymentReceiptTopUpPlan plan : plans) {
            plan.topUps().forEach(topUp -> toppedUpCharges.put(topUp.receipt().getLoanCharge().getId(), topUp.receipt().getLoanCharge()));
        }
        // Summed again after the reprocess: reverse-replayed transactions carry fresh receipts.
        final Map<Long, Money> receiptsByCharge = ChargePaymentReceiptTopUpPlanner.settledByCharge(loan.getLoanTransactions(), currency);
        for (final LoanCharge charge : toppedUpCharges.values()) {
            final Money paid = charge.getAmountPaid(currency);
            final Money receipts = receiptsByCharge.getOrDefault(charge.getId(), Money.zero(currency));
            if (!paid.isEqualTo(receipts)) {
                throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.receipt.topup.charge.mismatch",
                        "Charge " + charge.getId() + " of loan " + loan.getId() + " is marked paid " + paid + " but its receipts total "
                                + receipts + " after reprocessing; the loan was rolled back.",
                        loan.getId(), charge.getId());
            }
        }
    }

    /**
     * The top-up releases what the topped-up charge payments had parked, so the overpayment parked across the loan must
     * fall by at least that much. Anything less means money parked elsewhere instead: a charge payment whose clipped
     * part has no room, or a repayment that paid the same charge again (double collection, which is refunded rather
     * than topped up). The overpayment parked outside charge payments must not rise either: on its own the loan-wide
     * figure misses a repayment that parks more when another charge payment parks less than its stored (stale) figure.
     * Only money that parks is seen here: on an active loan a repayment's displaced money pays later principal or
     * interest instead, which the result reports through {@code repaymentChargesBefore/After}.
     */
    private static void assertOverpaymentReleased(final Loan loan, final List<ChargePaymentReceiptTopUpPlan> plans,
            final MonetaryCurrency currency, final LoanStatus statusBefore, final Parked parkedBefore, final Parked parkedAfter) {
        Money released = Money.zero(currency);
        for (final ChargePaymentReceiptTopUpPlan plan : plans) {
            if (plan.hasTopUps()) {
                released = released.plus(Money.of(currency, plan.overpaymentPortion()));
            }
        }
        final Money allowed = parkedBefore.total().minus(released);
        if (parkedAfter.total().isGreaterThan(allowed)
                || parkedAfter.outsideChargePayments().isGreaterThan(parkedBefore.outsideChargePayments())) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.receipt.topup.overpayment.moved",
                    "Overpayment parked on loan " + loan.getId() + " would go from " + parkedBefore.total() + " to " + parkedAfter.total()
                            + " (at most " + allowed + " allowed), and outside charge payments from " + parkedBefore.outsideChargePayments()
                            + " to " + parkedAfter.outsideChargePayments()
                            + " (no rise allowed): a charge payment would park a clipped part its charges have no room for, or a"
                            + " repayment that paid the same charge again would park it. The loan was rolled back; review it for refund.",
                    loan.getId());
        }
        if (!statusBefore.isOverpaid() && loan.getStatus().isOverpaid()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.receipt.topup.became.overpaid",
                    "Loan " + loan.getId() + " would become overpaid; the loan was rolled back.", loan.getId());
        }
    }

    /** Fee and penalty paid by the live repayment-like transactions, i.e. everything except charge payments. */
    private static Money chargesPaidByRepayments(final Collection<LoanTransaction> transactions, final MonetaryCurrency currency) {
        Money paid = Money.zero(currency);
        for (final LoanTransaction transaction : transactions) {
            if (transaction.isNotReversed() && (transaction.isRepaymentLikeType() || transaction.isRecoveryRepayment())) {
                paid = paid.plus(ChargePaymentReceiptTopUpPlanner.chargesPortion(transaction, currency));
            }
        }
        return paid;
    }

    private Set<Long> liveTransactionIds(final Loan loan) {
        final Set<Long> ids = new HashSet<>(this.loanTransactionRepository.findTransactionIdsByLoan(loan));
        this.loanTransactionRepository.findReversedTransactionIdsByLoan(loan).forEach(ids::remove);
        return ids;
    }

    private static Map<String, Long> countByClassification(final List<ChargePaymentReceiptTopUpPlan> plans) {
        final Map<String, Long> counts = new LinkedHashMap<>();
        plans.forEach(plan -> counts.merge(plan.classification().name(), 1L, Long::sum));
        return counts;
    }

    private static boolean needsAttention(final ChargePaymentReceiptTopUpPlan plan) {
        return plan.classification() != ChargePaymentReceiptClassification.CONSISTENT
                && plan.classification() != ChargePaymentReceiptClassification.SINGLE_RECEIPT;
    }

    private static Map<String, Object> describe(final ChargePaymentReceiptTopUpPlan plan) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("transactionId", plan.transaction().getId());
        out.put("classification", plan.classification().name());
        out.put("amount", plan.amount());
        out.put("receiptsTotalBefore", plan.receiptsTotal());
        out.put("overpaymentBefore", plan.overpaymentPortion());
        final List<Map<String, Object>> topUps = new ArrayList<>();
        for (final ChargePaymentReceiptTopUp topUp : plan.topUps()) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("receiptId", topUp.receipt().getId());
            entry.put("chargeId", topUp.receipt().getLoanCharge().getId());
            entry.put("from", topUp.from());
            entry.put("to", topUp.to());
            topUps.add(entry);
        }
        out.put("topUps", topUps);
        return out;
    }

    /**
     * Overpayment parked on live transactions: in total, charge payments included, and outside charge payments.
     * Measured on the transactions because the loan's overpaid amount leaves charge payments out.
     */
    private record Parked(Money total, Money outsideChargePayments) {

        static Parked on(final Collection<LoanTransaction> liveTransactions, final MonetaryCurrency currency) {
            Money total = Money.zero(currency);
            Money outsideChargePayments = Money.zero(currency);
            for (final LoanTransaction transaction : liveTransactions) {
                final Money overpayment = transaction.getOverPaymentPortion(currency);
                total = total.plus(overpayment);
                if (!transaction.isChargePayment()) {
                    outsideChargePayments = outsideChargePayments.plus(overpayment);
                }
            }
            return new Parked(total, outsideChargePayments);
        }
    }
}
