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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.common.AccountingConstants.AccrualAccountsForLoan;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryType;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.PortfolioProductType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRelationTypeEnum;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductRoundingModeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Raises the loan's clipped charge-payment receipts (GST rows are rebuilt with them), runs the standard
 * {@code reprocessTransactions} in the same transaction and on the same loan instance, then verifies the outcome. Any
 * failed check throws, which rolls back the top-ups and the reprocess together.
 * <p>
 * Refused up front: loans that are not active, closed (obligations met) or overpaid, progressive-schedule loans,
 * charged-off loans and loans with a live credit balance refund or refund for an active loan. Products with accounting
 * enabled are allowed: reprocessing corrects a charge payment in place and never re-posts its journal entry, which
 * carries the full amount when, as in this defect, the receipts were clipped after the payment was booked. A payment
 * whose journal entries hold overpayment is therefore not topped up
 * ({@link ChargePaymentReceiptClassification#LEDGER_OVERPAYMENT}).
 * <p>
 * Always rolled back when, after the reprocess, the overpayment parked across the loan did not fall by what the
 * topped-up payments had parked, the overpayment parked outside charge payments rose, the loan newly became overpaid,
 * or repayments paid less fee and penalty than before. When something was topped up, also when any repayment paid less
 * fee, or less penalty, than before, a topped-up or consistent charge payment still carries overpayment, another charge
 * payment carries more than before, or a charge a topped-up payment has receipts on is marked paid other than its
 * receipts. The before-figures are the stored ones, so a loan whose stored figures are out of date can fail these
 * checks with nothing topped up; the messages then say so.
 * <p>
 * Once every check has passed, the loan's delinquency tag is recalculated when something was topped up or its status
 * changed, as after a payment: the reprocess leaves the tag as it was, and COB never revisits a loan the top-up closed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargePaymentReceiptTopUpServiceImpl implements ChargePaymentReceiptTopUpService {

    private static final String ACTION_TOPPED_UP = "TOPPED_UP";
    private static final String ACTION_NO_TOP_UP = "NO_TOP_UP";

    // The result is stored in m_portfolio_command_source.result (TEXT, 64 KB on MySQL), so its lists are capped well
    // below that. Every committed top-up is also logged.
    private static final int MAX_LISTED_CHARGE_PAYMENTS = 100;
    private static final int MAX_LISTED_TOP_UPS = 200;
    private static final int MAX_LISTED_TRANSACTION_IDS = 200;
    private static final int MAX_NAMED_TRANSACTIONS = 5;

    private static final Pattern NON_LETTERS = Pattern.compile("[^A-Za-z]");
    private static final List<String> FLAG_WORDS = List.of("topup", "clipped", "receipt");

    /**
     * Credits minus debits of a loan transaction's journal entries on its product's overpayment account (financial
     * account type 11 under the cash and accrual rules alike). Reversals post opposite entries, so they net out.
     */
    private static final String BOOKED_OVERPAYMENT_SQL = "select coalesce(sum(case when je.type_enum = "
            + JournalEntryType.CREDIT.getValue() + " then je.amount else -je.amount end), 0) from acc_gl_journal_entry je"
            + " where je.loan_transaction_id = ? and je.account_id in (select pm.gl_account_id from acc_product_mapping pm"
            + " where pm.product_id = ? and pm.product_type = " + PortfolioProductType.LOAN.getValue() + " and pm.financial_account_type = "
            + AccrualAccountsForLoan.OVERPAYMENT.getValue() + ")";

    private final LoanAssembler loanAssembler;
    private final LoanWritePlatformService loanWritePlatformService;
    private final LoanProductRoundingModeService loanProductRoundingModeService;
    private final LoanTransactionRepository loanTransactionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final LoanAccountDomainService loanAccountDomainService;

    @Override
    public boolean isRequested(final JsonCommand command) {
        // The command has always ignored its body, so a body that is not a JSON object must keep working without the
        // flag.
        if (!(command.parsedJson() instanceof JsonObject body)) {
            return false;
        }
        if (!body.has(TOP_UP_CLIPPED_CHARGE_RECEIPTS_PARAM)) {
            rejectNearMissOfTheFlag(body);
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

    /**
     * Other unknown keys stay ignored, but one that looks like the flag without being it is refused: read as absent it
     * would silently commit a plain reprocess instead of the requested top-up. A key looks like the flag when, with
     * everything but ASCII letters dropped and case ignored, it contains "topup", "clipped" or "receipt", as a
     * misspelt, singular, reordered or oddly separated flag does. Keys of nested objects and query parameters are not
     * seen here.
     */
    private static void rejectNearMissOfTheFlag(final JsonObject body) {
        for (final String key : body.keySet()) {
            final String letters = NON_LETTERS.matcher(key).replaceAll("").toLowerCase(Locale.ROOT);
            if (FLAG_WORDS.stream().anyMatch(letters::contains)) {
                throw new PlatformApiDataValidationException("validation.msg.loan." + TOP_UP_CLIPPED_CHARGE_RECEIPTS_PARAM + ".misspelt",
                        "The parameter `" + key + "` is not `" + TOP_UP_CLIPPED_CHARGE_RECEIPTS_PARAM
                                + "`: the key must match exactly. Send it exactly, or leave it out for a plain reprocess.",
                        key);
            }
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult reprocessWithTopUp(final Long loanId, final JsonCommand command) {
        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        validateLoanInScope(loan);

        final MonetaryCurrency currency = loan.getCurrency();
        final LoanStatus statusBefore = loan.getStatus();
        final List<LoanTransaction> liveBefore = loan.getLoanTransactions(LoanTransaction::isNotReversed);
        final Parked parkedBefore = Parked.on(liveBefore, currency);
        final RepaymentCharges repaymentChargesBefore = RepaymentCharges.of(liveBefore, currency);
        // Nothing has changed the loan yet, so its live transactions are the database's; after the reprocess the ids
        // are read back from the database.
        final Set<Long> liveIdsBefore = liveBefore.stream().map(LoanTransaction::getId).collect(Collectors.toSet());

        final List<ChargePaymentReceiptTopUpPlan> plans = applyTopUps(loan, currency, liveBefore);
        final boolean toppedUp = plans.stream().anyMatch(ChargePaymentReceiptTopUpPlan::hasTopUps);

        // Re-assembles the loan by id inside this persistence context, so it reprocesses the receipts raised above.
        this.loanWritePlatformService.reprocessLoanTransactions(loanId, command);

        if (toppedUp) {
            assertChargePaymentsReconciled(loan, plans, currency);
            assertChargesMatchReceipts(loan, plans, currency);
        }
        final List<LoanTransaction> liveAfter = loan.getLoanTransactions(LoanTransaction::isNotReversed);
        final Parked parkedAfter = Parked.on(liveAfter, currency);
        final RepaymentCharges repaymentChargesAfter = RepaymentCharges.of(liveAfter, currency);
        final Replay replay = Replay.of(liveBefore, liveAfter);
        assertOverpaymentReleased(loan, plans, currency, toppedUp, statusBefore, parkedBefore, parkedAfter, replay);
        assertRepaymentChargesKept(loan, plans, currency, toppedUp, repaymentChargesBefore, repaymentChargesAfter, replay);
        // The reprocess leaves the delinquency tag as it was. A top-up can clear an overdue charge or close the loan,
        // and COB never revisits a closed loan, so the tag is recalculated here, as after a payment.
        if (toppedUp || loan.getStatus() != statusBefore) {
            this.loanAccountDomainService.setLoanDelinquencyTag(loan, DateUtils.getBusinessLocalDate());
        }
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
        // Non-zero when a charge payment still parks after a top-up, e.g. a residual on another charge payment.
        changes.put("parkedOnChargePaymentsAfter", parkedAfter.onChargePayments().getAmount());
        changes.put("repaymentChargesBefore", repaymentChargesBefore.combined(currency).getAmount());
        changes.put("repaymentChargesAfter", repaymentChargesAfter.combined(currency).getAmount());
        putListed(changes, "reversedTransactionIds", liveIdsBefore.stream().filter(id -> !liveIdsAfter.contains(id)).sorted().toList(),
                MAX_LISTED_TRANSACTION_IDS);
        putListed(changes, "newTransactionIds", liveIdsAfter.stream().filter(id -> !liveIdsBefore.contains(id)).sorted().toList(),
                MAX_LISTED_TRANSACTION_IDS);
        changes.put("chargePaymentCounts", countByClassification(plans));
        putChargePayments(changes, plans, currency);

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
        // The replay re-derives such a refund in place without re-posting its journal entry, so a top-up that changes
        // the fee or penalty paid on the installments it unwinds would restate its split silently.
        if (loan.getLoanTransactions().stream().anyMatch(LoanTransaction::isRefundForActiveLoan)) {
            throw refusal(loan, "refund.for.active.loan",
                    "a refund for an active loan exists, and reprocessing restates its split in place without a journal entry");
        }
    }

    private static GeneralPlatformDomainRuleException refusal(final Loan loan, final String code, final String reason) {
        return new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.receipt.topup." + code,
                "Charge payment receipts of loan " + loan.getId() + " cannot be topped up: " + reason + ".", loan.getId());
    }

    /**
     * Plans and applies the charge payments one at a time in (transaction date, id) order. Which payments are short is
     * taken once, before any top-up, and a payment is only topped up when no other short payment has receipts on any of
     * its charges that still have room. Two topped-up payments therefore never share a charge that still has room, so
     * merging each top-up into {@code settledByCharge} changes no later plan today; it keeps the room right should that
     * rule be relaxed.
     */
    private List<ChargePaymentReceiptTopUpPlan> applyTopUps(final Loan loan, final MonetaryCurrency currency,
            final List<LoanTransaction> liveTransactions) {
        final List<LoanTransaction> chargePayments = liveTransactions.stream().filter(LoanTransaction::isChargePayment)
                .sorted(Comparator.comparing(LoanTransaction::getTransactionDate).thenComparing(LoanTransaction::getId)).toList();
        final Map<Long, Set<Long>> shortPaymentIdsByCharge = ChargePaymentReceiptTopUpPlanner.shortPaymentIdsByCharge(chargePayments,
                currency);
        // Room is only read for a short payment's charges, so without one the receipts of every repayment are not
        // loaded.
        final Map<Long, Money> settledByCharge = shortPaymentIdsByCharge.isEmpty() ? new HashMap<>()
                : ChargePaymentReceiptTopUpPlanner.settledByCharge(liveTransactions, currency);
        final boolean accountingEnabled = !loan.getLoanProduct().isAccountingDisabled();
        // Resolved on the first top-up only: a loan with nothing to top up never needs it.
        RoundingMode taxRoundingMode = null;
        final List<ChargePaymentReceiptTopUpPlan> plans = new ArrayList<>(chargePayments.size());
        for (final LoanTransaction chargePayment : chargePayments) {
            final ChargePaymentReceiptTopUpPlan planned = ChargePaymentReceiptTopUpPlanner.plan(chargePayment, currency, settledByCharge,
                    shortPaymentIdsByCharge);
            final ChargePaymentReceiptTopUpPlan plan = planned.hasTopUps() && accountingEnabled && bookedOverpayment(loan, chargePayment)
                    ? planned.withoutTopUps(ChargePaymentReceiptClassification.LEDGER_OVERPAYMENT)
                    : planned;
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
     * Whether the charge payment's journal entries hold overpayment. A top-up moves the clipped part into fee or
     * penalty in place and posts no journal entry, which is only right while the entry carries the whole amount as fee
     * or penalty.
     */
    private boolean bookedOverpayment(final Loan loan, final LoanTransaction chargePayment) {
        final BigDecimal net = this.jdbcTemplate.queryForObject(BOOKED_OVERPAYMENT_SQL, BigDecimal.class, chargePayment.getId(),
                loan.getLoanProduct().getId());
        return net != null && net.signum() != 0;
    }

    /**
     * Logged once every check has passed, and only when the transaction commits, so a top-up that a failed check, a
     * maker-checker submission or an enclosing batch rolls back is never logged as applied. The figures are taken now.
     */
    private static void logTopUps(final Loan loan, final List<ChargePaymentReceiptTopUpPlan> plans) {
        final List<AppliedTopUp> applied = new ArrayList<>();
        for (final ChargePaymentReceiptTopUpPlan plan : plans) {
            for (final ChargePaymentReceiptTopUp topUp : plan.topUps()) {
                applied.add(new AppliedTopUp(loan.getId(), plan.transaction().getId(), topUp.receipt().getId(),
                        topUp.receipt().getLoanCharge().getId(), topUp.from(), topUp.to()));
            }
        }
        if (applied.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            applied.forEach(AppliedTopUp::write);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void afterCommit() {
                applied.forEach(AppliedTopUp::write);
            }
        });
    }

    /** One applied top-up, as logged once the transaction commits. */
    private record AppliedTopUp(Long loanId, Long transactionId, Long receiptId, Long chargeId, BigDecimal from, BigDecimal to) {

        void write() {
            log.info("Charge payment receipt top-up: loan [id={}] transaction [id={}] receipt [id={}] charge [id={}] {} -> {}", loanId,
                    transactionId, receiptId, chargeId, from, to);
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

    /**
     * Each charge a topped-up charge payment has receipts on must be marked paid by exactly what the live receipts on
     * it carry: the replay marks a payment's money on the earliest unpaid charge of its own set, so a raise can land on
     * one of its charges that was not topped up.
     */
    private static void assertChargesMatchReceipts(final Loan loan, final List<ChargePaymentReceiptTopUpPlan> plans,
            final MonetaryCurrency currency) {
        final Map<Long, LoanCharge> checkedCharges = new HashMap<>();
        for (final ChargePaymentReceiptTopUpPlan plan : plans) {
            if (plan.hasTopUps()) {
                plan.transaction().getLoanChargesPaid()
                        .forEach(receipt -> checkedCharges.put(receipt.getLoanCharge().getId(), receipt.getLoanCharge()));
            }
        }
        // Summed again after the reprocess: reverse-replayed transactions carry fresh receipts.
        final Map<Long, Money> receiptsByCharge = ChargePaymentReceiptTopUpPlanner.settledByCharge(loan.getLoanTransactions(), currency);
        for (final LoanCharge charge : checkedCharges.values()) {
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
     * Only money that parks is seen here; displaced money that pays other dues is caught by
     * {@link #assertRepaymentChargesKept}.
     */
    private static void assertOverpaymentReleased(final Loan loan, final List<ChargePaymentReceiptTopUpPlan> plans,
            final MonetaryCurrency currency, final boolean toppedUp, final LoanStatus statusBefore, final Parked parkedBefore,
            final Parked parkedAfter, final Replay replay) {
        Money released = Money.zero(currency);
        for (final ChargePaymentReceiptTopUpPlan plan : plans) {
            if (plan.hasTopUps()) {
                released = released.plus(Money.of(currency, plan.overpaymentPortion()));
            }
        }
        final Money allowed = parkedBefore.total().minus(released);
        if (parkedAfter.total().isGreaterThan(allowed)
                || parkedAfter.outsideChargePayments().isGreaterThan(parkedBefore.outsideChargePayments())) {
            throw rolledBack("overpayment.moved", loan, plans, toppedUp,
                    ", the overpayment parked on loan " + loan.getId() + " would go from " + parkedBefore.total() + " to "
                            + parkedAfter.total() + " (at most " + allowed + " allowed), and outside charge payments from "
                            + parkedBefore.outsideChargePayments() + " to " + parkedAfter.outsideChargePayments()
                            + " (no rise allowed). Parking more: "
                            + named(parkedBefore.byTransaction(), parkedAfter.byTransaction(), true, replay, currency) + ". ",
                    "A charge payment would park a clipped part its charges have no room for, or a repayment"
                            + " that paid a topped-up charge again would park it (double collection).");
        }
        if (!statusBefore.isOverpaid() && loan.getStatus().isOverpaid()) {
            throw rolledBack("became.overpaid", loan, plans, toppedUp,
                    ", loan " + loan.getId() + " would become overpaid by " + loan.getTotalOverpaidAsMoney() + ". ",
                    "A repayment had also paid a topped-up charge (double collection).");
        }
    }

    /**
     * After a top-up, no repayment may pay less fee, or less penalty, than before (a replacement is compared with the
     * transaction it replaced). A fall means a top-up claimed charge room that the repayment had used, so the repayment
     * would be reverse-replayed with new journal entries and its money moved to other dues: principal or interest on an
     * active loan, a penalty instead of a fee, or another repayment's dues, none of which a loan-wide fee-and-penalty
     * total shows. That double collection is reviewed rather than re-allocated. With nothing topped up only that total
     * must not fall.
     */
    private static void assertRepaymentChargesKept(final Loan loan, final List<ChargePaymentReceiptTopUpPlan> plans,
            final MonetaryCurrency currency, final boolean toppedUp, final RepaymentCharges before, final RepaymentCharges after,
            final Replay replay) {
        final Money feesBefore = total(before.fees(), currency);
        final Money feesAfter = total(after.fees(), currency);
        final Money penaltiesBefore = total(before.penalties(), currency);
        final Money penaltiesAfter = total(after.penalties(), currency);
        final boolean fell = toppedUp
                ? anyFell(before.fees(), replay.pairedWithBefore(after.fees()), currency)
                        || anyFell(before.penalties(), replay.pairedWithBefore(after.penalties()), currency)
                : after.combined(currency).isLessThan(before.combined(currency));
        if (fell) {
            throw rolledBack("repayment.charges.moved", loan, plans, toppedUp,
                    ", fee paid by repayments on loan " + loan.getId() + " would go from " + feesBefore + " to " + feesAfter
                            + " and penalty from " + penaltiesBefore + " to " + penaltiesAfter + ". Paying less fee: "
                            + named(before.fees(), after.fees(), false, replay, currency) + "; less penalty: "
                            + named(before.penalties(), after.penalties(), false, replay, currency) + ". ",
                    "A repayment had also paid a topped-up charge (double collection), so its money would"
                            + " move to other dues, unless the repayment's stored split was already out of date.");
        }
    }

    /**
     * A rollback of the whole loan: {@code detail} between the opening that says whether, and how much, was topped up
     * and the closing advice; {@code toppedUpCause} is the likely cause when something was topped up.
     */
    private static GeneralPlatformDomainRuleException rolledBack(final String code, final Loan loan,
            final List<ChargePaymentReceiptTopUpPlan> plans, final boolean toppedUp, final String detail, final String toppedUpCause) {
        return new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.receipt.topup." + code,
                topUpSummary(plans, toppedUp) + detail + advice(toppedUp, toppedUpCause), loan.getId());
    }

    /** Opens a rollback message: whether, and how much, was topped up before the check failed. */
    private static String topUpSummary(final List<ChargePaymentReceiptTopUpPlan> plans, final boolean toppedUp) {
        if (!toppedUp) {
            return "Nothing was topped up, but after reprocessing";
        }
        final long payments = plans.stream().filter(ChargePaymentReceiptTopUpPlan::hasTopUps).count();
        final long receipts = plans.stream().mapToLong(plan -> plan.topUps().size()).sum();
        return "After topping up " + receipts + " receipt(s) on " + payments + " charge payment(s)";
    }

    /**
     * Closes a rollback message. With nothing topped up the reprocess alone moved the money, so no refund is implied.
     */
    private static String advice(final boolean toppedUp, final String toppedUpCause) {
        return toppedUp ? toppedUpCause + " The loan was rolled back; review it for refund."
                : "Reprocessing alone does this, so the loan's stored figures are out of date. The loan was rolled back; review it.";
    }

    /**
     * The transactions whose figure rose (or fell) from {@code before} to {@code after}, at most
     * {@link #MAX_NAMED_TRANSACTIONS}, as "id TYPE before -> after"; a replacement is shown under the id of the
     * transaction it replaced, marked "(replayed)", one missing from {@code before} is new, one missing from
     * {@code after} was reversed.
     */
    private static String named(final Map<Long, Money> before, final Map<Long, Money> after, final boolean rose, final Replay replay,
            final MonetaryCurrency currency) {
        final Map<Long, Money> pairedAfter = replay.pairedWithBefore(after);
        final Set<Long> ids = new TreeSet<>(before.keySet());
        ids.addAll(pairedAfter.keySet());
        final List<String> moved = new ArrayList<>();
        for (final Long id : ids) {
            final Money from = before.getOrDefault(id, Money.zero(currency));
            final Money to = pairedAfter.getOrDefault(id, Money.zero(currency));
            if (rose ? to.isGreaterThan(from) : to.isLessThan(from)) {
                moved.add(id + " " + replay.byId().get(id).getTypeOf().name() + " "
                        + (before.containsKey(id) ? from.getAmount().toPlainString() : "new") + " -> "
                        + (pairedAfter.containsKey(id) ? to.getAmount().toPlainString() : "reversed")
                        + (replay.replacedIdByNewId().containsValue(id) ? " (replayed)" : ""));
            }
        }
        if (moved.isEmpty()) {
            return "none";
        }
        return moved.size() <= MAX_NAMED_TRANSACTIONS ? String.join("; ", moved)
                : String.join("; ", moved.subList(0, MAX_NAMED_TRANSACTIONS)) + "; and " + (moved.size() - MAX_NAMED_TRANSACTIONS)
                        + " more";
    }

    /** Fee and penalty paid by each persisted live repayment-like or recovery-repayment transaction, by id. */
    private record RepaymentCharges(Map<Long, Money> fees, Map<Long, Money> penalties) {

        static RepaymentCharges of(final Collection<LoanTransaction> transactions, final MonetaryCurrency currency) {
            final Map<Long, Money> fees = new HashMap<>();
            final Map<Long, Money> penalties = new HashMap<>();
            for (final LoanTransaction transaction : transactions) {
                if (transaction.getId() != null && transaction.isNotReversed()
                        && (transaction.isRepaymentLikeType() || transaction.isRecoveryRepayment())) {
                    fees.put(transaction.getId(), transaction.getFeeChargesPortion(currency));
                    penalties.put(transaction.getId(), transaction.getPenaltyChargesPortion(currency));
                }
            }
            return new RepaymentCharges(fees, penalties);
        }

        /** Fee and penalty together, as reported. */
        Money combined(final MonetaryCurrency currency) {
            return Money.zero(currency).plus(fees.values()).plus(penalties.values());
        }
    }

    private static Money total(final Map<Long, Money> byTransaction, final MonetaryCurrency currency) {
        return Money.zero(currency).plus(byTransaction.values());
    }

    /** Whether any transaction's figure in {@code before} is higher than its figure (zero if gone) in {@code after}. */
    private static boolean anyFell(final Map<Long, Money> before, final Map<Long, Money> after, final MonetaryCurrency currency) {
        return before.entrySet().stream()
                .anyMatch(entry -> after.getOrDefault(entry.getKey(), Money.zero(currency)).isLessThan(entry.getValue()));
    }

    private Set<Long> liveTransactionIds(final Loan loan) {
        final Set<Long> ids = new HashSet<>(this.loanTransactionRepository.findTransactionIdsByLoan(loan));
        this.loanTransactionRepository.findReversedTransactionIdsByLoan(loan).forEach(ids::remove);
        return ids;
    }

    /**
     * Puts at most {@code max} of {@code values} under {@code key}, and how many were left out under key + "NotListed".
     */
    private static void putListed(final Map<String, Object> changes, final String key, final List<?> values, final int max) {
        changes.put(key, values.size() > max ? values.subList(0, max) : values);
        if (values.size() > max) {
            changes.put(key + "NotListed", values.size() - max);
        }
    }

    private static Map<String, Long> countByClassification(final List<ChargePaymentReceiptTopUpPlan> plans) {
        final Map<String, Long> counts = new LinkedHashMap<>();
        plans.forEach(plan -> counts.merge(plan.classification().name(), 1L, Long::sum));
        return counts;
    }

    /**
     * Lists the payments that need attention, topped-up ones first, within the caps. Consistent and single-receipt
     * payments are only counted.
     */
    private static void putChargePayments(final Map<String, Object> changes, final List<ChargePaymentReceiptTopUpPlan> plans,
            final MonetaryCurrency currency) {
        final List<ChargePaymentReceiptTopUpPlan> attention = plans.stream().filter(ChargePaymentReceiptTopUpServiceImpl::needsAttention)
                .sorted(Comparator.comparing(ChargePaymentReceiptTopUpPlan::hasTopUps).reversed()).toList();
        final List<Map<String, Object>> listed = new ArrayList<>();
        int topUpsLeft = MAX_LISTED_TOP_UPS;
        for (final ChargePaymentReceiptTopUpPlan plan : attention.subList(0, Math.min(attention.size(), MAX_LISTED_CHARGE_PAYMENTS))) {
            listed.add(describe(plan, topUpsLeft, currency));
            topUpsLeft = Math.max(0, topUpsLeft - plan.topUps().size());
        }
        changes.put("chargePayments", listed);
        if (attention.size() > MAX_LISTED_CHARGE_PAYMENTS) {
            changes.put("chargePaymentsNotListed", attention.size() - MAX_LISTED_CHARGE_PAYMENTS);
        }
    }

    private static boolean needsAttention(final ChargePaymentReceiptTopUpPlan plan) {
        return plan.classification() != ChargePaymentReceiptClassification.CONSISTENT
                && plan.classification() != ChargePaymentReceiptClassification.SINGLE_RECEIPT;
    }

    private static Map<String, Object> describe(final ChargePaymentReceiptTopUpPlan plan, final int topUpsToList,
            final MonetaryCurrency currency) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("transactionId", plan.transaction().getId());
        out.put("classification", plan.classification().name());
        out.put("amount", plan.amount());
        out.put("receiptsTotalBefore", plan.receiptsTotal());
        out.put("overpaymentBefore", plan.overpaymentPortion());
        // Charge payments are corrected in place, so the planned transaction holds the reprocessed figures.
        out.put("overpaymentAfter", plan.transaction().getOverPaymentPortion(currency).getAmount());
        final List<Map<String, Object>> topUps = new ArrayList<>();
        for (final ChargePaymentReceiptTopUp topUp : plan.topUps().subList(0, Math.min(plan.topUps().size(), topUpsToList))) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("receiptId", topUp.receipt().getId());
            entry.put("chargeId", topUp.receipt().getLoanCharge().getId());
            entry.put("from", topUp.from());
            entry.put("to", topUp.to());
            topUps.add(entry);
        }
        out.put("topUps", topUps);
        if (plan.topUps().size() > topUpsToList) {
            out.put("topUpsNotListed", plan.topUps().size() - topUpsToList);
        }
        return out;
    }

    /**
     * Overpayment parked on persisted live transactions: in total (charge payments included), outside charge payments,
     * and by transaction id. Measured on the transactions because the loan's overpaid amount leaves charge payments
     * out. Chargebacks are left out: their overpayment portion is overpayment they used up, not money parked.
     */
    private record Parked(Money total, Money outsideChargePayments, Map<Long, Money> byTransaction) {

        static Parked on(final Collection<LoanTransaction> liveTransactions, final MonetaryCurrency currency) {
            Money total = Money.zero(currency);
            Money outsideChargePayments = Money.zero(currency);
            final Map<Long, Money> byTransaction = new HashMap<>();
            for (final LoanTransaction transaction : liveTransactions) {
                if (transaction.isChargeback()) {
                    continue;
                }
                final Money overpayment = transaction.getOverPaymentPortion(currency);
                total = total.plus(overpayment);
                if (!transaction.isChargePayment()) {
                    outsideChargePayments = outsideChargePayments.plus(overpayment);
                }
                if (transaction.getId() != null) {
                    byTransaction.put(transaction.getId(), overpayment);
                }
            }
            return new Parked(total, outsideChargePayments, byTransaction);
        }

        /** What charge payments still park. */
        Money onChargePayments() {
            return total.minus(outsideChargePayments);
        }
    }

    /**
     * The live transactions before and after the reprocess, by id, and each new one the reprocess created in place of
     * one that was live before (its REPLAYED relation), so that a reverse-replayed transaction's figures pair with its
     * replacement's. Relations copied from earlier replays point at transactions already reversed before, so they are
     * skipped.
     */
    private record Replay(Map<Long, LoanTransaction> byId, Map<Long, Long> replacedIdByNewId) {

        static Replay of(final List<LoanTransaction> liveBefore, final List<LoanTransaction> liveAfter) {
            final Map<Long, LoanTransaction> byId = new HashMap<>();
            liveBefore.forEach(transaction -> byId.put(transaction.getId(), transaction));
            final Set<Long> idsBefore = new HashSet<>(byId.keySet());
            final Map<Long, Long> replacedIdByNewId = new HashMap<>();
            for (final LoanTransaction transaction : liveAfter) {
                if (!idsBefore.contains(transaction.getId())) {
                    transaction
                            .getLoanTransactionRelations(
                                    relation -> LoanTransactionRelationTypeEnum.REPLAYED.equals(relation.getRelationType())
                                            && relation.getToTransaction() != null
                                            && idsBefore.contains(relation.getToTransaction().getId()))
                            .stream().findFirst()
                            .ifPresent(relation -> replacedIdByNewId.put(transaction.getId(), relation.getToTransaction().getId()));
                }
                byId.put(transaction.getId(), transaction);
            }
            return new Replay(byId, replacedIdByNewId);
        }

        /**
         * {@code after} keyed by the id of the transaction each figure replaces, so it pairs with the before-figures.
         */
        Map<Long, Money> pairedWithBefore(final Map<Long, Money> after) {
            final Map<Long, Money> paired = new HashMap<>();
            after.forEach((id, figure) -> paired.merge(replacedIdByNewId.getOrDefault(id, id), figure, Money::plus));
            return paired;
        }
    }
}
