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
package org.apache.fineract.infrastructure.springbatch.messagehandler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.springbatch.messagehandler.StepExecutionRequestHandler.HandleOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepLocator;
import org.springframework.batch.integration.partition.StepExecutionRequest;
import org.springframework.dao.OptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
public class StepExecutionRequestHandlerTest {

    private static final long JOB_EXECUTION_ID = 1L;
    private static final long STEP_EXECUTION_ID = 10L;
    private static final String STEP_NAME = "loanCOBWorkerStep";
    private static final int VISIBILITY_TIMEOUT_SECONDS = 1800;
    private static final int DEFAULT_THRESHOLD_SECONDS = 3600;

    @Mock
    private JobRepository jobRepository;
    @Mock
    private StepLocator stepLocator;
    @Mock
    private JobExplorer jobExplorer;
    @Mock
    private Step step;

    private StepExecutionRequestHandler underTest;

    @BeforeEach
    public void setUp() {
        FineractProperties fineractProperties = new FineractProperties();
        FineractProperties.FineractRemoteJobMessageHandlerProperties remoteJobMessageHandler = new FineractProperties.FineractRemoteJobMessageHandlerProperties();
        FineractProperties.FineractRemoteJobMessageHandlerSqsProperties sqs = new FineractProperties.FineractRemoteJobMessageHandlerSqsProperties();
        sqs.setVisibilityTimeoutSeconds(VISIBILITY_TIMEOUT_SECONDS);
        remoteJobMessageHandler.setSqs(sqs);
        fineractProperties.setRemoteJobMessageHandler(remoteJobMessageHandler);
        underTest = new StepExecutionRequestHandler(jobRepository, stepLocator, jobExplorer, fineractProperties);
    }

    private StepExecutionRequest request() {
        return new StepExecutionRequest(STEP_NAME, JOB_EXECUTION_ID, STEP_EXECUTION_ID);
    }

    private StepExecution stepExecution(BatchStatus status) {
        StepExecution stepExecution = new StepExecution(STEP_NAME, new JobExecution(JOB_EXECUTION_ID), STEP_EXECUTION_ID);
        stepExecution.setStatus(status);
        when(jobExplorer.getStepExecution(JOB_EXECUTION_ID, STEP_EXECUTION_ID)).thenReturn(stepExecution);
        return stepExecution;
    }

    @Test
    public void whenStepExecutionIsMissingThenMessageIsKeptForRetry() {
        when(jobExplorer.getStepExecution(JOB_EXECUTION_ID, STEP_EXECUTION_ID)).thenReturn(null);

        assertEquals(HandleOutcome.IN_FLIGHT, underTest.handle(request()));

        verifyNoInteractions(jobRepository, stepLocator);
    }

    @Test
    public void whenStepExecutionIsCompletedThenDuplicateRequestIsDiscarded() {
        stepExecution(BatchStatus.COMPLETED);

        assertEquals(HandleOutcome.DISCARD, underTest.handle(request()));

        verifyNoInteractions(jobRepository, stepLocator);
    }

    @Test
    public void whenStepExecutionIsAbandonedThenDuplicateRequestIsDiscarded() {
        stepExecution(BatchStatus.ABANDONED);

        assertEquals(HandleOutcome.DISCARD, underTest.handle(request()));

        verifyNoInteractions(jobRepository, stepLocator);
    }

    @Test
    public void whenStepExecutionIsFailedStoppedOrStoppingThenDuplicateRequestIsDiscarded() {
        for (BatchStatus status : new BatchStatus[] { BatchStatus.FAILED, BatchStatus.STOPPED, BatchStatus.STOPPING }) {
            stepExecution(status);

            assertEquals(HandleOutcome.DISCARD, underTest.handle(request()), "status " + status);
        }
        verifyNoInteractions(jobRepository, stepLocator);
    }

    @Test
    public void whenStepExecutionIsStartedRecentlyThenRequestIsKeptInFlight() {
        StepExecution stepExecution = stepExecution(BatchStatus.STARTED);
        stepExecution.setLastUpdated(LocalDateTime.now(ZoneId.systemDefault()).minusSeconds(60));

        assertEquals(HandleOutcome.IN_FLIGHT, underTest.handle(request()));

        verifyNoInteractions(jobRepository, stepLocator);
    }

    @Test
    public void whenStepExecutionIsStartedButStaleThenPartitionIsTakenOver() throws Exception {
        StepExecution stepExecution = stepExecution(BatchStatus.STARTED);
        stepExecution.setLastUpdated(LocalDateTime.now(ZoneId.systemDefault()).minusSeconds(2L * VISIBILITY_TIMEOUT_SECONDS + 60));
        when(stepLocator.getStep(STEP_NAME)).thenReturn(step);

        assertEquals(HandleOutcome.PROCESSED, underTest.handle(request()));

        verify(step).execute(stepExecution);
        verify(jobRepository).update(stepExecution);
    }

    @Test
    public void whenStepExecutionIsStartedWithinTwiceTheVisibilityTimeoutThenRequestIsKeptInFlight() {
        StepExecution stepExecution = stepExecution(BatchStatus.STARTED);
        stepExecution.setLastUpdated(LocalDateTime.now(ZoneId.systemDefault()).minusSeconds(VISIBILITY_TIMEOUT_SECONDS + 60));

        assertEquals(HandleOutcome.IN_FLIGHT, underTest.handle(request()));

        verifyNoInteractions(jobRepository, stepLocator);
    }

    @Test
    public void whenVisibilityTimeoutExceedsSqsCapThenThresholdUsesTheClampedValue() throws Exception {
        FineractProperties fineractProperties = new FineractProperties();
        FineractProperties.FineractRemoteJobMessageHandlerProperties remoteJobMessageHandler = new FineractProperties.FineractRemoteJobMessageHandlerProperties();
        FineractProperties.FineractRemoteJobMessageHandlerSqsProperties sqs = new FineractProperties.FineractRemoteJobMessageHandlerSqsProperties();
        sqs.setVisibilityTimeoutSeconds(50000);
        remoteJobMessageHandler.setSqs(sqs);
        fineractProperties.setRemoteJobMessageHandler(remoteJobMessageHandler);
        underTest = new StepExecutionRequestHandler(jobRepository, stepLocator, jobExplorer, fineractProperties);
        StepExecution stepExecution = stepExecution(BatchStatus.STARTED);
        // 2 x clamped 43200 = 86400; with the raw value the threshold would be 100000 and this would stay in flight
        stepExecution.setLastUpdated(LocalDateTime.now(ZoneId.systemDefault()).minusSeconds(86400 + 60));
        when(stepLocator.getStep(STEP_NAME)).thenReturn(step);

        assertEquals(HandleOutcome.PROCESSED, underTest.handle(request()));

        verify(step).execute(stepExecution);
    }

    @Test
    public void whenStepExecutionIsStartingThenPartitionIsProcessed() throws Exception {
        StepExecution stepExecution = stepExecution(BatchStatus.STARTING);
        when(stepLocator.getStep(STEP_NAME)).thenReturn(step);

        assertEquals(HandleOutcome.PROCESSED, underTest.handle(request()));

        verify(step).execute(stepExecution);
        verify(jobRepository).update(stepExecution);
    }

    @Test
    public void whenStepExecutionIsUnknownThenPartitionIsRetried() throws Exception {
        StepExecution stepExecution = stepExecution(BatchStatus.UNKNOWN);
        when(stepLocator.getStep(STEP_NAME)).thenReturn(step);

        assertEquals(HandleOutcome.PROCESSED, underTest.handle(request()));

        verify(step).execute(stepExecution);
    }

    @Test
    public void whenSqsConfigIsAbsentThenDefaultThresholdGovernsInFlightCheck() {
        FineractProperties fineractProperties = new FineractProperties();
        fineractProperties.setRemoteJobMessageHandler(new FineractProperties.FineractRemoteJobMessageHandlerProperties());
        underTest = new StepExecutionRequestHandler(jobRepository, stepLocator, jobExplorer, fineractProperties);
        StepExecution stepExecution = stepExecution(BatchStatus.STARTED);
        stepExecution.setLastUpdated(LocalDateTime.now(ZoneId.systemDefault()).minusSeconds(DEFAULT_THRESHOLD_SECONDS - 300));

        assertEquals(HandleOutcome.IN_FLIGHT, underTest.handle(request()));

        verifyNoInteractions(jobRepository, stepLocator);
    }

    @Test
    public void whenStepExecuteThrowsOptimisticLockThenMessageIsKeptAndFinalUpdateIsSkipped() throws Exception {
        StepExecution stepExecution = stepExecution(BatchStatus.STARTING);
        when(stepLocator.getStep(STEP_NAME)).thenReturn(step);
        doThrow(new OptimisticLockingFailureException("owned by another worker")).when(step).execute(stepExecution);

        assertEquals(HandleOutcome.IN_FLIGHT, underTest.handle(request()));

        verify(jobRepository, never()).update(any(StepExecution.class));
    }

    @Test
    public void whenFinalUpdateThrowsOptimisticLockThenMessageIsKeptInFlight() throws Exception {
        StepExecution stepExecution = stepExecution(BatchStatus.STARTING);
        when(stepLocator.getStep(STEP_NAME)).thenReturn(step);
        doThrow(new OptimisticLockingFailureException("stale version")).when(jobRepository).update(stepExecution);

        assertEquals(HandleOutcome.IN_FLIGHT, underTest.handle(request()));

        verify(step).execute(stepExecution);
    }

    @Test
    public void whenStepExecuteThrowsGenericExceptionThenStatusIsFailedAndUpdated() throws Exception {
        StepExecution stepExecution = stepExecution(BatchStatus.STARTING);
        when(stepLocator.getStep(STEP_NAME)).thenReturn(step);
        doThrow(new RuntimeException("boom")).when(step).execute(stepExecution);

        assertEquals(HandleOutcome.PROCESSED, underTest.handle(request()));

        verify(jobRepository).update(stepExecution);
        assertEquals(BatchStatus.FAILED, stepExecution.getStatus());
    }

    @Test
    public void whenStepExecuteIsInterruptedThenStatusIsStoppedAndUpdated() throws Exception {
        StepExecution stepExecution = stepExecution(BatchStatus.STARTING);
        when(stepLocator.getStep(STEP_NAME)).thenReturn(step);
        doThrow(new JobInterruptedException("interrupted")).when(step).execute(stepExecution);

        assertEquals(HandleOutcome.PROCESSED, underTest.handle(request()));

        verify(jobRepository).update(stepExecution);
        assertEquals(BatchStatus.STOPPED, stepExecution.getStatus());
    }

    @Test
    public void whenStartedWithNoTimestampsThenPartitionIsTakenOver() throws Exception {
        // a freshly constructed StepExecution has null lastUpdated and startTime
        StepExecution stepExecution = stepExecution(BatchStatus.STARTED);
        when(stepLocator.getStep(STEP_NAME)).thenReturn(step);

        assertEquals(HandleOutcome.PROCESSED, underTest.handle(request()));

        verify(step).execute(stepExecution);
    }

    @Test
    public void whenSkipPathsRunThenNothingThrows() {
        stepExecution(BatchStatus.COMPLETED);

        assertDoesNotThrow(() -> underTest.handle(request()));
    }
}
