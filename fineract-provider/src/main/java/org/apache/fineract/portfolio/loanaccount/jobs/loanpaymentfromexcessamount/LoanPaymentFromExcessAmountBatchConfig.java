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

import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class LoanPaymentFromExcessAmountBatchConfig {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private LoanAccountDomainService loanAccountDomainService;

    @Autowired
    private LoanRepositoryWrapper loanRepositoryWrapper;

    @Bean
    protected Step loanPaymentFromExcessAmountStep() {
        return new StepBuilder(JobName.LOAN_PAYMENT_FROM_EXCESS_AMOUNT.name(), jobRepository)
                .tasklet(loanPaymentFromExcessAmountTasklet(), transactionManager).build();
    }

    @Bean
    public Job loanPaymentFromExcessAmountJob() {
        return new JobBuilder(JobName.LOAN_PAYMENT_FROM_EXCESS_AMOUNT.name(), jobRepository).start(loanPaymentFromExcessAmountStep())
                .incrementer(new RunIdIncrementer()).build();
    }

    @Bean
    public LoanPaymentFromExcessAmountTasklet loanPaymentFromExcessAmountTasklet() {
        // Each loan is processed in its own REQUIRES_NEW transaction (see tasklet) so a failure on one loan does
        // not roll back the payments already made for the others.
        final TransactionTemplate perLoanTransactionTemplate = new TransactionTemplate(transactionManager);
        perLoanTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new LoanPaymentFromExcessAmountTasklet(loanAccountDomainService, loanRepositoryWrapper, perLoanTransactionTemplate);
    }
}
