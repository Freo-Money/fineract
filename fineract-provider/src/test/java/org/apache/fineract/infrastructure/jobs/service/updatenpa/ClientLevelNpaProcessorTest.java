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
package org.apache.fineract.infrastructure.jobs.service.updatenpa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.portfolio.client.npa.service.ClientNpaWritePlatformService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClientLevelNpaProcessorTest {

    @Mock
    private ClientNpaWritePlatformService clientNpaWritePlatformService;
    @Mock
    private ConfigurationDomainService configurationDomainService;

    @InjectMocks
    private ClientLevelNpaProcessor underTest;

    // enable-client-npa selects exactly one processor for the job's reconcile pass; the two must never both be active,
    // or two passes would write m_loan.is_npa from the same arrears data under different scopes.
    @Test
    void onlyClientLevelIsActiveWhenClientNpaEnabled() {
        when(configurationDomainService.isClientNpaEnabled()).thenReturn(true);

        assertTrue(underTest.isEnabled());
        assertFalse(new LoanLevelNpaProcessor(configurationDomainService, null, null, null, null).isEnabled());
    }

    @Test
    void onlyLoanLevelIsActiveWhenClientNpaDisabled() {
        when(configurationDomainService.isClientNpaEnabled()).thenReturn(false);

        assertFalse(underTest.isEnabled());
        assertTrue(new LoanLevelNpaProcessor(configurationDomainService, null, null, null, null).isEnabled());
    }
}
