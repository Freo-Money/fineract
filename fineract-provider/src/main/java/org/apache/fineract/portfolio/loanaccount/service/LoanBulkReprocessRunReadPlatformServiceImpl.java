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

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.loanaccount.data.LoanBulkReprocessFailurePageData;
import org.apache.fineract.portfolio.loanaccount.data.LoanBulkReprocessRunData;
import org.apache.fineract.portfolio.loanaccount.data.LoanReprocessFailureData;
import org.apache.fineract.portfolio.loanaccount.domain.bulkreprocess.LoanBulkReprocessRun;
import org.apache.fineract.portfolio.loanaccount.domain.bulkreprocess.LoanBulkReprocessRunItemStatus;
import org.apache.fineract.portfolio.loanaccount.domain.bulkreprocess.LoanBulkReprocessRunRepository;
import org.apache.fineract.portfolio.loanaccount.exception.LoanBulkReprocessRunNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LoanBulkReprocessRunReadPlatformServiceImpl implements LoanBulkReprocessRunReadPlatformService {

    private final LoanBulkReprocessRunRepository runRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public LoanBulkReprocessRunData retrieveRun(final Long runId) {
        final LoanBulkReprocessRun run = runRepository.findById(runId).orElseThrow(() -> new LoanBulkReprocessRunNotFoundException(runId));
        return toData(run);
    }

    @Override
    public LoanBulkReprocessFailurePageData retrieveFailures(final Long runId, final int offset, final int limit) {
        final int safeOffset = Math.max(0, offset);
        final int safeLimit = Math.min(LoanBulkReprocessConstants.MAX_FAILURE_DETAILS_IN_RESPONSE, Math.max(1, limit));

        final Integer totalFailed = jdbcTemplate.queryForObject(
                "select count(*) from m_loan_bulk_reprocess_run_item where run_id = ? and status = ?", Integer.class, runId,
                LoanBulkReprocessRunItemStatus.FAILED.name());

        final List<LoanReprocessFailureData> failures = jdbcTemplate.query(
                "select loan_id, error_message from m_loan_bulk_reprocess_run_item where run_id = ? and status = ? order by id limit ? offset ?",
                (rs, rowNum) -> new LoanReprocessFailureData(rs.getLong("loan_id"), rs.getString("error_message")), runId,
                LoanBulkReprocessRunItemStatus.FAILED.name(), safeLimit, safeOffset);

        final LoanBulkReprocessFailurePageData data = new LoanBulkReprocessFailurePageData();
        data.setRunId(runId);
        data.setOffset(safeOffset);
        data.setLimit(safeLimit);
        data.setTotalFailed(totalFailed != null ? totalFailed : 0);
        data.setFailures(failures);
        return data;
    }

    private LoanBulkReprocessRunData toData(final LoanBulkReprocessRun run) {
        final LoanBulkReprocessRunData data = new LoanBulkReprocessRunData();
        data.setRunId(run.getId());
        data.setStatus(run.getStatus() != null ? run.getStatus().name() : null);
        data.setBatchSize(run.getBatchSize());
        data.setTotalLoanIds(run.getTotalLoanIds());
        data.setProcessedCount(run.getProcessedCount());
        data.setFailedCount(run.getFailedCount());
        data.setCreatedDate(run.getCreatedDate().orElse(null));
        data.setStartedDate(run.getStartedDate());
        data.setFinishedDate(run.getFinishedDate());
        data.setErrorMessage(run.getErrorMessage());
        return data;
    }
}
