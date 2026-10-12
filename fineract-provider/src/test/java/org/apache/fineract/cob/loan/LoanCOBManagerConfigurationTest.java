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
package org.apache.fineract.cob.loan;

import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.springbatch.PropertyService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.partition.StepExecutionSplitter;
import org.springframework.batch.core.partition.support.PartitionStep;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.integration.partition.RemotePartitioningManagerStepBuilderFactory;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Guards the wiring in {@link LoanCOBManagerConfiguration#loanCOBStep()}: without the explicit splitter the builder
 * silently falls back to the stock, unordered one and LOAN_COB partitions are dispatched in arbitrary order again.
 */
@ExtendWith(MockitoExtension.class)
class LoanCOBManagerConfigurationTest {

    @Mock
    private JobRepository jobRepository;
    @Mock
    private JobExplorer jobExplorer;
    @Mock
    private PropertyService propertyService;

    @Test
    void loanCOBStepInstallsThePageOrderedSplitter() {
        LoanCOBManagerConfiguration configuration = new LoanCOBManagerConfiguration();
        ReflectionTestUtils.setField(configuration, "jobRepository", jobRepository);
        ReflectionTestUtils.setField(configuration, "stepBuilderFactory",
                new RemotePartitioningManagerStepBuilderFactory(jobRepository, jobExplorer));
        ReflectionTestUtils.setField(configuration, "propertyService", propertyService);
        ReflectionTestUtils.setField(configuration, "outboundRequests", new DirectChannel());
        when(propertyService.getPollInterval(LoanCOBConstant.JOB_NAME)).thenReturn(10000);

        Step step = configuration.loanCOBStep();

        Assertions.assertInstanceOf(PartitionStep.class, step);
        Assertions.assertEquals(LoanCOBConstant.LOAN_COB_PARTITIONER_STEP, step.getName());
        StepExecutionSplitter splitter = (StepExecutionSplitter) ReflectionTestUtils.getField(step, "stepExecutionSplitter");
        Assertions.assertInstanceOf(PageOrderedStepExecutionSplitter.class, splitter);
        Assertions.assertEquals(LoanCOBConstant.LOAN_COB_WORKER_STEP, splitter.getStepName());
    }
}
