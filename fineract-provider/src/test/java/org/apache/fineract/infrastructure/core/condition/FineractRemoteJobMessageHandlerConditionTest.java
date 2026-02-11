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
package org.apache.fineract.infrastructure.core.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(MockitoExtension.class)
class FineractRemoteJobMessageHandlerConditionTest {

    @Mock
    private ConditionContext conditionContext;

    @Mock
    private AnnotatedTypeMetadata metadata;

    private MockEnvironment environment;

    @InjectMocks
    private FineractRemoteJobMessageHandlerCondition underTest = new FineractRemoteJobMessageHandlerCondition();

    @BeforeEach
    public void setUp() {
        environment = new MockEnvironment();
        given(conditionContext.getEnvironment()).willReturn(environment);
    }

    @Test
    public void testMatchesShouldReturnFalseWhenSpringEventsIsEnabledAndManagerAndWorker() {
        // given
        setMessageHandlers(true, false, false, false);
        environment.withProperty("fineract.mode.batch-manager-enabled", "true");
        environment.withProperty("fineract.mode.batch-worker-enabled", "true");
        // when
        boolean result = underTest.matches(conditionContext, metadata);
        // then
        assertThat(result).isFalse();
    }

    @Test
    public void testMatchesShouldReturnTrueWhenSpringEventsIsEnabledAndManagerOnly() {
        // given
        setMessageHandlers(true, false, false, false);
        environment.withProperty("fineract.mode.batch-manager-enabled", "true");
        environment.withProperty("fineract.mode.batch-worker-enabled", "false");
        // when
        boolean result = underTest.matches(conditionContext, metadata);
        // then
        assertThat(result).isTrue();
    }

    @Test
    public void testMatchesShouldReturnTrueWhenSpringEventsIsEnabledAndWorkerOnly() {
        // given
        setMessageHandlers(true, false, false, false);
        environment.withProperty("fineract.mode.batch-manager-enabled", "false");
        environment.withProperty("fineract.mode.batch-worker-enabled", "true");
        // when
        boolean result = underTest.matches(conditionContext, metadata);
        // then
        assertThat(result).isTrue();
    }

    @Test
    public void testMatchesShouldReturnFalseWhenJmsIsEnabledAndManagerAndWorker() {
        // given
        setMessageHandlers(false, true, false, false);
        environment.withProperty("fineract.mode.batch-manager-enabled", "true");
        environment.withProperty("fineract.mode.batch-worker-enabled", "true");
        // when
        boolean result = underTest.matches(conditionContext, metadata);
        // then
        assertThat(result).isFalse();
    }

    @Test
    public void testMatchesShouldReturnFalseWhenJmsIsEnabledAndManagerOnly() {
        // given
        setMessageHandlers(false, true, false, false);
        environment.withProperty("fineract.mode.batch-manager-enabled", "true");
        environment.withProperty("fineract.mode.batch-worker-enabled", "false");
        // when
        boolean result = underTest.matches(conditionContext, metadata);
        // then
        assertThat(result).isFalse();
    }

    @Test
    public void testMatchesShouldReturnFalseWhenJmsIsEnabledAndWorkerOnly() {
        // given
        setMessageHandlers(false, true, false, false);
        environment.withProperty("fineract.mode.batch-manager-enabled", "false");
        environment.withProperty("fineract.mode.batch-worker-enabled", "true");
        // when
        boolean result = underTest.matches(conditionContext, metadata);
        // then
        assertThat(result).isFalse();
    }

    @Test
    public void testMatchesShouldReturnTrueWhenSpringEventsIsEnabledAndJmsIsEnabled() {
        // given
        setMessageHandlers(true, true, false, false);
        environment.withProperty("fineract.mode.batch-manager-enabled", "true");
        environment.withProperty("fineract.mode.batch-worker-enabled", "true");
        // when
        boolean result = underTest.matches(conditionContext, metadata);
        // then
        assertThat(result).isTrue();
    }

    @Test
    public void testMatchesShouldReturnFalseWhenNoMessagingIsConfiguredAndNotManagerOrWorker() {
        // given
        setMessageHandlers(false, false, false, false);
        environment.withProperty("fineract.mode.batch-manager-enabled", "false");
        environment.withProperty("fineract.mode.batch-worker-enabled", "false");
        // when
        boolean result = underTest.matches(conditionContext, metadata);
        // then
        assertThat(result).isFalse();
    }

    @Test
    public void testMatchesShouldReturnFalseWhenKafkaIsEnabledAndManagerAndWorker() {
        // given
        setMessageHandlers(false, false, true, false);
        environment.withProperty("fineract.mode.batch-manager-enabled", "true");
        environment.withProperty("fineract.mode.batch-worker-enabled", "true");
        // when
        boolean result = underTest.matches(conditionContext, metadata);
        // then
        assertThat(result).isFalse();
    }

    @Test
    public void testMatchesShouldReturnFalseWhenSqsIsEnabledAndManagerAndWorker() {
        // given
        setMessageHandlers(false, false, false, true);
        environment.withProperty("fineract.mode.batch-manager-enabled", "true");
        environment.withProperty("fineract.mode.batch-worker-enabled", "true");
        // when
        boolean result = underTest.matches(conditionContext, metadata);
        // then
        assertThat(result).isFalse();
    }

    @Test
    public void testMatchesShouldReturnTrueWhenKafkaAndSqsAreEnabledTogether() {
        // given
        setMessageHandlers(false, false, true, true);
        environment.withProperty("fineract.mode.batch-manager-enabled", "true");
        environment.withProperty("fineract.mode.batch-worker-enabled", "true");
        // when
        boolean result = underTest.matches(conditionContext, metadata);
        // then
        assertThat(result).isTrue();
    }

    private void setMessageHandlers(boolean springEventsEnabled, boolean jmsEnabled, boolean kafkaEnabled, boolean sqsEnabled) {
        environment.withProperty("fineract.remote-job-message-handler.spring-events.enabled", String.valueOf(springEventsEnabled));
        environment.withProperty("fineract.remote-job-message-handler.jms.enabled", String.valueOf(jmsEnabled));
        environment.withProperty("fineract.remote-job-message-handler.kafka.enabled", String.valueOf(kafkaEnabled));
        environment.withProperty("fineract.remote-job-message-handler.sqs.enabled", String.valueOf(sqsEnabled));
    }
}
