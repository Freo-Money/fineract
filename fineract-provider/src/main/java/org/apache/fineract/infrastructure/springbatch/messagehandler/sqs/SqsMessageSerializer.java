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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.springbatch.ContextualMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Serializes and deserializes {@link ContextualMessage} for SQS using the application's existing {@link ObjectMapper}
 * bean (from {@link org.apache.fineract.infrastructure.core.jersey.JerseyJacksonConverterConfig}). No additional JARs:
 * Jackson is already on the classpath via Spring Boot; this class only uses {@code ObjectMapper.writeValueAsString} /
 * {@code readValue} and the same bean used by REST and other JSON in the app. Tenant context
 * ({@link org.apache.fineract.infrastructure.core.domain.FineractContext}), dates
 * ({@link org.apache.fineract.infrastructure.core.jersey.serializer.legacy.JacksonLocalDateArrayModule}), and
 * {@link org.springframework.batch.integration.partition.StepExecutionRequest} are handled by that shared mapper.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fineract.remote-job-message-handler.sqs.enabled", havingValue = "true")
public class SqsMessageSerializer {

    private final ObjectMapper objectMapper;

    public String serialize(ContextualMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ContextualMessage for SQS", e);
            throw new IllegalStateException("Unable to serialize SQS message payload", e);
        }
    }

    public ContextualMessage deserialize(String messageBody) {
        try {
            return objectMapper.readValue(messageBody, ContextualMessage.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize SQS message body to ContextualMessage", e);
            throw new IllegalStateException("Unable to deserialize SQS message payload", e);
        }
    }
}
