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
import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.feeCharge;
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
import org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptRepairClassification;
import org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptRepairPlan;
import org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptRepairPlanner;
import org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptTopUp;
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

/**
 * End-to-end replay of the two production shapes (loans 386607 and 444453, data as of 2026-10-14) through the real
 * overdue-due-adv strategy: first as they are today (clipped receipts → gap parked as overpayment), then after the
 * planner's top-ups (the repair command's effect) → fully allocated, charges paid, installments complete.
 */
@ExtendWith(MockitoExtension.class)
class ChargePaymentReceiptProdShapeReplayTest {

    private static final MockedStatic<MoneyHelper> MONEY_HELPER = Mockito.mockStatic(MoneyHelper.class);
    /**
     * The legacy single-receipt path reads the tax rounding mode from a static holder; seed it like production does.
     */
    private static final MockedStatic<TemporaryConfigurationServiceContainer> TEMP_CONFIG = Mockito
            .mockStatic(TemporaryConfigurationServiceContainer.class);

    @Mock
    private Loan loan;
    @Mock
    private Office office;
    @Mock
    private LoanProduct loanProduct;

    private OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessor processor;
    private final ChargePaymentReceiptRepairPlanner planner = new ChargePaymentReceiptRepairPlanner();

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
        processor = new OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessor(
                Mockito.mock(ExternalIdFactory.class), Mockito.mock(LoanChargeValidator.class), Mockito.mock(LoanBalanceService.class));
        ChargePaymentReceiptFixtures.setBusinessDate(LocalDate.of(2026, 10, 14));
    }

    @AfterEach
    void tearDown() {
        ChargePaymentReceiptFixtures.resetContext();
        // charge-set allocation is opt-in; leave the shared static mock off for the other tests
        TEMP_CONFIG.when(TemporaryConfigurationServiceContainer::isChargePaymentChargeSetAllocationEnabled).thenReturn(false);
    }

    /**
     * Loan 386607's penalty payment, replayed once with charge-set allocation enabled: no repair command is involved,
     * the two clipped receipts are restored to 7 by the replay itself and nothing is parked.
     */
    @Test
    void loan386607_healsOnReprocess_whenChargeSetAllocationEnabled() {
        TEMP_CONFIG.when(TemporaryConfigurationServiceContainer::isChargePaymentChargeSetAllocationEnabled).thenReturn(true);
        Mockito.when(loan.getLoanProduct()).thenReturn(loanProduct);
        Mockito.when(loanProduct.isAccountingDisabled()).thenReturn(true);

        final LocalDate disbursement = LocalDate.of(2024, 7, 31);
        final LoanRepaymentScheduleInstallment inst23 = installment(loan, 23, LocalDate.of(2026, 5, 5), LocalDate.of(2026, 6, 5), 5866,
                2734);
        final List<LoanRepaymentScheduleInstallment> installments = List.of(inst23);
        // the processor only derives receipt amounts when the replay targets the loan's own schedule
        Mockito.when(loan.getRepaymentScheduleInstallments()).thenReturn(installments);
        final Set<LoanCharge> charges = new LinkedHashSet<>();
        final List<LoanCharge> penalties = new ArrayList<>();

        final LoanTransaction penaltyPayment = chargePayment(8239827L, loan, office, LocalDate.of(2026, 6, 30), 151);
        long receiptId = 5178008L;
        for (int day = 6; day <= 26; day++) {
            final LoanCharge charge = penaltyCharge(42500000L + day, LocalDate.of(2026, 5, day), 7);
            penalties.add(charge);
            charges.add(charge);
            receipt(receiptId++, penaltyPayment, charge, day == 20 ? 5 : day == 25 ? 3 : 7);
        }
        for (int day = 27; day <= 28; day++) {
            final LoanCharge charge = penaltyCharge(46300000L + day, LocalDate.of(2026, 5, day), 2);
            penalties.add(charge);
            charges.add(charge);
            receipt(receiptId++, penaltyPayment, charge, 2);
        }

        Mockito.when(loan.getLoanTransactions()).thenReturn(new ArrayList<>(List.of(penaltyPayment)));
        processor.reprocessLoanTransactions(disbursement, List.of(penaltyPayment), INR, installments, charges);

        assertEquals(amt(151), penaltyPayment.getPenaltyChargesPortion(INR).getAmount());
        assertTrue(penaltyPayment.getOverPaymentPortion(INR).isZero());
        assertEquals(amt(151), penaltyPayment.getLoanChargesPaidTotal(INR).getAmount());
        assertEquals(amt(151), inst23.getPenaltyChargesPaid(INR).getAmount());
        assertEquals(amt(7), byDay(penalties, 20).getAmountPaid(INR).getAmount());
        assertEquals(amt(7), byDay(penalties, 25).getAmountPaid(INR).getAmount());
        assertTrue(penalties.stream().allMatch(LoanCharge::isPaid));
        assertEquals(ChargePaymentReceiptRepairClassification.CONSISTENT,
                planner.plan(penaltyPayment, INR, installments, List.of(penaltyPayment)).classification());
    }

    /** Loan 386607: txn 8239826 (500 fee) and 8239827 (151 penalty, receipts 5178030=5 and 5178018=3 clipped). */
    @Test
    void loan386607_beforeAndAfterRepair() {
        final LocalDate disbursement = LocalDate.of(2024, 7, 31);
        final LoanRepaymentScheduleInstallment inst22 = installment(loan, 22, LocalDate.of(2026, 4, 5), LocalDate.of(2026, 5, 5), 5718,
                2882);
        final LoanRepaymentScheduleInstallment inst23 = installment(loan, 23, LocalDate.of(2026, 5, 5), LocalDate.of(2026, 6, 5), 5866,
                2734);
        final List<LoanRepaymentScheduleInstallment> installments = List.of(inst22, inst23);

        final LoanCharge feeMay = feeCharge(41843781L, LocalDate.of(2026, 5, 5), 500);
        final LoanCharge feeJun = feeCharge(55292516L, LocalDate.of(2026, 6, 5), 500);
        final Set<LoanCharge> charges = new LinkedHashSet<>(List.of(feeMay, feeJun));
        final List<LoanCharge> penalties = new ArrayList<>();

        final LoanTransaction feePayment = chargePayment(8239826L, loan, office, LocalDate.of(2026, 6, 30), 500);
        receipt(5178007L, feePayment, feeMay, 500);

        final LoanTransaction penaltyPayment = chargePayment(8239827L, loan, office, LocalDate.of(2026, 6, 30), 151);
        long receiptId = 5178008L;
        for (int day = 6; day <= 26; day++) {
            final LoanCharge charge = penaltyCharge(42500000L + day, LocalDate.of(2026, 5, day), 7);
            penalties.add(charge);
            charges.add(charge);
            receipt(receiptId++, penaltyPayment, charge, day == 20 ? 5 : day == 25 ? 3 : 7);
        }
        for (int day = 27; day <= 28; day++) {
            final LoanCharge charge = penaltyCharge(46300000L + day, LocalDate.of(2026, 5, day), 2);
            penalties.add(charge);
            charges.add(charge);
            receipt(receiptId++, penaltyPayment, charge, 2);
        }
        final List<LoanTransaction> transactions = List.of(feePayment, penaltyPayment);

        // --- as in prod today: the next reprocess re-parks the 6 ---
        processor.reprocessLoanTransactions(disbursement, transactions, INR, installments, charges);

        assertEquals(amt(500), feePayment.getFeeChargesPortion(INR).getAmount());
        assertTrue(feePayment.getOverPaymentPortion(INR).isZero());
        assertEquals(amt(145), penaltyPayment.getPenaltyChargesPortion(INR).getAmount());
        assertEquals(amt(6), penaltyPayment.getOverPaymentPortion(INR).getAmount());
        assertEquals(amt(151), inst23.getPenaltyChargesCharged(INR).getAmount());
        assertEquals(amt(145), inst23.getPenaltyChargesPaid(INR).getAmount());
        assertEquals(amt(500), inst22.getFeeChargesPaid(INR).getAmount());
        assertEquals(amt(0), inst23.getFeeChargesPaid(INR).getAmount());
        // With charge-set allocation off, charge marking is the legacy earliest-unpaid walk: the applied 145 is spent
        // on the earliest penalties, so the clipped days look fully paid and the last ones look unpaid, whatever their
        // receipts say. 20 x 7 = 140 lands on 6-25 May, the remaining 5 part-pays 26 May, and 27-28 May get nothing.
        assertEquals(amt(7), byDay(penalties, 20).getAmountPaid(INR).getAmount());
        assertEquals(amt(7), byDay(penalties, 25).getAmountPaid(INR).getAmount());
        assertEquals(amt(5), byDay(penalties, 26).getAmountPaid(INR).getAmount());
        assertFalse(byDay(penalties, 26).isPaid());
        assertTrue(byDay(penalties, 28).getAmountPaid(INR).isZero());

        // --- what the repair command does: plan, top up, reprocess ---
        final ChargePaymentReceiptRepairPlan feePlan = planner.plan(feePayment, INR, installments, transactions);
        assertEquals(ChargePaymentReceiptRepairClassification.SINGLE_RECEIPT, feePlan.classification());
        final ChargePaymentReceiptRepairPlan plan = planner.plan(penaltyPayment, INR, installments, transactions);
        assertEquals(ChargePaymentReceiptRepairClassification.CLIPPED, plan.classification());
        assertEquals(2, plan.topUps().size());
        for (final ChargePaymentReceiptTopUp topUp : plan.topUps()) {
            topUp.receipt().setAmount(topUp.to(), RoundingMode.HALF_EVEN);
        }
        assertEquals(amt(151), penaltyPayment.getLoanChargesPaidTotal(INR).getAmount());

        processor.reprocessLoanTransactions(disbursement, transactions, INR, installments, charges);

        assertEquals(amt(151), penaltyPayment.getPenaltyChargesPortion(INR).getAmount());
        assertTrue(penaltyPayment.getOverPaymentPortion(INR).isZero());
        assertEquals(amt(151), inst23.getPenaltyChargesPaid(INR).getAmount());
        assertTrue(penalties.stream().allMatch(LoanCharge::isPaid));
        assertEquals(amt(500), feePayment.getFeeChargesPortion(INR).getAmount());
        assertTrue(feeMay.isPaid());
        assertFalse(feeJun.isPaid()); // June fee is still outstanding on installment 23 — untouched by the repair
        // the loan still has 500 fee + principal/interest outstanding on 23, so it is not "complete" — only the
        // penalty residue that was blocking nothing here is gone
        assertEquals(ChargePaymentReceiptRepairClassification.CONSISTENT,
                planner.plan(penaltyPayment, INR, installments, transactions).classification());
    }

    /**
     * Loan 444453: txn 7000635 (120 penalty), FinFlux receipts 21/2/2/2 | 22/6/6/6 | 23/6/6/6/6/6, four lowered in
     * place.
     */
    @Test
    void loan444453_beforeAndAfterRepair() {
        final LocalDate disbursement = LocalDate.of(2025, 4, 18);
        final LoanRepaymentScheduleInstallment inst6 = installment(loan, 6, LocalDate.of(2025, 9, 5), LocalDate.of(2025, 10, 5), 5442,
                2564);
        final LoanRepaymentScheduleInstallment inst8 = installment(loan, 8, LocalDate.of(2025, 11, 5), LocalDate.of(2025, 12, 5), 5671,
                2335);
        final LoanRepaymentScheduleInstallment inst10 = installment(loan, 10, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 5), 5910,
                2096);
        final List<LoanRepaymentScheduleInstallment> installments = List.of(inst6, inst8, inst10);

        final LoanTransaction payment = chargePayment(7000635L, loan, office, LocalDate.of(2026, 1, 30), 120);
        final Set<LoanCharge> charges = new LinkedHashSet<>();
        final Object[][] rows = { { 4337132L, 29302494L, "2025-09-09", 21, 19 }, { 4337131L, 29341824L, "2025-09-10", 2, 2 },
                { 4337133L, 29382077L, "2025-09-11", 2, 2 }, { 4337134L, 29421124L, "2025-09-12", 2, 2 },
                { 4337136L, 31615757L, "2025-11-09", 22, 22 }, { 4337137L, 31655171L, "2025-11-10", 6, 5 },
                { 4337138L, 31695040L, "2025-11-11", 6, 6 }, { 4337139L, 31735273L, "2025-11-12", 6, 6 },
                { 4337140L, 33958274L, "2026-01-09", 23, 13 }, { 4337142L, 33998856L, "2026-01-10", 6, 6 },
                { 4337143L, 34038464L, "2026-01-11", 6, 6 }, { 4337144L, 34077734L, "2026-01-12", 6, 6 },
                { 4337135L, 34117526L, "2026-01-13", 6, 4 }, { 4337141L, 34157652L, "2026-01-14", 6, 6 } };
        for (final Object[] row : rows) {
            final LoanCharge charge = penaltyCharge((Long) row[1], LocalDate.parse((String) row[2]), (Integer) row[3]);
            charges.add(charge);
            receipt((Long) row[0], payment, charge, (Integer) row[4]);
        }
        final List<LoanTransaction> transactions = List.of(payment);

        processor.reprocessLoanTransactions(disbursement, transactions, INR, installments, charges);

        assertEquals(amt(105), payment.getPenaltyChargesPortion(INR).getAmount());
        assertEquals(amt(15), payment.getOverPaymentPortion(INR).getAmount());
        assertEquals(amt(25), inst6.getPenaltyChargesPaid(INR).getAmount());
        assertEquals(amt(39), inst8.getPenaltyChargesPaid(INR).getAmount());
        assertEquals(amt(41), inst10.getPenaltyChargesPaid(INR).getAmount());

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, installments, transactions);
        assertEquals(ChargePaymentReceiptRepairClassification.CLIPPED, plan.classification());
        assertEquals(4, plan.topUps().size());
        for (final ChargePaymentReceiptTopUp topUp : plan.topUps()) {
            topUp.receipt().setAmount(topUp.to(), RoundingMode.HALF_EVEN);
        }

        processor.reprocessLoanTransactions(disbursement, transactions, INR, installments, charges);

        assertEquals(amt(120), payment.getPenaltyChargesPortion(INR).getAmount());
        assertTrue(payment.getOverPaymentPortion(INR).isZero());
        assertEquals(amt(27), inst6.getPenaltyChargesPaid(INR).getAmount());
        assertEquals(amt(40), inst8.getPenaltyChargesPaid(INR).getAmount());
        assertEquals(amt(53), inst10.getPenaltyChargesPaid(INR).getAmount());
        assertTrue(charges.stream().allMatch(LoanCharge::isPaid));
        assertTrue(inst6.getPenaltyChargesOutstanding(INR).isZero());
        assertTrue(inst8.getPenaltyChargesOutstanding(INR).isZero());
        assertTrue(inst10.getPenaltyChargesOutstanding(INR).isZero());
    }

    private static LoanCharge byDay(final List<LoanCharge> penalties, final int day) {
        return penalties.stream().filter(c -> c.getDueLocalDate().getDayOfMonth() == day).findFirst().orElseThrow();
    }

    private static BigDecimal amt(final long value) {
        return BigDecimal.valueOf(value).setScale(0);
    }
}
