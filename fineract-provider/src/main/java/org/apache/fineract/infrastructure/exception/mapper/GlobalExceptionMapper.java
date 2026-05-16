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
package org.apache.fineract.infrastructure.exception.mapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.data.ApiGlobalErrorResponse;
import org.apache.fineract.infrastructure.exception.capture.ErrorCaptureFilter;
import org.slf4j.MDC;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Catch-all JAX-RS mapper invoked when no more specific {@link ExceptionMapper} handles a thrown exception. Its only
 * responsibilities are:
 * <ol>
 * <li>For 4xx {@link WebApplicationException}s with an attached response, pass the original response through unchanged
 * so existing 4xx contracts (status, headers, body) are preserved.</li>
 * <li>For 5xx (or any other unmapped Throwable), produce a standard {@link ApiGlobalErrorResponse} envelope and stash
 * the throwable as a request attribute so {@link ErrorCaptureFilter} can persist it with full stack trace.</li>
 * </ol>
 *
 * The mapper does <strong>not</strong> persist anything itself — capture lives in the filter so it can see 5xx from
 * specific Fineract mappers, Spring Security, and the dispatcher too.
 */
@Provider
@Component
@Scope("singleton")
@Slf4j
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    @Context
    private HttpServletRequest request;

    @Override
    public Response toResponse(final Throwable ex) {
        if (ex instanceof WebApplicationException wae && wae.getResponse() != null) {
            final Response original = wae.getResponse();
            if (original.getStatus() < 500) {
                return original;
            }
        }
        if (request != null) {
            request.setAttribute(ErrorCaptureFilter.REQUEST_ATTR_THROWABLE, ex);
        }
        final String traceId = MDC.get(ErrorCaptureFilter.TRACE_ID_MDC_KEY);
        final String developerMessage = ex.getMessage() == null || ex.getMessage().isBlank() ? "Internal Server Error" : ex.getMessage();
        final ApiGlobalErrorResponse envelope = ApiGlobalErrorResponse.serverSideError("error.msg.platform.server.side.error.trace",
                developerMessage, traceId);
        return Response.status(Status.INTERNAL_SERVER_ERROR).entity(envelope).type(MediaType.APPLICATION_JSON).build();
    }
}
