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

import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.dataqueries.domain.AdvancedRunReportRepository;
import org.apache.fineract.infrastructure.dataqueries.domain.AdvancedRunReportRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdvancedRunReportStatusService {

    private final AdvancedRunReportRepository advancedRunReportRepository;

    @Transactional
    public void updaterequestStatus(Long requestId, boolean isRunning, String errorMessage, String s3ReportPath) {
        AdvancedRunReportRequest request = advancedRunReportRepository.findById(requestId)
                .orElseThrow(() -> new IllegalStateException("runreport request not found: " + requestId));

        if (isRunning) {
            request.markRunning();
        } else if (errorMessage != null) {
            request.markFailed(errorMessage);
        } else {
            request.markCompleted(s3ReportPath);
        }

        advancedRunReportRepository.save(request);
    }
}
