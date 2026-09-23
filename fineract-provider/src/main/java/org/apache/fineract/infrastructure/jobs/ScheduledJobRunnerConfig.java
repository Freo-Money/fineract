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
package org.apache.fineract.infrastructure.jobs;

import java.util.List;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.persistence.ExtendedDataSourceTransactionManager;
import org.apache.fineract.infrastructure.core.persistence.ExtendedJpaTransactionManager;
import org.apache.fineract.infrastructure.core.persistence.TransactionLifecycleCallback;
import org.apache.fineract.infrastructure.core.service.database.RoutingDataSource;
import org.apache.fineract.infrastructure.jobs.config.FineractDataFieldMaxValueIncrementerFactory;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.explore.support.JobExplorerFactoryBean;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.dao.Jackson2ExecutionContextStringSerializer;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.item.database.support.DataFieldMaxValueIncrementerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableBatchProcessing
public class ScheduledJobRunnerConfig {

    // @Primary is required now that a second PlatformTransactionManager bean exists: unqualified
    // @Transactional and by-type injections must keep resolving to the JPA manager.
    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(FineractProperties fineractProperties,
            ObjectProvider<TransactionManagerCustomizers> transactionManagerCustomizers, List<TransactionLifecycleCallback> callbacks) {
        ExtendedJpaTransactionManager transactionManager = new ExtendedJpaTransactionManager(fineractProperties.getMode().isReadOnlyMode());
        transactionManager.setLifecycleCallbacks(callbacks);
        transactionManagerCustomizers.ifAvailable(customizers -> customizers.customize(transactionManager));
        return transactionManager;
    }

    @Bean
    public PlatformTransactionManager jdbcTransactionManager(FineractProperties fineractProperties, RoutingDataSource routingDataSource,
            ObjectProvider<TransactionManagerCustomizers> transactionManagerCustomizers, List<TransactionLifecycleCallback> callbacks) {
        ExtendedDataSourceTransactionManager transactionManager = new ExtendedDataSourceTransactionManager(
                fineractProperties.getMode().isReadOnlyMode());
        transactionManager.setDataSource(routingDataSource);
        transactionManager.setLifecycleCallbacks(callbacks);
        transactionManagerCustomizers.ifAvailable(customizers -> customizers.customize(transactionManager));
        return transactionManager;
    }

    @Bean
    public Jackson2ExecutionContextStringSerializer executionContextSerializer() {
        return new Jackson2ExecutionContextStringSerializer();
    }

    @Bean
    public DataFieldMaxValueIncrementerFactory incrementerFactory(RoutingDataSource routingDataSource) {
        // The DefaultDataFieldMaxValueIncrementerFactory has to be overridden because Spring 6 introduced
        // a new MariaDB incrementer that's incompatible with Spring Batch 4.x
        return new FineractDataFieldMaxValueIncrementerFactory(routingDataSource);
    }

    // The JobRepository stays on the JPA transaction manager: Spring Batch persists the step execution
    // context from INSIDE chunk transactions, and a second transaction manager on the same DataSource
    // would piggyback on the JPA transaction's connection and commit it mid-chunk. ISOLATION_DEFAULT
    // (required by the JPA manager's isolation guard) equals the READ_COMMITTED previously forced here
    // on PostgreSQL (production); on MySQL (local dev) the default is REPEATABLE_READ, so concurrent
    // job-launch races can surface differently there (gap-lock deadlocks instead of a clean
    // JobExecutionAlreadyRunningException). Duplicate job starts are still blocked on both backends by
    // the JOB_INST_UN unique constraint.
    @Bean
    public JobRepository jobRepository(RoutingDataSource routingDataSource, PlatformTransactionManager transactionManager,
            Jackson2ExecutionContextStringSerializer executionContextSerializer, DataFieldMaxValueIncrementerFactory incrementerFactory)
            throws Exception {
        JobRepositoryFactoryBean factory = new JobRepositoryFactoryBean();
        factory.setDataSource(routingDataSource);
        factory.setTransactionManager(transactionManager);
        factory.setIsolationLevelForCreate("ISOLATION_DEFAULT");
        factory.setSerializer(executionContextSerializer);
        factory.setIncrementerFactory(incrementerFactory);
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    @Bean
    public JobExplorer jobExplorer(RoutingDataSource routingDataSource, PlatformTransactionManager transactionManager,
            Jackson2ExecutionContextStringSerializer executionContextSerializer) throws Exception {
        JobExplorerFactoryBean jobExplorerFactoryBean = new JobExplorerFactoryBean();
        jobExplorerFactoryBean.setDataSource(routingDataSource);
        jobExplorerFactoryBean.setTransactionManager(transactionManager);
        jobExplorerFactoryBean.setSerializer(executionContextSerializer);
        jobExplorerFactoryBean.afterPropertiesSet();
        return jobExplorerFactoryBean.getObject();
    }

    @Bean
    public TaskExecutorJobLauncher jobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.afterPropertiesSet();
        return launcher;
    }
}
