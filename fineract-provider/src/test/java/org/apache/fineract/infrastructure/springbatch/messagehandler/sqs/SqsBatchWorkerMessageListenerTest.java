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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.springbatch.ContextualMessage;
import org.apache.fineract.infrastructure.springbatch.InputChannelInterceptor;
import org.apache.fineract.infrastructure.springbatch.messagehandler.StepExecutionRequestHandler;
import org.apache.fineract.infrastructure.springbatch.messagehandler.StepExecutionRequestHandler.HandleOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@ExtendWith(MockitoExtension.class)
public class SqsBatchWorkerMessageListenerTest {

    private static final String QUEUE_URL = "https://sqs.ap-south-1.amazonaws.com/123456789012/main-queue";

    @Mock
    private StepExecutionRequestHandler stepExecutionRequestHandler;
    @Mock
    private InputChannelInterceptor inputInterceptor;
    @Mock
    private SqsClient sqsClient;
    @Mock
    private SqsMessageSerializer sqsMessageSerializer;

    private FineractProperties.FineractRemoteJobMessageHandlerSqsProperties sqsProperties;
    private SqsBatchWorkerMessageListener underTest;

    @BeforeEach
    public void setUp() {
        FineractProperties fineractProperties = new FineractProperties();
        FineractProperties.FineractRemoteJobMessageHandlerProperties remoteJobMessageHandler = new FineractProperties.FineractRemoteJobMessageHandlerProperties();
        sqsProperties = new FineractProperties.FineractRemoteJobMessageHandlerSqsProperties();
        sqsProperties.setQueueUrl(QUEUE_URL);
        sqsProperties.setWaitTimeSeconds(20);
        sqsProperties.setMaxNumberOfMessages(1);
        remoteJobMessageHandler.setSqs(sqsProperties);
        fineractProperties.setRemoteJobMessageHandler(remoteJobMessageHandler);
        underTest = new SqsBatchWorkerMessageListener(stepExecutionRequestHandler, inputInterceptor, sqsClient, sqsMessageSerializer,
                fineractProperties);
    }

    private ReceiveMessageRequest receiveMessageRequest() {
        return ReflectionTestUtils.invokeMethod(underTest, "receiveMessageRequest");
    }

    @Test
    public void whenVisibilityTimeoutIsConfiguredThenItIsAppliedPerReceive() {
        sqsProperties.setVisibilityTimeoutSeconds(3600);

        assertEquals(3600, receiveMessageRequest().visibilityTimeout());
    }

    @Test
    public void whenVisibilityTimeoutIsNotConfiguredThenItIsOmitted() {
        sqsProperties.setVisibilityTimeoutSeconds(null);

        assertNull(receiveMessageRequest().visibilityTimeout());
    }

    @Test
    public void whenVisibilityTimeoutIsNotPositiveThenItIsOmitted() {
        sqsProperties.setVisibilityTimeoutSeconds(0);

        assertNull(receiveMessageRequest().visibilityTimeout());
    }

    @Test
    public void whenVisibilityTimeoutExceedsSqsHardCapThenItIsClamped() {
        sqsProperties.setVisibilityTimeoutSeconds(43201);

        assertEquals(43200, receiveMessageRequest().visibilityTimeout());
    }

    @Test
    public void whenPartitionIsProcessedThenMessageIsDeleted() {
        Message message = messageWithOutcome(HandleOutcome.PROCESSED);

        ReflectionTestUtils.invokeMethod(underTest, "processMessage", message);

        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    public void whenRequestIsDiscardableDuplicateThenMessageIsDeleted() {
        Message message = messageWithOutcome(HandleOutcome.DISCARD);

        ReflectionTestUtils.invokeMethod(underTest, "processMessage", message);

        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    public void whenPartitionIsInFlightThenMessageIsKeptAsRecoveryTrigger() {
        Message message = messageWithOutcome(HandleOutcome.IN_FLIGHT);

        ReflectionTestUtils.invokeMethod(underTest, "processMessage", message);

        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    public void whenProcessingFailsThenMessageIsLeftOnTheQueueForRedrive() {
        Message message = failingMessage();

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(underTest, "processMessage", message));

        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    private Message messageWithOutcome(HandleOutcome outcome) {
        Message message = deserializableMessage();
        when(stepExecutionRequestHandler.handle(any())).thenReturn(outcome);
        return message;
    }

    private Message failingMessage() {
        Message message = deserializableMessage();
        doThrow(new RuntimeException("partition processing failed")).when(stepExecutionRequestHandler).handle(any());
        return message;
    }

    private Message deserializableMessage() {
        Message message = Message.builder().messageId("message-1").body("payload").receiptHandle("receipt-1").build();
        when(sqsMessageSerializer.deserialize("payload")).thenReturn(new ContextualMessage());
        return message;
    }
}
