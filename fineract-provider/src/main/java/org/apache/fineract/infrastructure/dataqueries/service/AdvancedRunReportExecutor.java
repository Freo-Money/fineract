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
package org.apache.fineract.infrastructure.dataqueries.service;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.fineract.infrastructure.core.api.ApiParameterHelper;
import org.apache.fineract.infrastructure.core.config.TaskExecutorConstant;
import org.apache.fineract.infrastructure.core.domain.FineractContext;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformException;
import org.apache.fineract.infrastructure.core.exception.PlatformServiceUnavailableException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.dataqueries.domain.AdvancedRunReportRepository;
import org.apache.fineract.infrastructure.report.provider.ReportingProcessServiceProvider;
import org.apache.fineract.infrastructure.report.service.ReportingProcessService;
import org.glassfish.jersey.internal.util.collection.MultivaluedStringMap;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Dedicated async executor for report processing. By isolating the @Async method in a separate component, we ensure
 * Spring's proxy mechanism properly intercepts and schedules the execution in the async thread pool.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdvancedRunReportExecutor {

    private final AdvancedRunReportRepository advancedRunReportRepository;
    private final ReportingProcessServiceProvider reportingProcessServiceProvider;
    private final ReadReportingService readReportingService;
    private final AdvancedRunReportStatusService advancedRunReportStatusService;

    @Async(TaskExecutorConstant.ASYNC_REPORT_TASK_EXECUTOR_BEAN_NAME)
    public void executeReportAsync(Long reportId, String reportName, Map<String, String> flatParams, boolean isSelfServiceUserReport,
            FineractContext context, String s3ReportPath) {

        try {
            log.info("Starting async report request {} for report '{}'", reportId, reportName);

            ThreadLocalContextUtil.init(context);

            advancedRunReportStatusService.updaterequestStatus(reportId, true, null, s3ReportPath);
            log.info("request {} marked as RUNNING", reportId);

            MultivaluedMap<String, String> queryParams = new MultivaluedStringMap();
            flatParams.forEach(queryParams::putSingle);

            final boolean parameterTypeValue = ApiParameterHelper.parameterType(queryParams);

            queryParams.putSingle("isSelfServiceUserReport", Boolean.toString(isSelfServiceUserReport));
            queryParams.putSingle("reportlocation", s3ReportPath);

            String reportType = readReportingService.getReportType(reportName, isSelfServiceUserReport, parameterTypeValue);

            ReportingProcessService reportingProcessService = reportingProcessServiceProvider.findReportingProcessService(reportType);

            if (reportingProcessService == null) {
                throw new PlatformServiceUnavailableException("err.msg.report.service.implementation.missing",
                        ReportingProcessServiceProvider.SERVICE_MISSING + reportType, reportType);
            }

            log.info("Executing processRequest for runreport request {} using report type '{}'", reportId, reportType);

            Response response = reportingProcessService.processRequest(reportName, queryParams);

            if (response != null) {
                response.close();
            }

            advancedRunReportStatusService.updaterequestStatus(reportId, false, null, s3ReportPath);
            log.info("runreport request {} marked COMPLETED", reportId);

        } catch (Exception e) {
            log.error("runreport request {} failed", reportId, e);
            advancedRunReportStatusService.updaterequestStatus(reportId, false, resolvePersistableErrorMessage(e), s3ReportPath);
        } finally {
            ThreadLocalContextUtil.reset();
        }
    }

    private String resolvePersistableErrorMessage(final Exception exception) {
        final List<Throwable> throwableChain = ExceptionUtils.getThrowableList(exception);

        String fallbackMessage = null;

        for (Throwable throwable : throwableChain) {
            if (throwable instanceof AbstractPlatformException platformException) {
                final String platformMessage = sanitizePersistableMessage(platformException.getDefaultUserMessage());
                if (StringUtils.isNotBlank(platformMessage)) {
                    return truncateMessage(platformMessage);
                }
            }

            if (fallbackMessage == null) {
                final String standardMessage = sanitizePersistableMessage(throwable.getMessage());
                if (StringUtils.isNotBlank(standardMessage)) {
                    fallbackMessage = standardMessage;
                }
            }
        }

        return fallbackMessage != null ? truncateMessage(fallbackMessage) : "Unexpected error occurred while processing runreport request.";
    }

    private String sanitizePersistableMessage(final String message) {
        if (StringUtils.isBlank(message)) {
            return null;
        }

        final String trimmedMessage = message.trim();
        final String lowerCaseMessage = trimmedMessage.toLowerCase();

        if (lowerCaseMessage.contains("token has expired") || lowerCaseMessage.contains("provided token has expired")) {
            return "Authentication token has expired. Please refresh credentials.";
        }

        if ((trimmedMessage.startsWith("{") && trimmedMessage.endsWith("}"))
                || (trimmedMessage.startsWith("[") && trimmedMessage.endsWith("]"))) {
            return "Unexpected error occurred while processing runreport request.";
        }

        return trimmedMessage
                .replaceAll("(?i)(aws_secret_access_key|secretaccesskey|accesskey|sessiontoken|token|password)=([^,\\s]+)", "$1=***")
                .replaceAll("(?i)\"(aws_secret_access_key|secretaccesskey|accesskey|sessiontoken|token|password)\"\\s*:\\s*\"[^\"]+\"",
                        "\"$1\":\"***\"")
                .replaceAll("(?i)(x-amz-security-token|authorization)\\s*[:=]\\s*([^,\\s]+)", "$1=***");
    }

    private String truncateMessage(final String message) {
        return StringUtils.truncate(message, 1000);
    }
}
