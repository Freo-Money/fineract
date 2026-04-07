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
package org.apache.fineract.infrastructure.exception.repository;

import org.apache.fineract.infrastructure.exception.domain.ExceptionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Repository
public interface ExceptionLogRepository extends JpaRepository<ExceptionLog, Long> {

    ExceptionLog findByTraceId(String traceId);

    Page<ExceptionLog> findByExceptionType(String exceptionType, Pageable pageable);

    Page<ExceptionLog> findByRequestPath(String requestPath, Pageable pageable);

    Page<ExceptionLog> findByCreatedAtBetween(LocalDateTime from, LocalDateTime to, Pageable pageable);

    long count();

    @Modifying
    @Transactional
    @Query("DELETE FROM ExceptionLog WHERE createdAt < :date")
    int deleteByCreatedAtBefore(@Param("date") LocalDateTime date);

    long countByExceptionType(String exceptionType);
}
