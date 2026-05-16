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

import lombok.Builder;

/**
 * Immutable value object describing a single captured 5xx event. Passed to every {@link ExceptionSink}; sinks decide
 * how to persist it (database, Sentry, file, etc.).
 *
 * <p>
 * {@code throwable} is nullable: a 5xx response can be produced by an exception mapper without an exception reaching
 * the filter (e.g., a controller manually returns a 500 Response).
 */
@Builder
public record ExceptionRecord(String traceId, String exceptionType, String message, String stackTrace, String requestPath,
        String requestMethod, int statusCode, Throwable throwable) {
}
