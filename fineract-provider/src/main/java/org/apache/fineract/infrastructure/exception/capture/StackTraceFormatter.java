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

/**
 * Renders a Throwable into a bounded textual stack trace suitable for the {@code exception_log.stack_trace} column.
 * Bounded to avoid runaway payloads from deep cause chains or noisy frame lists.
 */
final class StackTraceFormatter {

    private static final int MAX_CAUSE_DEPTH = 5;
    private static final int MAX_FRAMES_PER_CAUSE = 50;
    private static final int MAX_TOTAL_LENGTH = 8000;

    private StackTraceFormatter() {}

    static String format(final Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;

        while (current != null && depth < MAX_CAUSE_DEPTH && sb.length() < MAX_TOTAL_LENGTH) {
            sb.append(depth == 0 ? "" : "Caused by: ").append(current.getClass().getName());
            if (current.getMessage() != null) {
                sb.append(": ").append(current.getMessage());
            }
            sb.append('\n');

            final StackTraceElement[] elements = current.getStackTrace();
            final int limit = Math.min(elements.length, MAX_FRAMES_PER_CAUSE);
            for (int i = 0; i < limit && sb.length() < MAX_TOTAL_LENGTH; i++) {
                sb.append("\tat ").append(elements[i]).append('\n');
            }
            if (elements.length > limit) {
                sb.append("\t... ").append(elements.length - limit).append(" more\n");
            }
            current = current.getCause();
            depth++;
        }
        if (current != null) {
            sb.append("\t... (cause chain truncated at depth ").append(MAX_CAUSE_DEPTH).append(")\n");
        }
        return sb.toString();
    }
}
