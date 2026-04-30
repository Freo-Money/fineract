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
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformInternalServerException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.loanaccount.data.LoanBulkReprocessRequestData;
import org.apache.fineract.portfolio.loanaccount.domain.bulkreprocess.LoanBulkReprocessRun;
import org.apache.fineract.portfolio.loanaccount.domain.bulkreprocess.LoanBulkReprocessRunItemStatus;
import org.apache.fineract.portfolio.loanaccount.domain.bulkreprocess.LoanBulkReprocessRunRepository;
import org.apache.fineract.portfolio.loanaccount.domain.bulkreprocess.LoanBulkReprocessRunStatus;
import org.apache.fineract.portfolio.loanaccount.exception.LoanBulkReprocessRunNotFoundException;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.configuration.JobLocator;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Slf4j
public class LoanBulkReprocessRunWritePlatformServiceImpl implements LoanBulkReprocessRunWritePlatformService {

    public static final String JOB_NAME = "LOAN_BULK_REPROCESS_RUN";

    private final PlatformSecurityContext securityContext;
    private final LoanBulkReprocessRunRepository runRepository;
    private final JdbcTemplate jdbcTemplate;
    private final JobLauncher jobLauncher;
    private final JobLocator jobLocator;
    private final LoanBulkReprocessRequestParser requestParser;

    public LoanBulkReprocessRunWritePlatformServiceImpl(final PlatformSecurityContext securityContext,
            final LoanBulkReprocessRunRepository runRepository, final JdbcTemplate jdbcTemplate,
            // Inject the dedicated async launcher so the API thread does not block on the run.
            @Qualifier(LoanBulkReprocessConstants.JOB_LAUNCHER_BEAN_NAME) final JobLauncher jobLauncher, final JobLocator jobLocator,
            final LoanBulkReprocessRequestParser requestParser) {
        this.securityContext = securityContext;
        this.runRepository = runRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.jobLauncher = jobLauncher;
        this.jobLocator = jobLocator;
        this.requestParser = requestParser;
    }

    @Override
    @Transactional
    public CommandProcessingResult submitAsyncRun(final JsonCommand command) {
        // Pre-resolve to fail fast on no auth — auditing will populate createdBy/createdOnUtc on save.
        final Long submittingUserId = securityContext.authenticatedUser().getId();
        final LoanBulkReprocessRequestData request = requestParser.parse(command.json());
        final int resolvedBatchSize = resolveBatchSize(request.getBatchSize());

        final LoanBulkReprocessRun run = new LoanBulkReprocessRun();
        run.setStatus(LoanBulkReprocessRunStatus.SUBMITTED);
        run.setBatchSize(resolvedBatchSize);
        run.setTotalLoanIds(request.getDistinctLoanIds().size());
        run.setProcessedCount(0);
        run.setFailedCount(0);
        final LoanBulkReprocessRun saved = runRepository.saveAndFlush(run);

        batchInsertItems(saved.getId(), request.getDistinctLoanIds());

        // Defer the launch to afterCompletion (committed branch) so the job sees the persisted run+items.
        // The injected jobLauncher is wired with its own ThreadPoolTaskExecutor (see
        // LoanBulkReprocessRunJobConfiguration),
        // so .run() hands off to that executor and returns immediately — the API request does not block.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void afterCompletion(int status) {
                // Spring Batch's JobRepository rejects job launches when an ambient transaction exists.
                // afterCompletion runs after transaction resources are unbound, so launching the job from here avoids:
                // "Existing transaction detected in JobRepository". Use this hook (not afterCommit) so the
                // rollback branch below can still mark the run FAILED.
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    launchJobOrMarkFailed(saved.getId(), submittingUserId);
                } else {
                    try {
                        runRepository.finish(saved.getId(), LoanBulkReprocessRunStatus.FAILED, DateUtils.getLocalDateTimeOfTenant(),
                                "Bulk reprocess submission transaction rolled back; job was not launched");
                    } catch (RuntimeException inner) {
                        log.error("Failed to mark run {} as FAILED after rollback", saved.getId(), inner);
                    }
                }
            }
        });
        // resourceId carries the run id; CommandSource framework persists this on m_portfolio_command_source.
        return new CommandProcessingResultBuilder().withCommandId(command.commandId()).withResourceIdAsString(saved.getId().toString())
                .withEntityId(saved.getId()).build();
    }

    @Override
    public LoanBulkReprocessRun getRun(final Long runId) {
        return runRepository.findById(runId).orElseThrow(() -> new LoanBulkReprocessRunNotFoundException(runId));
    }

    @Override
    @Transactional
    public CommandProcessingResult releasePendingItems(final JsonCommand command) {
        final Long runId = command.entityId();
        doReleasePendingItems(runId);
        return new CommandProcessingResultBuilder().withCommandId(command.commandId()).withResourceIdAsString(runId.toString())
                .withEntityId(runId).build();
    }

    private int doReleasePendingItems(final Long runId) {
        final LoanBulkReprocessRun run = runRepository.findById(runId).orElseThrow(() -> new LoanBulkReprocessRunNotFoundException(runId));
        // Refuse if the run is still RUNNING. The operator endpoint is for stuck runs only — releasing while a worker
        // is alive races with the cursor reader and the chunk writer's status flips.
        if (run.getStatus() == LoanBulkReprocessRunStatus.RUNNING) {
            throw new PlatformInternalServerException("error.loan.bulk.reprocess.run.still.running",
                    "Run " + runId + " is still RUNNING. Wait for it to finish or for the worker to die before releasing PENDING items.",
                    runId);
        }
        final int releasedCount = jdbcTemplate.update(
                "update m_loan_bulk_reprocess_run_item set status = ?, error_message = ?, processed_date = ? where run_id = ? and status = ?",
                LoanBulkReprocessRunItemStatus.FAILED.name(),
                "Released by operator. Run was stuck in non-terminal state; re-submit these loan ids if needed.",
                Timestamp.valueOf(DateUtils.getLocalDateTimeOfTenant()), runId, LoanBulkReprocessRunItemStatus.PENDING.name());
        // No-op release on an already-terminal run: do not overwrite the original finished_date / errorMessage.
        if (releasedCount == 0 && (run.getStatus() == LoanBulkReprocessRunStatus.FINISHED
                || run.getStatus() == LoanBulkReprocessRunStatus.FINISHED_WITH_FAILURES
                || run.getStatus() == LoanBulkReprocessRunStatus.FAILED)) {
            return 0;
        }
        // Reconcile run-row counters from the authoritative item table. Per-loan REQUIRES_NEW commits SUCCESS markers
        // independently of the chunk-tx counter increments, so the run row's processedCount can drift below the actual
        // SUCCESS count after a chunk rollback. Compute absolute values here so the run row is authoritative once the
        // operator closes it.
        final Integer successCount = jdbcTemplate.queryForObject(
                "select count(*) from m_loan_bulk_reprocess_run_item where run_id = ? and status = ?", Integer.class, runId,
                LoanBulkReprocessRunItemStatus.SUCCESS.name());
        final Integer failedCountFromItems = jdbcTemplate.queryForObject(
                "select count(*) from m_loan_bulk_reprocess_run_item where run_id = ? and status = ?", Integer.class, runId,
                LoanBulkReprocessRunItemStatus.FAILED.name());
        final int reconciledProcessed = successCount != null ? successCount : 0;
        final int reconciledFailed = failedCountFromItems != null ? failedCountFromItems : 0;
        runRepository.setCounts(runId, reconciledProcessed, reconciledFailed);

        final String finalMessage = releasedCount > 0
                ? "Operator released " + releasedCount + " stuck PENDING item(s). Re-submit those loan ids to retry."
                : "No PENDING items found to release.";
        final LoanBulkReprocessRunStatus finalStatus = reconciledFailed > 0 ? LoanBulkReprocessRunStatus.FINISHED_WITH_FAILURES
                : LoanBulkReprocessRunStatus.FINISHED;
        runRepository.finish(runId, finalStatus, DateUtils.getLocalDateTimeOfTenant(), finalMessage);
        return releasedCount;
    }

    private void batchInsertItems(final Long runId, final List<Long> distinctLoanIds) {
        jdbcTemplate.batchUpdate("insert into m_loan_bulk_reprocess_run_item (run_id, loan_id, status) values (?, ?, ?)", distinctLoanIds,
                LoanBulkReprocessConstants.JDBC_BATCH_SIZE_DEFAULT, (ps, loanId) -> {
                    ps.setLong(1, runId);
                    ps.setLong(2, loanId);
                    ps.setString(3, LoanBulkReprocessRunItemStatus.PENDING.name());
                });
    }

    /**
     * Called from the {@code afterCompletion} committed branch. Catches launch failures so the run does not stay stuck
     * in {@code SUBMITTED} — Spring's transaction synchronization swallows callback exceptions, so we record FAILED
     * ourselves.
     */
    private void launchJobOrMarkFailed(final Long runId, final Long submittingUserId) {
        try {
            launchJob(runId, submittingUserId);
        } catch (RuntimeException e) {
            log.error("Failed to launch bulk loan reprocess job for runId {}", runId, e);
            // Always include the exception type so triage doesn't depend on a useful getMessage().
            final String raw = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            final String trimmed = raw.length() > 1990 ? raw.substring(0, 1990) : raw;
            try {
                runRepository.finish(runId, LoanBulkReprocessRunStatus.FAILED, DateUtils.getLocalDateTimeOfTenant(), trimmed);
            } catch (RuntimeException inner) {
                log.error("Also failed to mark run {} as FAILED after launch failure", runId, inner);
            }
        }
    }

    private void launchJob(final Long runId, final Long submittingUserId) {
        final LoanBulkReprocessRun run = runRepository.findById(runId).orElseThrow(() -> new LoanBulkReprocessRunNotFoundException(runId));
        final Job job;
        try {
            job = jobLocator.getJob(JOB_NAME);
        } catch (NoSuchJobException e) {
            // Pass the original cause via the message-args (AbstractPlatformException pulls out Throwable as cause).
            throw new PlatformInternalServerException("error.loan.bulk.reprocess.job.not.found",
                    "Bulk loan reprocess job not registered: " + JOB_NAME, JOB_NAME, e);
        }
        if (ThreadLocalContextUtil.getTenant() == null) {
            throw new PlatformInternalServerException("error.loan.bulk.reprocess.tenant.missing",
                    "Tenant identifier is missing while launching bulk reprocess job", runId);
        }
        final String tenantIdentifier = ThreadLocalContextUtil.getTenant().getTenantIdentifier();
        final JobParameters jobParameters = new JobParametersBuilder().addLong("runId", runId)
                .addLong("batchSize",
                        run.getBatchSize() != null ? run.getBatchSize().longValue() : LoanBulkReprocessConstants.DEFAULT_BATCH_SIZE)
                .addString("tenantIdentifier", tenantIdentifier).addLong("submittedByUserId", submittingUserId)
                // ensure uniqueness per submission
                .addLong("submittedAt", System.currentTimeMillis()).toJobParameters();
        try {
            jobLauncher.run(job, jobParameters);
        } catch (Exception e) {
            throw new PlatformInternalServerException("error.loan.bulk.reprocess.job.launch.failed",
                    "Failed to launch bulk loan reprocess job for runId " + runId, runId, e);
        }
    }

    private int resolveBatchSize(final Integer batchSize) {
        if (batchSize == null) {
            return LoanBulkReprocessConstants.DEFAULT_BATCH_SIZE;
        }
        if (batchSize < LoanBulkReprocessConstants.MIN_BATCH_SIZE || batchSize > LoanBulkReprocessConstants.MAX_BATCH_SIZE) {
            // Should already be validated by request parser, but keep server-side safety.
            return LoanBulkReprocessConstants.DEFAULT_BATCH_SIZE;
        }
        return batchSize;
    }
}
