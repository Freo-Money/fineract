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
package org.apache.fineract.portfolio.loanaccount.domain.bulkreprocess;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface LoanBulkReprocessRunRepository extends JpaRepository<LoanBulkReprocessRun, Long> {

    // markRunning and finish are invoked from JobExecutionListener callbacks, which run
    // outside any ambient transaction. @Transactional ensures the @Modifying UPDATE has one.
    @Modifying
    @Transactional
    @Query("""
            update LoanBulkReprocessRun r
               set r.status = :status,
                   r.startedDate = :startedDate
             where r.id = :runId
            """)
    int markRunning(@Param("runId") Long runId, @Param("status") LoanBulkReprocessRunStatus status,
            @Param("startedDate") LocalDateTime startedDate);

    @Modifying
    @Transactional
    @Query("""
            update LoanBulkReprocessRun r
               set r.processedCount = r.processedCount + :processedDelta,
                   r.failedCount = r.failedCount + :failedDelta
             where r.id = :runId
            """)
    int incrementCounts(@Param("runId") Long runId, @Param("processedDelta") int processedDelta, @Param("failedDelta") int failedDelta);

    // Used by releasePendingItems to reconcile run-row counters from the authoritative item table after a stuck
    // run is closed by the operator (handles drift caused by chunk-tx rollback after per-loan REQUIRES_NEW commits).
    @Modifying
    @Transactional
    @Query("""
            update LoanBulkReprocessRun r
               set r.processedCount = :processedCount,
                   r.failedCount = :failedCount
             where r.id = :runId
            """)
    int setCounts(@Param("runId") Long runId, @Param("processedCount") int processedCount, @Param("failedCount") int failedCount);

    @Modifying
    @Transactional
    @Query("""
            update LoanBulkReprocessRun r
               set r.status = :status,
                   r.finishedDate = :finishedDate,
                   r.errorMessage = :errorMessage
             where r.id = :runId
            """)
    int finish(@Param("runId") Long runId, @Param("status") LoanBulkReprocessRunStatus status,
            @Param("finishedDate") LocalDateTime finishedDate, @Param("errorMessage") String errorMessage);
}
