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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Persists captured 5xx events to the tenant database. Performs the tenant-context guard BEFORE delegating to
 * {@link ExceptionLogWriter}, so the transactional proxy never tries to open a connection against a tenant-aware
 * DataSource without a bound tenant.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JpaExceptionSink implements ExceptionSink {

    private final ExceptionLogWriter writer;

    @Override
    public void record(final ExceptionRecord r) {
        if (ThreadLocalContextUtil.getContext() == null) {
            log.warn("Skipping DB capture for 5xx without tenant context. traceId={} path={} status={}", r.traceId(), r.requestPath(),
                    r.statusCode());
            return;
        }
        try {
            writer.write(r);
        } catch (Exception e) {
            log.error("Failed to persist exception log. traceId={} path={}", r.traceId(), r.requestPath(), e);
        }
    }
}
