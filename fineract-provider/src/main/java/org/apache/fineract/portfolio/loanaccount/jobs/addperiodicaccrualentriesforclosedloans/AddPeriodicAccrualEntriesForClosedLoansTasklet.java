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
package org.apache.fineract.portfolio.loanaccount.jobs.addperiodicaccrualentriesforclosedloans;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.loanaccount.service.LoanAccrualsProcessingService;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * Continues periodic accrual on loans closed / overpaid by repayment until their last due date (COB processes open
 * loans only).
 */
@RequiredArgsConstructor
public class AddPeriodicAccrualEntriesForClosedLoansTasklet implements Tasklet {

    private final LoanAccrualsProcessingService loanAccrualsProcessingService;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        // same accrual horizon as the COB step, which runs under the COB action context and accrues to the COB date
        final LocalDate tillDate = ThreadLocalContextUtil.getBusinessDateByType(BusinessDateType.COB_DATE);
        loanAccrualsProcessingService.addPeriodicAccrualsForClosedLoans(tillDate);
        return RepeatStatus.FINISHED;
    }
}
