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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanInterestRecalculationDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class LoanAccrualsProcessingServiceImplTest {

    private static final Set<LoanTransactionType> ACCRUAL_TYPES = Set.of(LoanTransactionType.ACCRUAL,
            LoanTransactionType.ACCRUAL_ADJUSTMENT, LoanTransactionType.ACCRUAL_SUSPENSE, LoanTransactionType.ACCRUAL_SUSPENSE_REVERSE,
            LoanTransactionType.ACCRUAL_WRITEOFF);

    @InjectMocks
    private LoanAccrualsProcessingServiceImpl accrualsProcessingService;

    @Mock
    private Loan loan;

    @Mock
    private LoanStatus loanStatus;

    @Mock
    private BusinessEventNotifierService businessEventNotifierService;

    @Mock
    private LoanTransactionRepository loanTransactionRepository;

    @Mock
    private JournalEntryWritePlatformService journalEntryWritePlatformService;

    @Mock
    private ConfigurationDomainService configurationDomainService;

    @Mock
    private LoanRepositoryWrapper loanRepositoryWrapper;

    @BeforeEach
    void setUp() {
        when(loan.isClosed()).thenReturn(false);
        when(loan.getStatus()).thenReturn(loanStatus);
        when(loanStatus.isOverpaid()).thenReturn(false);
    }

    @ParameterizedTest
    @MethodSource("loanStatusTestCases")
    void addPeriodicAccruals_ShouldNotProceed_WhenLoanIsClosedOrOverpaid(final boolean isClosed, final boolean isOverpaid) {
        // Given
        final LocalDate tillDate = LocalDate.now(ZoneId.systemDefault());
        when(loan.isClosed()).thenReturn(isClosed);

        when(loan.getStatus()).thenReturn(loanStatus);
        when(loanStatus.isOverpaid()).thenReturn(isOverpaid);

        // When
        accrualsProcessingService.addPeriodicAccruals(tillDate, loan);

        // Then
        verify(loan, times(1)).isClosed();

        verify(loanTransactionRepository, never()).saveAndFlush(any());
        verifyNoInteractions(journalEntryWritePlatformService);
        verify(businessEventNotifierService, never()).notifyPostBusinessEvent(any());
        verify(loan, never()).addLoanTransaction(any());
    }

    private static Stream<Arguments> loanStatusTestCases() {
        return Stream.of(Arguments.of(true, false), // Loan is closed
                Arguments.of(false, true) // Loan is overpaid
        );
    }

    // --- daily accrual on closed loans ---------------------------------------------------------------------------

    private void givenPeriodicAccrualLoan() {
        when(loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()).thenReturn(true);
        when(loan.isChargedOff()).thenReturn(false);
        when(loan.isContractTermination()).thenReturn(false);
        when(loan.isNpa()).thenReturn(false);
        when(loan.isForeclosure()).thenReturn(false);
        // stop right after the status guards: compounding-posted-as-transaction exits before any repository access,
        // and getLoanInterestRecalculationDetails() being called proves the guards let the loan through
        final LoanInterestRecalculationDetails recalculationDetails = mock(LoanInterestRecalculationDetails.class);
        when(recalculationDetails.isCompoundingToBePostedAsTransaction()).thenReturn(true);
        when(loan.getLoanInterestRecalculationDetails()).thenReturn(recalculationDetails);
    }

    private void givenLoanAccessibleById() {
        when(loanRepositoryWrapper.findOneWithNotFoundDetection(7L)).thenReturn(loan);
        when(configurationDomainService.retrieveAccrueClosedLoansMaturityLookbackDays()).thenReturn(30L);
    }

    @ParameterizedTest
    @MethodSource("closedByRepaymentStatuses")
    void addPeriodicAccrualsForLoanId_ShouldAccrue_WhenClosedOrOverpaidByRepayment(final boolean closedObligationsMet,
            final boolean overpaid) {
        givenPeriodicAccrualLoan();
        givenLoanAccessibleById();
        when(loan.isOpen()).thenReturn(false);
        when(loan.isClosed()).thenReturn(closedObligationsMet);
        when(loanStatus.isClosedObligationsMet()).thenReturn(closedObligationsMet);
        when(loanStatus.isOverpaid()).thenReturn(overpaid);

        final Map<String, Object> changes = accrualsProcessingService.addPeriodicAccrualsForLoanId(7L, LocalDate.of(2026, 9, 24));

        verify(loan, times(1)).getLoanInterestRecalculationDetails();
        assertEquals(Boolean.TRUE, changes.get("closedLoan"));
    }

    private static Stream<Arguments> closedByRepaymentStatuses() {
        return Stream.of(Arguments.of(true, false), Arguments.of(false, true));
    }

    @Test
    void addPeriodicAccrualsForLoanId_ShouldAccrueActiveLoan_ThroughCobPath() {
        givenPeriodicAccrualLoan();
        givenLoanAccessibleById();
        when(loan.isOpen()).thenReturn(true);
        when(loanStatus.isClosedObligationsMet()).thenReturn(false);
        when(loanStatus.isOverpaid()).thenReturn(false);

        final Map<String, Object> changes = accrualsProcessingService.addPeriodicAccrualsForLoanId(7L, LocalDate.of(2026, 9, 24));

        verify(loan, times(1)).getLoanInterestRecalculationDetails();
        assertEquals(Boolean.FALSE, changes.get("closedLoan"));
        assertFalse(changes.containsKey("skippedReason"));
    }

    @Test
    void addPeriodicAccrualsForLoanId_ShouldSkipWithReason_WhenClosedLoanIsNpa() {
        givenPeriodicAccrualLoan();
        givenLoanAccessibleById();
        when(loan.isNpa()).thenReturn(true);
        when(loan.isOpen()).thenReturn(false);
        when(loan.isClosed()).thenReturn(true);
        when(loanStatus.isClosedObligationsMet()).thenReturn(true);

        final Map<String, Object> changes = accrualsProcessingService.addPeriodicAccrualsForLoanId(7L, LocalDate.of(2026, 9, 24));

        verify(loan, never()).getLoanInterestRecalculationDetails();
        verifyNoInteractions(loanTransactionRepository);
        assertTrue(changes.containsKey("skippedReason"));
    }

    @Test
    void addPeriodicAccrualsForLoanId_ShouldSkipWithReason_WhenLoanWasForeclosed() {
        givenPeriodicAccrualLoan();
        givenLoanAccessibleById();
        when(loan.isForeclosure()).thenReturn(true);
        when(loan.isOpen()).thenReturn(false);
        when(loan.isClosed()).thenReturn(true);
        when(loanStatus.isClosedObligationsMet()).thenReturn(true);

        final Map<String, Object> changes = accrualsProcessingService.addPeriodicAccrualsForLoanId(7L, LocalDate.of(2026, 9, 24));

        verify(loan, never()).getLoanInterestRecalculationDetails();
        verifyNoInteractions(loanTransactionRepository);
        assertTrue(changes.containsKey("skippedReason"));
    }

    @Test
    void addPeriodicAccrualsForClosedLoans_ShouldDoNothing_WhenLookbackIsNotConfigured() throws Exception {
        when(configurationDomainService.retrieveAccrueClosedLoansMaturityLookbackDays()).thenReturn(null);

        accrualsProcessingService.addPeriodicAccrualsForClosedLoans(LocalDate.of(2026, 9, 24));

        verifyNoInteractions(loanRepositoryWrapper);
    }

    @Test
    void addPeriodicAccrualsForClosedLoans_ShouldSelectByMaturityLookback() throws Exception {
        when(configurationDomainService.retrieveAccrueClosedLoansMaturityLookbackDays()).thenReturn(30L);
        when(loanRepositoryWrapper.findClosedLoanIdsForPeriodicAccrual(any(), any(), any(), anyBoolean())).thenReturn(List.of());

        accrualsProcessingService.addPeriodicAccrualsForClosedLoans(LocalDate.of(2026, 9, 24));

        // maturity on/after business date - 30 days: loans still in their residual tenor plus the missed-run window
        verify(loanRepositoryWrapper, times(1)).findClosedLoanIdsForPeriodicAccrual(any(), eq(LocalDate.of(2026, 9, 24)),
                eq(LocalDate.of(2026, 8, 25)), anyBoolean());
    }

    @Test
    void addPeriodicAccrualsForClosedLoans_ShouldTreatNegativeLookbackAsZero() throws Exception {
        when(configurationDomainService.retrieveAccrueClosedLoansMaturityLookbackDays()).thenReturn(-5L);
        when(loanRepositoryWrapper.findClosedLoanIdsForPeriodicAccrual(any(), any(), any(), anyBoolean())).thenReturn(List.of());

        accrualsProcessingService.addPeriodicAccrualsForClosedLoans(LocalDate.of(2026, 9, 24));

        verify(loanRepositoryWrapper, times(1)).findClosedLoanIdsForPeriodicAccrual(any(), eq(LocalDate.of(2026, 9, 24)),
                eq(LocalDate.of(2026, 9, 24)), anyBoolean());
    }

    @Test
    void addPeriodicAccrualsForLoanId_ShouldSkipClosedLoan_WhenAccrualOnClosedLoansIsDisabled() {
        givenPeriodicAccrualLoan();
        when(loan.isClosed()).thenReturn(true);
        when(loanStatus.isClosedObligationsMet()).thenReturn(true);
        when(loanRepositoryWrapper.findOneWithNotFoundDetection(7L)).thenReturn(loan);
        when(configurationDomainService.retrieveAccrueClosedLoansMaturityLookbackDays()).thenReturn(null);

        final Map<String, Object> changes = accrualsProcessingService.addPeriodicAccrualsForLoanId(7L, LocalDate.of(2026, 9, 24));

        // the job's kill switch also covers the ops endpoint
        verify(loan, never()).getLoanInterestRecalculationDetails();
        assertEquals(Boolean.TRUE, changes.get("closedLoan"));
        assertTrue(changes.containsKey("skippedReason"));
    }

    @Test
    void addPeriodicAccrualsForLoanId_ShouldAccrueClosedLoan_ForPostTillDateEndpoint() {
        givenPeriodicAccrualLoan();
        when(loan.isOpen()).thenReturn(false);
        when(loan.isClosed()).thenReturn(true);
        when(loanStatus.isClosedObligationsMet()).thenReturn(true);
        when(loanRepositoryWrapper.findOneWithNotFoundDetection(7L)).thenReturn(loan);
        when(configurationDomainService.retrieveAccrueClosedLoansMaturityLookbackDays()).thenReturn(30L);

        final Map<String, Object> changes = accrualsProcessingService.addPeriodicAccrualsForLoanId(7L, LocalDate.of(2026, 9, 24));

        // the closed loan went through the accrual path (COB would have skipped it) and the response says so
        verify(loan, times(1)).getLoanInterestRecalculationDetails();
        assertEquals(Boolean.TRUE, changes.get("closedLoan"));
    }

    // --- foreclosure ---------------------------------------------------------------------------------------------

    @Test
    void processAccrualsOnLoanForeClosure_ShouldAccrue_WhenForeclosureDateEqualsAccruedTill() {
        // the old implementation returned when the foreclosure date equalled accrued_till, leaving same-day charges
        // un-accrued; the final accrual up to the foreclosure date must run regardless
        final LocalDate foreClosureDate = LocalDate.of(2027, 5, 3);
        when(loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()).thenReturn(true);
        when(loan.isChargedOff()).thenReturn(false);
        when(loan.isContractTermination()).thenReturn(false);
        when(loan.isOpen()).thenReturn(true);
        when(loan.getAccruedTill()).thenReturn(foreClosureDate);
        when(loanTransactionRepository.findNonReversedByLoanAndTypes(loan, ACCRUAL_TYPES)).thenReturn(List.of());
        // stop the final accrual right after its guards (compounding-posted products are excluded there)
        final LoanInterestRecalculationDetails recalculationDetails = mock(LoanInterestRecalculationDetails.class);
        when(recalculationDetails.isCompoundingToBePostedAsTransaction()).thenReturn(false, true, true);
        when(loan.getLoanInterestRecalculationDetails()).thenReturn(recalculationDetails);

        accrualsProcessingService.processAccrualsOnLoanForeClosure(loan, foreClosureDate);

        // accrued_till moved to the foreclosure date, existing accruals reprocessed, then the installment-wise catch-up
        // and the final accrual both entered
        verify(loan, times(1)).setAccruedTill(foreClosureDate);
        verify(loanTransactionRepository, times(1)).findNonReversedByLoanAndTypes(loan, ACCRUAL_TYPES);
        verify(loan, times(3)).getLoanInterestRecalculationDetails();
        verify(loanRepositoryWrapper, times(1)).save(loan);
    }

    @Test
    void processAccrualsOnLoanForeClosure_ShouldDoNothing_WhenProductIsNotPeriodicAccrual() {
        when(loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()).thenReturn(false);

        accrualsProcessingService.processAccrualsOnLoanForeClosure(loan, LocalDate.of(2027, 5, 3));

        verify(loan, never()).setAccruedTill(any());
        verifyNoInteractions(loanTransactionRepository);
    }

    @Test
    void addPeriodicAccruals_CobPath_ShouldStillSkipClosedLoans() {
        // COB never accrues closed loans; the closed-loan job is the only path that does
        givenPeriodicAccrualLoan();
        when(loan.isClosed()).thenReturn(true);
        when(loanStatus.isClosedObligationsMet()).thenReturn(true);

        accrualsProcessingService.addPeriodicAccruals(LocalDate.of(2026, 9, 24), loan);

        verify(loan, never()).getLoanInterestRecalculationDetails();
    }
}
