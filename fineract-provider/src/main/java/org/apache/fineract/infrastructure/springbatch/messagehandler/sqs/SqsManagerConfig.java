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

import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.springbatch.ContextualMessage;
import org.apache.fineract.infrastructure.springbatch.OutputChannelInterceptor;
import org.apache.fineract.infrastructure.springbatch.messagehandler.conditions.sqs.SqsManagerCondition;
import org.springframework.batch.integration.config.annotation.EnableBatchIntegration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.handler.LoggingHandler;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Configuration
@EnableBatchIntegration
@Conditional(SqsManagerCondition.class)
@Import(SqsBrokerConfiguration.class)
public class SqsManagerConfig {

    @Autowired
    private DirectChannel outboundRequests;
    @Autowired
    private OutputChannelInterceptor outputInterceptor;
    @Autowired
    private FineractProperties fineractProperties;
    @Autowired
    private SqsMessageSerializer sqsMessageSerializer;

    @Bean
    public IntegrationFlow outboundFlow(SqsClient sqsClient) {
        String queueUrl = fineractProperties.getRemoteJobMessageHandler().getSqs().getQueueUrl();
        return IntegrationFlow.from(outboundRequests) //
                .intercept(outputInterceptor) //
                .log(LoggingHandler.Level.DEBUG) //
                .handle((payload, headers) -> {
                    ContextualMessage contextualMessage = (ContextualMessage) payload;
                    sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl)
                            .messageBody(sqsMessageSerializer.serialize(contextualMessage)).build());
                    return null;
                }).get();
    }
}
