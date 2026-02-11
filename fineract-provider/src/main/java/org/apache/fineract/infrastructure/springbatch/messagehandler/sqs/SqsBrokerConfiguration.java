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

import com.google.common.base.Strings;
import java.net.URI;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

@Configuration
@ConditionalOnProperty(value = "fineract.remote-job-message-handler.sqs.enabled", havingValue = "true")
public class SqsBrokerConfiguration {

    @Bean
    public SqsClient sqsClient(FineractProperties fineractProperties) {
        FineractProperties.FineractRemoteJobMessageHandlerSqsProperties sqsProperties = fineractProperties.getRemoteJobMessageHandler()
                .getSqs();
        SqsClientBuilder builder = SqsClient.builder().credentialsProvider(getCredentialProvider(sqsProperties));

        if (!Strings.isNullOrEmpty(sqsProperties.getRegion())) {
            builder.region(Region.of(sqsProperties.getRegion()));
        }
        if (!Strings.isNullOrEmpty(sqsProperties.getEndpoint())) {
            builder.endpointOverride(URI.create(sqsProperties.getEndpoint()));
        }
        return builder.build();
    }

    private AwsCredentialsProvider getCredentialProvider(FineractProperties.FineractRemoteJobMessageHandlerSqsProperties sqsProperties) {
        if (!sqsProperties.isAccessKeyProtected()) {
            return DefaultCredentialsProvider.create();
        }

        return StaticCredentialsProvider.create(AwsBasicCredentials.create(sqsProperties.getAccessKey(), sqsProperties.getSecretKey()));
    }
}
