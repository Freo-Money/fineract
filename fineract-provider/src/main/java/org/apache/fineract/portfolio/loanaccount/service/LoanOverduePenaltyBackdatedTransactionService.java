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

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.portfolio.charge.service.ChargeUtils;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.loanschedule.data.OverdueLoanScheduleData;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Worker that aligns the overdue-installment penalty state of a loan to a target {@code asOfDate}: deactivates
 * penalties dated after that date, reverses accrual transactions in the same window (so journal entries — including any
 * NPA suspense legs — are unwound), reprocesses the loan, and re-applies any missing penalties up to {@code asOfDate}.
 * The config gate lives in {@link LoanOverduePenaltyAlignmentService}; this service is the implementation, called
 * whenever the gate is open.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanOverduePenaltyBackdatedTransactionService {

    private final LoanChargeRepository loanChargeRepository;
    private final LoanTransactionRepository loanTransactionRepository;
    private final LoanJournalEntryPoster loanJournalEntryPoster;
    private final OverdueInstallmentPenaltyScheduleDataBuilder overdueInstallmentPenaltyScheduleDataBuilder;
    private final ChargeUtils chargeUtils;
    private final ObjectProvider<LoanChargeWritePlatformService> loanChargeWritePlatformServiceProvider;

    @Transactional(propagation = Propagation.REQUIRED)
    public void alignPenaltiesAsOf(final Loan loan, final LocalDate asOfDate) {
        if (loan == null || asOfDate == null || !loan.isOpen()) {
            return;
        }
        final Long loanId = loan.getId();
        log.info("alignPenaltiesAsOf: loanId={}, asOfDate={}", loanId, asOfDate);

        // Step 1: deactivate overdue-installment penalties that, from the as-of view, shouldn't exist:
        // - charges dated AFTER asOfDate (their day hadn't happened yet from the as-of perspective)
        // - charges whose installment is still WITHIN its penaltyWaitPeriod at asOfDate (the customer is paying
        // inside the grace window, so no penalty should accrue for that installment regardless of charge date —
        // a backdated payment on day 7 for an installment due day 5 with waitPeriod=4 means the customer is still
        // inside the grace window, and the penalties COB applied for days 6/7 shouldn't apply).
        // Iterate loan.getCharges() (not the repository query) so we also see penalties dated <= asOfDate that need
        // the within-window check.
        int deactivated = 0;
        if (loan.getCharges() != null) {
            for (LoanCharge charge : loan.getCharges()) {
                if (charge == null || !charge.isActive() || charge.getChargeTimeType() == null
                        || !charge.getChargeTimeType().isOverdueInstallment()) {
                    continue;
                }
                final LocalDate chargeDue = charge.getDueLocalDate();
                final boolean datedAfterAsOfDate = chargeDue != null && chargeDue.isAfter(asOfDate);
                final boolean installmentWithinWaitWindow = isInstallmentWithinPenaltyWaitWindow(charge, asOfDate);
                if (datedAfterAsOfDate || installmentWithinWaitWindow) {
                    charge.setActive(false);
                    loanChargeRepository.save(charge);
                    deactivated++;
                }
            }
        }

        // Step 2: clear derived penaltyAccrued so reprocess recalculates from active charges.
        if (loan.getRepaymentScheduleInstallments() != null) {
            loan.getRepaymentScheduleInstallments().forEach(si -> {
                if (si != null) {
                    si.setPenaltyAccrued(null);
                }
            });
        }

        // Step 3: reverse accrual-related transactions dated after asOfDate. Inlined as `reverse + save + post-JE`
        // (rather than going through LoanAdjustmentService) to keep this service free of the dependency cycle that
        // adjustment carries into the user-transaction write path. The JE poster reverses the offsetting entries —
        // including any NPA suspense legs — based on the now-reversed transaction.
        final List<LoanTransaction> accrualsToReverse = loan.getLoanTransactions(tx -> tx.isNotReversed() && tx.getTransactionDate() != null
                && tx.getTransactionDate().isAfter(asOfDate) && tx.isAccrualRelated());
        for (LoanTransaction tx : accrualsToReverse) {
            tx.reverse(ExternalId.empty());
            tx.manuallyAdjustedOrReversed();
            loanTransactionRepository.saveAndFlush(tx);
            loanJournalEntryPoster.postJournalEntriesForLoanTransaction(tx, false, false);
        }

        // Step 4: apply any penalties missing as-of asOfDate. The asOfDate-aware overload bounds the per-installment
        // schedule to asOfDate so no post-asOfDate days are re-created. The applier internally reprocesses + saves
        // when it actually posts a penalty; if there is nothing to apply, the caller's transaction allocation (which
        // unconditionally reprocesses) refreshes derived state. So we don't reprocess explicitly here — that was a
        // redundant extra reprocess+save per backdated transaction.
        final List<OverdueLoanScheduleData> eligible = overdueInstallmentPenaltyScheduleDataBuilder.build(loan, asOfDate)
                .overdueInstallments();
        if (!eligible.isEmpty()) {
            loanChargeWritePlatformServiceProvider.getObject().applyOverdueChargesForLoan(loanId, eligible, asOfDate);
        }

        log.info("alignPenaltiesAsOf: loanId={}, deactivatedOverdueCharges={}, reversedAccruals={}, applyableInstallments={}", loanId,
                deactivated, accrualsToReverse.size(), eligible.size());
    }

    /**
     * Lightweight helper for the POST-allocation phase: applies any penalties that are missing as-of {@code asOfDate}
     * for installments still overdue, using the asOfDate-aware overload. Compared to {@link #alignPenaltiesAsOf}, this
     * method does NOT deactivate any charges, NOT reverse accruals, and NOT reset penaltyAccrued — the just-completed
     * allocation has already left the loan in the correct shape; we only need to fill in days that the PRE alignment
     * intentionally left out (i.e. dates after txnDate up to today). The eligible-builder uses {@code obligationsMet}
     * to skip installments paid by the just-allocated transaction, so the paid installment is not re-penalised for the
     * post-txnDate window.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void applyMissingPenaltiesUpTo(final Loan loan, final LocalDate asOfDate) {
        if (loan == null || asOfDate == null || !loan.isOpen()) {
            return;
        }
        final List<OverdueLoanScheduleData> eligible = overdueInstallmentPenaltyScheduleDataBuilder.build(loan, asOfDate)
                .overdueInstallments();
        if (eligible.isEmpty()) {
            return;
        }
        log.info("applyMissingPenaltiesUpTo: loanId={}, asOfDate={}, applyableInstallments={}", loan.getId(), asOfDate, eligible.size());
        loanChargeWritePlatformServiceProvider.getObject().applyOverdueChargesForLoan(loan.getId(), eligible, asOfDate);
    }

    /**
     * Returns true if the installment that this overdue-installment penalty charge is attached to is still inside its
     * {@code penaltyWaitPeriod} window at {@code asOfDate} — i.e. {@code asOfDate < installmentDueDate + waitPeriod}.
     * Mirrors the eligibility rule used by {@link OverdueInstallmentPenaltyScheduleDataBuilder} so the alignment worker
     * treats deactivation and re-application consistently: if the builder wouldn't apply a penalty for this installment
     * as of {@code asOfDate}, any pre-existing penalties for it shouldn't be live either.
     */
    private boolean isInstallmentWithinPenaltyWaitWindow(final LoanCharge charge, final LocalDate asOfDate) {
        if (charge.getOverdueInstallmentCharge() == null || charge.getOverdueInstallmentCharge().getInstallment() == null) {
            return false;
        }
        final LoanRepaymentScheduleInstallment installment = charge.getOverdueInstallmentCharge().getInstallment();
        if (installment.getDueDate() == null) {
            return false;
        }
        final long waitPeriod = chargeUtils.retrievePenaltyWaitPeriodDays(charge.getCharge());
        return asOfDate.isBefore(installment.getDueDate().plusDays(waitPeriod));
    }
}
