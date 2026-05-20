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

import com.google.gson.Gson;
import jakarta.ws.rs.core.MultivaluedMap;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.FineractContext;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.dataqueries.data.AdvancedRunReportResponse;
import org.apache.fineract.infrastructure.dataqueries.domain.AdvancedRunReportRepository;
import org.apache.fineract.infrastructure.dataqueries.domain.AdvancedRunReportRequest;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdvancedRunReportExecutionServiceImpl implements AdvancedRunReportExecutionService {

    private final PlatformSecurityContext securityContext;
    private final AdvancedRunReportRepository advancedRunReportRepository;
    private final AdvancedRunReportExecutor advancedRunReportExecutor;
    private final Gson gson = new Gson();

    @Override
    @Transactional
    public AdvancedRunReportResponse submitReportRequest(String reportName, MultivaluedMap<String, String> queryParams,
            boolean isSelfServiceUserReport) {

        final AppUser currentUser = securityContext.authenticatedUser();

        Map<String, String> flatParams = new HashMap<>();
        queryParams.forEach((key, values) -> {
            if (values != null && !values.isEmpty()) {
                flatParams.put(key, values.getFirst());
            }
        });

        String serializedParams = gson.toJson(flatParams);

        AdvancedRunReportRequest request = AdvancedRunReportRequest.create(reportName, serializedParams, currentUser.getId());

        request = advancedRunReportRepository.saveAndFlush(request);

        log.info("Created run report request {} for report '{}' by user {}", request.getId(), reportName, currentUser.getId());

        FineractContext context = ThreadLocalContextUtil.getContext();
        String s3ReportPath = generateS3ReportPath(reportName, currentUser.getUsername());

        triggerAsyncExecutionAfterCommit(request.getId(), reportName, flatParams, isSelfServiceUserReport, context, s3ReportPath);

        return AdvancedRunReportResponse.builder().requestId(request.getId()).message("Report request created successfully").statusCode(202)
                .build();
    }

    private void triggerAsyncExecutionAfterCommit(Long requestId, String reportName, Map<String, String> flatParams,
            boolean isSelfServiceUserReport, FineractContext context, String s3ReportPath) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    advancedRunReportExecutor.executeReportAsync(requestId, reportName, flatParams, isSelfServiceUserReport, context,
                            s3ReportPath);
                }
            });
        } else {
            advancedRunReportExecutor.executeReportAsync(requestId, reportName, flatParams, isSelfServiceUserReport, context, s3ReportPath);
        }
    }

    private String generateS3ReportPath(String reportName, String username) {

        String dateFolder = LocalDate.now(ZoneId.systemDefault()).toString();

        String sanitizedReportName = reportName.replaceAll("[^a-zA-Z0-9-_]", "_").toLowerCase(Locale.ROOT);

        String fileName = sanitizedReportName + "_" + UUID.randomUUID() + ".csv";

        return String.format("generated-reports/%s/%s/%s", dateFolder, username, fileName);
    }
}
