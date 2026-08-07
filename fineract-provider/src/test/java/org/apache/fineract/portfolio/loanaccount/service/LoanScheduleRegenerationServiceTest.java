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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.loanaccount.data.ScheduleGeneratorDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanLifecycleStateMachine;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Guards the sequence that both the single-loan correction and bulk repair depend on.
 * <p>
 * Three of these behaviours were bought with production incidents and would regress silently: the overdue-penalty
 * snapshot, which stops percentage penalties re-basing onto installments that only look unpaid mid-regeneration; the
 * accrual high-water mark, which decides whether reversed interest ever comes back; and the re-accrual that keeps the
 * loan whole rather than leaving a hole until COB next runs.
 */
class LoanScheduleRegenerationServiceTest {

    private static final BigDecimal ORIGINAL_PENALTY = new BigDecimal("7.13");
    private static final BigDecimal REBASED_PENALTY = new BigDecimal("1346.33");
    private static final Long PENALTY_ID = 42L;

    private final LoanUtilService loanUtilService = mock(LoanUtilService.class);
    private final LoanScheduleService loanScheduleService = mock(LoanScheduleService.class);
    private final ReprocessLoanTransactionsService reprocessLoanTransactionsService = mock(ReprocessLoanTransactionsService.class);
    private final LoanAccrualsProcessingService loanAccrualsProcessingService = mock(LoanAccrualsProcessingService.class);
    private final LoanLifecycleStateMachine loanLifecycleStateMachine = mock(LoanLifecycleStateMachine.class);
    private final LoanAccountDomainService loanAccountDomainService = mock(LoanAccountDomainService.class);

    private final LoanScheduleRegenerationService service = new LoanScheduleRegenerationService(loanUtilService, loanScheduleService,
            reprocessLoanTransactionsService, loanAccrualsProcessingService, loanLifecycleStateMachine, loanAccountDomainService);

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil
                .setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.now(ZoneId.systemDefault()))));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    // ----- eligibility -----

    @Test
    void onlyPlainlyActiveLoansAreRegenerable() {
        assertThat(LoanScheduleRegenerationService.isRegenerable(cumulativeLoan())).isTrue();
    }

    @Test
    void settledLoansAreNotRegenerable() {
        final Loan closed = cumulativeLoan();
        when(closed.getStatus()).thenReturn(LoanStatus.CLOSED_OBLIGATIONS_MET);
        assertThat(LoanScheduleRegenerationService.isRegenerable(closed)).isFalse();

        final Loan chargedOff = cumulativeLoan();
        when(chargedOff.isChargedOff()).thenReturn(true);
        assertThat(LoanScheduleRegenerationService.isRegenerable(chargedOff)).isFalse();

        final Loan foreclosed = cumulativeLoan();
        when(foreclosed.isForeclosure()).thenReturn(true);
        assertThat(LoanScheduleRegenerationService.isRegenerable(foreclosed)).isFalse();

        final Loan terminated = cumulativeLoan();
        when(terminated.isContractTermination()).thenReturn(true);
        assertThat(LoanScheduleRegenerationService.isRegenerable(terminated)).isFalse();
    }

    // ----- which replay path -----

    @Test
    void cumulativeLoanIsRegeneratedThenReplayedExactlyOnce() {
        final Loan loan = cumulativeLoan();

        service.regenerateAndReplay(loan);

        verify(loanScheduleService).regenerateRepaymentSchedule(eq(loan), any(ScheduleGeneratorDTO.class));
        verify(reprocessLoanTransactionsService).reprocessTransactions(loan);
    }

    /**
     * Progressive loans have their own replay path and are not regenerated here. They are excluded by
     * {@link LoanScheduleRegenerationService#isRegenerable} and the exclusion is enforced, rather than the service
     * returning as though the regeneration had happened: a silent skip would let a bulk repair report a clean run over
     * exactly the loans it failed to touch.
     */
    @Test
    void progressiveLoanIsRejectedRatherThanSilentlySkipped() {
        final Loan loan = cumulativeLoan();
        when(loan.isCumulativeSchedule()).thenReturn(false);

        assertThat(LoanScheduleRegenerationService.isRegenerable(loan)).isFalse();
        assertThatThrownBy(() -> service.regenerateAndReplay(loan)).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("whose schedule can be regenerated");

        verify(loanScheduleService, never()).regenerateRepaymentSchedule(any(), any());
        verify(loanScheduleService, never()).regenerateRepaymentScheduleWithInterestRecalculation(any(), any());
        verify(reprocessLoanTransactionsService, never()).reprocessTransactions(loan);
    }

    // ----- the overdue penalty snapshot -----

    /**
     * Rebuilding the schedule zeroes installment paid amounts before charges are recalculated, so a percentage overdue
     * penalty momentarily sees the whole installment as in arrears and re-bases onto it. Observed live: 7.13 of daily
     * penalties became 1,346.33 on a 12,000 loan after a two-day disbursement-date correction.
     */
    @Test
    void restoresAnOverduePenaltyThatTheRegenerationReBased() {
        final LoanCharge penalty = overduePenalty();
        when(penalty.getAmount()).thenReturn(ORIGINAL_PENALTY, REBASED_PENALTY);
        final Loan loan = cumulativeLoan();
        when(loan.getActiveCharges()).thenReturn(Set.of(penalty));

        service.regenerateAndReplay(loan);

        verify(penalty).setAmount(ORIGINAL_PENALTY);
        verify(penalty).setAmountPercentageAppliedTo(new BigDecimal("23.00"));
        verify(penalty).setAmountOutstanding(ORIGINAL_PENALTY);
    }

    @Test
    void leavesAnOverduePenaltyAloneWhenTheRegenerationDidNotMoveIt() {
        final LoanCharge penalty = overduePenalty();
        when(penalty.getAmount()).thenReturn(ORIGINAL_PENALTY, ORIGINAL_PENALTY);
        final Loan loan = cumulativeLoan();
        when(loan.getActiveCharges()).thenReturn(Set.of(penalty));

        service.regenerateAndReplay(loan);

        verify(penalty, never()).setAmount(any());
    }

    @Test
    void ordinaryChargesAreNotSnapshotted() {
        final LoanCharge fee = mock(LoanCharge.class);
        lenient().when(fee.isOverdueInstallmentCharge()).thenReturn(false);
        lenient().when(fee.getId()).thenReturn(9L);
        final Loan loan = cumulativeLoan();
        when(loan.getActiveCharges()).thenReturn(Set.of(fee));

        service.regenerateAndReplay(loan);

        verify(fee, never()).setAmount(any());
    }

    // ----- derived state -----

    @Test
    void clearsTheAccrualHighWaterMarkAndTheCobMarker() {
        final Loan loan = cumulativeLoan();

        service.regenerateAndReplay(loan);

        // accruedTill has to come back with the reversed accruals, or the next accrual run believes those periods are
        // already covered and the reversed interest never returns.
        verify(loan).setAccruedTill(null);
        verify(loan).setLastClosedBusinessDate(null);
        verify(loanAccrualsProcessingService).reprocessExistingAccruals(loan, true);
        verify(loanLifecycleStateMachine).determineAndTransition(eq(loan), any(LocalDate.class));
        verify(loanAccountDomainService).setLoanDelinquencyTag(eq(loan), any(LocalDate.class));
    }

    // ----- re-accrual -----

    /**
     * Without this the loan under-states accrued interest from the moment it is regenerated until COB next runs, and
     * accrued interest is read straight off these transactions for revenue projection.
     * <p>
     * The order is part of the contract: {@code addPeriodicAccruals} skips closed and overpaid loans, and that guard
     * only works once the loan carries the status the regeneration actually produced. Verified with {@code InOrder} so
     * a re-shuffle of {@code settleDerivedState} fails here instead of posting accruals for a loan the next line
     * closes.
     */
    @Test
    void reAccruesUpToTodayForPeriodicAccrualProductsAfterTheStatusTransition() throws Exception {
        final Loan loan = cumulativeLoan();
        when(loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()).thenReturn(true);

        service.regenerateAndReplay(loan);

        final InOrder inOrder = inOrder(loanLifecycleStateMachine, loanAccrualsProcessingService);
        inOrder.verify(loanLifecycleStateMachine).determineAndTransition(eq(loan), any(LocalDate.class));
        inOrder.verify(loanAccrualsProcessingService).addPeriodicAccruals(any(LocalDate.class), eq(loan));
    }

    @Test
    void doesNotReAccrueWhenTheProductKeepsNoPeriodicAccrual() throws Exception {
        final Loan loan = cumulativeLoan();
        when(loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()).thenReturn(false);

        service.regenerateAndReplay(loan);

        verify(loanAccrualsProcessingService, never()).addPeriodicAccruals(any(LocalDate.class), any(Loan.class));
    }

    /**
     * A loan left with its old accruals reversed and no replacements is worse than one that was never corrected, so a
     * failure here has to take the whole operation with it rather than be swallowed.
     * <p>
     * Thrown as an unchecked exception on purpose: the single-loan accrual overload never actually raises the
     * {@code MultiException} its interface declares - real failures arrive unchecked - so this is the shape the
     * production catch has to handle.
     */
    @Test
    void anAccrualFailureTakesTheWholeOperationDown() throws Exception {
        final Loan loan = cumulativeLoan();
        when(loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()).thenReturn(true);
        doThrow(new IllegalStateException("ledger unavailable")).when(loanAccrualsProcessingService)
                .addPeriodicAccruals(any(LocalDate.class), eq(loan));

        assertThatThrownBy(() -> service.regenerateAndReplay(loan)).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("Failed to re-post accruals").hasCauseInstanceOf(IllegalStateException.class);
    }

    // ----- fixtures -----

    private Loan cumulativeLoan() {
        final LoanProductRelatedDetail detail = mock(LoanProductRelatedDetail.class);
        lenient().when(detail.getLoanScheduleType()).thenReturn(LoanScheduleType.CUMULATIVE);

        final Loan loan = mock(Loan.class);
        lenient().when(loan.isCumulativeSchedule()).thenReturn(true);
        lenient().when(loan.getId()).thenReturn(1L);
        lenient().when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        lenient().when(loan.isChargedOff()).thenReturn(false);
        lenient().when(loan.isForeclosure()).thenReturn(false);
        lenient().when(loan.isContractTermination()).thenReturn(false);
        lenient().when(loan.getLoanProductRelatedDetail()).thenReturn(detail);
        lenient().when(loan.isInterestBearingAndInterestRecalculationEnabled()).thenReturn(false);
        lenient().when(loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()).thenReturn(false);
        lenient().when(loan.getActiveCharges()).thenReturn(Set.of());
        lenient().when(loanUtilService.buildScheduleGeneratorDTO(loan, null)).thenReturn(mock(ScheduleGeneratorDTO.class));
        return loan;
    }

    private LoanCharge overduePenalty() {
        final LoanCharge penalty = mock(LoanCharge.class);
        lenient().when(penalty.isOverdueInstallmentCharge()).thenReturn(true);
        lenient().when(penalty.getId()).thenReturn(PENALTY_ID);
        lenient().when(penalty.getAmountPercentageAppliedTo()).thenReturn(new BigDecimal("23.00"));
        lenient().when(penalty.getAmountOutstanding()).thenReturn(ORIGINAL_PENALTY);
        return penalty;
    }
}
