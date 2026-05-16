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
package org.apache.fineract.infrastructure.exception.api;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.infrastructure.configuration.data.GlobalConfigurationPropertyData;
import org.apache.fineract.infrastructure.configuration.service.ConfigurationReadPlatformService;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.exception.domain.ExceptionLog;
import org.apache.fineract.infrastructure.exception.dto.ExceptionLogResponse;
import org.apache.fineract.infrastructure.exception.exception.ExceptionLogNotFoundException;
import org.apache.fineract.infrastructure.exception.service.ExceptionLogService;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Path("/v1/exception-logs")
@Component
@Produces({ MediaType.APPLICATION_JSON })
@Slf4j
@RequiredArgsConstructor
public class ExceptionLogApiResource {

    private static final String RESOURCE_NAME_FOR_PERMISSIONS = "EXCEPTIONLOG";
    private static final int MAX_CLEANUP_DAYS = 3650;
    private static final Sort CREATED_DATE_DESC_SORT = Sort.by(Sort.Direction.DESC, "createdDate");

    private static final String CODE_PARAM_BLANK = "error.msg.exception.log.parameter.blank";
    private static final String CODE_PARAM_INVALID_ISO_DATETIME = "error.msg.exception.log.parameter.invalid.iso.datetime";
    private static final String CODE_DATE_RANGE_INVALID = "error.msg.exception.log.date.range.invalid";
    private static final String CODE_CLEANUP_DAYS_OUT_OF_RANGE = "error.msg.exception.log.cleanup.days.out.of.range";
    private static final String CODE_CLEANUP_DAYS_NOT_CONFIGURED = "error.msg.exception.log.cleanup.days.not.configured";

    private final ExceptionLogService exceptionLogService;
    private final PlatformSecurityContext context;
    private final ConfigurationReadPlatformService configurationReadPlatformService;
    private final ToApiJsonSerializer<Object> toApiJsonSerializer;
    private final ApiRequestParameterHelper apiRequestParameterHelper;

    @GET
    public String getAllExceptionLogs(@Context final UriInfo uriInfo, @DefaultValue("0") @QueryParam("page") final int page,
            @DefaultValue("20") @QueryParam("size") final int size) {
        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);

        final Pageable pageable = PageRequest.of(page, size, CREATED_DATE_DESC_SORT);
        final Page<ExceptionLog> logs = this.exceptionLogService.getAllExceptionLogs(pageable);
        log.debug("Retrieved {} exception logs", logs.getTotalElements());
        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.toApiJsonSerializer.serialize(settings, logs);
    }

    @GET
    @Path("/by-type/{exceptionType}")
    public String getExceptionLogsByType(@Context final UriInfo uriInfo, @PathParam("exceptionType") final String exceptionType,
            @DefaultValue("0") @QueryParam("page") final int page, @DefaultValue("20") @QueryParam("size") final int size,
            @DefaultValue("exact") @QueryParam("match") final String match) {
        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        requireNonBlank(exceptionType, "exceptionType");

        final Pageable pageable = PageRequest.of(page, size, CREATED_DATE_DESC_SORT);
        final String trimmedType = exceptionType.trim();
        final Page<ExceptionLog> logs;

        if ("contains".equalsIgnoreCase(match)) {
            logs = this.exceptionLogService.searchExceptionLogsByTypeContaining(trimmedType, pageable);
        } else {
            logs = this.exceptionLogService.getExceptionLogsByType(trimmedType, pageable);
        }
        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.toApiJsonSerializer.serialize(settings, logs);
    }

    @GET
    @Path("/by-path")
    public String getExceptionLogsByPath(@Context final UriInfo uriInfo, @QueryParam("path") final String path,
            @DefaultValue("0") @QueryParam("page") final int page, @DefaultValue("20") @QueryParam("size") final int size) {
        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        requireNonBlank(path, "path");

        final Pageable pageable = PageRequest.of(page, size, CREATED_DATE_DESC_SORT);
        final Page<ExceptionLog> logs = this.exceptionLogService.getExceptionLogsByPath(path.trim(), pageable);
        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.toApiJsonSerializer.serialize(settings, logs);
    }

    @GET
    @Path("/by-date-range")
    public String getExceptionLogsByDateRange(@Context final UriInfo uriInfo, @QueryParam("from") final String from,
            @QueryParam("to") final String to, @DefaultValue("0") @QueryParam("page") final int page,
            @DefaultValue("20") @QueryParam("size") final int size) {
        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);

        final OffsetDateTime fromDateTime = parseIsoDateTime(from, "from");
        final OffsetDateTime toDateTime = parseIsoDateTime(to, "to");
        if (fromDateTime.isAfter(toDateTime)) {
            throw validationException(CODE_DATE_RANGE_INVALID, "'from' must be less than or equal to 'to'", "from");
        }

        final Pageable pageable = PageRequest.of(page, size, CREATED_DATE_DESC_SORT);
        final Page<ExceptionLog> logs = this.exceptionLogService.getExceptionLogsByDateRange(fromDateTime, toDateTime, pageable);
        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.toApiJsonSerializer.serialize(settings, logs);
    }

    @GET
    @Path("/by-trace/{traceId}")
    public String getExceptionLogByTraceId(@PathParam("traceId") final String traceId, @Context final UriInfo uriInfo) {
        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        requireNonBlank(traceId, "traceId");

        final ExceptionLog exceptionLog = this.exceptionLogService.getExceptionLogByTraceId(traceId)
                .orElseThrow(() -> new ExceptionLogNotFoundException(traceId));
        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.toApiJsonSerializer.serialize(settings, exceptionLog);
    }

    @DELETE
    @Path("/cleanup")
    public String cleanupOldExceptionLogs(@Context final UriInfo uriInfo, @QueryParam("days") final Integer daysOverride) {
        this.context.authenticatedUser().validateHasDeletePermission(RESOURCE_NAME_FOR_PERMISSIONS);

        final int days = resolveCleanupDays(daysOverride);
        final int deletedCount = this.exceptionLogService.deleteExceptionLogsOlderThanDays(days);
        log.info("Deleted {} exception logs older than {} days", deletedCount, days);
        final ExceptionLogResponse cleanupResult = ExceptionLogResponse.builder().deletedCount(deletedCount).olderThanDays(days)
                .message("Deleted " + deletedCount + " exception logs older than " + days + " days").build();
        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.toApiJsonSerializer.serialize(settings, cleanupResult);
    }

    private int resolveCleanupDays(final Integer daysOverride) {
        if (daysOverride != null) {
            if (daysOverride <= 0 || daysOverride > MAX_CLEANUP_DAYS) {
                throw validationException(CODE_CLEANUP_DAYS_OUT_OF_RANGE, "days must be between 1 and " + MAX_CLEANUP_DAYS, "days");
            }
            return daysOverride;
        }
        final GlobalConfigurationPropertyData cleanupDaysConfig = this.configurationReadPlatformService
                .retrieveGlobalConfiguration(GlobalConfigurationConstants.PURGE_EXCEPTION_LOGS_OLDER_THAN_DAYS);
        if (cleanupDaysConfig.getValue() == null) {
            throw validationException(CODE_CLEANUP_DAYS_NOT_CONFIGURED,
                    "Cleanup configuration must define a numeric days value, or pass ?days=", "days");
        }
        final int days = cleanupDaysConfig.getValue().intValue();
        if (days <= 0 || days > MAX_CLEANUP_DAYS) {
            throw validationException(CODE_CLEANUP_DAYS_OUT_OF_RANGE, "Configured cleanup days must be between 1 and " + MAX_CLEANUP_DAYS,
                    "days");
        }
        return days;
    }

    private OffsetDateTime parseIsoDateTime(final String rawValue, final String paramName) {
        requireNonBlank(rawValue, paramName);
        try {
            return OffsetDateTime.parse(rawValue, DateTimeFormatter.ISO_DATE_TIME);
        } catch (DateTimeParseException ex) {
            throw validationException(CODE_PARAM_INVALID_ISO_DATETIME,
                    paramName + " must be a valid ISO-8601 datetime, e.g. 2026-04-24T12:30:00", paramName, ex);
        }
    }

    private void requireNonBlank(final String value, final String paramName) {
        if (value == null || value.trim().isEmpty()) {
            throw validationException(CODE_PARAM_BLANK, paramName + " must not be blank", paramName, null);
        }
    }

    private PlatformApiDataValidationException validationException(final String code, final String defaultMessage, final String paramName) {
        return validationException(code, defaultMessage, paramName, null);
    }

    private PlatformApiDataValidationException validationException(final String code, final String defaultMessage, final String paramName,
            final Throwable cause) {
        final List<ApiParameterError> errors = new ArrayList<>();
        errors.add(ApiParameterError.parameterError(code, defaultMessage, paramName));
        return cause == null ? new PlatformApiDataValidationException(errors) : new PlatformApiDataValidationException(errors, cause);
    }

}
