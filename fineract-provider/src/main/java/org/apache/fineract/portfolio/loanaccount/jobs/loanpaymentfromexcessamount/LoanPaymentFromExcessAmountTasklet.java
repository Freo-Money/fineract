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

package org.apache.fineract.portfolio.loanaccount.jobs.loanpaymentfromexcessamount;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.transaction.support.TransactionTemplate;

@RequiredArgsConstructor
@Slf4j
public class LoanPaymentFromExcessAmountTasklet implements Tasklet {

    private final LoanAccountDomainService loanAccountDomainService;
    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final TransactionTemplate transactionTemplate;
    private final ExternalIdFactory externalIdFactory;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        final LocalDate businessDate = DateUtils.getBusinessLocalDate();
        // Collect ids only; each loan is then loaded and processed in its own REQUIRES_NEW transaction so one
        // failing loan neither rolls back the others nor poisons a shared persistence context.
        final List<Long> loanIds = loanRepositoryWrapper.getLoanIdsWithExcessAmount(businessDate);
        final List<Throwable> exceptions = new ArrayList<>();

        for (final Long loanId : loanIds) {
            try {
                transactionTemplate.executeWithoutResult(status -> applyExcessForLoan(loanId, businessDate));
            } catch (Exception e) {
                log.error("Loan Payment From Excess Amount failed for loan {}", loanId, e);
                exceptions.add(e);
            }
        }

        if (!exceptions.isEmpty()) {
            throw new JobExecutionException(exceptions);
        }

        return RepeatStatus.FINISHED;
    }

    private void applyExcessForLoan(final Long loanId, final LocalDate businessDate) {
        final Loan loan = loanRepositoryWrapper.findOneWithNotFoundDetection(loanId);
        final BigDecimal parkedPool = loan.getTotalExcessPaymentAmount();
        if (parkedPool == null || parkedPool.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        // makeRepayment refuses any transaction for a client/group that is no longer active; report instead of failing
        // the job night after night.
        if ((loan.client() != null && !loan.client().isActive()) || (loan.group() != null && !loan.group().isActive())) {
            log.warn("Loan [id={}] holds a parked excess pool of {} but its client/group is not active; it needs manual handling.",
                    loan.getId(), parkedPool);
            return;
        }

        // Safety net: a pool that outlived the last open installment (payoff via waiver, charge payment, adjustment,
        // ...) has nothing left to settle and must be reclassified to overpayment through a full-pool sweep, so the
        // parking liability moves to the overpayment liability in the ledger.
        if (allInstallmentsMet(loan)) {
            if (!(loan.getStatus().isActive() || loan.isClosedObligationsMet() || loan.getStatus().isOverpaid())) {
                log.warn("Loan [id={}] in status {} still holds a parked excess pool of {}; it needs manual handling.", loan.getId(),
                        loan.getStatus(), parkedPool);
                return;
            }
            log.info("Loan [id={}] is fully paid with parked excess {} outstanding; reclassifying it to overpayment.", loan.getId(),
                    parkedPool);
            postSweep(loan, businessDate, parkedPool, "Parked excess reclassified to overpayment");
            return;
        }

        if (!loan.getStatus().isActive()) {
            log.warn("Loan [id={}] in status {} still holds a parked excess pool of {}; it needs manual handling.", loan.getId(),
                    loan.getStatus(), parkedPool);
            return;
        }

        // Sized the same way as a sweep after a back-dated posting (everything due on or before the business date, or
        // the whole pool when nothing is open beyond it).
        loanAccountDomainService.sweepParkedExcessDueOn(loan, businessDate, "Auto Payment");
    }

    private boolean allInstallmentsMet(final Loan loan) {
        final List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        return !installments.isEmpty() && installments.stream().noneMatch(LoanRepaymentScheduleInstallment::isNotFullyPaidOff);
    }

    /**
     * The safety-net reclassification is dated on the business date the job runs. Holiday / non-working-day validation
     * is skipped: the sweep is system generated.
     */
    private void postSweep(final Loan loan, final LocalDate businessDate, final BigDecimal amount, final String note) {
        loanAccountDomainService.makeRepayment(LoanTransactionType.REPAYMENT_FROM_EXCESS_AMOUNT, loan, businessDate, amount, null, note,
                externalIdFactory.create(), false, null, false, null, true, false);
    }
}
