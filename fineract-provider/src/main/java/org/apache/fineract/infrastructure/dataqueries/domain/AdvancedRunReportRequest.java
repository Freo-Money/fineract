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
package org.apache.fineract.infrastructure.dataqueries.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.dataqueries.data.AdvancedRunReportStatus;

@Entity
@Table(name = "m_runreport_request")
@Getter
@Setter
@NoArgsConstructor
public class AdvancedRunReportRequest extends AbstractPersistableCustom<Long> {

    @Column(name = "report_name", nullable = false, length = 100)
    private String reportName;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "report_params", columnDefinition = "TEXT")
    private String reportParams;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AdvancedRunReportStatus status;

    @Column(name = "s3_file_path", length = 1024)
    private String s3FilePath;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    public static AdvancedRunReportRequest create(String reportName, String reportParams, Long userId) {
        AdvancedRunReportRequest request = new AdvancedRunReportRequest();
        request.reportName = reportName;
        request.reportParams = reportParams;
        request.status = AdvancedRunReportStatus.PENDING;
        request.userId = userId;
        request.createdAt = DateUtils.getOffsetDateTimeOfTenant();
        return request;
    }

    public void markRunning() {
        this.status = AdvancedRunReportStatus.RUNNING;
        this.startedAt = DateUtils.getOffsetDateTimeOfTenant();
    }

    public void markCompleted(String s3FilePath) {
        this.status = AdvancedRunReportStatus.COMPLETED;
        this.s3FilePath = s3FilePath;
        this.completedAt = DateUtils.getOffsetDateTimeOfTenant();
    }

    public void markFailed(String errorMessage) {
        this.status = AdvancedRunReportStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = DateUtils.getOffsetDateTimeOfTenant();
    }
}
