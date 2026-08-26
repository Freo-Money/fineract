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
package org.apache.fineract.portfolio.loanaccount.domain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.service.LoanOverduePenaltyPreTransactionEventService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Sizing and dating of the sweeps that settle installments from the parked excess pool: the shared sizing used by the
 * nightly job, and the due-date sweeps posted after a back-dated repayment (with the penalty reconcile before each).
 * Schedule: two installments of 3,120 due 02-01 and 03-01; business date 03-05. The back-dating check
 * (enforce-loan-transaction-not-before-last-user-transaction) is enforced unless a test turns it off.
 */
class LoanAccountDomainServiceJpaExcessParkingTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("INR", 2, 1);
    private static final MockedStatic<MoneyHelper> MONEY_HELPER = Mockito.mockStatic(MoneyHelper.class);
    private static final LocalDate DUE_1 = LocalDate.of(2027, 2, 1);
    private static final LocalDate DUE_2 = LocalDate.of(2027, 3, 1);
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 3, 5);
    private static final BigDecimal EMI = BigDecimal.valueOf(3120);

    private final AtomicReference<BigDecimal> pool = new AtomicReference<>();
    private LoanAccountDomainServiceJpa service;
    private LoanOverduePenaltyPreTransactionEventService penaltyService;
    private ConfigurationDomainService configurationDomainService;
    private Loan loan;
    private LoanProductRelatedDetail detail;
    private List<LoanRepaymentScheduleInstallment> installments;

    @BeforeAll
    static void init() {
        MONEY_HELPER.when(MoneyHelper::getMathContext).thenReturn(new MathContext(12, RoundingMode.HALF_EVEN));
        MONEY_HELPER.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
    }

    @AfterAll
    static void destruct() {
        MONEY_HELPER.close();
    }

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, BUSINESS_DATE)));

        loan = Mockito.mock(Loan.class);
        detail = Mockito.mock(LoanProductRelatedDetail.class);
        lenient().when(detail.isEnableExcessPaymentParking()).thenReturn(true);
        lenient().when(loan.getLoanProductRelatedDetail()).thenReturn(detail);
        lenient().when(loan.getCurrency()).thenReturn(CURRENCY);
        lenient().when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        lenient().when(loan.getTotalExcessPaymentAmount()).thenAnswer(invocation -> pool.get());
        installments = new ArrayList<>(List.of(installment(1, LocalDate.of(2027, 1, 1), DUE_1), installment(2, DUE_1, DUE_2)));
        lenient().when(loan.getRepaymentScheduleInstallments()).thenReturn(installments);

        service = Mockito.mock(LoanAccountDomainServiceJpa.class, Mockito.CALLS_REAL_METHODS);
        final ExternalIdFactory externalIdFactory = Mockito.mock(ExternalIdFactory.class);
        lenient().when(externalIdFactory.create()).thenReturn(ExternalId.empty());
        setField("externalIdFactory", externalIdFactory);
        penaltyService = Mockito.mock(LoanOverduePenaltyPreTransactionEventService.class);
        @SuppressWarnings("unchecked")
        final ObjectProvider<LoanOverduePenaltyPreTransactionEventService> provider = Mockito.mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(penaltyService);
        lenient().when(penaltyService.isOverduePenaltyReconcileEnabled(any())).thenReturn(true);
        setField("overduePenaltyEventServiceProvider", provider);
        configurationDomainService = Mockito.mock(ConfigurationDomainService.class);
        lenient().when(configurationDomainService.isEnforceLoanTransactionNotBeforeLastUserTransactionEnabled()).thenReturn(true);
        setField("configurationDomainService", configurationDomainService);

        // A posted sweep pays what is due on its date from the pool, like the processor does.
        lenient().doAnswer(invocation -> {
            final LocalDate date = invocation.getArgument(2);
            Money remaining = Money.of(CURRENCY, invocation.getArgument(3));
            for (final LoanRepaymentScheduleInstallment installment : installments) {
                if (!installment.getDueDate().isAfter(date) && remaining.isGreaterThanZero()) {
                    final Money interest = smaller(installment.getInterestOutstanding(CURRENCY), remaining);
                    installment.payInterestComponent(date, interest);
                    remaining = remaining.minus(interest);
                    final Money principal = smaller(installment.getPrincipalOutstanding(CURRENCY), remaining);
                    installment.payPrincipalComponent(date, principal);
                    remaining = remaining.minus(principal);
                    installment.updateObligationsMet(CURRENCY, date);
                }
            }
            pool.set(pool.get().subtract((BigDecimal) invocation.getArgument(3)));
            return null;
        }).when(service).makeRepayment(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean(), any(), any(),
                anyBoolean());
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void sweepIsSizedToWhatIsDueWhileALaterInstallmentIsOpen() {
        pool.set(BigDecimal.valueOf(5000));

        service.sweepParkedExcessDueOn(loan, DUE_1, "Auto Payment");

        verifySweep(times(1), DUE_1, EMI);
        verifySweepCount(1);
    }

    @Test
    void sweepDrawsTheWholePoolWhenNothingIsOpenAfterTheDueInstallments() {
        pool.set(BigDecimal.valueOf(7000));

        service.sweepParkedExcessDueOn(loan, DUE_2, "Auto Payment");

        // Both installments are due (6,240) and nothing is open beyond them: the whole pool (7,000) goes in, the
        // surplus
        // becomes the sweep's overpayment instead of staying parked on a paid-off loan.
        verifySweep(times(1), DUE_2, BigDecimal.valueOf(7000));
        verifySweepCount(1);
    }

    @Test
    void sweepIsCappedByThePool() {
        pool.set(BigDecimal.valueOf(1000));

        service.sweepParkedExcessDueOn(loan, DUE_1, "Auto Payment");

        verifySweep(times(1), DUE_1, BigDecimal.valueOf(1000));
        verifySweepCount(1);
    }

    @Test
    void backDatedRepaymentSettlesEachMissedDueDateOnTimeAfterReconcilingPenalties() {
        pool.set(BigDecimal.valueOf(6240));
        lenient().when(loan.getLastUserTransactionDate()).thenReturn(LocalDate.of(2027, 1, 30));

        service.settleParkedExcessForDueDatesFrom(loan, LocalDate.of(2027, 1, 31), true);

        final InOrder inOrder = Mockito.inOrder(penaltyService, service);
        inOrder.verify(penaltyService).reconcileOverduePenaltiesUpToDate(loan, DUE_1);
        inOrder.verify(service).makeRepayment(eq(LoanTransactionType.REPAYMENT_FROM_EXCESS_AMOUNT), eq(loan), eq(DUE_1),
                argThat(amount -> amount.compareTo(EMI) == 0), any(), any(), any(), anyBoolean(), any(), anyBoolean(), any(), any(),
                anyBoolean());
        inOrder.verify(penaltyService).reconcileOverduePenaltiesUpToDate(loan, DUE_2);
        inOrder.verify(service).makeRepayment(eq(LoanTransactionType.REPAYMENT_FROM_EXCESS_AMOUNT), eq(loan), eq(DUE_2),
                argThat(amount -> amount.compareTo(EMI) == 0), any(), any(), any(), anyBoolean(), any(), anyBoolean(), any(), any(),
                anyBoolean());
        verifySweepCount(2);
    }

    @Test
    void dueDatesBeforeTheLatestUserTransactionAreLeftToTheNightlyJob() {
        pool.set(BigDecimal.valueOf(6240));
        lenient().when(loan.getLastUserTransactionDate()).thenReturn(LocalDate.of(2027, 2, 10));

        service.settleParkedExcessForDueDatesFrom(loan, LocalDate.of(2027, 1, 31), true);

        verify(penaltyService, never()).reconcileOverduePenaltiesUpToDate(loan, DUE_1);
        verifySweep(never(), DUE_1, EMI);
        verifySweep(times(1), DUE_2, BigDecimal.valueOf(6240));
        verifySweepCount(1);
    }

    @Test
    void afterAChargeIsAddedTheDueDateIsSettledWithoutReconcilingPenalties() {
        pool.set(BigDecimal.valueOf(6240));
        lenient().when(loan.getLastUserTransactionDate()).thenReturn(LocalDate.of(2027, 1, 10));

        service.settleParkedExcessForDueDatesFrom(loan, DUE_1, false);

        verify(penaltyService, never()).reconcileOverduePenaltiesUpToDate(any(), any());
        verifySweep(times(1), DUE_1, EMI);
    }

    @Test
    void whereBackDatingIsAllowedABackDatedRepaymentSettlesDueDatesBeforeLaterTransactions() {
        pool.set(BigDecimal.valueOf(6240));
        lenient().when(configurationDomainService.isEnforceLoanTransactionNotBeforeLastUserTransactionEnabled()).thenReturn(false);
        lenient().when(loan.getLastUserTransactionDate()).thenReturn(LocalDate.of(2027, 2, 10));

        service.settleParkedExcessForDueDatesFrom(loan, LocalDate.of(2027, 1, 31), true);

        final InOrder inOrder = Mockito.inOrder(penaltyService, service);
        inOrder.verify(penaltyService).reconcileOverduePenaltiesUpToDate(loan, DUE_1);
        inOrder.verify(service).makeRepayment(eq(LoanTransactionType.REPAYMENT_FROM_EXCESS_AMOUNT), eq(loan), eq(DUE_1),
                argThat(amount -> amount.compareTo(EMI) == 0), any(), any(), any(), anyBoolean(), any(), anyBoolean(), any(), any(),
                anyBoolean());
        verifySweep(times(1), DUE_2, EMI);
        verifySweepCount(2);
    }

    @Test
    void whereBackDatingIsAllowedAChargeStillLeavesDueDatesBeforeLaterTransactionsToTheNightlyJob() {
        pool.set(BigDecimal.valueOf(6240));
        lenient().when(configurationDomainService.isEnforceLoanTransactionNotBeforeLastUserTransactionEnabled()).thenReturn(false);
        lenient().when(loan.getLastUserTransactionDate()).thenReturn(LocalDate.of(2027, 2, 10));

        service.settleParkedExcessForDueDatesFrom(loan, DUE_1, false);

        verifySweep(never(), DUE_1, EMI);
        verifySweep(times(1), DUE_2, BigDecimal.valueOf(6240));
        verifySweepCount(1);
    }

    @Test
    void whereBackDatingIsAllowedADueDateBeforeALaterChargeRefundIsLeftToTheNightlyJob() {
        pool.set(BigDecimal.valueOf(6240));
        lenient().when(configurationDomainService.isEnforceLoanTransactionNotBeforeLastUserTransactionEnabled()).thenReturn(false);
        lenient().when(loan.getLastUserTransactionDate()).thenReturn(LocalDate.of(2027, 1, 30));
        // A repayment-type transaction cannot be dated before a charge refund: the sweep for 02-01 would be refused.
        final LoanTransaction chargeRefund = Mockito.mock(LoanTransaction.class);
        lenient().when(chargeRefund.isChargeRefund()).thenReturn(true);
        lenient().when(chargeRefund.getTransactionDate()).thenReturn(LocalDate.of(2027, 2, 10));
        lenient().when(loan.getLoanTransactions()).thenReturn(List.of(chargeRefund));

        service.settleParkedExcessForDueDatesFrom(loan, LocalDate.of(2027, 1, 31), true);

        verify(penaltyService, never()).reconcileOverduePenaltiesUpToDate(loan, DUE_1);
        verifySweep(never(), DUE_1, EMI);
        verifySweep(times(1), DUE_2, BigDecimal.valueOf(6240));
        verifySweepCount(1);
    }

    @Test
    void missedDueDatesAreSweptOnThoseDatesWithoutAReconcileWhenNoPenaltyFollows() {
        pool.set(BigDecimal.valueOf(6240));

        service.settleMissedParkedExcessDueDates(loan, BUSINESS_DATE);

        verify(penaltyService, never()).reconcileOverduePenaltiesUpToDate(any(), any());
        verifySweep(times(1), DUE_1, EMI);
        verifySweep(times(1), DUE_2, EMI);
        verifySweepCount(2);
    }

    @Test
    void aPenaltyLeviedAfterAMissedDueDateIsReconciledBeforeItsSweep() {
        pool.set(BigDecimal.valueOf(6240));
        final LoanCharge penalty = Mockito.mock(LoanCharge.class);
        lenient().when(penalty.isActive()).thenReturn(true);
        lenient().when(penalty.isOverdueInstallmentCharge()).thenReturn(true);
        lenient().when(penalty.getDueLocalDate()).thenReturn(LocalDate.of(2027, 2, 5));
        lenient().when(loan.getLoanCharges()).thenReturn(Set.of(penalty));

        service.settleMissedParkedExcessDueDates(loan, BUSINESS_DATE);

        final InOrder inOrder = Mockito.inOrder(penaltyService, service);
        inOrder.verify(penaltyService).reconcileOverduePenaltiesUpToDate(loan, DUE_1);
        inOrder.verify(service).makeRepayment(eq(LoanTransactionType.REPAYMENT_FROM_EXCESS_AMOUNT), eq(loan), eq(DUE_1),
                argThat(amount -> amount.compareTo(EMI) == 0), any(), any(), any(), anyBoolean(), any(), anyBoolean(), any(), any(),
                anyBoolean());
        // The 02-05 penalty is not dated after 03-01: no reconcile before that sweep.
        verify(penaltyService, never()).reconcileOverduePenaltiesUpToDate(loan, DUE_2);
        verifySweepCount(2);
    }

    @Test
    void aDueDateOnOrBeforeTheLatestSweepIsNotMissed() {
        pool.set(BigDecimal.valueOf(6240));
        // Swept on 02-01, then reopened (e.g. a charge added later): left to the run-date sweep.
        final LoanTransaction sweep = Mockito.mock(LoanTransaction.class);
        lenient().when(sweep.isRepaymentFromExcessAmount()).thenReturn(true);
        lenient().when(sweep.isNotReversed()).thenReturn(true);
        lenient().when(sweep.getTransactionDate()).thenReturn(DUE_1);
        lenient().when(loan.getLoanTransactions()).thenReturn(List.of(sweep));

        service.settleMissedParkedExcessDueDates(loan, BUSINESS_DATE);

        verifySweep(never(), DUE_1, EMI);
        verifySweep(times(1), DUE_2, BigDecimal.valueOf(6240));
        verifySweepCount(1);
    }

    @Test
    void theRunDateItselfIsLeftToTheRunDateSweep() {
        pool.set(BigDecimal.valueOf(6240));

        service.settleMissedParkedExcessDueDates(loan, DUE_2);

        verifySweep(times(1), DUE_1, EMI);
        verifySweepCount(1);
    }

    @Test
    void noMissedDateSettlementForAnNpaLoanOrWithoutThePenaltyReconcile() {
        pool.set(BigDecimal.valueOf(6240));
        lenient().when(loan.isNpa()).thenReturn(true);
        service.settleMissedParkedExcessDueDates(loan, BUSINESS_DATE);

        lenient().when(loan.isNpa()).thenReturn(false);
        lenient().when(penaltyService.isOverduePenaltyReconcileEnabled(any())).thenReturn(false);
        service.settleMissedParkedExcessDueDates(loan, BUSINESS_DATE);

        verifySweepCount(0);
    }

    @Test
    void aMissedDueDateBeforeTheLatestUserTransactionIsLeftWhileTheCheckIsEnforced() {
        pool.set(BigDecimal.valueOf(6240));
        lenient().when(loan.getLastUserTransactionDate()).thenReturn(LocalDate.of(2027, 2, 10));

        service.settleMissedParkedExcessDueDates(loan, BUSINESS_DATE);

        verifySweep(never(), DUE_1, EMI);
        verifySweep(times(1), DUE_2, BigDecimal.valueOf(6240));
        verifySweepCount(1);
    }

    @Test
    void aSweepThatDoesNotReduceThePoolIsNotPostedAgain() {
        pool.set(BigDecimal.valueOf(5000));
        // The sweep applies nothing: no installment is paid and the pool is unchanged.
        Mockito.doReturn(null).when(service).makeRepayment(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                anyBoolean(), any(), any(), anyBoolean());

        service.sweepParkedExcessDueOn(loan, DUE_2, "Auto Payment");

        verifySweepCount(1);
    }

    @Test
    void nothingIsPostedWithoutAPoolOrWithoutParking() {
        pool.set(null);
        service.settleParkedExcessForDueDatesFrom(loan, LocalDate.of(2027, 1, 31), true);

        pool.set(BigDecimal.valueOf(6240));
        lenient().when(detail.isEnableExcessPaymentParking()).thenReturn(false);
        service.settleParkedExcessForDueDatesFrom(loan, LocalDate.of(2027, 1, 31), true);

        verify(penaltyService, never()).reconcileOverduePenaltiesUpToDate(any(), any());
        verifySweepCount(0);
    }

    private void verifySweep(final org.mockito.verification.VerificationMode mode, final LocalDate date, final BigDecimal amount) {
        verify(service, mode).makeRepayment(eq(LoanTransactionType.REPAYMENT_FROM_EXCESS_AMOUNT), eq(loan), eq(date),
                argThat(value -> value != null && value.compareTo(amount) == 0), any(), any(), any(), anyBoolean(), any(), anyBoolean(),
                any(), any(), anyBoolean());
    }

    private void verifySweepCount(final int count) {
        verify(service, times(count)).makeRepayment(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean(),
                any(), any(), anyBoolean());
    }

    private static Money smaller(final Money first, final Money second) {
        return first.isGreaterThan(second) ? second : first;
    }

    private LoanRepaymentScheduleInstallment installment(final int number, final LocalDate from, final LocalDate due) {
        return new LoanRepaymentScheduleInstallment(loan, number, from, due, BigDecimal.valueOf(3000), BigDecimal.valueOf(120),
                BigDecimal.ZERO, BigDecimal.ZERO, false, null, BigDecimal.ZERO);
    }

    private void setField(final String name, final Object value) throws ReflectiveOperationException {
        final Field field = LoanAccountDomainServiceJpa.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
