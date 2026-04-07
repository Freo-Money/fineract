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

import java.io.PrintWriter;
import java.io.StringWriter;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.exception.domain.ExceptionLog;
import org.apache.fineract.infrastructure.exception.repository.ExceptionLogRepository;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logException(Throwable ex, String traceId, String path, String method, int statusCode, String message) {
        try {
            String stackTrace = getStackTrace(ex);
            String exceptionType = ex.getClass().getName();
            String exceptionMessage = message != null ? message : ex.getMessage();

            ExceptionLog exceptionLog = ExceptionLog.builder()
                    .traceId(traceId)
                    .exceptionType(exceptionType)
                    .exceptionMessage(exceptionMessage)
                    .stackTrace(stackTrace)
                    .requestPath(path)
                    .requestMethod(method)
                    .statusCode(statusCode)
                    .build();

            exceptionLogRepository.save(exceptionLog);
            log.info("Exception logged to database with traceId={} exceptionType={}", traceId, exceptionType);
        } catch (Exception e) {
            log.error("Failed to log exception to database", e);
        }
    }

    private String getStackTrace(Throwable ex) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        return sw.toString();
    }
}