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

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.exception.domain.ExceptionLog;
import org.apache.fineract.infrastructure.exception.dto.ExceptionLogStats;
import org.apache.fineract.infrastructure.exception.service.ExceptionLogService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Path("/v1/exception-logs")
@Component
@Consumes({ MediaType.APPLICATION_JSON })
@Produces({ MediaType.APPLICATION_JSON })
@Slf4j
public class ExceptionLogApiResource {

    private final ExceptionLogService exceptionLogService;

    public ExceptionLogApiResource(ExceptionLogService exceptionLogService) {
        this.exceptionLogService = exceptionLogService;
    }

    @GET
    public Page<ExceptionLog> getAllExceptionLogs(@DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ExceptionLog> logs = exceptionLogService.getAllExceptionLogs(pageable);
        log.info("Retrieved {} exception logs", logs.getTotalElements());
        return logs;
    }

    @GET
    @Path("/{traceId}")
    public ExceptionLog getExceptionLogByTraceId(@PathParam("traceId") String traceId) {
        ExceptionLog exceptionLog = exceptionLogService.getExceptionLogByTraceId(traceId);
        if (exceptionLog == null) {
            log.warn("Exception log not found for traceId={}", traceId);
            throw new NotFoundException("Exception log not found for traceId=" + traceId);
        }
        return exceptionLog;
    }

    @GET
    @Path("/by-type/{exceptionType}")
    public Page<ExceptionLog> getExceptionLogsByType(@PathParam("exceptionType") String exceptionType,
            @DefaultValue("0") @QueryParam("page") int page, @DefaultValue("20") @QueryParam("size") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ExceptionLog> logs = exceptionLogService.getExceptionLogsByType(exceptionType, pageable);
        log.info("Retrieved {} exception logs of type={}", logs.getTotalElements(), exceptionType);
        return logs;
    }

    @GET
    @Path("/by-path")
    public Page<ExceptionLog> getExceptionLogsByPath(@QueryParam("path") String path, @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ExceptionLog> logs = exceptionLogService.getExceptionLogsByPath(path, pageable);
        log.info("Retrieved {} exception logs for path={}", logs.getTotalElements(), path);
        return logs;
    }

    @GET
    @Path("/by-date-range")
    public Page<ExceptionLog> getExceptionLogsByDateRange(@QueryParam("from") String from, @QueryParam("to") String to,
            @DefaultValue("0") @QueryParam("page") int page, @DefaultValue("20") @QueryParam("size") int size) {
        DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
        LocalDateTime fromDateTime = LocalDateTime.parse(from, formatter);
        LocalDateTime toDateTime = LocalDateTime.parse(to, formatter);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ExceptionLog> logs = exceptionLogService.getExceptionLogsByDateRange(fromDateTime, toDateTime, pageable);
        log.info("Retrieved {} exception logs from {} to {}", logs.getTotalElements(), from, to);
        return logs;
    }

    @DELETE
    @Path("/{id}")
    public Response deleteExceptionLog(@PathParam("id") Long id) {
        exceptionLogService.deleteExceptionLog(id);
        log.info("Deleted exception log with id={}", id);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/cleanup/{days}")
    public String cleanupOldExceptionLogs(@PathParam("days") int days) {
        int deletedCount = exceptionLogService.deleteExceptionLogsOlderThanDays(days);
        log.info("Deleted {} exception logs older than {} days", deletedCount, days);
        return "Deleted " + deletedCount + " exception logs older than " + days + " days";
    }

    @GET
    @Path("/stats/summary")
    public ExceptionLogStats getExceptionLogStats() {
        return exceptionLogService.getExceptionLogStats();
    }
}
