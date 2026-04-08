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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.exception.domain.ExceptionLog;
import org.apache.fineract.infrastructure.exception.dto.ExceptionLogStats;
import org.apache.fineract.infrastructure.exception.repository.ExceptionLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
public class ExceptionLogService {

    private final ExceptionLogRepository exceptionLogRepository;

    public ExceptionLogService(ExceptionLogRepository exceptionLogRepository) {
        this.exceptionLogRepository = exceptionLogRepository;
    }

    public Page<ExceptionLog> getAllExceptionLogs(Pageable pageable) {
        return exceptionLogRepository.findAll(pageable);
    }

    public ExceptionLog getExceptionLogByTraceId(String traceId) {
        return exceptionLogRepository.findByTraceId(traceId);
    }

    public Page<ExceptionLog> getExceptionLogsByType(String exceptionType, Pageable pageable) {
        return exceptionLogRepository.findByExceptionType(exceptionType, pageable);
    }

    public Page<ExceptionLog> getExceptionLogsByPath(String path, Pageable pageable) {
        return exceptionLogRepository.findByRequestPath(path, pageable);
    }

    public Page<ExceptionLog> getExceptionLogsByDateRange(LocalDateTime from, LocalDateTime to, Pageable pageable) {
        return exceptionLogRepository.findByCreatedAtBetween(from, to, pageable);
    }

    @Transactional
    public void deleteExceptionLog(Long id) {
        exceptionLogRepository.deleteById(id);
        log.info("Deleted exception log with id={}", id);
    }

    @Transactional
    public int deleteExceptionLogsOlderThanDays(int days) {
        LocalDateTime cutoffDate = LocalDateTime.now(ZoneId.systemDefault()).minusDays(days);
        int deletedCount = exceptionLogRepository.deleteByCreatedAtBefore(cutoffDate);
        log.info("Deleted {} exception logs older than {} days", deletedCount, days);
        return deletedCount;
    }

    public ExceptionLogStats getExceptionLogStats() {
        long totalCount = exceptionLogRepository.count();

        Page<ExceptionLog> allLogs = exceptionLogRepository.findAll(Pageable.unpaged());
        List<ExceptionLog> logs = allLogs.getContent();

        List<String> topExceptionTypes = logs.stream()
                .collect(Collectors.groupingByConcurrent(ExceptionLog::getExceptionType, Collectors.counting())).entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(5).map(e -> e.getKey() + " (" + e.getValue() + ")")
                .collect(Collectors.toList());

        List<String> topPaths = logs.stream().collect(Collectors.groupingByConcurrent(ExceptionLog::getRequestPath, Collectors.counting()))
                .entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(5)
                .map(e -> e.getKey() + " (" + e.getValue() + ")").collect(Collectors.toList());

        return ExceptionLogStats.builder().totalExceptions(totalCount).topExceptionTypes(topExceptionTypes).topErrorPaths(topPaths)
                .recordsCount(logs.size()).build();
    }
}
