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
package org.apache.fineract.portfolio.loanaccount.jobs.bulkreprocess;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.businessdate.service.BusinessDateReadPlatformService;
import org.apache.fineract.infrastructure.core.domain.FineractContext;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.RoutingDataSource;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.portfolio.loanaccount.domain.bulkreprocess.LoanBulkReprocessRunRepository;
import org.apache.fineract.portfolio.loanaccount.service.LoanBulkReprocessConstants;
import org.apache.fineract.portfolio.loanaccount.service.LoanBulkReprocessRunWritePlatformServiceImpl;
import org.apache.fineract.portfolio.loanaccount.service.LoanReprocessSingleLoanService;
import org.apache.fineract.useradministration.domain.AppUserRepositoryWrapper;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@RequiredArgsConstructor
public class LoanBulkReprocessRunJobConfiguration {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final RoutingDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final LoanReprocessSingleLoanService loanReprocessSingleLoanService;
    private final LoanBulkReprocessRunRepository runRepository;
    private final TenantDetailsService tenantDetailsService;
    private final AppUserRepositoryWrapper userRepository;
    private final BusinessDateReadPlatformService businessDateReadPlatformService;

    @Bean(name = LoanBulkReprocessRunWritePlatformServiceImpl.JOB_NAME)
    public Job loanBulkReprocessRunJob() {
        return new JobBuilder(LoanBulkReprocessRunWritePlatformServiceImpl.JOB_NAME, jobRepository).start(loanBulkReprocessRunStep(null))
                .listener((JobExecutionListener) loanBulkReprocessRunJobListener()).build();
    }

    @Bean
    public LoanBulkReprocessRunJobListener loanBulkReprocessRunJobListener() {
        return new LoanBulkReprocessRunJobListener(runRepository, tenantDetailsService, userRepository, businessDateReadPlatformService);
    }

    /**
     * Dedicated thread pool for the bulk reprocess job. Sized to a single worker so a submission accepts work
     * immediately (the API thread returns) but never runs more than one bulk reprocess at the same time per node.
     * Submissions beyond capacity are queued.
     */
    @Bean(name = LoanBulkReprocessConstants.JOB_TASK_EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor loanBulkReprocessTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(LoanBulkReprocessConstants.JOB_LAUNCHER_QUEUE_CAPACITY);
        executor.setThreadNamePrefix("loan-bulk-reprocess-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // Propagate tenant + business date + security context from the submitter thread to the async worker thread.
        // Without this, RoutingDataSource resolves to "no-tenant" and Spring Batch metadata tables are looked up in the
        // wrong DB.
        executor.setTaskDecorator(task -> {
            final FineractContext fineractContext = ThreadLocalContextUtil.getContext();
            final SecurityContext securityContext = SecurityContextHolder.getContext();
            return () -> {
                try {
                    ThreadLocalContextUtil.init(fineractContext);
                    SecurityContextHolder.setContext(securityContext);
                    task.run();
                } finally {
                    ThreadLocalContextUtil.reset();
                    SecurityContextHolder.clearContext();
                }
            };
        });
        executor.initialize();
        return executor;
    }

    /**
     * Asynchronous {@link JobLauncher} for the bulk reprocess job: {@code run()} hands off to the executor and returns
     * immediately with a {@link org.springframework.batch.core.JobExecution}, instead of running the job on the
     * caller's thread (which the default platform-wide launcher does, since it is wired with
     * {@link org.springframework.core.task.SyncTaskExecutor}).
     */
    @Bean(name = LoanBulkReprocessConstants.JOB_LAUNCHER_BEAN_NAME)
    public JobLauncher loanBulkReprocessJobLauncher(ThreadPoolTaskExecutor loanBulkReprocessTaskExecutor) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(loanBulkReprocessTaskExecutor);
        launcher.afterPropertiesSet();
        return launcher;
    }

    @Bean
    @JobScope
    public Step loanBulkReprocessRunStep(@Value("#{jobParameters['batchSize']}") Integer batchSize) {
        final int effectiveBatchSize = (batchSize == null) ? LoanBulkReprocessConstants.DEFAULT_BATCH_SIZE
                : Math.min(LoanBulkReprocessConstants.MAX_BATCH_SIZE, Math.max(LoanBulkReprocessConstants.MIN_BATCH_SIZE, batchSize));
        return new StepBuilder("Loan bulk reprocess run step", jobRepository)
                .<LoanBulkReprocessRunItemRow, LoanBulkReprocessRunItemResult>chunk(effectiveBatchSize, transactionManager)
                .reader(loanBulkReprocessRunItemReader(null)).processor(loanBulkReprocessRunItemProcessor())
                .writer(loanBulkReprocessRunItemWriter(null)).build();
    }

    @Bean
    @StepScope
    public JdbcCursorItemReader<LoanBulkReprocessRunItemRow> loanBulkReprocessRunItemReader(
            @Value("#{jobParameters['runId']}") Long runId) {
        JdbcCursorItemReader<LoanBulkReprocessRunItemRow> reader = new JdbcCursorItemReader<>();
        reader.setName("loanBulkReprocessRunItemReader");
        reader.setDataSource(dataSource);
        // Reduce round-trips for large runs
        reader.setFetchSize(LoanBulkReprocessConstants.JDBC_FETCH_SIZE);
        reader.setSql("""
                select id, loan_id
                  from m_loan_bulk_reprocess_run_item
                 where run_id = ?
                   and status = 'PENDING'
                 order by id
                """);
        reader.setPreparedStatementSetter(ps -> ps.setLong(1, runId));
        reader.setRowMapper((rs, rowNum) -> new LoanBulkReprocessRunItemRow(rs.getLong("id"), rs.getLong("loan_id")));
        return reader;
    }

    @Bean
    public ItemProcessor<LoanBulkReprocessRunItemRow, LoanBulkReprocessRunItemResult> loanBulkReprocessRunItemProcessor() {
        return item -> {
            try {
                // The service commits the loan reprocess and the run-item SUCCESS marker atomically in a
                // single REQUIRES_NEW transaction, so a later chunk-tx rollback cannot leave the loan
                // reprocessed-but-PENDING (the previously misleading-audit bug).
                loanReprocessSingleLoanService.regenerateScheduleAndReprocessLoan(item.loanId(), item.id());
                return LoanBulkReprocessRunItemResult.success(item.id());
            } catch (Exception e) {
                // Trim to fit error_message VARCHAR(2000); writer will fail the whole chunk on truncation otherwise.
                final String raw = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                final String trimmed = raw.length() > 1990 ? raw.substring(0, 1990) : raw;
                return LoanBulkReprocessRunItemResult.failure(item.id(), trimmed);
            }
        };
    }

    @Bean
    @StepScope
    public ItemWriter<LoanBulkReprocessRunItemResult> loanBulkReprocessRunItemWriter(@Value("#{jobParameters['runId']}") Long runId) {
        return items -> {
            if (items == null || items.isEmpty()) {
                return;
            }
            final List<LoanBulkReprocessRunItemResult> list = new ArrayList<>(items.getItems());
            int processedDelta = 0;
            int failedDelta = 0;
            final List<LoanBulkReprocessRunItemResult> failureRows = new ArrayList<>();
            for (LoanBulkReprocessRunItemResult item : list) {
                if (item.success()) {
                    // SUCCESS rows are already committed atomically inside the per-loan REQUIRES_NEW tx
                    // by LoanReprocessSingleLoanService#regenerateScheduleAndReprocessLoan. Do NOT update them
                    // here — that would re-introduce the chunk-rollback hole this design closes.
                    processedDelta++;
                } else {
                    failedDelta++;
                    failureRows.add(item);
                }
            }

            // FAILED rows are not yet persisted (the per-loan tx rolled back before reaching the SUCCESS marker).
            // status='PENDING' guard: an operator releasePendingItems call between cursor read and writer flip
            // could have already moved the row to FAILED. With the guard the UPDATE no-ops on already-released rows.
            if (!failureRows.isEmpty()) {
                final Timestamp processedAt = Timestamp.valueOf(DateUtils.getLocalDateTimeOfTenant());
                jdbcTemplate.batchUpdate(
                        "update m_loan_bulk_reprocess_run_item set status = 'FAILED', error_message = ?, processed_date = ? where id = ? and status = 'PENDING'",
                        failureRows, LoanBulkReprocessConstants.JDBC_BATCH_SIZE_DEFAULT, (ps, item) -> {
                            ps.setString(1, item.errorMessage());
                            ps.setTimestamp(2, processedAt);
                            ps.setLong(3, item.id());
                        });
            }

            // Counters live on m_loan_bulk_reprocess_run and are updated in this chunk tx. On a chunk rollback
            // these increments are lost, but the per-item SUCCESS rows survive (they're the source of truth);
            // the operator releasePendingItems endpoint reconciles run-level state after a stuck run.
            runRepository.incrementCounts(runId, processedDelta, failedDelta);
        };
    }
}
