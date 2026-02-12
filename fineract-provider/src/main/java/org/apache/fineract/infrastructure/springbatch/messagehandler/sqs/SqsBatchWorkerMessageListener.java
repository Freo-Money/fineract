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
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * SQS worker that polls the remote job queue and processes partition requests. Multitenant context is carried in
 * {@link ContextualMessage#getContext()} and restored by {@link InputChannelInterceptor#beforeHandleMessage} before the
 * step runs, so each partition runs with the correct tenant context. On failure, the message is either sent to the
 * configured DLQ (if dlqQueueUrl is set) and removed from the main queue, or left on the queue for visibility timeout
 * and optional redrive policy to move to DLQ after maxReceiveCount.
 */
@Slf4j
@RequiredArgsConstructor
public class SqsBatchWorkerMessageListener {

    private static final int IDLE_SLEEP_MILLIS = 500;

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
        String queueUrl = queueUrl();
        String dlqUrl = dlqQueueUrl();
        try {
            ContextualMessage contextualMessage = sqsMessageSerializer.deserialize(message.body());
            log.debug("Received SQS partition message {}", message.messageId());
            // Restores tenant context (multitenant) before running the step
            stepExecutionRequestHandler.handle(inputInterceptor.beforeHandleMessage(contextualMessage));
            sqsClient.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(message.receiptHandle()).build());
            log.debug("SQS message deleted after successful processing {}", message.messageId());
        } catch (Exception e) {
            log.error("Exception while processing SQS message {}", message.messageId(), e);
            if (dlqUrl != null && !dlqUrl.isBlank()) {
                try {
                    sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(dlqUrl).messageBody(message.body()).build());
                    sqsClient.deleteMessage(
                            DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(message.receiptHandle()).build());
                    log.info("Moved failed SQS message {} to DLQ", message.messageId());
                } catch (Exception ex) {
                    log.error("Failed to send message to DLQ {}", message.messageId(), ex);
                }
            }
            // Otherwise do not delete: message becomes visible again after visibility timeout; configure queue redrive
            // policy to send to DLQ after maxReceiveCount
        }
    }

    /**
     * Build receive request. Visibility timeout is configured at queue level in AWS; set the queue's default visibility
     * timeout to match visibilityTimeoutSeconds in config. Use ChangeMessageVisibility if processing may exceed it.
     */
    private ReceiveMessageRequest receiveMessageRequest() {
        FineractProperties.FineractRemoteJobMessageHandlerSqsProperties sqsProperties = fineractProperties.getRemoteJobMessageHandler()
                .getSqs();
        return ReceiveMessageRequest.builder().queueUrl(queueUrl()).waitTimeSeconds(defaultIfNull(sqsProperties.getWaitTimeSeconds(), 20))
                .maxNumberOfMessages(defaultIfNull(sqsProperties.getMaxNumberOfMessages(), 1)).build();
    }

    private int defaultIfNull(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String queueUrl() {
        return fineractProperties.getRemoteJobMessageHandler().getSqs().getQueueUrl();
    }

    private String dlqQueueUrl() {
        return fineractProperties.getRemoteJobMessageHandler().getSqs().getDlqQueueUrl();
    }

    private void sleep() {
        try {
            Thread.sleep(IDLE_SLEEP_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
