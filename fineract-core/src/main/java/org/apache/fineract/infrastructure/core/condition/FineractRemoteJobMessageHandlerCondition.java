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

import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.config.FineractProperties;

@Slf4j
public class FineractRemoteJobMessageHandlerCondition extends PropertiesCondition {

    @Override
    protected boolean matches(FineractProperties properties) {
        boolean isSpringEventsEnabled = isSpringEventsEnabled(properties);

        boolean conditionFails = false;
        if (isAnyMessageHandlerConfigured(properties) && isBatchInstance(properties)) {
            if (!isOnlyOneMessageHandlerEnabled(properties)) {
                conditionFails = true;
                log.error("For remote partitioning jobs exactly one Message Handler must be enabled.");
            } else if (isSpringEventsEnabled && !isBatchManagerAndWorkerTogether(properties)) {
                conditionFails = true;
                log.error("If Spring Event Message Handler is enabled, the instance must be Batch Manager and Batch Worker too.");
            }
        }
        return conditionFails;
    }

    private boolean isOnlyOneMessageHandlerEnabled(FineractProperties properties) {
        long enabledCount = Stream
                .of(isSpringEventsEnabled(properties), isJmsEnabled(properties), isKafkaEnabled(properties), isSqsEnabled(properties))
                .filter(Boolean::booleanValue).count();
        return enabledCount == 1;
    }

    private boolean isAnyMessageHandlerConfigured(FineractProperties properties) {
        return isSpringEventsEnabled(properties) || isJmsEnabled(properties) || isKafkaEnabled(properties) || isSqsEnabled(properties);
    }

    private boolean isSpringEventsEnabled(FineractProperties properties) {
        return properties.getRemoteJobMessageHandler().getSpringEvents() != null
                && properties.getRemoteJobMessageHandler().getSpringEvents().isEnabled();
    }

    private boolean isJmsEnabled(FineractProperties properties) {
        return properties.getRemoteJobMessageHandler().getJms() != null && properties.getRemoteJobMessageHandler().getJms().isEnabled();
    }

    private boolean isKafkaEnabled(FineractProperties properties) {
        return properties.getRemoteJobMessageHandler().getKafka() != null && properties.getRemoteJobMessageHandler().getKafka().isEnabled();
    }

    private boolean isSqsEnabled(FineractProperties properties) {
        return properties.getRemoteJobMessageHandler().getSqs() != null && properties.getRemoteJobMessageHandler().getSqs().isEnabled();
    }

    private boolean isBatchInstance(FineractProperties properties) {
        boolean isBatchManagerModeEnabled = properties.getMode().isBatchManagerEnabled();
        boolean isBatchWorkerModeEnabled = properties.getMode().isBatchWorkerEnabled();
        return isBatchManagerModeEnabled || isBatchWorkerModeEnabled;
    }

    private boolean isBatchManagerAndWorkerTogether(FineractProperties properties) {
        boolean isBatchManagerModeEnabled = properties.getMode().isBatchManagerEnabled();
        boolean isBatchWorkerModeEnabled = properties.getMode().isBatchWorkerEnabled();
        return isBatchManagerModeEnabled && isBatchWorkerModeEnabled;
    }
}
