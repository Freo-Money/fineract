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

import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.INR;
import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.chargePayment;
import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.installment;
import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.penaltyCharge;
import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.receipt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.UnsupportedParameterException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanLifecycleStateMachine;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoanChargePaymentReceiptRepairServiceImplTest {

    private static final MockedStatic<MoneyHelper> MONEY_HELPER = Mockito.mockStatic(MoneyHelper.class);
    private static final long LOAN_ID = 386607L;
    private static final LocalDate PAYMENT_DATE = LocalDate.of(2026, 6, 30);

    @Mock
    private LoanAssembler loanAssembler;
    @Mock
    private LoanWritePlatformService loanWritePlatformService;
    @Mock
    private LoanProduct loanProduct;
    @Mock
    private LoanLifecycleStateMachine loanLifecycleStateMachine;
    @Mock
    private LoanRepository loanRepository;
    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private Loan loan;
    @Mock
    private Office office;

    private final FromJsonHelper fromJsonHelper = new FromJsonHelper();
    private LoanChargePaymentReceiptRepairServiceImpl underTest;
    private LoanTransaction payment;
    private LoanChargePaidBy clipped;
    private LoanCharge may11;

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
    void setUp() {
        ChargePaymentReceiptFixtures.setBusinessDate(PAYMENT_DATE);
        underTest = new LoanChargePaymentReceiptRepairServiceImpl(loanAssembler, loanWritePlatformService, loanLifecycleStateMachine,
                loanRepository, configurationDomainService, fromJsonHelper);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, LocalDate.of(2026, 5, 5), LocalDate.of(2026, 6, 5), 100,
                10);
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        payment = chargePayment(100L, loan, office, PAYMENT_DATE, 14);
        receipt(11L, payment, may10, 7);
        clipped = receipt(12L, payment, may11, 5);
        payment.updateComponents(Money.zero(INR), Money.zero(INR), Money.zero(INR), Money.of(INR, BigDecimal.valueOf(12)));
        payment.setOverPayments(Money.of(INR, BigDecimal.valueOf(2)));

        when(loanAssembler.assembleFrom(LOAN_ID)).thenReturn(loan);
        when(loan.getId()).thenReturn(LOAN_ID);
        when(loan.getCurrency()).thenReturn(INR);
        when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        when(loan.getExternalId()).thenReturn(ExternalId.empty());
        when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(installment));
        when(loan.getLoanTransactions()).thenReturn(List.of(payment));
        when(loan.isNotDisbursed()).thenReturn(false);
        when(loan.isProgressiveSchedule()).thenReturn(false);
        when(loan.isChargedOff()).thenReturn(false);
        when(loan.isClosedWrittenOff()).thenReturn(false);
        when(loan.getLoanProduct()).thenReturn(loanProduct);
        when(loanProduct.isAccountingDisabled()).thenReturn(true);
        when(configurationDomainService.getTaxRoundingMode()).thenReturn(RoundingMode.HALF_EVEN);
    }

    @AfterEach
    void tearDown() {
        ChargePaymentReceiptFixtures.resetContext();
    }

    @Test
    void dryRunByDefault_reportsThePlan_andTouchesNothing() {
        final CommandProcessingResult result = underTest.repairChargePaymentReceipts(LOAN_ID, JsonCommand.from(null));

        assertEquals("DRY_RUN", result.getChanges().get("action"));
        assertEquals(BigDecimal.valueOf(5), clipped.getAmount());
        verify(loanWritePlatformService, never()).reprocessLoanTransactions(any(), any());
        verify(loanLifecycleStateMachine, never()).determineAndTransition(any(), any());
        verify(loanRepository, never()).saveAndFlush(any());
        final Map<String, Object> transaction = firstTransaction(result);
        assertEquals("CLIPPED", transaction.get("classification"));
        assertEquals(Boolean.FALSE, transaction.get("repaired"));
    }

    @Test
    void liveRun_topsUpReceipts_reprocesses_transitions_andReportsRepaired() {
        doAnswer(invocation -> {
            // the real reprocess resets the derived components and replays the repaired receipts
            payment.resetDerivedComponents();
            payment.updateComponents(Money.zero(INR), Money.zero(INR), Money.zero(INR), Money.of(INR, BigDecimal.valueOf(14)));
            payment.setOverPayments(Money.zero(INR));
            return null;
        }).when(loanWritePlatformService).reprocessLoanTransactions(eq(LOAN_ID), any(JsonCommand.class));

        final CommandProcessingResult result = underTest.repairChargePaymentReceipts(LOAN_ID, liveCommand());

        assertEquals("REPAIRED", result.getChanges().get("action"));
        assertEquals(BigDecimal.valueOf(7), clipped.getAmount());
        // the standard command owns reprocess + transition + save
        verify(loanWritePlatformService).reprocessLoanTransactions(eq(LOAN_ID), any(JsonCommand.class));
        verify(loanLifecycleStateMachine, never()).determineAndTransition(any(), any());
        verify(loanRepository, never()).saveAndFlush(any());
        final Map<String, Object> transaction = firstTransaction(result);
        assertEquals(Boolean.TRUE, transaction.get("repaired"));
        assertEquals(BigDecimal.valueOf(14), transaction.get("receiptsTotalAfter"));
    }

    @Test
    void liveRun_failsWhenTheReprocessStillLeavesOverpayment() {
        // reprocess mocked as a no-op: portions stay 12 / 2 although receipts now sum to 14
        assertThrows(GeneralPlatformDomainRuleException.class, () -> underTest.repairChargePaymentReceipts(LOAN_ID, liveCommand()));
        verify(loanWritePlatformService).reprocessLoanTransactions(eq(LOAN_ID), any(JsonCommand.class));
    }

    @Test
    void staleOverpaidDate_withConsistentReceipts_onlyRunsTheStatusTransition() {
        clipped.setAmount(BigDecimal.valueOf(7), RoundingMode.HALF_EVEN);
        payment.resetDerivedComponents();
        payment.updateComponents(Money.zero(INR), Money.zero(INR), Money.zero(INR), Money.of(INR, BigDecimal.valueOf(14)));
        payment.setOverPayments(Money.zero(INR));
        when(loan.getOverpaidOnDate()).thenReturn(LocalDate.of(2026, 10, 5));

        final CommandProcessingResult result = underTest.repairChargePaymentReceipts(LOAN_ID, liveCommand());

        assertEquals("STATUS_REFRESHED", result.getChanges().get("action"));
        verify(loanWritePlatformService, never()).reprocessLoanTransactions(any(), any());
        verify(loanLifecycleStateMachine).determineAndTransition(eq(loan), any(LocalDate.class));
        verify(loanRepository).saveAndFlush(loan);
    }

    @Test
    void consistentLoan_isANoOp() {
        clipped.setAmount(BigDecimal.valueOf(7), RoundingMode.HALF_EVEN);
        payment.resetDerivedComponents();
        payment.updateComponents(Money.zero(INR), Money.zero(INR), Money.zero(INR), Money.of(INR, BigDecimal.valueOf(14)));
        payment.setOverPayments(Money.zero(INR));

        final CommandProcessingResult result = underTest.repairChargePaymentReceipts(LOAN_ID, liveCommand());

        assertEquals("NO_CHANGE", result.getChanges().get("action"));
        verify(loanWritePlatformService, never()).reprocessLoanTransactions(any(), any());
        verify(loanLifecycleStateMachine, never()).determineAndTransition(any(), any());
        verify(loanRepository, never()).saveAndFlush(any());
    }

    @Test
    void unknownParameter_isRejected() {
        assertThrows(UnsupportedParameterException.class,
                () -> underTest.repairChargePaymentReceipts(LOAN_ID, command("{\"dryRun\": false, \"force\": true}")));
    }

    @Test
    void nonBooleanDryRun_isRejected() {
        // Every lenient reader turns "yes", 1 or null into false, which would silently run the destructive repair.
        for (final String value : new String[] { "\"yes\"", "1", "null", "\"true\"" }) {
            final String json = "{\"dryRun\": " + value + "}";
            assertThrows(GeneralPlatformDomainRuleException.class, () -> underTest.repairChargePaymentReceipts(LOAN_ID, command(json)));
        }
    }

    @Test
    void staleOverpaidDate_withAnUnrepairableDefect_isNotRefreshed() {
        // receipts 7 + 5 but the charge of the clipped receipt is now only worth 5: nothing to top up → SURPLUS
        may11.setAmount(BigDecimal.valueOf(5));
        may11.setAmountOutstanding(BigDecimal.valueOf(5));
        when(loan.getOverpaidOnDate()).thenReturn(LocalDate.of(2026, 10, 5));

        final CommandProcessingResult result = underTest.repairChargePaymentReceipts(LOAN_ID, liveCommand());

        assertEquals("NO_CHANGE", result.getChanges().get("action"));
        assertEquals("SURPLUS", firstTransaction(result).get("classification"));
        verify(loanLifecycleStateMachine, never()).determineAndTransition(any(), any());
    }

    @Test
    void liveRun_failsWhenAPreviouslyConsistentChargePaymentRegresses() {
        final LoanCharge jun = penaltyCharge(3L, LocalDate.of(2026, 5, 12), 4);
        final LoanTransaction consistent = chargePayment(101L, loan, office, PAYMENT_DATE, 4);
        receipt(13L, consistent, jun, 2);
        receipt(14L, consistent, penaltyCharge(4L, LocalDate.of(2026, 5, 13), 2), 2);
        consistent.updateComponents(Money.zero(INR), Money.zero(INR), Money.zero(INR), Money.of(INR, BigDecimal.valueOf(4)));
        when(loan.getLoanTransactions()).thenReturn(List.of(payment, consistent));
        doAnswer(invocation -> {
            payment.resetDerivedComponents();
            payment.updateComponents(Money.zero(INR), Money.zero(INR), Money.zero(INR), Money.of(INR, BigDecimal.valueOf(14)));
            // the sibling comes up short after the reprocess
            consistent.resetDerivedComponents();
            consistent.updateComponents(Money.zero(INR), Money.zero(INR), Money.zero(INR), Money.of(INR, BigDecimal.valueOf(3)));
            consistent.setOverPayments(Money.of(INR, BigDecimal.ONE));
            return null;
        }).when(loanWritePlatformService).reprocessLoanTransactions(eq(LOAN_ID), any(JsonCommand.class));

        assertThrows(GeneralPlatformDomainRuleException.class, () -> underTest.repairChargePaymentReceipts(LOAN_ID, liveCommand()));
    }

    @Test
    void accountingEnabledProduct_isRejected() {
        when(loanProduct.isAccountingDisabled()).thenReturn(false);
        assertThrows(GeneralPlatformDomainRuleException.class, () -> underTest.repairChargePaymentReceipts(LOAN_ID, liveCommand()));
        verify(loanWritePlatformService, never()).reprocessLoanTransactions(any(), any());
    }

    @Test
    void creditBalanceRefund_isRejected() {
        final LoanTransaction refund = LoanTransaction.loanPayment(loan, office, Money.of(INR, BigDecimal.valueOf(2)), null,
                LocalDate.of(2026, 10, 6), ExternalId.empty(), LoanTransactionType.CREDIT_BALANCE_REFUND);
        when(loan.getLoanTransactions()).thenReturn(List.of(payment, refund));
        assertThrows(GeneralPlatformDomainRuleException.class, () -> underTest.repairChargePaymentReceipts(LOAN_ID, liveCommand()));
    }

    @Test
    void writtenOffLoan_isRejected() {
        when(loan.isClosedWrittenOff()).thenReturn(true);
        assertThrows(GeneralPlatformDomainRuleException.class, () -> underTest.repairChargePaymentReceipts(LOAN_ID, liveCommand()));
    }

    @Test
    void progressiveLoan_isRejected() {
        when(loan.isProgressiveSchedule()).thenReturn(true);
        assertThrows(GeneralPlatformDomainRuleException.class, () -> underTest.repairChargePaymentReceipts(LOAN_ID, liveCommand()));
    }

    private JsonCommand liveCommand() {
        return command("{\"dryRun\": false}");
    }

    private JsonCommand command(final String json) {
        return JsonCommand.from(json, fromJsonHelper.parse(json), fromJsonHelper, "LOAN", LOAN_ID, null, null, null, LOAN_ID, null, null,
                null, null, null, null, null, ExternalId.empty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstTransaction(final CommandProcessingResult result) {
        return ((List<Map<String, Object>>) result.getChanges().get("transactions")).get(0);
    }
}
