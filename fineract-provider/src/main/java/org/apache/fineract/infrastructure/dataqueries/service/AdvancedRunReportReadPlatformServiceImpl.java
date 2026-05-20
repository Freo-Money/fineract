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

import jakarta.persistence.EntityNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.domain.JdbcSupport;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.PaginationHelper;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.dataqueries.data.AdvancedRunReportData;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.security.utils.ColumnValidator;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdvancedRunReportReadPlatformServiceImpl implements AdvancedRunReportReadPlatformService {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformSecurityContext context;
    private final PaginationHelper paginationHelper;
    private final DatabaseSpecificSQLGenerator sqlGenerator;
    private final ColumnValidator columnValidator;

    private final RunReportRequestMapper mapper = new RunReportRequestMapper();

    @Override
    public Page<AdvancedRunReportData> retrieveAllUserReports(final SearchParameters searchParameters) {
        final AppUser currentUser = this.context.authenticatedUser();
        final List<Object> params = new ArrayList<>();
        final StringBuilder sql = new StringBuilder(300);

        params.add(currentUser.getId());

        sql.append("select ").append(this.sqlGenerator.calcFoundRows()).append(" ").append(this.mapper.schema())
                .append(" where rr.user_id = ? ");

        applyFilters(searchParameters, sql, params);
        applySorting(searchParameters, sql);
        applyPagination(searchParameters, sql);

        return this.paginationHelper.fetchPage(this.jdbcTemplate, sql.toString(), params.toArray(), this.mapper);
    }

    private void applyFilters(final SearchParameters searchParameters, final StringBuilder sql, final List<Object> params) {
        if (searchParameters == null) {
            return;
        }

        if (StringUtils.isNotBlank(searchParameters.getName())) {
            sql.append(" and rr.report_name like ? ");
            params.add("%" + searchParameters.getName() + "%");
        }
    }

    private void applySorting(final SearchParameters searchParameters, final StringBuilder sql) {
        if (searchParameters != null && searchParameters.hasOrderBy()) {
            sql.append(" order by ").append(searchParameters.getOrderBy());
            this.columnValidator.validateSqlInjection(sql.toString(), searchParameters.getOrderBy());

            if (searchParameters.hasSortOrder()) {
                sql.append(" ").append(searchParameters.getSortOrder());
                this.columnValidator.validateSqlInjection(sql.toString(), searchParameters.getSortOrder());
            }
        } else {
            sql.append(" order by rr.created_at desc ");
        }
    }

    private void applyPagination(final SearchParameters searchParameters, final StringBuilder sql) {
        if (searchParameters == null || !searchParameters.hasLimit()) {
            return;
        }

        sql.append(" ");
        if (searchParameters.hasOffset()) {
            sql.append(this.sqlGenerator.limit(searchParameters.getLimit(), searchParameters.getOffset()));
        } else {
            sql.append(this.sqlGenerator.limit(searchParameters.getLimit()));
        }
    }

    @Override
    public AdvancedRunReportData retrieveReportForDownload(final Long reportId) {
        final AppUser currentUser = this.context.authenticatedUser();
        final List<Object> params = new ArrayList<>();

        params.add(reportId);
        params.add(currentUser.getId());

        final StringBuilder sql = new StringBuilder(300);
        sql.append("select ").append(this.mapper.schema()).append(" where rr.id = ? and rr.user_id = ? ");

        final AdvancedRunReportData reportData = this.jdbcTemplate.queryForObject(sql.toString(), params.toArray(), this.mapper);

        if (reportData == null) {
            throw new EntityNotFoundException("RunReportRequest with id " + reportId + " not found");
        }

        if (!isCompleted(reportData.getStatus())) {
            throw new IllegalStateException(
                    "Report download is only available for completed reports. Current status: " + reportData.getStatus());
        }

        return reportData;
    }

    @Override
    public AdvancedRunReportData retrieveReportRequestStatus(final Long reportId) {
        final AppUser currentUser = this.context.authenticatedUser();
        final List<Object> params = new ArrayList<>();

        params.add(reportId);
        params.add(currentUser.getId());

        final StringBuilder sql = new StringBuilder(300);
        sql.append("select ").append(this.mapper.schema()).append(" where rr.id = ? and rr.user_id = ? ");

        final AdvancedRunReportData reportData = this.jdbcTemplate.queryForObject(sql.toString(), params.toArray(), this.mapper);

        if (reportData == null) {
            throw new EntityNotFoundException("RunReportRequest with id " + reportId + " not found");
        }

        return reportData;
    }

    private boolean isCompleted(final String status) {
        return "COMPLETED".equals(status);
    }

    private static final class RunReportRequestMapper implements RowMapper<AdvancedRunReportData> {

        public String schema() {
            return " rr.id as id, " + " rr.report_name as reportName, " + " rr.report_params as reportParams, " + " rr.status as status, "
                    + " rr.s3_file_path as s3FilePath, " + " rr.error_message as errorMessage, " + " rr.user_id as userId, "
                    + " rr.created_at as createdAt, " + " rr.started_at as startedAt, " + " rr.completed_at as completedAt "
                    + " from m_runreport_request rr";
        }

        @Override
        public AdvancedRunReportData mapRow(final ResultSet rs, final int rowNum) throws SQLException {
            final Long id = JdbcSupport.getLong(rs, "id");
            final String reportName = rs.getString("reportName");
            final String status = rs.getString("status");
            final String s3FilePath = rs.getString("s3FilePath");
            final String errorMessage = rs.getString("errorMessage");
            final Long userId = JdbcSupport.getLong(rs, "userId");
            final java.time.OffsetDateTime createdAt = JdbcSupport.getDateTime(rs, "createdAt") != null
                    ? JdbcSupport.getDateTime(rs, "createdAt").toOffsetDateTime()
                    : null;
            final java.time.OffsetDateTime startedAt = JdbcSupport.getDateTime(rs, "startedAt") != null
                    ? JdbcSupport.getDateTime(rs, "startedAt").toOffsetDateTime()
                    : null;
            final java.time.OffsetDateTime completedAt = JdbcSupport.getDateTime(rs, "completedAt") != null
                    ? JdbcSupport.getDateTime(rs, "completedAt").toOffsetDateTime()
                    : null;

            return AdvancedRunReportData.builder().id(id).reportName(reportName).status(status).s3FilePath(s3FilePath)
                    .errorMessage(errorMessage).userId(userId).createdAt(createdAt).startedAt(startedAt).completedAt(completedAt).build();
        }
    }
}
