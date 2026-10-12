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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.partition.support.PartitionNameProvider;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.partition.support.SimpleStepExecutionSplitter;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ExecutionContext;

@ExtendWith(MockitoExtension.class)
class PageOrderedStepExecutionSplitterTest {

    private static final String WORKER_STEP = LoanCOBConstant.LOAN_COB_WORKER_STEP;
    private static final JobInstance JOB_INSTANCE = new JobInstance(1L, "LOAN_COB");

    @Mock
    private JobRepository jobRepository;
    @Mock
    private Partitioner partitioner;

    @Test
    void partitionsAreCreatedPersistedAndReturnedInPageOrderRegardlessOfMapOrder() throws JobExecutionException {
        // given: a scrambled map, as Collectors.toMap in LoanCOBPartitioner produces
        Map<String, ExecutionContext> contexts = new HashMap<>();
        for (long page : new long[] { 7, 2, 11, 0, 5, 1, 10, 3 }) {
            contexts.put(LoanCOBPartitioner.PARTITION_PREFIX + page, contextForPage(page));
        }
        when(partitioner.partition(anyInt())).thenReturn(contexts);
        when(jobRepository.getLastStepExecution(any(), anyString())).thenReturn(null);
        StepExecution managerStepExecution = newJobExecution(10L).createStepExecution(LoanCOBConstant.LOAN_COB_PARTITIONER_STEP);

        // when
        List<StepExecution> result = new ArrayList<>(splitter().split(managerStepExecution, 6));

        // then: numeric, not lexical, order in the returned set
        List<String> expected = List.of("partition_0", "partition_1", "partition_2", "partition_3", "partition_5", "partition_7",
                "partition_10", "partition_11");
        Assertions.assertEquals(expected, stepSuffixes(result));

        // and: the collection handed to addAll (which assigns the ids) is already in that order
        ArgumentCaptor<Collection<StepExecution>> persisted = ArgumentCaptor.captor();
        verify(jobRepository).addAll(persisted.capture());
        Assertions.assertEquals(expected, stepSuffixes(new ArrayList<>(persisted.getValue())));

        // and: the job execution's own step list (creation order) matches too
        List<String> created = new ArrayList<>(List.of(LoanCOBConstant.LOAN_COB_PARTITIONER_STEP));
        created.addAll(expected);
        Assertions.assertEquals(created, stepSuffixes(new ArrayList<>(managerStepExecution.getJobExecution().getStepExecutions())));
    }

    @Test
    void partitionKeyWithoutNumericSuffixSortsLast() throws JobExecutionException {
        Map<String, ExecutionContext> contexts = new HashMap<>();
        contexts.put("partition_legacy", new ExecutionContext());
        contexts.put("partition_1", contextForPage(1));
        contexts.put("partition_0", contextForPage(0));
        when(partitioner.partition(anyInt())).thenReturn(contexts);
        when(jobRepository.getLastStepExecution(any(), anyString())).thenReturn(null);
        StepExecution managerStepExecution = newJobExecution(10L).createStepExecution(LoanCOBConstant.LOAN_COB_PARTITIONER_STEP);

        List<StepExecution> result = new ArrayList<>(splitter().split(managerStepExecution, 6));

        Assertions.assertEquals(List.of("partition_0", "partition_1", "partition_legacy"), stepSuffixes(result));
    }

    @Test
    void partitionCompletedInAnEarlierJobExecutionIsNotDispatchedAgain() throws JobExecutionException {
        // given: partition_1 completed in a previous execution of the same job instance (restart case, as the parent)
        Map<String, ExecutionContext> contexts = new HashMap<>();
        contexts.put("partition_0", contextForPage(0));
        contexts.put("partition_1", contextForPage(1));
        contexts.put("partition_2", contextForPage(2));
        when(partitioner.partition(anyInt())).thenReturn(contexts);
        StepExecution completedEarlier = newJobExecution(9L).createStepExecution(WORKER_STEP + ":partition_1");
        completedEarlier.setStatus(BatchStatus.COMPLETED);
        when(jobRepository.getLastStepExecution(any(), anyString())).thenReturn(null);
        when(jobRepository.getLastStepExecution(any(), eq(WORKER_STEP + ":partition_1"))).thenReturn(completedEarlier);
        StepExecution managerStepExecution = newJobExecution(10L).createStepExecution(LoanCOBConstant.LOAN_COB_PARTITIONER_STEP);

        // when
        List<StepExecution> result = new ArrayList<>(splitter().split(managerStepExecution, 6));

        // then
        Assertions.assertEquals(List.of("partition_0", "partition_2"), stepSuffixes(result));
    }

    @Test
    void restartedPartitionFromAnOlderBuildKeepsItsOldContextAndStillSortsByPage() throws JobExecutionException {
        // given: partition_0 FAILED in an execution created by a build whose contexts carried nothing extra; the parent
        // swaps that old context in on restart. Ordering must not depend on context contents.
        Map<String, ExecutionContext> contexts = new HashMap<>();
        contexts.put("partition_10", contextForPage(10));
        contexts.put("partition_2", contextForPage(2));
        contexts.put("partition_0", contextForPage(0));
        when(partitioner.partition(anyInt())).thenReturn(contexts);
        ExecutionContext oldContext = new ExecutionContext();
        oldContext.put("partition", "partition_0");
        StepExecution failedEarlier = newJobExecution(9L).createStepExecution(WORKER_STEP + ":partition_0");
        failedEarlier.setStatus(BatchStatus.FAILED);
        failedEarlier.setExecutionContext(oldContext);
        when(jobRepository.getLastStepExecution(any(), anyString())).thenReturn(null);
        when(jobRepository.getLastStepExecution(any(), eq(WORKER_STEP + ":partition_0"))).thenReturn(failedEarlier);
        StepExecution managerStepExecution = newJobExecution(10L).createStepExecution(LoanCOBConstant.LOAN_COB_PARTITIONER_STEP);

        List<StepExecution> result = new ArrayList<>(splitter().split(managerStepExecution, 6));

        Assertions.assertEquals(List.of("partition_0", "partition_2", "partition_10"), stepSuffixes(result));
        Assertions.assertSame(oldContext, result.get(0).getExecutionContext());
    }

    @Test
    void gridSizeIsRecordedUnderTheParentsKeyAndContextIsPersisted() throws JobExecutionException {
        when(partitioner.partition(anyInt())).thenReturn(Map.of("partition_0", contextForPage(0)));
        when(jobRepository.getLastStepExecution(any(), anyString())).thenReturn(null);
        StepExecution managerStepExecution = newJobExecution(10L).createStepExecution(LoanCOBConstant.LOAN_COB_PARTITIONER_STEP);

        splitter().split(managerStepExecution, 6);

        Assertions.assertEquals(SimpleStepExecutionSplitter.class.getSimpleName() + ".GRID_SIZE",
                PageOrderedStepExecutionSplitter.GRID_SIZE_KEY);
        Assertions.assertEquals(6L, managerStepExecution.getExecutionContext().getLong(PageOrderedStepExecutionSplitter.GRID_SIZE_KEY));
        verify(jobRepository).updateExecutionContext(managerStepExecution);
        verify(partitioner).partition(6);
    }

    @Test
    void restartKeepsThePreviouslyRecordedGridSize() throws JobExecutionException {
        when(partitioner.partition(anyInt())).thenReturn(Map.of("partition_0", contextForPage(0)));
        when(jobRepository.getLastStepExecution(any(), anyString())).thenReturn(null);
        StepExecution managerStepExecution = newJobExecution(10L).createStepExecution(LoanCOBConstant.LOAN_COB_PARTITIONER_STEP);
        ExecutionContext restoredContext = new ExecutionContext();
        restoredContext.putLong(PageOrderedStepExecutionSplitter.GRID_SIZE_KEY, 4L);
        restoredContext.clearDirtyFlag();
        managerStepExecution.setExecutionContext(restoredContext);

        splitter().split(managerStepExecution, 6);

        verify(partitioner).partition(4);
    }

    @Test
    void partitionNameProviderPartitionersAreRejectedUpFront() {
        Partitioner namingPartitioner = new NamingPartitioner();

        Assertions.assertThrows(IllegalStateException.class,
                () -> new PageOrderedStepExecutionSplitter(jobRepository, false, WORKER_STEP, namingPartitioner));
    }

    @Test
    void mandatoryPropertiesAreCheckedInTheConstructor() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PageOrderedStepExecutionSplitter(null, false, WORKER_STEP, partitioner));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PageOrderedStepExecutionSplitter(jobRepository, false, " ", partitioner));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PageOrderedStepExecutionSplitter(jobRepository, false, WORKER_STEP, null));
    }

    @Test
    void pageNumberIsTheNumericSuffixOfTheKey() {
        Assertions.assertEquals(0L, PageOrderedStepExecutionSplitter.pageNo("partition_0"));
        Assertions.assertEquals(1234L, PageOrderedStepExecutionSplitter.pageNo("partition_1234"));
        Assertions.assertEquals(Long.MAX_VALUE, PageOrderedStepExecutionSplitter.pageNo("partition_"));
        Assertions.assertEquals(Long.MAX_VALUE, PageOrderedStepExecutionSplitter.pageNo("partition_x"));
    }

    private PageOrderedStepExecutionSplitter splitter() {
        return new PageOrderedStepExecutionSplitter(jobRepository, false, WORKER_STEP, partitioner);
    }

    private static JobExecution newJobExecution(long id) {
        return new JobExecution(JOB_INSTANCE, id, new JobParameters());
    }

    private static ExecutionContext contextForPage(long page) {
        ExecutionContext context = new ExecutionContext();
        context.put("partition", LoanCOBPartitioner.PARTITION_PREFIX + page);
        return context;
    }

    private static List<String> stepSuffixes(List<StepExecution> stepExecutions) {
        return stepExecutions.stream().map(StepExecution::getStepName).map(name -> name.substring(name.indexOf(':') + 1)).toList();
    }

    private static final class NamingPartitioner implements Partitioner, PartitionNameProvider {

        @Override
        public Map<String, ExecutionContext> partition(int gridSize) {
            return Map.of();
        }

        @Override
        public Collection<String> getPartitionNames(int gridSize) {
            return List.of();
        }
    }
}
