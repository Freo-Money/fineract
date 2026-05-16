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
 * Strategy for persisting a captured {@link ExceptionRecord}. Implementations are discovered by Spring and fan out by
 * the capture filter. Add a new sink (e.g., Sentry) by implementing this interface and exposing it as a Spring bean —
 * no changes required to the filter or to other sinks.
 *
 * <p>
 * Implementations must be defensive: throwing from {@code record} would let one sink's failure abort the others.
 */
public interface ExceptionSink {

    /**
     * Persist the given record. Must not propagate exceptions to the caller.
     */
    void record(ExceptionRecord record);
}
