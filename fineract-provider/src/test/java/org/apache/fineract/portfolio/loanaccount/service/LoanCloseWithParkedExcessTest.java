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
package org.apache.fineract.portfolio.loanaccount.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanTransactionValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Closing a loan writes off an outstanding within the in-arrears tolerance. While money is parked that outstanding is
 * already covered, so close is refused, as write-off and close-as-rescheduled are.
 */
@ExtendWith(MockitoExtension.class)
class LoanCloseWithParkedExcessTest {

    private static final Long LOAN_ID = 1L;

    @Mock
    private LoanAssembler loanAssembler;

    @Mock
    private LoanTransactionValidator loanTransactionValidator;

    @InjectMocks
    private LoanWritePlatformServiceJpaRepositoryImpl loanWritePlatformService;

    @Test
    void closeIsRefusedWhileMoneyIsParked() {
        final Loan loan = mock(Loan.class);
        when(loan.getTotalExcessPaymentAmount()).thenReturn(BigDecimal.valueOf(500));
        when(loanAssembler.assembleFrom(LOAN_ID)).thenReturn(loan);

        final GeneralPlatformDomainRuleException exception = assertThrows(GeneralPlatformDomainRuleException.class,
                () -> loanWritePlatformService.closeLoan(LOAN_ID, mock(JsonCommand.class)));

        assertEquals("error.msg.loan.close.not.allowed.with.parked.excess", exception.getGlobalisationMessageCode());
    }
}
