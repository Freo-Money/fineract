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
package org.apache.fineract.portfolio.client.npa.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.jobs.service.updatenpa.LoanNpaUpdateService;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.client.exception.LoanTransactionBeforeClientNpaStartException;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpa;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaHistory;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaHistoryRepository;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaLoan;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaLoanHistory;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaLoanHistoryRepository;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaLoanRepository;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaMovementType;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaRepository;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.service.LoanAccrualsProcessingService;
import org.apache.fineract.portfolio.loanproduct.domain.ClientNpaExitStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClientNpaWritePlatformServiceTest {

    private static final Long CLIENT_ID = 10L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 30);

    @Mock
    private ClientNpaRepository clientNpaRepository;
    @Mock
    private ClientNpaHistoryRepository clientNpaHistoryRepository;
    @Mock
    private ClientNpaLoanRepository clientNpaLoanRepository;
    @Mock
    private ClientNpaLoanHistoryRepository clientNpaLoanHistoryRepository;
    @Mock
    private LoanRepository loanRepository;
    @Mock
    private LoanRepositoryWrapper loanRepositoryWrapper;
    @Mock
    private ClientRepositoryWrapper clientRepositoryWrapper;
    @Mock
    private BusinessEventNotifierService businessEventNotifierService;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private LoanAccrualsProcessingService loanAccrualsProcessingService;
    @Mock
    private LoanNpaUpdateService loanNpaUpdateService;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private Loan loan;

    @InjectMocks
    private ClientNpaWritePlatformService underTest;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE)));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void exitWritesOneCompletedHistoryRowAndEntryWritesNone() {
        final ClientNpa clientNpa = new ClientNpa(CLIENT_ID);
        clientNpa.setNpa(true);
        clientNpa.setNpaStartDate(BUSINESS_DATE.minusDays(30));
        clientNpa.setTriggerLoanId(100L);
        clientNpa.setMovementType(ClientNpaMovementType.SYSTEM);
        clientNpa.setExitStrategy(ClientNpaExitStrategy.ANY_NPA_LOAN_EXISTS);
        final ClientNpaLoan mapping = new ClientNpaLoan(CLIENT_ID, 100L, BUSINESS_DATE.minusDays(30), 3);
        when(clientNpaRepository.findByClientIdForUpdate(CLIENT_ID)).thenReturn(Optional.of(clientNpa));
        when(loanRepository.findLoanByClientId(CLIENT_ID)).thenReturn(List.of());
        when(clientNpaLoanRepository.findByClientId(CLIENT_ID)).thenReturn(List.of(mapping));

        underTest.reEvaluateClient(CLIENT_ID);

        // The spell is recorded only now, complete: start carried from the status row, end stamped at exit
        final ArgumentCaptor<ClientNpaHistory> clientHistory = ArgumentCaptor.forClass(ClientNpaHistory.class);
        verify(clientNpaHistoryRepository).save(clientHistory.capture());
        assertEquals(BUSINESS_DATE.minusDays(30), clientHistory.getValue().getStartDate());
        assertEquals(BUSINESS_DATE, clientHistory.getValue().getEndDate());
        assertEquals(100L, clientHistory.getValue().getTriggerLoanId());
        assertEquals("ANY_NPA_LOAN_EXISTS", clientHistory.getValue().getExitReason());

        // Loan spell carries its start date across from the mapping row it replaces
        final ArgumentCaptor<ClientNpaLoanHistory> loanHistory = ArgumentCaptor.forClass(ClientNpaLoanHistory.class);
        verify(clientNpaLoanHistoryRepository).save(loanHistory.capture());
        assertEquals(BUSINESS_DATE.minusDays(30), loanHistory.getValue().getStartDate());
        assertEquals(BUSINESS_DATE, loanHistory.getValue().getEndDate());
        verify(clientNpaLoanRepository).deleteByClientId(CLIENT_ID);
    }

    @Test
    void reEvaluateRepairsDriftWhenStayNpaKeepsClientNpaWithoutAnIndependentlyNpaLoan() {
        final ClientNpa clientNpa = new ClientNpa(CLIENT_ID);
        clientNpa.setNpa(true);
        clientNpa.setExitStrategy(ClientNpaExitStrategy.STAY_NPA);
        when(clientNpaRepository.findByClientIdForUpdate(CLIENT_ID)).thenReturn(Optional.of(clientNpa));
        when(loanRepository.findLoanByClientId(CLIENT_ID)).thenReturn(List.of(loan));
        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        when(loan.getId()).thenReturn(100L);
        when(loan.isNpa()).thenReturn(false);
        when(loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()).thenReturn(false);
        // STAY_NPA keeps the client NPA precisely when no loan is independently NPA
        when(loanNpaUpdateService.wouldBeLoanLevelNpa(loan, BUSINESS_DATE)).thenReturn(false);
        when(clientNpaLoanRepository.findByClientIdAndLoanId(CLIENT_ID, 100L)).thenReturn(Optional.empty());

        underTest.reEvaluateClient(CLIENT_ID);

        // The unmapped, unflagged loan is repaired rather than left for a reconcile pass that cannot fix it
        verify(clientNpaLoanRepository).save(any());
        verify(loan).setNpa(true);
        verify(loanRepositoryWrapper).save(loan);
        assertTrue(clientNpa.isNpa());
    }

    @Test
    void reEvaluateExitsWhenAnyNpaLoanExistsStrategyHasNoNpaLoanLeft() {
        final ClientNpa clientNpa = new ClientNpa(CLIENT_ID);
        clientNpa.setNpa(true);
        clientNpa.setExitStrategy(ClientNpaExitStrategy.ANY_NPA_LOAN_EXISTS);
        when(clientNpaRepository.findByClientIdForUpdate(CLIENT_ID)).thenReturn(Optional.of(clientNpa));
        when(loanRepository.findLoanByClientId(CLIENT_ID)).thenReturn(List.of(loan));
        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        when(loan.getId()).thenReturn(100L);
        when(loanNpaUpdateService.wouldBeLoanLevelNpa(loan, BUSINESS_DATE)).thenReturn(false);
        when(clientNpaLoanRepository.findByClientId(CLIENT_ID)).thenReturn(List.of());

        underTest.reEvaluateClient(CLIENT_ID);

        // Exit must still win over the new drift-repair branch
        assertFalse(clientNpa.isNpa());
        verify(clientNpaLoanRepository).deleteByClientId(CLIENT_ID);
        verify(clientNpaLoanRepository, never()).save(any());
    }

    @Test
    void onLoanDisbursedPropagatesWhenClientNpaEvenIfFeatureDisabled() {
        final ClientNpa clientNpa = new ClientNpa(CLIENT_ID);
        clientNpa.setNpa(true);
        when(loan.getClientId()).thenReturn(CLIENT_ID);
        when(loan.getId()).thenReturn(100L);
        when(loan.isNpa()).thenReturn(false);
        when(loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()).thenReturn(false);
        when(clientNpaRepository.findByClientIdForUpdate(CLIENT_ID)).thenReturn(Optional.of(clientNpa));
        when(clientNpaLoanRepository.findByClientIdAndLoanId(CLIENT_ID, 100L)).thenReturn(Optional.empty());

        underTest.onLoanDisbursed(loan);

        verify(loan).setNpa(true);
        verify(loanRepositoryWrapper).save(loan);
        verify(configurationDomainService, never()).isClientNpaEnabled();
    }

    @Test
    void onLoanDisbursedTakesLockAndSkipsWhenClientExitedNpaConcurrently() {
        final ClientNpa exited = new ClientNpa(CLIENT_ID);
        exited.setNpa(false);
        when(loan.getClientId()).thenReturn(CLIENT_ID);
        // Locking read observes the concurrent exit's committed state, unlike the unlocked existsByClientIdAndNpaTrue
        when(clientNpaRepository.findByClientIdForUpdate(CLIENT_ID)).thenReturn(Optional.of(exited));

        underTest.onLoanDisbursed(loan);

        verify(clientNpaLoanRepository, never()).save(any());
        verify(loan, never()).setNpa(anyBoolean());
    }

    @Test
    void onLoanClosedDoesNotReEvaluateWhenFeatureDisabled() {
        when(loan.getClientId()).thenReturn(CLIENT_ID);
        when(loan.getId()).thenReturn(100L);
        when(clientNpaLoanRepository.findByClientIdAndLoanId(CLIENT_ID, 100L)).thenReturn(Optional.empty());
        when(clientNpaRepository.existsByClientIdAndNpaTrue(CLIENT_ID)).thenReturn(true);
        when(configurationDomainService.isClientNpaEnabled()).thenReturn(false);

        underTest.onLoanClosed(loan);

        // The client lock is still taken (deadlock-ordering), but no re-evaluation runs while the feature is off
        verify(loanRepository, never()).findLoanByClientId(any());
    }

    @Test
    void onLoanClosedLocksClientRowBeforeTouchingMappingRows() {
        when(loan.getClientId()).thenReturn(CLIENT_ID);
        when(loan.getId()).thenReturn(100L);
        when(clientNpaLoanRepository.findByClientIdAndLoanId(CLIENT_ID, 100L))
                .thenReturn(Optional.of(new ClientNpaLoan(CLIENT_ID, 100L, BUSINESS_DATE.minusDays(30), 3)));
        when(clientNpaRepository.existsByClientIdAndNpaTrue(CLIENT_ID)).thenReturn(false);

        underTest.onLoanClosed(loan);

        // reEvaluateClient's exit path holds the client lock while deleting mappings; taking the locks in the
        // opposite order here would deadlock a user close against the nightly reconcile
        final InOrder inOrder = inOrder(clientNpaRepository, clientNpaLoanRepository);
        inOrder.verify(clientNpaRepository).findByClientIdForUpdate(CLIENT_ID);
        inOrder.verify(clientNpaLoanRepository).findByClientIdAndLoanId(CLIENT_ID, 100L);
        inOrder.verify(clientNpaLoanRepository).deleteByClientIdAndLoanId(CLIENT_ID, 100L);
    }

    @Test
    void exitUnwindsContagionOnLoanWhoseProductHasNoNpaConfig() {
        final ClientNpa clientNpa = new ClientNpa(CLIENT_ID);
        clientNpa.setNpa(true);
        clientNpa.setExitStrategy(ClientNpaExitStrategy.ANY_NPA_LOAN_EXISTS);
        when(clientNpaRepository.findByClientIdForUpdate(CLIENT_ID)).thenReturn(Optional.of(clientNpa));
        when(loanRepository.findLoanByClientId(CLIENT_ID)).thenReturn(List.of(loan));
        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        when(loan.getId()).thenReturn(100L);
        when(loan.isNpa()).thenReturn(true);
        // Product without NPA config: updateNpaStatusForLoan would early-return and strand the contagion flag
        when(loan.loanProduct()).thenReturn(null);
        when(loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()).thenReturn(true);
        when(loanNpaUpdateService.wouldBeLoanLevelNpa(loan, BUSINESS_DATE)).thenReturn(false);
        when(clientNpaLoanRepository.findByClientId(CLIENT_ID)).thenReturn(List.of());

        underTest.reEvaluateClient(CLIENT_ID);

        assertFalse(clientNpa.isNpa());
        verify(loanNpaUpdateService, never()).updateNpaStatusForLoan(any(), any());
        // Clears is_npa and reverses the outstanding suspense in one call, joining the exit transaction
        verify(loanAccrualsProcessingService).reverseAccrualSuspenseForAlreadyNonNpaLoans(List.of(100L));
    }

    @Test
    void reEvaluateClearsStrandedContagionFlagWhenClientNotNpa() {
        final ClientNpa clientNpa = new ClientNpa(CLIENT_ID); // npa = false
        when(clientNpaRepository.findByClientIdForUpdate(CLIENT_ID)).thenReturn(Optional.of(clientNpa));
        when(loanRepository.findLoanByClientId(CLIENT_ID)).thenReturn(List.of(loan));
        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        when(loan.getId()).thenReturn(100L);
        when(loan.isNpa()).thenReturn(true);
        when(loan.loanProduct()).thenReturn(null);
        when(loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()).thenReturn(false);
        when(loanNpaUpdateService.wouldBeLoanLevelNpa(loan, BUSINESS_DATE)).thenReturn(false);

        underTest.reEvaluateClient(CLIENT_ID);

        // Residue repair: without it the reconcile stale-flag entry query reselects this client every night forever
        verify(loan).setNpa(false);
        verify(loanRepositoryWrapper).save(loan);
        verify(clientNpaHistoryRepository, never()).save(any());
    }

    @Test
    void validateThrowsWhenTransactionBeforeClientNpaStart() {
        final ClientNpa clientNpa = new ClientNpa(CLIENT_ID);
        clientNpa.setNpa(true);
        clientNpa.setNpaStartDate(BUSINESS_DATE);
        when(loan.getClientId()).thenReturn(CLIENT_ID);
        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        when(loan.getId()).thenReturn(100L);
        when(clientNpaRepository.findByClientId(CLIENT_ID)).thenReturn(Optional.of(clientNpa));

        assertThrows(LoanTransactionBeforeClientNpaStartException.class,
                () -> underTest.validateTransactionNotBeforeClientNpaStart(loan, BUSINESS_DATE.minusDays(1)));
    }

    @Test
    void validatePassesWhenTransactionWithinNpaPeriod() {
        final ClientNpa clientNpa = new ClientNpa(CLIENT_ID);
        clientNpa.setNpa(true);
        clientNpa.setNpaStartDate(BUSINESS_DATE);
        when(loan.getClientId()).thenReturn(CLIENT_ID);
        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        when(clientNpaRepository.findByClientId(CLIENT_ID)).thenReturn(Optional.of(clientNpa));

        // On the NPA start date and after are within the NPA period and allowed
        assertDoesNotThrow(() -> underTest.validateTransactionNotBeforeClientNpaStart(loan, BUSINESS_DATE));
        assertDoesNotThrow(() -> underTest.validateTransactionNotBeforeClientNpaStart(loan, BUSINESS_DATE.plusDays(1)));
    }

    @Test
    void validateNoopWhenClientNotNpa() {
        final ClientNpa clientNpa = new ClientNpa(CLIENT_ID); // npa = false
        when(loan.getClientId()).thenReturn(CLIENT_ID);
        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        when(clientNpaRepository.findByClientId(CLIENT_ID)).thenReturn(Optional.of(clientNpa));

        assertDoesNotThrow(() -> underTest.validateTransactionNotBeforeClientNpaStart(loan, BUSINESS_DATE.minusDays(30)));
    }

    // Contagion maps active loans only, so non-active loans sit outside the NPA period the guard protects: a
    // written-off loan's recovery repayment or a closed/overpaid loan's refund must not be blocked by a sibling loan
    // having dragged the client into NPA.
    @Test
    void validateExemptsRecoveryOnWrittenOffLoanFromNpaStartGuard() {
        when(loan.getClientId()).thenReturn(CLIENT_ID);
        when(loan.getStatus()).thenReturn(LoanStatus.CLOSED_WRITTEN_OFF);

        assertDoesNotThrow(() -> underTest.validateTransactionNotBeforeClientNpaStart(loan, BUSINESS_DATE.minusDays(30)));
        verify(clientNpaRepository, never()).findByClientId(any());
    }

    @Test
    void validateExemptsClosedAndOverpaidLoansFromNpaStartGuard() {
        when(loan.getClientId()).thenReturn(CLIENT_ID);

        when(loan.getStatus()).thenReturn(LoanStatus.CLOSED_OBLIGATIONS_MET);
        assertDoesNotThrow(() -> underTest.validateTransactionNotBeforeClientNpaStart(loan, BUSINESS_DATE.minusDays(30)));

        when(loan.getStatus()).thenReturn(LoanStatus.OVERPAID);
        assertDoesNotThrow(() -> underTest.validateTransactionNotBeforeClientNpaStart(loan, BUSINESS_DATE.minusDays(30)));

        verify(clientNpaRepository, never()).findByClientId(any());
    }
}
