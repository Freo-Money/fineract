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
package org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.refEq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionToRepaymentScheduleMapping;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.service.LoanBalanceService;
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

/**
 * Unit tests for the migration-cutoff guard in
 * {@link org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.AbstractLoanRepaymentScheduleTransactionProcessor#reprocessLoanTransactions
 * reprocessLoanTransactions(...migrationCutoffDate)}.
 *
 * <p>
 * Behaviour under test: when {@code migrationCutoffDate} is non-null, transactions with
 * {@code transactionDate < migrationCutoffDate} are replayed via their stored
 * {@link LoanTransactionToRepaymentScheduleMapping} rows (strategy bypassed); transactions on or after the cutoff flow
 * through the strategy. When {@code migrationCutoffDate} is null, behaviour matches the cutoff-unaware overload.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
public class MigrationCutoffReprocessTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("USD", 2, 1);
    private static final MockedStatic<MoneyHelper> MONEY_HELPER = Mockito.mockStatic(MoneyHelper.class);

    private static final LocalDate DISBURSEMENT_DATE = LocalDate.of(2022, 7, 11);
    private static final LocalDate MIGRATION_CUTOFF = LocalDate.of(2024, 6, 1);
    private static final LocalDate PRE_CUTOFF_TXN_DATE = LocalDate.of(2022, 9, 30);
    private static final LocalDate POST_CUTOFF_TXN_DATE = LocalDate.of(2024, 8, 15);

    private OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessor underTest;

    @Mock
    private Office office;

    @Mock
    private Loan loan;

    @Mock
    private LoanChargeValidator loanChargeValidator;

    @Mock
    private LoanBalanceService loanBalanceService;

    @BeforeAll
    public static void init() {
        MONEY_HELPER.when(MoneyHelper::getMathContext).thenReturn(new MathContext(12, RoundingMode.HALF_EVEN));
        MONEY_HELPER.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
    }

    @AfterAll
    public static void destruct() {
        MONEY_HELPER.close();
    }

    @BeforeEach
    public void setUp() {
        underTest = new OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessor(
                Mockito.mock(ExternalIdFactory.class), loanChargeValidator, loanBalanceService);
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil
                .setBusinessDates(new java.util.HashMap<>(java.util.Map.of(BusinessDateType.BUSINESS_DATE, POST_CUTOFF_TXN_DATE)));
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    public void migratedTransactionWithPenaltyMapping_isReplayedViaMapping_strategyBypassed() {
        // Spy installment: stub payPenaltyChargesComponent to act as if 590 is outstanding (the production
        // wrapper.reprocess
        // would normally load this from LoanCharge rows, which we mock-bypass here for unit-test focus).
        final LoanRepaymentScheduleInstallment installment = spy(
                new LoanRepaymentScheduleInstallment(loan, 1, DISBURSEMENT_DATE, LocalDate.of(2022, 10, 5), BigDecimal.valueOf(2500),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(590), false, null, BigDecimal.ZERO));
        final List<LoanRepaymentScheduleInstallment> installments = new ArrayList<>(List.of(installment));

        // Pre-cutoff repayment of 590 with stored mapping: penalty=590, all others zero.
        final Money fiveNinety = Money.of(CURRENCY, BigDecimal.valueOf(590));
        final Money zero = Money.zero(CURRENCY);
        Mockito.doReturn(fiveNinety).when(installment).payPenaltyChargesComponent(any(LocalDate.class), any(Money.class));

        final LoanTransaction migratedTxn = spy(
                LoanTransaction.repayment(office, fiveNinety, null, PRE_CUTOFF_TXN_DATE, ExternalId.empty()));
        Mockito.lenient().when(migratedTxn.getLoan()).thenReturn(loan);
        Mockito.lenient().when(loan.getId()).thenReturn(42L);

        final LoanTransactionToRepaymentScheduleMapping mapping = LoanTransactionToRepaymentScheduleMapping.createFrom(migratedTxn,
                installment, zero, zero, zero, fiveNinety);
        migratedTxn.getLoanTransactionToRepaymentScheduleMappings().add(mapping);

        underTest.reprocessLoanTransactions(DISBURSEMENT_DATE, new ArrayList<>(List.of(migratedTxn)), CURRENCY, installments,
                new HashSet<>(), MIGRATION_CUTOFF);

        // Migrated txn must apply penalty 590 directly to the mapped installment.
        verify(installment, times(1)).payPenaltyChargesComponent(eq(PRE_CUTOFF_TXN_DATE), refEq(fiveNinety));
        // Strategy first-pass (principal across all) MUST NOT touch principal for a migrated txn.
        verify(installment, never()).payPrincipalComponent(eq(PRE_CUTOFF_TXN_DATE), any(Money.class));
        verify(installment, never()).payInterestComponent(eq(PRE_CUTOFF_TXN_DATE), any(Money.class));
        verify(installment, never()).payFeeChargesComponent(eq(PRE_CUTOFF_TXN_DATE), any(Money.class));
    }

    @Test
    public void migratedTransactionWithBadComponentSum_isSkippedNotApplied() {
        final LoanRepaymentScheduleInstallment installment = spy(
                new LoanRepaymentScheduleInstallment(loan, 1, DISBURSEMENT_DATE, LocalDate.of(2022, 10, 5), BigDecimal.valueOf(2500),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(590), false, null, BigDecimal.ZERO));
        final List<LoanRepaymentScheduleInstallment> installments = new ArrayList<>(List.of(installment));

        // Transaction amount = 590, but mapping says principal=100, penalty=100 (sum=200) — inconsistent.
        final Money txnAmount = Money.of(CURRENCY, BigDecimal.valueOf(590));
        final Money hundred = Money.of(CURRENCY, BigDecimal.valueOf(100));
        final Money zero = Money.zero(CURRENCY);
        final LoanTransaction badMigratedTxn = spy(
                LoanTransaction.repayment(office, txnAmount, null, PRE_CUTOFF_TXN_DATE, ExternalId.empty()));
        when(badMigratedTxn.getLoan()).thenReturn(loan);

        final LoanTransactionToRepaymentScheduleMapping mapping = LoanTransactionToRepaymentScheduleMapping.createFrom(badMigratedTxn,
                installment, hundred, zero, zero, hundred);
        badMigratedTxn.getLoanTransactionToRepaymentScheduleMappings().add(mapping);

        underTest.reprocessLoanTransactions(DISBURSEMENT_DATE, new ArrayList<>(List.of(badMigratedTxn)), CURRENCY, installments,
                new HashSet<>(), MIGRATION_CUTOFF);

        // No payXxxComponent calls — txn was skipped due to sum mismatch.
        verify(installment, never()).payPrincipalComponent(any(LocalDate.class), any(Money.class));
        verify(installment, never()).payInterestComponent(any(LocalDate.class), any(Money.class));
        verify(installment, never()).payFeeChargesComponent(any(LocalDate.class), any(Money.class));
        verify(installment, never()).payPenaltyChargesComponent(any(LocalDate.class), any(Money.class));
    }

    @Test
    public void nullCutoff_treatsAllTransactionsAsCurrent_strategyRuns() {
        final LoanRepaymentScheduleInstallment installment = spy(
                new LoanRepaymentScheduleInstallment(loan, 1, DISBURSEMENT_DATE, LocalDate.of(2022, 10, 5), BigDecimal.valueOf(2500),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(590), false, null, BigDecimal.ZERO));
        final List<LoanRepaymentScheduleInstallment> installments = new ArrayList<>(List.of(installment));

        // Pre-cutoff dated repayment of 590, but no cutoff configured.
        final Money txnAmount = Money.of(CURRENCY, BigDecimal.valueOf(590));
        final LoanTransaction txn = spy(LoanTransaction.repayment(office, txnAmount, null, PRE_CUTOFF_TXN_DATE, ExternalId.empty()));
        Mockito.lenient().when(txn.getLoan()).thenReturn(loan);
        Mockito.lenient().when(loan.getRepaymentScheduleInstallments()).thenReturn(installments);

        underTest.reprocessLoanTransactions(DISBURSEMENT_DATE, new ArrayList<>(List.of(txn)), CURRENCY, installments, new HashSet<>(),
                null);

        // Strategy first-pass (principal across all installments) MUST be invoked — principal of 590 applied to
        // installment 1 since principal is highest priority for this strategy.
        verify(installment, times(1)).payPrincipalComponent(eq(PRE_CUTOFF_TXN_DATE), any(Money.class));
        // And NO penalty is paid (would have been the FinFlux allocation, but strategy reallocates).
        verify(installment, never()).payPenaltyChargesComponent(eq(PRE_CUTOFF_TXN_DATE), any(Money.class));
    }

    @Test
    public void migratedTransactionWithChargePaidBy_relinksLoanChargeAmountPaid() {
        final LoanRepaymentScheduleInstallment installment = spy(
                new LoanRepaymentScheduleInstallment(loan, 1, DISBURSEMENT_DATE, LocalDate.of(2022, 10, 5), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(590), false, null, BigDecimal.ZERO));
        final List<LoanRepaymentScheduleInstallment> installments = new ArrayList<>(List.of(installment));

        final LoanCharge penaltyCharge = Mockito.mock(LoanCharge.class);
        when(penaltyCharge.isDueAtDisbursement()).thenReturn(false);
        final Set<LoanCharge> charges = new HashSet<>();
        charges.add(penaltyCharge);

        final Money fiveNinety = Money.of(CURRENCY, BigDecimal.valueOf(590));
        final Money zero = Money.zero(CURRENCY);
        final LoanTransaction migratedTxn = spy(
                LoanTransaction.repayment(office, fiveNinety, null, PRE_CUTOFF_TXN_DATE, ExternalId.empty()));
        when(migratedTxn.getLoan()).thenReturn(loan);

        // Mapping: penalty=590, others zero
        final LoanTransactionToRepaymentScheduleMapping mapping = LoanTransactionToRepaymentScheduleMapping.createFrom(migratedTxn,
                installment, zero, zero, zero, fiveNinety);
        migratedTxn.getLoanTransactionToRepaymentScheduleMappings().add(mapping);

        // ChargePaidBy linking the 590 to the penalty charge
        final LoanChargePaidBy chargePaidBy = new LoanChargePaidBy(migratedTxn, penaltyCharge, BigDecimal.valueOf(590), null,
                RoundingMode.HALF_EVEN);
        migratedTxn.getLoanChargesPaid().add(chargePaidBy);

        underTest.reprocessLoanTransactions(DISBURSEMENT_DATE, new ArrayList<>(List.of(migratedTxn)), CURRENCY, installments, charges,
                MIGRATION_CUTOFF);

        // Charge.updatePaidAmountBy must be called with the migrated paid amount.
        verify(penaltyCharge, times(1)).updatePaidAmountBy(refEq(fiveNinety), any(), refEq(fiveNinety));
        // The charge is reset at the start of reprocess (resetPaidAmount), then re-linked by our replay.
        verify(penaltyCharge, times(1)).resetPaidAmount(CURRENCY);
    }

    /**
     * Pre-cutoff transactions whose semantics do not map to paid-amount methods (e.g., write-off, charges waiver,
     * interest waiver) must NOT be replayed via {@code installment.payXxxComponent}; they need to flow through the
     * strategy / specific handlers so the correct fields (e.g., {@code principalWrittenOff}, {@code feeChargesWaived})
     * are populated.
     */
    @Test
    public void preCutoffWriteOff_isNotReplayedViaPayComponents() {
        final LoanRepaymentScheduleInstallment installment = spy(
                new LoanRepaymentScheduleInstallment(loan, 1, DISBURSEMENT_DATE, LocalDate.of(2022, 10, 5), BigDecimal.valueOf(2500),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, null, BigDecimal.ZERO));
        final List<LoanRepaymentScheduleInstallment> installments = new ArrayList<>(List.of(installment));

        // Build a pre-cutoff WRITE_OFF transaction with a mapping (as migration might have created).
        final Money writeOffAmount = Money.of(CURRENCY, BigDecimal.valueOf(2500));
        final Money zero = Money.zero(CURRENCY);
        final LoanTransaction writeOffTxn = spy(LoanTransaction.writeoff(loan, office, PRE_CUTOFF_TXN_DATE, ExternalId.empty()));
        Mockito.lenient().when(writeOffTxn.getLoan()).thenReturn(loan);
        // Force amount so the txn has a numeric value (writeoff constructor leaves amount null in test).
        Mockito.lenient().doReturn(writeOffAmount).when(writeOffTxn).getAmount(any(MonetaryCurrency.class));

        final LoanTransactionToRepaymentScheduleMapping mapping = LoanTransactionToRepaymentScheduleMapping.createFrom(writeOffTxn,
                installment, writeOffAmount, zero, zero, zero);
        writeOffTxn.getLoanTransactionToRepaymentScheduleMappings().add(mapping);

        underTest.reprocessLoanTransactions(DISBURSEMENT_DATE, new ArrayList<>(List.of(writeOffTxn)), CURRENCY, installments,
                new HashSet<>(), MIGRATION_CUTOFF);

        // Write-off must NOT be applied via paid-amount methods. principalCompleted must stay zero — the principal is
        // written off, not paid.
        verify(installment, never()).payPrincipalComponent(any(LocalDate.class), any(Money.class));
        verify(installment, never()).payInterestComponent(any(LocalDate.class), any(Money.class));
        verify(installment, never()).payFeeChargesComponent(any(LocalDate.class), any(Money.class));
        verify(installment, never()).payPenaltyChargesComponent(any(LocalDate.class), any(Money.class));
    }
}
