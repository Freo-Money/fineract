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
package org.apache.fineract.infrastructure.jobs.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.Trigger;
import org.quartz.TriggerKey;

/**
 * The trigger listener must never let an exception reach Quartz: a throwing TriggerListener aborts the fire without
 * completing the trigger in the job store, which leaves a {@code @DisallowConcurrentExecution} job permanently blocked
 * in RAMJobStore on that node. Any failure has to surface as a veto instead.
 */
class SchedulerTriggerListenerTest {

    private static final String TENANT = "default";

    private TenantDetailsService tenantDetailsService;
    private SchedulerVetoer schedulerVetoer;
    private SchedulerTriggerListener listener;
    private Trigger trigger;
    private JobExecutionContext context;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.reset();
        tenantDetailsService = mock(TenantDetailsService.class);
        schedulerVetoer = mock(SchedulerVetoer.class);
        listener = new SchedulerTriggerListener(tenantDetailsService, schedulerVetoer);

        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(SchedulerServiceConstants.TENANT_IDENTIFIER, TENANT);
        trigger = mock(Trigger.class);
        when(trigger.getJobDataMap()).thenReturn(jobDataMap);
        when(trigger.getKey()).thenReturn(TriggerKey.triggerKey("someJob", "someGroup"));
        context = mock(JobExecutionContext.class);

        when(tenantDetailsService.loadTenantById(TENANT))
                .thenReturn(new FineractPlatformTenant(1L, TENANT, "Default", "Asia/Kolkata", null));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void vetoDecisionIsPassedThrough() {
        when(schedulerVetoer.veto(trigger, context)).thenReturn(false);
        assertFalse(listener.vetoJobExecution(trigger, context));

        when(schedulerVetoer.veto(trigger, context)).thenReturn(true);
        assertTrue(listener.vetoJobExecution(trigger, context));
    }

    @Test
    void exceptionFromVetoerFailsClosedAsVeto() {
        when(schedulerVetoer.veto(trigger, context)).thenThrow(new IllegalStateException("claim failed"));

        assertTrue(listener.vetoJobExecution(trigger, context));
        assertNull(ThreadLocalContextUtil.getTenant(), "tenant context must be reset even on the failure path");
    }

    @Test
    void exceptionWhileLoadingTenantFailsClosedAsVeto() {
        when(tenantDetailsService.loadTenantById(any())).thenThrow(new IllegalStateException("tenant lookup failed"));

        assertTrue(listener.vetoJobExecution(trigger, context));
        assertNull(ThreadLocalContextUtil.getTenant());
    }
}
