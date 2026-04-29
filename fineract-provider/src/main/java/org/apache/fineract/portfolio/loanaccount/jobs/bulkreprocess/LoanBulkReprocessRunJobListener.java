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

import java.time.LocalDate;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.businessdate.service.BusinessDateReadPlatformService;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.portfolio.loanaccount.domain.bulkreprocess.LoanBulkReprocessRun;
import org.apache.fineract.portfolio.loanaccount.domain.bulkreprocess.LoanBulkReprocessRunRepository;
import org.apache.fineract.portfolio.loanaccount.domain.bulkreprocess.LoanBulkReprocessRunStatus;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepositoryWrapper;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@RequiredArgsConstructor
public class LoanBulkReprocessRunJobListener implements JobExecutionListener {

    private final LoanBulkReprocessRunRepository runRepository;
    private final TenantDetailsService tenantDetailsService;
    private final AppUserRepositoryWrapper userRepository;
    private final BusinessDateReadPlatformService businessDateReadPlatformService;
    private static final String CTX_INITIALIZED_KEY = "bulkReprocessContextInitialized";

    @Override
    public void beforeJob(JobExecution jobExecution) {
        final Long runId = jobExecution.getJobParameters().getLong("runId");
        initThreadLocalContextIfNeeded(jobExecution);
        runRepository.markRunning(runId, LoanBulkReprocessRunStatus.RUNNING, DateUtils.getLocalDateTimeOfTenant());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        final Long runId = jobExecution.getJobParameters().getLong("runId");

        String errorMessage = null;
        LoanBulkReprocessRunStatus finalStatus;
        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            LoanBulkReprocessRun run = runRepository.findById(runId).orElse(null);
            if (run != null && run.getFailedCount() != null && run.getFailedCount() > 0) {
                finalStatus = LoanBulkReprocessRunStatus.FINISHED_WITH_FAILURES;
            } else {
                finalStatus = LoanBulkReprocessRunStatus.FINISHED;
            }
        } else {
            finalStatus = LoanBulkReprocessRunStatus.FAILED;
            if (jobExecution.getAllFailureExceptions() != null && !jobExecution.getAllFailureExceptions().isEmpty()) {
                Throwable t = jobExecution.getAllFailureExceptions().get(0);
                // Always include the exception type so triage doesn't depend on a useful getMessage().
                // Trim to fit error_message VARCHAR(2000); without this, long Spring Batch exception chains can
                // overflow the column and the finish() UPDATE fails — leaving the run in an inconsistent state.
                final String raw = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
                errorMessage = raw.length() > 1990 ? raw.substring(0, 1990) : raw;
            }
        }
        runRepository.finish(runId, finalStatus, DateUtils.getLocalDateTimeOfTenant(), errorMessage);
        final Object initialized = jobExecution.getExecutionContext().get(CTX_INITIALIZED_KEY);
        if (initialized instanceof Boolean b && b) {
            ThreadLocalContextUtil.reset();
            SecurityContextHolder.clearContext();
        }
    }

    private void initThreadLocalContextIfNeeded(final JobExecution jobExecution) {
        if (ThreadLocalContextUtil.getTenant() != null) {
            return;
        }
        jobExecution.getExecutionContext().put(CTX_INITIALIZED_KEY, Boolean.TRUE);
        final String tenantIdentifier = jobExecution.getJobParameters().getString("tenantIdentifier");
        final FineractPlatformTenant tenant = tenantDetailsService.loadTenantById(tenantIdentifier);
        ThreadLocalContextUtil.setTenant(tenant);
        AppUser user = this.userRepository.fetchSystemUser();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(user, user.getPassword(), user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
        HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>(businessDateReadPlatformService.getBusinessDates());
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil.setBusinessDates(businessDates);
    }
}
