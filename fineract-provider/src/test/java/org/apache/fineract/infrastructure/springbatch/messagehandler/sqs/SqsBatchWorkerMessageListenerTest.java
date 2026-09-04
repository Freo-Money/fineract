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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.springbatch.ContextualMessage;
import org.apache.fineract.infrastructure.springbatch.InputChannelInterceptor;
import org.apache.fineract.infrastructure.springbatch.messagehandler.StepExecutionRequestHandler;
import org.apache.fineract.infrastructure.springbatch.messagehandler.StepExecutionRequestHandler.HandleOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
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
    private MockEnvironment environment;
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
        environment = new MockEnvironment();
        underTest = new SqsBatchWorkerMessageListener(stepExecutionRequestHandler, inputInterceptor, sqsClient, sqsMessageSerializer,
                fineractProperties, environment);
    }

    @AfterEach
    public void tearDown() {
        // Safety net: a failed assertion must not leak a worker thread that later touches this test's mocks while
        // other tests run. Zero drain window makes the stop immediate; stop() is idempotent and a no-op before start()
        sqsProperties.setShutdownTimeoutSeconds(0);
        underTest.stop();
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
    public void whenVisibilityTimeoutIsNotConfiguredThenTheDefaultIsAppliedPerReceive() {
        // the effective default must be applied instead of falling back to the queue attribute: the orphan-takeover
        // threshold in StepExecutionRequestHandler assumes the same effective value for its redelivery math
        sqsProperties.setVisibilityTimeoutSeconds(null);

        assertEquals(3600, receiveMessageRequest().visibilityTimeout());
    }

    @Test
    public void whenVisibilityTimeoutIsNotPositiveThenTheDefaultIsAppliedPerReceive() {
        sqsProperties.setVisibilityTimeoutSeconds(0);

        assertEquals(3600, receiveMessageRequest().visibilityTimeout());
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

    @Test
    public void whenStopIsCalledBeforeStartThenNothingHappens() {
        assertDoesNotThrow(() -> underTest.stop());
    }

    @Test
    public void whenListenerIsIdleThenStopDrainsPromptly() throws Exception {
        CountDownLatch polling = new CountDownLatch(1);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(invocation -> {
            polling.countDown();
            return ReceiveMessageResponse.builder().build();
        });
        underTest.start();
        assertTrue(polling.await(5, TimeUnit.SECONDS));

        // With nothing in flight the worker must be force-stopped immediately instead of waiting out the drain window
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> underTest.stop());
    }

    @Test
    public void whenListenerIsStartedAndStoppedThenIsRunningTracksIt() {
        // lenient: on a slow scheduler the worker thread may never poll before stop() force-stops it
        lenient().when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().build());

        assertFalse(underTest.isRunning());
        underTest.start();
        assertTrue(underTest.isRunning());
        underTest.stop();
        assertFalse(underTest.isRunning());
    }

    @Test
    public void whenMessageArrivesAfterStopBeganThenItIsReleasedInsteadOfProcessed() throws Exception {
        CountDownLatch receiveStarted = new CountDownLatch(1);
        Message message = Message.builder().messageId("message-1").body("payload").receiptHandle("receipt-1").build();
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(invocation -> {
            receiveStarted.countDown();
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                // stop() aborted the poll while the SQS response was already on the wire; deliver it anyway
            }
            return ReceiveMessageResponse.builder().messages(message).build();
        });
        underTest.start();
        assertTrue(receiveStarted.await(5, TimeUnit.SECONDS));

        underTest.stop();

        verify(sqsClient).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
        verify(stepExecutionRequestHandler, never()).handle(any());
        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    public void whenHandlerFinishesOnAnInterruptedThreadThenMessageIsStillDeleted() {
        Message message = deserializableMessage();
        when(stepExecutionRequestHandler.handle(any())).thenAnswer(invocation -> {
            // Simulates the drain deadline interrupting the worker just as the partition completes
            Thread.currentThread().interrupt();
            return HandleOutcome.PROCESSED;
        });

        ReflectionTestUtils.invokeMethod(underTest, "processMessage", message);

        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
        // The interrupt flag must be restored after the delete (and cleared here so it cannot leak into later tests)
        assertTrue(Thread.interrupted());
    }

    @Test
    public void whenReleaseRunsOnAnInterruptedThreadThenVisibilityIsStillReset() {
        Message message = Message.builder().messageId("message-1").body("payload").receiptHandle("receipt-1").build();
        // Simulates shutdownNow() having interrupted the worker just before the late-received message is released
        Thread.currentThread().interrupt();

        ReflectionTestUtils.invokeMethod(underTest, "releaseMessage", message);

        verify(sqsClient).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
        // The interrupt flag must be restored after the release (and cleared here so it cannot leak into later tests)
        assertTrue(Thread.interrupted());
    }

    @Test
    public void whenShutdownTimeoutIsNegativeThenDefaultDrainWindowIsUsed() {
        sqsProperties.setShutdownTimeoutSeconds(-1);

        assertEquals(25, underTest.drainTimeoutSeconds());
    }

    @Test
    public void whenShutdownTimeoutIsNotConfiguredThenDefaultDrainWindowIsUsed() {
        sqsProperties.setShutdownTimeoutSeconds(null);

        assertEquals(25, underTest.drainTimeoutSeconds());
    }

    @Test
    public void whenShutdownOccursMidPartitionThenDrainLetsItFinishAndDeleteItsMessage() throws Exception {
        startWithInFlightPartition(30, invocation -> {
            Thread.sleep(1000);
            return HandleOutcome.PROCESSED;
        });

        underTest.stop();

        // With the old shutdownNow-first behavior the sleep would be interrupted and the delete never issued
        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    public void whenDrainWindowIsZeroThenInFlightPartitionIsInterruptedAndMessageKept() throws Exception {
        startWithInFlightPartition(0, invocation -> {
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted mid-partition", e);
            }
            return HandleOutcome.PROCESSED;
        });

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> underTest.stop());

        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    public void whenStopRunsTwiceOnAStuckPartitionThenSecondInvocationReturnsImmediately() throws Exception {
        // Simulates a partition stuck in an uninterruptible call: swallows every interrupt and never returns, so the
        // leaked daemon thread never touches the mocks again after this test
        startWithInFlightPartition(2, invocation -> {
            while (true) {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    // deliberately swallowed
                }
            }
        });

        underTest.stop();

        // The @PreDestroy safety net re-enters stop() after the lifecycle stop; it must not re-run the drain
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> underTest.stop());
    }

    @Test
    public void whenDrainWindowIsSmallThenAQuickPartitionStillFinishesInsteadOfBeingInterrupted() throws Exception {
        // Regression for the grace-reserve arithmetic: a 4s budget must still drain (reserve min(5, 4/2)=2s leaves a
        // 2s in-flight wait for the 300ms partition), not silently behave like drain-disabled
        startWithInFlightPartition(4, invocation -> {
            Thread.sleep(300);
            return HandleOutcome.PROCESSED;
        });

        underTest.stop();

        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    public void whenDrainBudgetExceedsTheLifecyclePhaseTimeoutThenItIsFlagged() {
        environment.setProperty("spring.lifecycle.timeout-per-shutdown-phase", "30s");
        sqsProperties.setShutdownTimeoutSeconds(90);

        assertTrue(underTest.drainExceedsLifecyclePhaseTimeout());
    }

    @Test
    public void whenDrainBudgetFitsTheLifecyclePhaseTimeoutThenItIsNotFlagged() {
        environment.setProperty("spring.lifecycle.timeout-per-shutdown-phase", "100s");
        sqsProperties.setShutdownTimeoutSeconds(90);

        assertFalse(underTest.drainExceedsLifecyclePhaseTimeout());
    }

    @Test
    public void whenPhaseTimeoutIsNotConfiguredThenTheSpring30sDefaultIsAssumed() {
        sqsProperties.setShutdownTimeoutSeconds(31);
        assertTrue(underTest.drainExceedsLifecyclePhaseTimeout());

        sqsProperties.setShutdownTimeoutSeconds(25);
        assertFalse(underTest.drainExceedsLifecyclePhaseTimeout());
    }

    /**
     * Shared scaffold for the drain tests: stubs a single-message receive followed by empty polls, runs the given
     * partition body on the worker thread, starts the listener and returns once the partition is in flight.
     */
    private void startWithInFlightPartition(int shutdownTimeoutSeconds, Answer<Object> partitionBody) throws Exception {
        sqsProperties.setShutdownTimeoutSeconds(shutdownTimeoutSeconds);
        CountDownLatch handleStarted = new CountDownLatch(1);
        Message message = deserializableMessage();
        when(stepExecutionRequestHandler.handle(any())).thenAnswer(invocation -> {
            handleStarted.countDown();
            return partitionBody.answer(invocation);
        });
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(message).build())
                .thenReturn(ReceiveMessageResponse.builder().build());
        underTest.start();
        assertTrue(handleStarted.await(5, TimeUnit.SECONDS));
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
