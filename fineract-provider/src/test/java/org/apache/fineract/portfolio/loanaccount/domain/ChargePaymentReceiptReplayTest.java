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

import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.INR;
import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.chargePayment;
import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.installment;
import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.penaltyCharge;
import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.receipt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.configuration.service.TemporaryConfigurationServiceContainer;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.impl.OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.service.LoanBalanceService;
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

/**
 * Release behaviour, with charge-set allocation disabled: a multi-receipt charge payment replays its stored receipt
 * amounts verbatim, so a receipt set that no longer sums to the transaction amount parks the gap as overpayment (the
 * defect the repair command exists for), and charges are marked by the legacy earliest-unpaid walk. These tests exist
 * to prove the branch changes nothing while the flag is off; the flag-on behaviour is in
 * {@link ChargePaymentChargeSetAllocationReplayTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChargePaymentReceiptReplayTest {

    private static final MockedStatic<MoneyHelper> MONEY_HELPER = Mockito.mockStatic(MoneyHelper.class);
    /** With charge-set allocation off, charge marking goes through the legacy path, which reads this static holder. */
    private static final MockedStatic<TemporaryConfigurationServiceContainer> TEMP_CONFIG = Mockito
            .mockStatic(TemporaryConfigurationServiceContainer.class);
    private static final LocalDate DISBURSEMENT = LocalDate.of(2026, 4, 5);
    private static final LocalDate FROM = LocalDate.of(2026, 5, 5);
    private static final LocalDate DUE = LocalDate.of(2026, 6, 5);
    private static final LocalDate PAYMENT_DATE = LocalDate.of(2026, 6, 30);

    @Mock
    private Loan loan;
    @Mock
    private Office office;
    @Mock
    private LoanProduct loanProduct;

    private OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessor underTest;

    @BeforeAll
    static void init() {
        MONEY_HELPER.when(MoneyHelper::getMathContext).thenReturn(new MathContext(12, RoundingMode.HALF_EVEN));
        MONEY_HELPER.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
        TEMP_CONFIG.when(TemporaryConfigurationServiceContainer::getTaxRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
    }

    @AfterAll
    static void destruct() {
        MONEY_HELPER.close();
        TEMP_CONFIG.close();
    }

    @BeforeEach
    void setUp() {
        underTest = new OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessor(
                Mockito.mock(ExternalIdFactory.class), Mockito.mock(LoanChargeValidator.class), Mockito.mock(LoanBalanceService.class));
        ChargePaymentReceiptFixtures.setBusinessDate(PAYMENT_DATE);
        // Every other condition charge-set allocation needs is satisfied here, so the disabled configuration flag is
        // the ONLY reason these tests see release behaviour. Flip the flag and they must fail.
        Mockito.when(loan.getLoanProduct()).thenReturn(loanProduct);
        Mockito.when(loanProduct.isAccountingDisabled()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        ChargePaymentReceiptFixtures.resetContext();
    }

    @Test
    void clippedReceipts_parkTheGapAsOverpayment_andTheLegacyWalkMarksTheEarliestChargesFirst() {
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanCharge may12 = penaltyCharge(3L, LocalDate.of(2026, 5, 12), 2);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 16);
        receipt(11L, payment, may10, 7);
        receipt(12L, payment, may11, 5); // clipped by the pre-#153 re-derivation: should be 7
        receipt(13L, payment, may12, 2);

        Mockito.when(loan.getRepaymentScheduleInstallments()).thenReturn(new ArrayList<>(List.of(installment)));
        Mockito.when(loan.getLoanTransactions()).thenReturn(new ArrayList<>(List.of(payment)));
        underTest.reprocessLoanTransactions(DISBURSEMENT, List.of(payment), INR, List.of(installment), charges(may10, may11, may12));

        assertEquals(BigDecimal.valueOf(14), payment.getPenaltyChargesPortion(INR).getAmount());
        assertEquals(BigDecimal.valueOf(2), payment.getOverPaymentPortion(INR).getAmount());
        assertEquals(BigDecimal.valueOf(14), installment.getPenaltyChargesPaid(INR).getAmount());
        // The legacy walk spends the applied 14 on the earliest charges first, so it reports may11 as fully paid even
        // though its receipt carries 5, and may12 as unpaid even though its receipt carries 2. That divergence between
        // the charges and the receipts is what charge-set allocation fixes; here it must be preserved.
        assertEquals(BigDecimal.valueOf(7), may10.getAmountPaid(INR).getAmount());
        assertEquals(BigDecimal.valueOf(7), may11.getAmountPaid(INR).getAmount());
        assertTrue(may12.getAmountPaid(INR).isZero());
        assertTrue(may10.isPaid());
        assertTrue(may11.isPaid());
        assertFalse(may12.isPaid());
    }

    @Test
    void consistentReceipts_areFullyAbsorbed_andEveryChargeIsPaid() {
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanCharge may12 = penaltyCharge(3L, LocalDate.of(2026, 5, 12), 2);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 16);
        receipt(11L, payment, may10, 7);
        receipt(12L, payment, may11, 7);
        receipt(13L, payment, may12, 2);

        Mockito.when(loan.getRepaymentScheduleInstallments()).thenReturn(new ArrayList<>(List.of(installment)));
        Mockito.when(loan.getLoanTransactions()).thenReturn(new ArrayList<>(List.of(payment)));
        underTest.reprocessLoanTransactions(DISBURSEMENT, List.of(payment), INR, List.of(installment), charges(may10, may11, may12));

        assertEquals(BigDecimal.valueOf(16), payment.getPenaltyChargesPortion(INR).getAmount());
        assertTrue(payment.getOverPaymentPortion(INR).isZero());
        assertEquals(BigDecimal.valueOf(16), installment.getPenaltyChargesPaid(INR).getAmount());
        assertTrue(may10.isPaid());
        assertTrue(may11.isPaid());
        assertTrue(may12.isPaid());
    }

    @Test
    void receiptOnInactiveCharge_isStillMarkedByTheLegacyWalk() {
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        may11.setActive(false);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 14);
        receipt(11L, payment, may10, 7);
        receipt(12L, payment, may11, 7);

        Mockito.when(loan.getRepaymentScheduleInstallments()).thenReturn(new ArrayList<>(List.of(installment)));
        Mockito.when(loan.getLoanTransactions()).thenReturn(new ArrayList<>(List.of(payment)));
        underTest.reprocessLoanTransactions(DISBURSEMENT, List.of(payment), INR, List.of(installment), charges(may10, may11));

        assertEquals(BigDecimal.valueOf(14), payment.getPenaltyChargesPortion(INR).getAmount());
        assertEquals(BigDecimal.valueOf(7), may10.getAmountPaid(INR).getAmount());
        // findEarliestUnpaidChargeFromUnOrderedSet filters on outstanding, not on active, so the legacy walk marks a
        // deactivated charge as paid. Charge-set allocation refuses to, and reports it for review instead.
        assertEquals(BigDecimal.valueOf(7), may11.getAmountPaid(INR).getAmount());
    }

    private static Set<LoanCharge> charges(final LoanCharge... charges) {
        return new LinkedHashSet<>(List.of(charges));
    }
}
