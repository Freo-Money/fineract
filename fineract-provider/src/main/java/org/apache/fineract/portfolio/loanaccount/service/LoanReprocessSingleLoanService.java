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

import java.sql.Timestamp;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.loanaccount.data.ScheduleGeneratorDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.bulkreprocess.LoanBulkReprocessRunItemStatus;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One Spring-proxied transaction per loan so bulk reprocess can commit per id.
 */
@Service
@RequiredArgsConstructor
public class LoanReprocessSingleLoanService {

    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final ReprocessLoanTransactionsService reprocessLoanTransactionsService;
    private final LoanScheduleService loanScheduleService;
    private final LoanUtilService loanUtilService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Dedicated bulk-flow method: for cumulative loans, recalculate schedule (which routes interest-bearing loans with
     * recalculation enabled through {@code regenerateRepaymentScheduleWithInterestRecalculation}) and reprocess
     * transactions in one call.
     * <p>
     * Atomicity guarantee: the loan reprocess and the {@code m_loan_bulk_reprocess_run_item.status = 'SUCCESS'} flip
     * for {@code runItemId} commit together in this single REQUIRES_NEW transaction. If the loan reprocess throws,
     * neither commits and the run-item stays {@code PENDING}, so the cursor reader will pick it up again on a restart.
     * If a chunk-level rollback happens after this method returns, the per-loan reprocess and SUCCESS marker still
     * survive because they were already committed in the inner transaction. This prevents the bug where a chunk-tx
     * rollback would leave loans actually reprocessed but their run-item rows stuck at PENDING.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void regenerateScheduleAndReprocessLoan(Long loanId, Long runItemId) {
        final Loan loan = loanRepositoryWrapper.findOneWithNotFoundDetection(loanId);
        if (LoanScheduleType.CUMULATIVE.equals(loan.getLoanProductRelatedDetail().getLoanScheduleType())) {
            final ScheduleGeneratorDTO scheduleGeneratorDTO = loanUtilService.buildScheduleGeneratorDTO(loan, null);
            loanScheduleService.recalculateSchedule(loan, scheduleGeneratorDTO);
        } else {
            reprocessLoanTransactionsService.reprocessTransactions(loan);
        }

        // PENDING guard: respects an operator releasePendingItems call (which moves PENDING -> FAILED).
        // If the row was released between cursor read and this commit, this UPDATE is a no-op and we let
        // the operator's intent stand.
        jdbcTemplate.update(
                "update m_loan_bulk_reprocess_run_item set status = ?, error_message = null, processed_date = ? where id = ? and status = ?",
                LoanBulkReprocessRunItemStatus.SUCCESS.name(), Timestamp.valueOf(DateUtils.getLocalDateTimeOfTenant()), runItemId,
                LoanBulkReprocessRunItemStatus.PENDING.name());
    }
}
