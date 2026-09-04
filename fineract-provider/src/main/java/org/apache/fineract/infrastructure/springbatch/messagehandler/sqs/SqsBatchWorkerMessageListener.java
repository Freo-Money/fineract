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
package org.apache.fineract.infrastructure.springbatch.messagehandler.sqs;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.springbatch.ContextualMessage;
import org.apache.fineract.infrastructure.springbatch.InputChannelInterceptor;
import org.apache.fineract.infrastructure.springbatch.messagehandler.StepExecutionRequestHandler;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * SQS worker that polls the remote job queue and processes partition requests. Multitenant context is carried in
 * {@link ContextualMessage#getContext()} and restored by {@link InputChannelInterceptor#beforeHandleMessage} before the
 * step runs, so each partition runs with the correct tenant context. The message is deleted only when the handler
 * reports the partition as consumed (processed or discardable duplicate). It is intentionally kept on the queue when
 * the partition is still in flight on another worker (the message is that worker's crash-recovery trigger) and on
 * failure (retry for transient errors); the queue's redrive policy moves repeatedly failing messages to the DLQ once
 * maxReceiveCount is exceeded.
 *
 * <p>
 * Implemented as a {@link SmartLifecycle} (the default phase, so it stops in the first shutdown phase — before the web
 * graceful-shutdown phase, before cobTaskExecutor's lifecycle stop, and before any bean destruction): on shutdown,
 * intake stops immediately, an in-flight partition gets the configured drain window to finish and delete its message,
 * and only then are the worker threads force-stopped. Draining before the web phase is deliberate: on a combined
 * web+worker instance the two phases compete for the orchestrator's stop window, and a COB partition (no client retry,
 * recovery costs hours) is prioritized over in-flight HTTP requests (clients retry). See the aws-sqs docs for sizing.
 */
@Slf4j
@RequiredArgsConstructor
public class SqsBatchWorkerMessageListener implements SmartLifecycle {

    private static final int IDLE_SLEEP_MILLIS = 500;
    private static final int DRAIN_POLL_MILLIS = 200;
    // Slice of the drain budget reserved for the post-interrupt wind-down after shutdownNow(): the interrupted
    // partition needs time to persist its batch status and finish an in-flight SQS delete/release. Effective reserve
    // is min(this, budget / 2) so small configured budgets still spend at least half their time actually draining
    private static final int FORCE_STOP_GRACE_SECONDS = 5;
    // Must fit inside both the default spring.lifecycle.timeout-per-shutdown-phase (30s, or the lifecycle processor
    // stops waiting for the drain) and default orchestrator stop windows (ECS/Kubernetes 30s). Deployments with longer
    // partitions must raise the env var together with FINERACT_TIMEOUT_PER_SHUTDOWN and the orchestrator stopTimeout
    private static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 25;
    private static final String LIFECYCLE_PHASE_TIMEOUT_PROPERTY = "spring.lifecycle.timeout-per-shutdown-phase";
    // Spring's default when the property is not set
    private static final long DEFAULT_LIFECYCLE_PHASE_TIMEOUT_SECONDS = 30;

    private final StepExecutionRequestHandler stepExecutionRequestHandler;
    private final InputChannelInterceptor inputInterceptor;
    private final SqsClient sqsClient;
    private final SqsMessageSerializer sqsMessageSerializer;
    private final FineractProperties fineractProperties;
    private final Environment environment;
    // Monitor-guarded: every access happens inside the synchronized start()/stop() methods (the async drain thread
    // and the @PreDestroy safety net both enter through synchronized stop())
    private ExecutorService executorService;
    private volatile boolean running;
    // Counts in-flight receive batches (== partitions with the default max-number-of-messages=1)
    private final AtomicInteger inFlightBatches = new AtomicInteger();

    @Override
    public synchronized void start() {
        ExecutorService previous = executorService;
        if (previous != null && !previous.isTerminated()) {
            // Either already started, or a restart raced the previous pool's drain — old workers share the running
            // flag, so starting a new pool now would revive them against a shut-down pool
            if (!running) {
                log.warn("Not starting SQS worker listener: previous worker pool has not terminated yet");
            }
            return;
        }
        if (drainExceedsLifecyclePhaseTimeout()) {
            log.warn(
                    "SQS shutdown-timeout-seconds ({}) exceeds {} — the lifecycle processor will abandon the drain at the phase "
                            + "timeout and bean destruction will then block behind it until the drain finishes. Raise the phase timeout "
                            + "(FINERACT_TIMEOUT_PER_SHUTDOWN) and the orchestrator stop window to at least the drain budget",
                    drainTimeoutSeconds(), LIFECYCLE_PHASE_TIMEOUT_PROPERTY);
        }
        running = true;
        int concurrency = defaultIfNull(fineractProperties.getRemoteJobMessageHandler().getSqs().getConcurrency(), 1);
        concurrency = Math.max(1, concurrency);
        AtomicInteger workerIndex = new AtomicInteger(0);
        executorService = Executors.newFixedThreadPool(concurrency, r -> {
            Thread thread = new Thread(r, "fineract-sqs-batch-worker-" + workerIndex.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 0; i < concurrency; i++) {
            final int index = i;
            executorService.execute(() -> {
                try {
                    pollMessages(index);
                } catch (Exception e) {
                    log.error("SQS worker thread failed (worker {}), stopping poll loop", index, e);
                }
            });
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Runs the blocking drain on a dedicated thread so the lifecycle processor's timeout-per-shutdown-phase bounds the
     * wait instead of the drain blocking context shutdown unboundedly.
     */
    @Override
    public void stop(Runnable callback) {
        Thread drainThread = new Thread(() -> {
            try {
                stop();
            } finally {
                callback.run();
            }
        }, "fineract-sqs-batch-worker-drain");
        drainThread.setDaemon(true);
        drainThread.start();
    }

    @Override
    public synchronized void stop() {
        running = false;
        ExecutorService executor = executorService;
        if (executor == null) {
            return;
        }
        if (executor.isShutdown()) {
            // A previous stop() already drained and force-stopped this pool (lifecycle stop first, then the
            // @PreDestroy safety net re-enters here). Re-running the drain would double shutdown time in exactly the
            // stuck-partition case the drain exists for, and cannot help a thread the first shutdownNow missed
            return;
        }
        // Graceful drain: intake is stopped (running=false makes the poll loop release any message a long poll still
        // returns), but an in-flight partition gets the drain window to finish and delete its message before the
        // worker threads are force-stopped
        executor.shutdown();
        int drainTimeoutSeconds = drainTimeoutSeconds();
        try {
            // One budget shared between the in-flight wait and the post-interrupt wind-down, so the whole stop fits
            // inside spring.lifecycle.timeout-per-shutdown-phase (default 30s) — overrunning that would let bean
            // destruction race the still-running drain
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(drainTimeoutSeconds);
            long forceStopReserveNanos = TimeUnit.SECONDS.toNanos(Math.min(FORCE_STOP_GRACE_SECONDS, drainTimeoutSeconds / 2));
            while (inFlightBatches.get() > 0 && System.nanoTime() - (deadlineNanos - forceStopReserveNanos) < 0) {
                TimeUnit.MILLISECONDS.sleep(DRAIN_POLL_MILLIS);
            }
            if (inFlightBatches.get() > 0) {
                log.warn(
                        "SQS worker did not finish {} in-flight receive batch(es) within the {}s drain window; interrupting. "
                                + "Recovery: STOPPED/FAILED partitions need a manual job restart, STARTED rows are taken over after the "
                                + "orphan threshold — see docs/aws-sqs-remote-job-message-handler-implementation.md",
                        inFlightBatches.get(), drainTimeoutSeconds);
            }
            // Aborts idle long polls immediately (AbortedException) and interrupts any partition that overran the
            // window
            executor.shutdownNow();
            long remainingNanos = Math.max(deadlineNanos - System.nanoTime(), TimeUnit.SECONDS.toNanos(1));
            if (!executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) {
                log.warn("SQS worker listener executor did not stop within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("SQS worker drain interrupted; force-stopping with {} receive batch(es) still in flight", inFlightBatches.get());
            executor.shutdownNow();
        }
    }

    /**
     * Safety net for a context refresh that fails after the lifecycle has started: destroyBeans() then runs without a
     * lifecycle stop, which would leak the poller threads. Idempotent and near-instant when the lifecycle stop already
     * drained.
     */
    @PreDestroy
    void destroy() {
        stop();
    }

    // package-private for tests; null or negative config falls back to the default, an explicit 0 disables the drain.
    // The last min(FORCE_STOP_GRACE_SECONDS, value / 2) seconds of the budget are reserved for the post-interrupt
    // wind-down, so the in-flight wait proper is the configured value minus that reserve
    int drainTimeoutSeconds() {
        Integer configured = fineractProperties.getRemoteJobMessageHandler().getSqs().getShutdownTimeoutSeconds();
        return configured == null || configured < 0 ? DEFAULT_SHUTDOWN_TIMEOUT_SECONDS : configured;
    }

    // package-private for tests. A drain budget beyond the lifecycle phase timeout silently voids the drain guarantee
    // (the phase expires mid-drain and bean destruction blocks on the stop() monitor), so start() warns about it
    boolean drainExceedsLifecyclePhaseTimeout() {
        long phaseTimeoutSeconds = DEFAULT_LIFECYCLE_PHASE_TIMEOUT_SECONDS;
        String configured = environment.getProperty(LIFECYCLE_PHASE_TIMEOUT_PROPERTY);
        if (configured != null) {
            try {
                phaseTimeoutSeconds = DurationStyle.detectAndParse(configured).toSeconds();
            } catch (RuntimeException e) {
                log.debug("Could not parse {} value '{}'", LIFECYCLE_PHASE_TIMEOUT_PROPERTY, configured, e);
                return false;
            }
        }
        return drainTimeoutSeconds() > phaseTimeoutSeconds;
    }

    private void pollMessages(int workerIndex) {
        while (running) {
            if (Thread.currentThread().isInterrupted()) {
                log.debug("SQS worker {} interrupted, stopping", workerIndex);
                break;
            }
            try {
                ReceiveMessageResponse response = sqsClient.receiveMessage(receiveMessageRequest());
                if (response.messages().isEmpty()) {
                    sleep();
                    continue;
                }
                // The increment-then-check ordering closes the race with the drain in stop(): once stop() has set
                // running=false, either this thread sees it below and releases the messages, or it incremented first
                // and the drain waits for the processing to finish. Re-checked per message because shutdown can begin
                // mid-batch when max-number-of-messages > 1
                inFlightBatches.incrementAndGet();
                try {
                    for (Message message : response.messages()) {
                        if (!running) {
                            // Shutdown began; don't start a new partition this late
                            releaseMessage(message);
                        } else {
                            processMessage(message);
                        }
                    }
                } finally {
                    inFlightBatches.decrementAndGet();
                }
            } catch (Exception e) {
                handlePollFailure(workerIndex, e);
            }
        }
    }

    private void handlePollFailure(int workerIndex, Exception e) {
        boolean shutdownInProgress = !running || Thread.currentThread().isInterrupted();
        if (!shutdownInProgress) {
            log.error("Exception while polling SQS queue (worker {}), will retry after sleep", workerIndex, e);
            sleep();
        } else if (e instanceof AbortedException) {
            // Expected during shutdown: shutdownNow() aborts a blocked long poll
            log.debug("SQS poll aborted during shutdown (worker {})", workerIndex, e);
        } else {
            // A genuine receive failure (credentials, throttling, network teardown) during shutdown must not hide in
            // the shutdown noise: a message SQS already dispatched for this receive stays invisible until the
            // visibility timeout
            log.warn("SQS poll failed during shutdown (worker {})", workerIndex, e);
        }
    }

    private void processMessage(Message message) {
        try {
            ContextualMessage contextualMessage = sqsMessageSerializer.deserialize(message.body());
            log.debug("Received SQS partition message {}", message.messageId());
            // Restores tenant context (multitenant) before running the step
            StepExecutionRequestHandler.HandleOutcome outcome = stepExecutionRequestHandler
                    .handle(inputInterceptor.beforeHandleMessage(contextualMessage));
            if (outcome == StepExecutionRequestHandler.HandleOutcome.IN_FLIGHT) {
                // The partition is still running on another worker and this message is its only recovery trigger.
                // Keep it: it stays invisible until the visibility timeout expires and is then re-evaluated —
                // discarded once the partition finished, or taken over once it looks orphaned
                log.info("Keeping SQS message {} on the queue; partition is in flight on another worker", message.messageId());
                return;
            }
            deleteMessage(message);
            log.debug("SQS message {} deleted (outcome {})", message.messageId(), outcome);
        } catch (Exception e) {
            // Intentionally not deleted: only infrastructure errors reach this catch (business failures are recorded
            // in batch metadata by the handler, which returns normally). The message becomes visible again after the
            // visibility timeout so transient errors are retried, and the queue's redrive policy moves it to the DLQ
            // once maxReceiveCount is exceeded
            log.error("Exception while processing SQS message {}", message.messageId(), e);
        }
    }

    private void deleteMessage(Message message) {
        callWithInterruptFlagCleared(() -> sqsClient
                .deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl()).receiptHandle(message.receiptHandle()).build()));
    }

    /**
     * Best effort: make a message received after shutdown began immediately visible again so another worker picks it up
     * right away instead of after the visibility timeout.
     */
    private void releaseMessage(Message message) {
        try {
            callWithInterruptFlagCleared(() -> sqsClient.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                    .queueUrl(queueUrl()).receiptHandle(message.receiptHandle()).visibilityTimeout(0).build()));
            log.info("Released SQS message {} back to the queue; worker is shutting down", message.messageId());
        } catch (Exception e) {
            log.warn("Could not release SQS message {} during shutdown; it becomes visible again after the visibility timeout",
                    message.messageId(), e);
        }
    }

    /**
     * Runs an SQS call with any pre-existing interrupt flag cleared (and restored afterwards). Both the delete and the
     * release run during shutdown, when shutdownNow() may already have interrupted the worker, and the AWS SDK aborts
     * calls on interrupted threads (AbortedException) — which would leave an already-consumed message to burn a
     * redelivery cycle, or a released message invisible for the full visibility timeout. An interrupt that lands while
     * the call is in flight can still abort it; that residual window is accepted (the message then simply waits out the
     * visibility timeout).
     */
    private void callWithInterruptFlagCleared(Runnable sqsCall) {
        boolean interrupted = Thread.interrupted();
        try {
            sqsCall.run();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Build receive request. The effective visibility timeout is applied on every receive so the configured value (or
     * its default when unset) is authoritative and never silently falls back to the queue's default attribute — the
     * orphan-takeover threshold in StepExecutionRequestHandler assumes the same effective value, and redelivery cadence
     * and takeover math must not disagree. Values above the SQS hard cap of 43200 seconds (12h) are clamped.
     */
    private ReceiveMessageRequest receiveMessageRequest() {
        FineractProperties.FineractRemoteJobMessageHandlerSqsProperties sqsProperties = fineractProperties.getRemoteJobMessageHandler()
                .getSqs();
        return ReceiveMessageRequest.builder().queueUrl(queueUrl()).waitTimeSeconds(defaultIfNull(sqsProperties.getWaitTimeSeconds(), 20))
                .maxNumberOfMessages(defaultIfNull(sqsProperties.getMaxNumberOfMessages(), 1))
                .visibilityTimeout(sqsProperties.getEffectiveVisibilityTimeoutSeconds()).build();
    }

    private int defaultIfNull(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String queueUrl() {
        return fineractProperties.getRemoteJobMessageHandler().getSqs().getQueueUrl();
    }

    private void sleep() {
        try {
            Thread.sleep(IDLE_SLEEP_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
