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
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.loanaccount.api.LoanChargePaymentReceiptRepairApiConstants;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanLifecycleStateMachine;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /loans/{id}?command=repairChargePaymentReceipts} body {@code {"dryRun": true|false}} (default true).
 * <p>
 * Classifies every live charge payment of the loan with {@link ChargePaymentReceiptRepairPlanner}, tops up the receipts
 * of the CLIPPED ones, then runs the standard {@code reprocessTransactions} command (reprocess + status transition +
 * save) so the repaired receipts drive the allocation, and asserts the outcome on EVERY charge payment of the loan
 * before the transaction commits. Dry run computes and reports the same plan without touching anything. A loan whose
 * charge payments are all consistent but still carries a stale ACTIVE + overpaid-on date gets the status transition
 * alone (no reprocess). Idempotent: a repaired loan classifies CONSISTENT and nothing runs.
 * <p>
 * Refused (nothing can be repaired safely): undisbursed, progressive-schedule, charged-off and written-off loans, loans
 * with a live credit-balance refund (it may have consumed the very overpayment this repair removes) and loans on
 * products with accounting enabled (the charge payment is corrected in place, so its journal entries would not be
 * re-posted).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanChargePaymentReceiptRepairServiceImpl implements LoanChargePaymentReceiptRepairService {

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private static final String ACTION_DRY_RUN = "DRY_RUN";
    private static final String ACTION_REPAIRED = "REPAIRED";
    private static final String ACTION_STATUS_REFRESHED = "STATUS_REFRESHED";
    private static final String ACTION_NO_CHANGE = "NO_CHANGE";

    private final LoanAssembler loanAssembler;
    private final LoanWritePlatformService loanWritePlatformService;
    private final LoanLifecycleStateMachine loanLifecycleStateMachine;
    private final LoanRepository loanRepository;
    private final ConfigurationDomainService configurationDomainService;
    private final FromJsonHelper fromApiJsonHelper;
    private final ChargePaymentReceiptRepairPlanner planner = new ChargePaymentReceiptRepairPlanner();

    @Transactional
    @Override
    public CommandProcessingResult repairChargePaymentReceipts(final Long loanId, final JsonCommand command) {
        final boolean dryRun = parseDryRun(command);
        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        validateLoanIsRepairable(loan);

        final MonetaryCurrency currency = loan.getCurrency();
        final List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        final List<LoanTransaction> liveTransactions = loan.getLoanTransactions().stream().filter(LoanTransaction::isNotReversed).toList();
        final List<LoanTransaction> chargePayments = liveTransactions.stream().filter(LoanTransaction::isChargePayment)
                .sorted(Comparator.comparing(LoanTransaction::getTransactionDate).thenComparing(LoanTransaction::getId)).toList();
        final List<ChargePaymentReceiptRepairPlan> plans = chargePayments.stream()
                .map(transaction -> this.planner.plan(transaction, currency, installments, liveTransactions)).toList();
        final List<ChargePaymentReceiptRepairPlan> repairable = plans.stream().filter(ChargePaymentReceiptRepairPlan::isRepairable)
                .toList();
        // A loan with no charge payments at all is NOT "all consistent": allMatch is vacuously true on an empty
        // stream, and this command must not run the state machine on a loan it found nothing wrong with.
        final boolean allConsistent = !plans.isEmpty()
                && plans.stream().allMatch(plan -> plan.classification() == ChargePaymentReceiptRepairClassification.CONSISTENT
                        || plan.classification() == ChargePaymentReceiptRepairClassification.SINGLE_RECEIPT);

        final String statusBefore = loan.getStatus().name();
        final LocalDate overpaidOnDateBefore = loan.getOverpaidOnDate();
        final Set<Long> transactionIdsBefore = liveTransactionIds(loan);
        // Only a loan whose charge payments are ALL consistent may have its status refreshed: with any unrepaired
        // defect, the summary the state machine would act on is the wrong one.
        final boolean staleOverpaidDate = allConsistent && loan.getStatus().isActive() && overpaidOnDateBefore != null;

        String action = ACTION_NO_CHANGE;
        if (dryRun) {
            action = ACTION_DRY_RUN;
        } else if (!repairable.isEmpty()) {
            // Captured before the repair: a repair must never move overpayment somewhere else on the loan, and
            // assertRepaired only inspects charge payments, so it would not see money relocated onto a repayment.
            final Money overpaymentBefore = totalOverpayment(loan, currency);
            applyTopUps(loan, currency, installments, liveTransactions, repairable);
            // The standard command re-assembles the loan inside this persistence context (same managed entities), so
            // it replays the receipts just topped up, then runs the status transition and saves.
            this.loanWritePlatformService.reprocessLoanTransactions(loanId, command);
            assertRepaired(loan, plans, currency);
            final Money overpaymentAfter = totalOverpayment(loan, currency);
            if (overpaymentAfter.isGreaterThan(overpaymentBefore)) {
                throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.receipt.repair.failed",
                        "Charge payment receipt repair of loan " + loan.getId() + " increased the loan's overpayment from "
                                + overpaymentBefore + " to " + overpaymentAfter + "; the loan was rolled back.",
                        loan.getId(), overpaymentBefore.getAmount(), overpaymentAfter.getAmount());
            }
            action = ACTION_REPAIRED;
        } else if (staleOverpaidDate) {
            // Nothing to allocate differently: the summary is already right, only the overpaid-on date survived an
            // earlier status change that bypassed the state machine. The transition clears it.
            this.loanLifecycleStateMachine.determineAndTransition(loan, resolveTransitionDate(loan));
            this.loanRepository.saveAndFlush(loan);
            action = ACTION_STATUS_REFRESHED;
        }

        final boolean reprocessed = ACTION_REPAIRED.equals(action);
        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("loanId", loan.getId());
        changes.put("dryRun", dryRun);
        changes.put("action", action);
        changes.put("statusBefore", statusBefore);
        changes.put("statusAfter", loan.getStatus().name());
        changes.put("overpaidOnDateBefore", Objects.toString(overpaidOnDateBefore, null));
        changes.put("overpaidOnDateAfter", Objects.toString(loan.getOverpaidOnDate(), null));
        changes.put("staleOverpaidOnDate", staleOverpaidDate);
        final Set<Long> transactionIdsAfter = liveTransactionIds(loan);
        changes.put("reversedTransactionIds",
                transactionIdsBefore.stream().filter(id -> !transactionIdsAfter.contains(id)).sorted().toList());
        changes.put("newTransactionIds", transactionIdsAfter.stream().filter(id -> !transactionIdsBefore.contains(id)).sorted().toList());
        changes.put("transactions",
                plans.stream().map(plan -> describe(plan, currency, reprocessed, reprocessed && plan.isRepairable())).toList());

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

    private boolean parseDryRun(final JsonCommand command) {
        if (command == null || StringUtils.isBlank(command.json())) {
            return true;
        }
        this.fromApiJsonHelper.checkForUnsupportedParameters(MAP_TYPE, command.json(),
                LoanChargePaymentReceiptRepairApiConstants.SUPPORTED_PARAMETERS);
        if (!command.parameterExists(LoanChargePaymentReceiptRepairApiConstants.DRY_RUN_PARAM)) {
            return true;
        }
        // Must not fail open. Every lenient reader turns an unparseable value into false and therefore into a live
        // repair: booleanPrimitiveValueOfParameterNamed defaults null to false, and Gson resolves a string or a number
        // through Boolean.parseBoolean, so "yes" and 1 both read as false. The element's own type is checked instead.
        final JsonElement dryRunElement = this.fromApiJsonHelper.parse(command.json()).getAsJsonObject()
                .get(LoanChargePaymentReceiptRepairApiConstants.DRY_RUN_PARAM);
        if (dryRunElement == null || !dryRunElement.isJsonPrimitive() || !dryRunElement.getAsJsonPrimitive().isBoolean()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.receipt.repair.dry.run.invalid",
                    "The dryRun parameter must be the boolean true or false.", LoanChargePaymentReceiptRepairApiConstants.DRY_RUN_PARAM);
        }
        return dryRunElement.getAsBoolean();
    }

    private static void validateLoanIsRepairable(final Loan loan) {
        if (loan.isNotDisbursed()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.receipt.repair.not.disbursed",
                    "Charge payment receipts cannot be repaired because loan with id " + loan.getId() + " is not disbursed.", loan.getId());
        }
        if (loan.isProgressiveSchedule()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.receipt.repair.unsupported.schedule.type",
                    "Charge payment receipts can only be repaired for cumulative (classic) loans; loan with id " + loan.getId()
                            + " uses a progressive schedule.",
                    loan.getId());
        }
        if (loan.getLoanProduct() != null && !loan.getLoanProduct().isAccountingDisabled()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.receipt.repair.accounting.enabled",
                    "Charge payment receipts cannot be repaired on loan " + loan.getId()
                            + ": its product has accounting enabled and the corrected charge payment's journal entries would not be re-posted.",
                    loan.getId());
        }
        if (loan.isChargedOff() || loan.isClosedWrittenOff()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.receipt.repair.loan.settled",
                    "Charge payment receipts cannot be repaired on loan " + loan.getId() + ": it is charged-off or written-off.",
                    loan.getId());
        }
        if (loan.getLoanTransactions().stream().anyMatch(t -> t.isNotReversed() && t.isCreditBalanceRefund())) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.receipt.repair.credit.balance.refund",
                    "Charge payment receipts cannot be repaired on loan " + loan.getId()
                            + ": a credit balance refund exists and may have consumed the overpayment this repair removes.",
                    loan.getId());
        }
    }

    /**
     * Applies the top-ups one transaction at a time, re-planning each transaction immediately before its own top-ups.
     * The plans handed in were all sized against the same pre-repair state, so two transactions clipped on the SAME
     * charge would each have been sized against the other's clipped receipt and together exceed the charge. Re-planning
     * makes the second one see the first one's top-up; a transaction that is no longer repairable is left untouched and
     * {@code assertRepaired} then refuses the loan.
     */
    private Set<Long> applyTopUps(final Loan loan, final MonetaryCurrency currency,
            final List<LoanRepaymentScheduleInstallment> installments, final List<LoanTransaction> liveTransactions,
            final List<ChargePaymentReceiptRepairPlan> repairable) {
        final Set<Long> applied = new LinkedHashSet<>();
        // The mode the charge payment itself was created with (LoanChargeService#handleChargePaidTransaction), so a
        // topped-up receipt's tax split is the one its own transaction would have written.
        final RoundingMode taxRoundingMode = this.configurationDomainService.getTaxRoundingMode();
        for (final ChargePaymentReceiptRepairPlan planned : repairable) {
            final ChargePaymentReceiptRepairPlan plan = this.planner.plan(planned.transaction(), currency, installments, liveTransactions);
            if (!plan.isRepairable()) {
                log.info(
                        "Charge payment receipt repair: loan [id={}] transaction [id={}] is no longer repairable after the earlier "
                                + "top-ups (classification {}); left untouched.",
                        loan.getId(), plan.transaction().getId(), plan.classification());
                continue;
            }
            for (final ChargePaymentReceiptTopUp topUp : plan.topUps()) {
                final LoanChargePaidBy receipt = topUp.receipt();
                receipt.setAmount(topUp.to(), taxRoundingMode);
                log.info("Charge payment receipt repair: loan [id={}] transaction [id={}] receipt [id={}] charge [id={}] {} -> {}",
                        loan.getId(), plan.transaction().getId(), receipt.getId(), receipt.getLoanCharge().getId(), topUp.from(),
                        topUp.to());
            }
            applied.add(plan.transaction().getId());
        }
        return applied;
    }

    /**
     * After the reprocess every repaired AND every previously consistent charge payment must be fully absorbed, and no
     * other charge payment may have got worse (the reprocess re-allocates all of them). Anything else means the
     * installment buckets cannot take the money (an installment-level defect this command does not fix); fail so the
     * loan rolls back untouched.
     */
    private static void assertRepaired(final Loan loan, final List<ChargePaymentReceiptRepairPlan> plans, final MonetaryCurrency currency) {
        for (final ChargePaymentReceiptRepairPlan plan : plans) {
            final LoanTransaction transaction = plan.transaction();
            final Money amount = transaction.getAmount(currency);
            final Money overpayment = transaction.getOverPaymentPortion(currency);
            final Money portions = transaction.getFeeChargesPortion(currency).plus(transaction.getPenaltyChargesPortion(currency));
            final Money receipts = transaction.getLoanChargesPaidTotal(currency);
            final boolean mustReconcile = plan.isRepairable()
                    || plan.classification() == ChargePaymentReceiptRepairClassification.CONSISTENT;
            final boolean failed = mustReconcile ? !overpayment.isZero() || !portions.isEqualTo(amount) || !receipts.isEqualTo(amount)
                    : overpayment.isGreaterThan(Money.of(currency, plan.overpaymentPortion()));
            if (failed) {
                throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.receipt.repair.failed",
                        "Charge payment receipt repair of loan " + loan.getId() + " transaction " + transaction.getId()
                                + " did not reconcile after reprocessing (amount " + amount + ", fee+penalty " + portions + ", overpayment "
                                + overpayment + ", receipts " + receipts + "); the loan was rolled back.",
                        loan.getId(), transaction.getId(), amount.getAmount(), portions.getAmount(), overpayment.getAmount(),
                        receipts.getAmount());
            }
        }
    }

    /** Overpayment parked across every live transaction of the loan, charge payments and repayments alike. */
    private static Money totalOverpayment(final Loan loan, final MonetaryCurrency currency) {
        Money total = Money.zero(currency);
        for (final LoanTransaction transaction : loan.getLoanTransactions()) {
            if (transaction.isNotReversed()) {
                total = total.plus(transaction.getOverPaymentPortion(currency));
            }
        }
        return total;
    }

    /** Mirrors the transition-date rule of {@code reprocessLoanTransactions}; used by the status-refresh-only path. */
    private static LocalDate resolveTransitionDate(final Loan loan) {
        return loan.getRepaymentScheduleInstallments().stream().map(LoanRepaymentScheduleInstallment::getObligationsMetOnDate)
                .filter(Objects::nonNull).max(LocalDate::compareTo).orElseGet(DateUtils::getBusinessLocalDate);
    }

    private static Set<Long> liveTransactionIds(final Loan loan) {
        return loan.getLoanTransactions().stream().filter(LoanTransaction::isNotReversed).map(LoanTransaction::getId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static Map<String, Object> describe(final ChargePaymentReceiptRepairPlan plan, final MonetaryCurrency currency,
            final boolean reprocessed, final boolean repaired) {
        final LoanTransaction transaction = plan.transaction();
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("transactionId", transaction.getId());
        out.put("transactionDate", transaction.getTransactionDate().toString());
        out.put("amount", plan.amount());
        out.put("classification", plan.classification().name());
        out.put("repaired", repaired);
        out.put("receiptsTotalBefore", plan.receiptsTotal());
        out.put("gap", plan.gap());
        out.put("portionsBefore", portions(plan.feePortion(), plan.penaltyPortion(), plan.overpaymentPortion()));
        if (reprocessed) {
            out.put("portionsAfter", portions(transaction.getFeeChargesPortion(currency).getAmount(),
                    transaction.getPenaltyChargesPortion(currency).getAmount(), transaction.getOverPaymentPortion(currency).getAmount()));
            out.put("receiptsTotalAfter", transaction.getLoanChargesPaidTotal(currency).getAmount());
        }
        final List<Map<String, Object>> topUps = new ArrayList<>();
        for (final ChargePaymentReceiptTopUp topUp : plan.topUps()) {
            final Map<String, Object> t = new LinkedHashMap<>();
            t.put("receiptId", topUp.receipt().getId());
            t.put("chargeId", topUp.receipt().getLoanCharge().getId());
            t.put("from", topUp.from());
            t.put("to", topUp.to());
            topUps.add(t);
        }
        out.put("topUps", topUps);
        // The per-receipt detail is only described for a transaction that needs attention. This whole result is
        // serialised into m_portfolio_command_source.result, a TEXT column (64 KB on MySQL), and a loan with a long
        // daily-penalty history carries over a thousand receipts: describing the consistent ones too would overflow it
        // and the repair would be rolled back by the failure to save its own result.
        if (plan.classification() == ChargePaymentReceiptRepairClassification.CONSISTENT
                || plan.classification() == ChargePaymentReceiptRepairClassification.SINGLE_RECEIPT) {
            out.put("receiptCount", plan.receipts().size());
            return out;
        }
        final List<Map<String, Object>> receipts = new ArrayList<>();
        for (final ChargePaymentReceiptView view : plan.receipts()) {
            final Map<String, Object> r = new LinkedHashMap<>();
            r.put("receiptId", view.receiptId());
            r.put("chargeId", view.chargeId());
            r.put("amount", view.amount());
            r.put("chargeCap", view.chargeCap());
            r.put("chargeActive", view.chargeActive());
            r.put("feeCharge", view.feeCharge());
            r.put("installmentNumber", view.installmentNumber());
            r.put("mappedBy", view.mappedBy());
            r.put("bucketAmount", view.bucketAmount());
            r.put("bucketCompleted", view.bucketCompleted());
            receipts.add(r);
        }
        out.put("receipts", receipts);
        return out;
    }

    private static Map<String, BigDecimal> portions(final BigDecimal fee, final BigDecimal penalty, final BigDecimal overpayment) {
        final Map<String, BigDecimal> p = new LinkedHashMap<>();
        p.put("fee", fee);
        p.put("penalty", penalty);
        p.put("overpayment", overpayment);
        return p;
    }
}
