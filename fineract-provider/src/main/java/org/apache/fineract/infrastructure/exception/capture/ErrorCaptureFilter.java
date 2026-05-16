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
package org.apache.fineract.infrastructure.exception.capture;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Outermost servlet filter that captures every HTTP-visible 5xx response — whether it came from a JAX-RS exception
 * mapper (Fineract's own or the catch-all), Spring Security, the dispatcher itself, or an uncaught exception. Every
 * sink registered as a Spring bean implementing {@link ExceptionSink} receives the record; sinks are independent and
 * one failure does not affect the others.
 *
 * <p>
 * The filter generates a {@code traceId} per request, exposes it via the {@code X-Trace-Id} response header so the
 * client can quote it back to support, and stamps it into MDC so every log line during the request carries it.
 */
@Slf4j
@RequiredArgsConstructor
public class ErrorCaptureFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_MDC_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    /**
     * Request-scoped attribute used by JAX-RS {@code ExceptionMapper}s to share the original Throwable with this filter
     * when the mapper converts it into a 5xx response (so the throwable doesn't propagate out of the chain).
     */
    public static final String REQUEST_ATTR_THROWABLE = "fineract.errorCapture.throwable";

    private final List<ExceptionSink> sinks;

    @Override
    protected void doFilterInternal(@NonNull final HttpServletRequest request, @NonNull final HttpServletResponse response,
            @NonNull final FilterChain chain) throws ServletException, IOException {
        final String traceId = UUID.randomUUID().toString();
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            chain.doFilter(request, response);
            if (response.getStatus() >= 500) {
                final Object stashed = request.getAttribute(REQUEST_ATTR_THROWABLE);
                final Throwable mapped = stashed instanceof Throwable th ? th : null;
                capture(traceId, request, response.getStatus(), mapped);
            }
        } catch (final Throwable t) {
            capture(traceId, request, 500, t);
            throw t;
        } finally {
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    private void capture(final String traceId, final HttpServletRequest request, final int status, final Throwable t) {
        final ExceptionRecord record = ExceptionRecord.builder() //
                .traceId(traceId) //
                .exceptionType(t != null ? t.getClass().getName() : "UnmappedHttp" + status) //
                .message(t != null ? t.getMessage() : "HTTP " + status) //
                .stackTrace(StackTraceFormatter.format(t)) //
                .requestPath(request.getRequestURI()) //
                .requestMethod(request.getMethod()) //
                .statusCode(status) //
                .throwable(t) //
                .build();
        log.error("5xx captured traceId={} path={} method={} status={}", traceId, record.requestPath(), record.requestMethod(), status, t);
        for (final ExceptionSink sink : sinks) {
            try {
                sink.record(record);
            } catch (final Throwable e) {
                log.error("Sink {} failed to record traceId={}", sink.getClass().getSimpleName(), traceId, e);
            }
        }
    }
}
