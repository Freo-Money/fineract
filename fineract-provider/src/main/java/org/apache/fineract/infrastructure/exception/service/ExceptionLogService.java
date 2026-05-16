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

import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.exception.domain.ExceptionLog;
import org.apache.fineract.infrastructure.exception.repository.ExceptionLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
@RequiredArgsConstructor
public class ExceptionLogService {

    private final ExceptionLogRepository exceptionLogRepository;

    public Page<ExceptionLog> getAllExceptionLogs(Pageable pageable) {
        return exceptionLogRepository.findAll(pageable);
    }

    public Optional<ExceptionLog> getExceptionLogByTraceId(String traceId) {
        return exceptionLogRepository.findByTraceId(traceId);
    }

    public Page<ExceptionLog> getExceptionLogsByType(String exceptionType, Pageable pageable) {
        return exceptionLogRepository.findByExceptionType(exceptionType, pageable);
    }

    public Page<ExceptionLog> searchExceptionLogsByTypeContaining(String exceptionType, Pageable pageable) {
        return exceptionLogRepository.findByExceptionTypeContainingIgnoreCase(exceptionType, pageable);
    }

    public Page<ExceptionLog> getExceptionLogsByPath(String path, Pageable pageable) {
        return exceptionLogRepository.findByRequestPath(path, pageable);
    }

    public Page<ExceptionLog> getExceptionLogsByDateRange(OffsetDateTime from, OffsetDateTime to, Pageable pageable) {
        return exceptionLogRepository.findByCreatedDateBetween(from, to, pageable);
    }

    @Transactional
    public int deleteExceptionLogsOlderThanDays(int days) {
        OffsetDateTime cutoffDate = DateUtils.getAuditOffsetDateTime().minusDays(days);
        int deletedCount = exceptionLogRepository.deleteByCreatedDateBefore(cutoffDate);
        log.info("Deleted {} exception logs older than {} days", deletedCount, days);
        return deletedCount;
    }
}
