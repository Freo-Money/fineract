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
import org.apache.fineract.infrastructure.springbatch.InputChannelInterceptor;
import org.apache.fineract.infrastructure.springbatch.messagehandler.StepExecutionRequestHandler;
import org.apache.fineract.infrastructure.springbatch.messagehandler.conditions.sqs.SqsWorkerCondition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration(proxyBeanMethods = false)
@Conditional(SqsWorkerCondition.class)
@Import(SqsBrokerConfiguration.class)
public class SqsWorkerConfig {

    @Bean
    public SqsBatchWorkerMessageListener sqsBatchWorkerMessageListener(StepExecutionRequestHandler stepExecutionRequestHandler,
            InputChannelInterceptor inputInterceptor, SqsClient sqsClient, SqsMessageSerializer sqsMessageSerializer,
            FineractProperties fineractProperties) {
        return new SqsBatchWorkerMessageListener(stepExecutionRequestHandler, inputInterceptor, sqsClient, sqsMessageSerializer,
                fineractProperties);
    }
}
