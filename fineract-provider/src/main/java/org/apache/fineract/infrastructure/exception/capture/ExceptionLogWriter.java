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
package org.apache.fineract.infrastructure.exception.capture;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.exception.domain.ExceptionLog;
import org.apache.fineract.infrastructure.exception.repository.ExceptionLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional writer for {@link ExceptionLog}. Kept in its own bean so the Spring proxy that opens the transaction
 * does not run before {@link JpaExceptionSink}'s tenant-context guard. {@code REQUIRES_NEW} so the log row commits
 * independently of any outer business transaction that may have been rolled back by the original failure.
 */
@Component
@RequiredArgsConstructor
public class ExceptionLogWriter {

    private static final int EXCEPTION_TYPE_MAX = 255;

    private final ExceptionLogRepository exceptionLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(final ExceptionRecord r) {
        final ExceptionLog entity = ExceptionLog.builder() //
                .traceId(r.traceId()) //
                .exceptionType(truncate(r.exceptionType(), EXCEPTION_TYPE_MAX)) //
                .exceptionMessage(r.message()) //
                .stackTrace(r.stackTrace()) //
                .requestPath(r.requestPath()) //
                .requestMethod(r.requestMethod()) //
                .statusCode(r.statusCode()) //
                .build();
        exceptionLogRepository.save(entity);
    }

    private static String truncate(final String value, final int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 1) + "…";
    }
}
