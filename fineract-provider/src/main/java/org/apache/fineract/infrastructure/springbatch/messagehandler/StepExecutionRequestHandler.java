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

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

    // Takeover threshold is a multiple of the redelivery interval so one slow chunk on a live worker does not get its
    // partition stolen on the first redelivery (lastUpdated only advances at chunk commits)
    private static final int ORPHANED_PARTITION_THRESHOLD_MULTIPLIER = 2;
    // Sanity bound: the largest threshold the pre-knob coupling could produce (2 x the SQS visibility cap). The cap
    // alone does not guarantee takeover happens before the recovery message dead-letters — that depends on the actual
    // visibility timeout and the queue's maxReceiveCount, which the receive-budget startup warning covers
    private static final int MAX_ORPHANED_PARTITION_THRESHOLD_SECONDS = ORPHANED_PARTITION_THRESHOLD_MULTIPLIER
            * FineractProperties.FineractRemoteJobMessageHandlerSqsProperties.MAX_SQS_VISIBILITY_TIMEOUT_SECONDS;

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
        return Duration.ofSeconds(effectiveOrphanedPartitionThresholdSeconds());
    }

    /**
     * The takeover threshold actually in effect: the configured dedicated threshold clamped to the cap, or 2x the
     * effective visibility timeout when unset/non-positive. The single source for the takeover decision and the startup
     * warnings, so a warning can never describe behavior the takeover path does not have.
     */
    private long effectiveOrphanedPartitionThresholdSeconds() {
        FineractProperties.FineractRemoteJobMessageHandlerSqsProperties sqs = sqsProperties();
        // A dedicated threshold decouples takeover speed from the redelivery interval; without it, one knob (the
        // visibility timeout) has to trade fast orphan recovery against stealing a live worker's slow partition
        Integer configuredThreshold = sqs.getOrphanedPartitionThresholdSeconds();
        if (configuredThreshold != null && configuredThreshold > 0) {
            return Math.min(configuredThreshold, MAX_ORPHANED_PARTITION_THRESHOLD_SECONDS);
        }
        return (long) ORPHANED_PARTITION_THRESHOLD_MULTIPLIER * sqs.getEffectiveVisibilityTimeoutSeconds();
    }

    /**
     * Never null: a missing SQS config block yields an empty properties object whose getters resolve the same defaults
     * as unset fields, so callers never re-implement the unset-default resolution.
     */
    private FineractProperties.FineractRemoteJobMessageHandlerSqsProperties sqsProperties() {
        FineractProperties.FineractRemoteJobMessageHandlerProperties remoteJobMessageHandler = fineractProperties
                .getRemoteJobMessageHandler();
        FineractProperties.FineractRemoteJobMessageHandlerSqsProperties sqs = remoteJobMessageHandler != null
                ? remoteJobMessageHandler.getSqs()
                : null;
        return sqs != null ? sqs : new FineractProperties.FineractRemoteJobMessageHandlerSqsProperties();
    }

    /**
     * The takeover contract cannot be validated in code (the safe threshold floor is the longest single chunk, which
     * only the operator's own runtime data can tell), so misconfiguration risks are surfaced at startup instead: a
     * non-UTC system zone skews the cross-JVM staleness comparison; a threshold below the visibility timeout is a
     * deliberate fast-recovery setting but steals live partitions whose current chunk outlives it; a threshold well
     * above the visibility timeout needs more redeliveries — and therefore more SQS receives — before takeover is ever
     * allowed, and the queue's maxReceiveCount must cover them or the recovery message dead-letters first; a threshold
     * above the cap is almost certainly a units mistake.
     */
    @PostConstruct
    void warnOnRiskyOrphanTakeoverConfiguration() {
        ZoneId systemZone = ZoneId.systemDefault();
        if (!ZoneOffset.UTC.equals(systemZone.normalized())) {
            // Staleness compares LocalDateTime values written by other workers' JVMs in their system zones, so a zone
            // mismatch between containers makes live partitions look hours stale (instant steals) or dead ones look
            // fresh (no takeover). The production jib image pins UTC, so a non-UTC zone is always anomalous
            log.warn("System timezone is {} (not UTC): orphan-partition staleness is compared across worker JVMs via zone-local "
                    + "timestamps — a zone mismatch between workers causes live-partition steals or missed takeovers. Verify every "
                    + "worker container runs the same timezone", systemZone);
        }
        FineractProperties.FineractRemoteJobMessageHandlerSqsProperties sqs = sqsProperties();
        Integer configuredThreshold = sqs.getOrphanedPartitionThresholdSeconds();
        if (configuredThreshold == null || configuredThreshold <= 0) {
            return;
        }
        long effectiveThreshold = effectiveOrphanedPartitionThresholdSeconds();
        long effectiveVisibilityTimeout = sqs.getEffectiveVisibilityTimeoutSeconds();
        if (configuredThreshold > effectiveThreshold) {
            log.warn("orphaned-partition-threshold-seconds ({}) exceeds the {}s cap (2x the SQS visibility maximum) and is "
                    + "clamped to it — check for a units mistake", configuredThreshold, effectiveThreshold);
        }
        if (effectiveThreshold < effectiveVisibilityTimeout) {
            log.warn("orphaned-partition-threshold-seconds ({}s effective) is below the effective visibility timeout ({}s): dead "
                    + "partitions recover on the first redelivery, but a live partition whose current chunk outlives the "
                    + "threshold WILL be stolen and executed concurrently. Verify the longest single chunk stays well under "
                    + "the threshold", effectiveThreshold, effectiveVisibilityTimeout);
        } else if (effectiveThreshold > ORPHANED_PARTITION_THRESHOLD_MULTIPLIER * effectiveVisibilityTimeout) {
            // Takeover is only evaluated when SQS redelivers the recovery message (~every visibility timeout), and
            // every refused redelivery burns a receive toward the queue redrive policy's maxReceiveCount
            long receivesBeforeTakeover = (effectiveThreshold + effectiveVisibilityTimeout - 1) / effectiveVisibilityTimeout + 1;
            log.warn(
                    "orphaned-partition-threshold-seconds ({}s effective, visibility timeout {}s) allows takeover only after ~{} "
                            + "receives of the recovery message; size the queue redrive policy's maxReceiveCount to at least {} "
                            + "(takeover receives plus margin for deploy-wave releases and transient errors), or the message "
                            + "dead-letters first and the partition stays STARTED until manual intervention",
                    effectiveThreshold, effectiveVisibilityTimeout, receivesBeforeTakeover, receivesBeforeTakeover + 2);
        }
    }
}
