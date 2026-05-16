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
package org.apache.fineract.infrastructure.exception.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.infrastructure.configuration.data.GlobalConfigurationPropertyData;
import org.apache.fineract.infrastructure.configuration.service.ConfigurationReadPlatformService;
import org.apache.fineract.infrastructure.exception.service.ExceptionLogService;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PurgeExceptionLogsTasklet implements Tasklet {

    private final ExceptionLogService exceptionLogService;
    private final ConfigurationReadPlatformService configurationReadPlatformService;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        try {
            final GlobalConfigurationPropertyData config = configurationReadPlatformService
                    .retrieveGlobalConfiguration(GlobalConfigurationConstants.PURGE_EXCEPTION_LOGS_OLDER_THAN_DAYS);
            if (!config.isEnabled()) {
                log.debug("Skipping exception log purge: configuration {} is disabled",
                        GlobalConfigurationConstants.PURGE_EXCEPTION_LOGS_OLDER_THAN_DAYS);
                return RepeatStatus.FINISHED;
            }
            if (config.getValue() == null || config.getValue() <= 0) {
                log.warn("Skipping exception log purge: configuration value is missing or non-positive ({})", config.getValue());
                return RepeatStatus.FINISHED;
            }
            final int days = config.getValue().intValue();
            final int deleted = exceptionLogService.deleteExceptionLogsOlderThanDays(days);
            log.info("Purged {} exception logs older than {} days", deleted, days);
        } catch (Exception e) {
            log.error("Error while purging exception logs", e);
        }
        return RepeatStatus.FINISHED;
    }
}
