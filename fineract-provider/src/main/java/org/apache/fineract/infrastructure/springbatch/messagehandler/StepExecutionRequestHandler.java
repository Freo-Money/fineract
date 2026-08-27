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

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepLocator;
import org.springframework.batch.integration.partition.StepExecutionRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Executes a partition step on a batch worker. Message brokers such as SQS deliver at-least-once, so the same partition
 * request can reach more than one worker; every duplicate-detection path here must return normally (never throw) and
 * report a {@link HandleOutcome} so the caller can decide whether to acknowledge/delete the message or leave it for
 * redelivery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "fineract.mode.batch-worker-enabled", havingValue = "true")
public class StepExecutionRequestHandler {

    private static final int DEFAULT_VISIBILITY_TIMEOUT_SECONDS = 3600;
    // Must match the per-receive clamp in SqsBatchWorkerMessageListener: SQS caps visibility at 43200 seconds (12h)
    private static final int MAX_SQS_VISIBILITY_TIMEOUT_SECONDS = 43200;
    // Takeover threshold is a multiple of the redelivery interval so one slow chunk on a live worker does not get its
    // partition stolen on the first redelivery (lastUpdated only advances at chunk commits)
    private static final int ORPHANED_PARTITION_THRESHOLD_MULTIPLIER = 2;

    /**
     * Outcome of a partition request, telling the caller what to do with the broker message.
     */
    public enum HandleOutcome {
        /** The step was executed (successfully or not); the message is consumed and can be deleted. */
        PROCESSED,
        /**
         * Stray duplicate of a partition that already reached a terminal status; the message is useless and can be
         * deleted.
         */
        DISCARD,
        /**
         * The request cannot be consumed yet: the partition is still running on another worker, or its metadata could
         * not be read. The message must NOT be deleted — it is the only recovery trigger if that worker dies. Left on
         * the queue, it is re-evaluated after the next visibility expiry: discarded once the partition finished, or
         * taken over once it looks orphaned.
         */
        IN_FLIGHT
    }

    private final JobRepository jobRepository;
    private final StepLocator stepLocator;
    private final JobExplorer jobExplorer;
    private final FineractProperties fineractProperties;

    public HandleOutcome handle(StepExecutionRequest request) {

        Long jobExecutionId = request.getJobExecutionId();
        Long stepExecutionId = request.getStepExecutionId();
        String stepName = request.getStepName();

        StepExecution stepExecution = jobExplorer.getStepExecution(jobExecutionId, stepExecutionId);
        if (stepExecution == null) {
            // Possibly transient (DB failover, metadata not yet visible), so keep the message and retry; if the
            // execution is permanently gone (e.g. batch metadata cleanup), the queue redrive policy eventually
            // dead-letters the message after maxReceiveCount
            log.warn("No StepExecution found for jobExecutionId={} stepExecutionId={}; keeping message for retry", jobExecutionId,
                    stepExecutionId);
            return HandleOutcome.IN_FLIGHT;
        }
        HandleOutcome duplicateOutcome = evaluateDuplicate(stepExecution);
        if (duplicateOutcome != null) {
            return duplicateOutcome;
        }

        Step step = stepLocator.getStep(stepName);
        boolean ownedByOtherWorker = false;
        try {
            step.execute(stepExecution);
        } catch (JobInterruptedException e) {
            // based on org.springframework.batch.core.step.AbstractStep.determineBatchStatus
            stepExecution.addFailureException(e);
            stepExecution.setStatus(BatchStatus.STOPPED);
        } catch (OptimisticLockingFailureException e) {
            // another worker updated this step execution concurrently (at-least-once redelivery); it owns the
            // partition, so skip the final update and keep the message as its recovery trigger
            ownedByOtherWorker = true;
            log.warn("Optimistic lock conflict on stepExecutionId={}; another worker owns this partition", stepExecutionId, e);
        } catch (Exception e) {
            stepExecution.addFailureException(e);
            stepExecution.setStatus(BatchStatus.FAILED);
        } finally {
            if (!ownedByOtherWorker) {
                ownedByOtherWorker = !updateStepExecution(stepExecution);
            }
        }
        return ownedByOtherWorker ? HandleOutcome.IN_FLIGHT : HandleOutcome.PROCESSED;
    }

    /**
     * Returns the outcome for a request whose step execution should not be (re-)executed, or null when it should be
     * processed.
     */
    private HandleOutcome evaluateDuplicate(StepExecution stepExecution) {
        BatchStatus status = stepExecution.getStatus();
        if (status == BatchStatus.COMPLETED || status == BatchStatus.ABANDONED || status == BatchStatus.FAILED
                || status == BatchStatus.STOPPED || status == BatchStatus.STOPPING) {
            // Terminal or stopping partitions are only retried through the job restart machinery; a broker message
            // carrying one of these statuses is a stray at-least-once duplicate and must not re-execute the step
            log.warn("Skipping duplicate partition request for stepExecutionId={} (status={})", stepExecution.getId(), status);
            return HandleOutcome.DISCARD;
        }
        if (status == BatchStatus.STARTED) {
            // Another worker may still hold this partition; only take it over if it looks orphaned. The system default
            // zone is intentional: Spring Batch persists lastUpdated via LocalDateTime.now() in the same JVM zone
            LocalDateTime lastUpdated = stepExecution.getLastUpdated() != null ? stepExecution.getLastUpdated()
                    : stepExecution.getStartTime();
            Duration age = lastUpdated != null ? Duration.between(lastUpdated, LocalDateTime.now(ZoneId.systemDefault())) : null;
            if (age != null && age.compareTo(orphanedPartitionThreshold()) < 0) {
                log.warn("Partition request for stepExecutionId={} is in flight (STARTED, last updated {}s ago); keeping message",
                        stepExecution.getId(), age.toSeconds());
                return HandleOutcome.IN_FLIGHT;
            }
            log.warn("Taking over apparently orphaned partition stepExecutionId={} (STARTED, last updated {}s ago)", stepExecution.getId(),
                    age != null ? age.toSeconds() : -1);
        }
        return null;
    }

    /**
     * Returns true when the update was saved, false on an optimistic lock conflict — the in-memory step execution is
     * stale because another worker wrote it concurrently, so that worker owns the partition and the message must be
     * kept as its recovery trigger (IN_FLIGHT).
     */
    private boolean updateStepExecution(StepExecution stepExecution) {
        try {
            jobRepository.update(stepExecution);
            return true;
        } catch (OptimisticLockingFailureException e) {
            log.warn("Optimistic lock conflict while saving stepExecutionId={}; another worker updated this partition concurrently",
                    stepExecution.getId(), e);
            return false;
        }
    }

    private Duration orphanedPartitionThreshold() {
        FineractProperties.FineractRemoteJobMessageHandlerProperties remoteJobMessageHandler = fineractProperties
                .getRemoteJobMessageHandler();
        Integer visibilityTimeoutSeconds = remoteJobMessageHandler != null && remoteJobMessageHandler.getSqs() != null
                ? remoteJobMessageHandler.getSqs().getVisibilityTimeoutSeconds()
                : null;
        int effectiveVisibilityTimeout = visibilityTimeoutSeconds == null || visibilityTimeoutSeconds <= 0
                ? DEFAULT_VISIBILITY_TIMEOUT_SECONDS
                : Math.min(visibilityTimeoutSeconds, MAX_SQS_VISIBILITY_TIMEOUT_SECONDS);
        return Duration.ofSeconds((long) ORPHANED_PARTITION_THRESHOLD_MULTIPLIER * effectiveVisibilityTimeout);
    }
}
