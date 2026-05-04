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
package org.apache.fineract.infrastructure.exception.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.config.TaskExecutorConstant;
import org.apache.fineract.infrastructure.exception.domain.ExceptionLog;
import org.apache.fineract.infrastructure.exception.repository.ExceptionLogRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class ErrorLoggingService {

    private final ExceptionLogRepository exceptionLogRepository;

    public ErrorLoggingService(ExceptionLogRepository exceptionLogRepository) {
        this.exceptionLogRepository = exceptionLogRepository;
    }

    @Async(TaskExecutorConstant.EXCEPTION_LOGGING_TASK_EXECUTOR_BEAN_NAME)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logException(Throwable ex, String traceId, String path, String method, int statusCode, String message) {
        try {
            String stackTrace = getStackTrace(ex);
            String exceptionType = ex.getClass().getName();
            String exceptionMessage = message != null ? message : ex.getMessage();

            ExceptionLog exceptionLog = ExceptionLog.builder().traceId(traceId).exceptionType(exceptionType)
                    .exceptionMessage(exceptionMessage).stackTrace(stackTrace).requestPath(path).requestMethod(method)
                    .statusCode(statusCode).build();

            exceptionLogRepository.save(exceptionLog);
            log.info("Exception logged to database with traceId={} exceptionType={}", traceId, exceptionType);
        } catch (Exception e) {
            log.error("Failed to log exception to database", e);
        }
    }

    private String getStackTrace(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        int maxCauseDepth = 5;
        int maxTotalLength = 8000;

        Throwable current = ex;
        int depth = 0;

        while (current != null && depth < maxCauseDepth && sb.length() < maxTotalLength) {
            if (depth == 0) {
                sb.append(current.getClass().getName());
            } else {
                sb.append("Caused by: ").append(current.getClass().getName());
            }

            if (current.getMessage() != null) {
                sb.append(": ").append(current.getMessage());
            }
            sb.append("\n");

            StackTraceElement[] elements = current.getStackTrace();
            int limit = Math.min(elements.length, 50);

            for (int i = 0; i < limit && sb.length() < maxTotalLength; i++) {
                sb.append("\tat ").append(elements[i]).append("\n");
            }

            if (elements.length > limit) {
                sb.append("\t... ").append(elements.length - limit).append(" more\n");
            }

            current = current.getCause();
            depth++;
        }

        if (current != null) {
            sb.append("\t... (cause chain truncated at depth ").append(maxCauseDepth).append(")\n");
        }

        return sb.toString();
    }

}
