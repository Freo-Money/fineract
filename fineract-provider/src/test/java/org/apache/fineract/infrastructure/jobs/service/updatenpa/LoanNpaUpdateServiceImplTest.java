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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.apache.fineract.infrastructure.core.service.database.RoutingDataSourceService;
import org.apache.fineract.infrastructure.core.service.database.RoutingDataSourceServiceFactory;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaRepository;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.service.LoanAccrualsProcessingService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoanNpaUpdateServiceImplTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 30);

    @Mock
    private RoutingDataSourceServiceFactory dataSourceServiceFactory;
    @Mock
    private RoutingDataSourceService routingDataSourceService;
    @Mock
    private DataSource dataSource;
    @Mock
    private LoanAccrualsProcessingService loanAccrualsProcessingService;
    @Mock
    private LoanRepositoryWrapper loanRepositoryWrapper;
    @Mock
    private ClientNpaRepository clientNpaRepository;
    @Mock
    private Loan loan;
    @Mock
    private LoanProduct product;

    @InjectMocks
    private LoanNpaUpdateServiceImpl underTest;

    @BeforeEach
    void setUp() {
        when(dataSourceServiceFactory.determineDataSourceService()).thenReturn(routingDataSourceService);
        when(routingDataSourceService.retrieveDataSource()).thenReturn(dataSource);
    }

    @Test
    void wouldBeLoanLevelNpaFalseWhenProductHasNoOverdueDays() {
        when(loan.loanProduct()).thenReturn(product);
        when(product.getOverdueDaysForNPA()).thenReturn(null);
        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);

        assertFalse(underTest.wouldBeLoanLevelNpa(loan, BUSINESS_DATE));
    }

    @Test
    void wouldBeLoanLevelNpaTrueWhenArrearsExceedThreshold() {
        when(loan.loanProduct()).thenReturn(product);
        when(product.getOverdueDaysForNPA()).thenReturn(90);
        when(product.isAccountMovesOutOfNPAOnlyOnArrearsCompletion()).thenReturn(false);
        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        when(loan.getId()).thenReturn(100L);

        try (MockedConstruction<JdbcTemplate> ignored = mockConstruction(JdbcTemplate.class, (mock, context) -> when(
                mock.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of(BUSINESS_DATE.minusDays(120))))) {
            assertTrue(underTest.wouldBeLoanLevelNpa(loan, BUSINESS_DATE));
        }
    }

    @Test
    void wouldBeLoanLevelNpaFalseForSubThresholdArrearsOnArrearsCompletionProductWhenLoanNotNpa() {
        // The arrears-completion flag governs exit only; a loan that was never NPA does not become NPA from
        // sub-threshold arrears
        when(loan.loanProduct()).thenReturn(product);
        when(product.getOverdueDaysForNPA()).thenReturn(90);
        when(product.isAccountMovesOutOfNPAOnlyOnArrearsCompletion()).thenReturn(true);
        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        when(loan.isNpa()).thenReturn(false);
        when(loan.getId()).thenReturn(100L);

        try (MockedConstruction<JdbcTemplate> ignored = mockConstruction(JdbcTemplate.class,
                (mock, context) -> when(mock.query(anyString(), any(RowMapper.class), any()))
                        .thenReturn(List.of(BUSINESS_DATE.minusDays(1))))) {
            assertFalse(underTest.wouldBeLoanLevelNpa(loan, BUSINESS_DATE));
        }
    }

    @Test
    void wouldBeLoanLevelNpaTrueForAnyArrearsOnArrearsCompletionProductWhenLoanAlreadyNpa() {
        // Once NPA, an arrears-completion loan stays NPA while any arrears remain (including under contagion —
        // deliberate conservative exit until independent-vs-contagion cause is modeled per loan)
        when(loan.loanProduct()).thenReturn(product);
        when(product.getOverdueDaysForNPA()).thenReturn(90);
        when(product.isAccountMovesOutOfNPAOnlyOnArrearsCompletion()).thenReturn(true);
        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        when(loan.isNpa()).thenReturn(true);
        when(loan.getId()).thenReturn(100L);

        try (MockedConstruction<JdbcTemplate> ignored = mockConstruction(JdbcTemplate.class,
                (mock, context) -> when(mock.query(anyString(), any(RowMapper.class), any()))
                        .thenReturn(List.of(BUSINESS_DATE.minusDays(1))))) {
            assertTrue(underTest.wouldBeLoanLevelNpa(loan, BUSINESS_DATE));
        }
    }

    @Test
    void wouldBeLoanLevelNpaTrueOnExactThresholdBoundaryWhenLoanAlreadyNpa() {
        // On the boundary day (overdue_since == businessDate - overdueDays) the loan-level keep rule retains NPA,
        // so this predicate must agree — entry semantics here would exit the client and re-enter it next COB
        when(loan.loanProduct()).thenReturn(product);
        when(product.getOverdueDaysForNPA()).thenReturn(90);
        when(product.isAccountMovesOutOfNPAOnlyOnArrearsCompletion()).thenReturn(false);
        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        when(loan.isNpa()).thenReturn(true);
        when(loan.getId()).thenReturn(100L);

        try (MockedConstruction<JdbcTemplate> ignored = mockConstruction(JdbcTemplate.class,
                (mock, context) -> when(mock.query(anyString(), any(RowMapper.class), any()))
                        .thenReturn(List.of(BUSINESS_DATE.minusDays(90))))) {
            assertTrue(underTest.wouldBeLoanLevelNpa(loan, BUSINESS_DATE));
        }
    }

    @Test
    void updateNpaStatusDoesNotClearLoanFlagWhileClientIsNpa() {
        when(loan.loanProduct()).thenReturn(product);
        when(product.getOverdueDaysForNPA()).thenReturn(90);
        when(product.isAccountMovesOutOfNPAOnlyOnArrearsCompletion()).thenReturn(true);
        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        when(loan.isNpa()).thenReturn(true);
        when(loan.getId()).thenReturn(100L);
        when(loan.getClientId()).thenReturn(10L);
        when(clientNpaRepository.existsByClientIdAndNpaTrue(10L)).thenReturn(true);

        try (MockedConstruction<JdbcTemplate> ignored = mockConstruction(JdbcTemplate.class,
                (mock, context) -> when(mock.query(anyString(), any(RowMapper.class), any())).thenReturn(Collections.emptyList()))) {
            underTest.updateNpaStatusForLoan(loan, BUSINESS_DATE);
        }

        verify(loan, never()).setNpa(false);
        verify(loanRepositoryWrapper, never()).save(loan);
        verify(loanAccrualsProcessingService, never()).reverseAccrualSuspenseForAlreadyNonNpaLoans(any());
    }
}
