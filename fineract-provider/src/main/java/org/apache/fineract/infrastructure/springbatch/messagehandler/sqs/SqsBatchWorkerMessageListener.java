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

import jakarta.annotation.PostConstruct;
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
import software.amazon.awssdk.services.sqs.SqsClient;
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
 */
@Slf4j
@RequiredArgsConstructor
public class SqsBatchWorkerMessageListener {

    private static final int IDLE_SLEEP_MILLIS = 500;
    // SQS hard cap for the ReceiveMessage VisibilityTimeout parameter is 43200 seconds (12h)
    private static final int MAX_SQS_VISIBILITY_TIMEOUT_SECONDS = 43200;

    private final StepExecutionRequestHandler stepExecutionRequestHandler;
    private final InputChannelInterceptor inputInterceptor;
    private final SqsClient sqsClient;
    private final SqsMessageSerializer sqsMessageSerializer;
    private final FineractProperties fineractProperties;
    private ExecutorService executorService;
    private volatile boolean running;

    @PostConstruct
    public void start() {
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

    @PreDestroy
    public void stop() {
        running = false;
        if (executorService == null) {
            return;
        }
        executorService.shutdownNow();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("SQS worker listener executor did not stop within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
                response.messages().forEach(this::processMessage);
            } catch (Exception e) {
                log.error("Exception while polling SQS queue (worker {}), will retry after sleep", workerIndex, e);
                sleep();
            }
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
            sqsClient.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl()).receiptHandle(message.receiptHandle()).build());
            log.debug("SQS message {} deleted (outcome {})", message.messageId(), outcome);
        } catch (Exception e) {
            // Intentionally not deleted: only infrastructure errors reach this catch (business failures are recorded
            // in batch metadata by the handler, which returns normally). The message becomes visible again after the
            // visibility timeout so transient errors are retried, and the queue's redrive policy moves it to the DLQ
            // once maxReceiveCount is exceeded
            log.error("Exception while processing SQS message {}", message.messageId(), e);
        }
    }

    /**
     * Build receive request. The visibility timeout is applied per-receive so the configured visibility-timeout-seconds
     * value is authoritative and does not silently fall back to the queue's default attribute. Values above the SQS
     * hard cap of 43200 seconds (12h) are clamped.
     */
    private ReceiveMessageRequest receiveMessageRequest() {
        FineractProperties.FineractRemoteJobMessageHandlerSqsProperties sqsProperties = fineractProperties.getRemoteJobMessageHandler()
                .getSqs();
        ReceiveMessageRequest.Builder builder = ReceiveMessageRequest.builder().queueUrl(queueUrl())
                .waitTimeSeconds(defaultIfNull(sqsProperties.getWaitTimeSeconds(), 20))
                .maxNumberOfMessages(defaultIfNull(sqsProperties.getMaxNumberOfMessages(), 1));
        Integer visibilityTimeout = sqsProperties.getVisibilityTimeoutSeconds();
        if (visibilityTimeout != null && visibilityTimeout > 0) {
            builder.visibilityTimeout(Math.min(visibilityTimeout, MAX_SQS_VISIBILITY_TIMEOUT_SECONDS));
        }
        return builder.build();
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
