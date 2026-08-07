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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.portfolio.loanaccount.data.ScheduleGeneratorDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanLifecycleStateMachine;
import org.springframework.stereotype.Service;

/**
 * Regenerates a loan's repayment schedule, replays its transactions against the result, and settles everything derived
 * from the schedule that the replay itself does not touch.
 * <p>
 * Shared deliberately. Two callers need this exact sequence and must not diverge: the single-loan parameter correction
 * ({@code command=reprocessLoan}) and bulk repair after a product configuration change such as a rounding-mode or
 * broken-period-interest change.
 * <p>
 * The settlement steps are the easy ones to forget. Neither {@code ReprocessLoanTransactionsServiceImpl} nor
 * {@code LoanScheduleService} settles loan status, so without them a regeneration that changes the total obligation
 * leaves the loan on a stale status, stale accruals and a stale delinquency classification - with the general ledger
 * quietly disagreeing with the schedule.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanScheduleRegenerationService {

    private static final String ERROR_ACCRUAL_FAILED = "error.msg.loan.regeneration.accrual.reposting.failed";

    private final LoanUtilService loanUtilService;
    private final LoanScheduleService loanScheduleService;
    private final ReprocessLoanTransactionsService reprocessLoanTransactionsService;
    private final LoanAccrualsProcessingService loanAccrualsProcessingService;
    private final LoanLifecycleStateMachine loanLifecycleStateMachine;
    private final LoanAccountDomainService loanAccountDomainService;

    /**
     * Whether a loan may be regenerated and replayed at all.
     * <p>
     * Restricted to plainly active loans. A closed, overpaid, written-off, charged-off, foreclosed or
     * contract-terminated loan carries settlement state that regeneration would invalidate without unwinding.
     * <p>
     * Also restricted to cumulative schedules. Progressive loans have their own replay path and are not regenerated
     * here; without this check the predicate would report them regenerable while {@link #regenerateAndReplay} did
     * nothing, so a bulk repair would report a clean run over exactly the loans it failed to touch.
     */
    public static boolean isRegenerable(final Loan loan) {
        return loan.getStatus().isActive() && !loan.isChargedOff() && !loan.isForeclosure() && !loan.isContractTermination()
                && loan.isCumulativeSchedule();
    }

    /**
     * Regenerates the schedule, replays the transactions, then settles status, accruals and delinquency.
     * <p>
     * Callers own three things: the eligibility check ({@link #isRegenerable}), whatever parameter mutation prompted
     * this - the loan is expected to arrive already carrying its corrected values - and persisting the loan afterwards.
     * Callers must still save: nothing here persists the caller's own mutations, though the re-accrual step does save
     * and flush the loan and its new accrual transactions as a side effect of reusing the COB accrual path.
     */
    public void regenerateAndReplay(final Loan loan) {
        // Backstop, not the primary gate: callers are expected to check isRegenerable up front so the caller can
        // raise its own domain error. Enforced here because the previous silent skip meant an ineligible loan came
        // back from this method looking successfully regenerated.
        if (!isRegenerable(loan)) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.regeneration.loan.is.not.regenerable",
                    "Loan " + loan.getId() + " is not in a state whose schedule can be regenerated.", loan.getId());
        }
        regenerateAndReplaySchedule(loan);
        settleDerivedState(loan);
    }

    /**
     * Cumulative loans go through {@code recalculateSchedule}, which regenerates and then replays in one call; calling
     * {@code reprocessTransactions} afterwards would replay twice. Non-cumulative schedules never reach here -
     * {@link #isRegenerable} excludes them and {@link #regenerateAndReplay} enforces it.
     */
    private void regenerateAndReplaySchedule(final Loan loan) {
        final ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, null);

        // This is LoanScheduleService#recalculateSchedule expanded, so that overdue penalties can be restored between
        // the regeneration and the replay. See restoreOverduePenalties for why that window matters.
        final Map<Long, OverduePenaltySnapshot> penalties = snapshotOverduePenalties(loan);
        if (loan.isInterestBearingAndInterestRecalculationEnabled() && !loan.isChargedOff()) {
            this.loanScheduleService.regenerateRepaymentScheduleWithInterestRecalculation(loan, scheduleGeneratorDTO);
        } else {
            this.loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
        }
        restoreOverduePenalties(loan, penalties);
        this.reprocessLoanTransactionsService.reprocessTransactions(loan);
    }

    /**
     * Overdue-installment penalties as they stood before regeneration, keyed by charge id.
     */
    private record OverduePenaltySnapshot(BigDecimal amount, BigDecimal amountPercentageAppliedTo, BigDecimal amountOutstanding) {
    }

    private Map<Long, OverduePenaltySnapshot> snapshotOverduePenalties(final Loan loan) {
        final Map<Long, OverduePenaltySnapshot> snapshot = new HashMap<>();
        for (final LoanCharge charge : loan.getActiveCharges()) {
            if (charge.isOverdueInstallmentCharge() && charge.getId() != null) {
                snapshot.put(charge.getId(), new OverduePenaltySnapshot(charge.getAmount(), charge.getAmountPercentageAppliedTo(),
                        charge.getAmountOutstanding()));
            }
        }
        return snapshot;
    }

    /**
     * Puts overdue penalties back the way they were before the schedule was rebuilt.
     * <p>
     * {@code regenerateRepaymentSchedule} rebuilds the installments - which resets their paid amounts to zero - and
     * only then recalculates every active charge. A percentage-based overdue penalty therefore re-bases onto the
     * <i>whole</i> installment instead of the part actually in arrears, because at that instant nothing looks paid. The
     * replay restores the payments afterwards, but the charge amounts have already been fixed at the inflated figure.
     * <p>
     * Observed live: a two-day disbursement-date correction re-based 31 daily penalties from 1% of the 23.00 in arrears
     * to 1% of the full 4,343.00 installment, turning 7.13 of penalties into 1,346.33 on a 12,000 loan.
     * <p>
     * These penalties are a function of arrears that have already occurred, not of the schedule being rebuilt, so
     * carrying them across unchanged is the correct outcome. Any genuine change in arrears is picked up by the overdue
     * charge job on its normal run.
     */
    private void restoreOverduePenalties(final Loan loan, final Map<Long, OverduePenaltySnapshot> snapshot) {
        if (snapshot.isEmpty()) {
            return;
        }
        for (final LoanCharge charge : loan.getActiveCharges()) {
            final OverduePenaltySnapshot original = charge.getId() == null ? null : snapshot.get(charge.getId());
            if (original == null) {
                continue;
            }
            if (MathUtil.isEqualTo(charge.getAmount(), original.amount())) {
                continue;
            }
            log.debug("Loan {}: restoring overdue penalty {} from {} back to {}", loan.getId(), charge.getId(), charge.getAmount(),
                    original.amount());
            charge.setAmount(original.amount());
            charge.setAmountPercentageAppliedTo(original.amountPercentageAppliedTo());
            charge.setAmountOutstanding(original.amountOutstanding());
        }
    }

    private void settleDerivedState(final Loan loan) {
        // Posted accrual transactions carry the interest the old schedule implied. Re-deriving them is what keeps the
        // ledger in step with the regenerated installments.
        this.loanAccrualsProcessingService.reprocessExistingAccruals(loan, true);
        // reprocessExistingAccruals reverses accruals that no longer match the rebuilt schedule but does not re-post
        // replacements, so the high-water mark has to come back with it. Leaving accruedTill where it was makes the
        // next accrual run believe those periods are already covered, and the reversed interest is lost from income
        // and receivable for good. Observed live: a 342.79 accrual reversed with accruedTill still at the later date.
        loan.setAccruedTill(null);

        final LocalDate businessDate = DateUtils.getBusinessLocalDate();
        // Status follows from the resulting balances, not from an event - a regeneration that shrinks the obligation
        // below what has been paid legitimately closes or overpays the loan. No separate summary refresh is needed:
        // determineAndTransition calls updateLoanSummaryDerivedFields itself before deciding
        // (DefaultLoanLifecycleStateMachine:60), so the status is derived from freshly recomputed balances.
        this.loanLifecycleStateMachine.determineAndTransition(loan, businessDate);
        // After the transition, not before: addPeriodicAccruals skips closed and overpaid loans, and that guard only
        // works once the loan carries the status the regeneration actually produced. Re-accruing first would post
        // fresh accruals for a loan the very next line closes.
        reaccrueUpToDate(loan, businessDate);
        this.loanAccountDomainService.setLoanDelinquencyTag(loan, businessDate);
        // Let COB re-derive from scratch; the dates it previously closed against no longer describe this loan.
        loan.setLastClosedBusinessDate(null);
    }

    /**
     * Re-accrues up to today, so the loan leaves this operation whole rather than waiting for the next COB.
     * <p>
     * The step above reverses accruals the rebuilt schedule no longer justifies and nulls {@code accruedTill}, but
     * posts nothing in their place. Left there, the loan under-states accrued interest from the moment it is
     * regenerated until COB next runs - and accrued interest is read straight off these transactions for revenue
     * projection, so a regeneration inside a reporting window would put a hole in that number for reasons no one
     * reading the report could see.
     * <p>
     * This is the same call the COB accrual step makes, so the figures it posts are the ones COB would have posted;
     * running it here only brings them forward. Periodic accrual only - upfront-accrual products have their accruals
     * settled by {@code reprocessExistingAccruals} itself, and products keeping no accrual accounting have nothing to
     * post.
     */
    private void reaccrueUpToDate(final Loan loan, final LocalDate tillDate) {
        if (!loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
            return;
        }
        try {
            this.loanAccrualsProcessingService.addPeriodicAccruals(tillDate, loan);
        } catch (final Exception e) {
            // Exception, not the declared MultiException: the single-loan overload never actually constructs one -
            // only the batch overload does - so real failures (a JE posting error, a null installment) arrive
            // unchecked. Taking the whole operation down is the point either way: a loan left with its old accruals
            // reversed and no replacements is worse than one that was never corrected, and there is no partial state
            // worth keeping.
            throw new GeneralPlatformDomainRuleException(ERROR_ACCRUAL_FAILED,
                    "Failed to re-post accruals for loan " + loan.getId() + " after regenerating its schedule.", e);
        }
    }
}
