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
import java.util.List;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptRepairClassification;
import org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptRepairPlan;
import org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptRepairPlanner;
import org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptTopUp;
import org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptView;
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

@ExtendWith(MockitoExtension.class)
class ChargePaymentReceiptRepairPlannerTest {

    private static final MockedStatic<MoneyHelper> MONEY_HELPER = Mockito.mockStatic(MoneyHelper.class);
    private static final LocalDate PAYMENT_DATE = LocalDate.of(2026, 6, 30);

    @Mock
    private Loan loan;
    @Mock
    private Office office;

    private final ChargePaymentReceiptRepairPlanner planner = new ChargePaymentReceiptRepairPlanner();

    @BeforeEach
    void setUp() {
        ChargePaymentReceiptFixtures.setBusinessDate(PAYMENT_DATE);
    }

    @AfterEach
    void tearDown() {
        ChargePaymentReceiptFixtures.resetContext();
    }

    @BeforeAll
    static void init() {
        MONEY_HELPER.when(MoneyHelper::getMathContext).thenReturn(new MathContext(12, RoundingMode.HALF_EVEN));
        MONEY_HELPER.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
    }

    @AfterAll
    static void destruct() {
        MONEY_HELPER.close();
    }

    /** Prod loan 386607, txn 8239827: 23 penalty receipts, two clipped (5 and 3 instead of 7), gap 6. */
    @Test
    void loan386607Shape_isClipped_andTopUpsFollowDueDateOrder() {
        final List<LoanRepaymentScheduleInstallment> installments = List
                .of(installment(loan, 23, LocalDate.of(2026, 5, 5), LocalDate.of(2026, 6, 5), 5866, 2734));
        final LoanTransaction payment = chargePayment(8239827L, loan, office, PAYMENT_DATE, 151);
        long receiptId = 5178008L;
        for (int day = 6; day <= 26; day++) {
            final LoanCharge charge = penaltyCharge(day, LocalDate.of(2026, 5, day), 7);
            final long amount = day == 20 ? 5 : day == 25 ? 3 : 7;
            receipt(receiptId++, payment, charge, amount);
        }
        receipt(receiptId++, payment, penaltyCharge(27L, LocalDate.of(2026, 5, 27), 2), 2);
        receipt(receiptId, payment, penaltyCharge(28L, LocalDate.of(2026, 5, 28), 2), 2);
        payment.updateComponents(Money.zero(INR), Money.zero(INR), Money.zero(INR), Money.of(INR, BigDecimal.valueOf(145)));
        payment.setOverPayments(Money.of(INR, BigDecimal.valueOf(6)));

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, installments, List.of(payment));

        assertEquals(ChargePaymentReceiptRepairClassification.CLIPPED, plan.classification());
        assertTrue(plan.isRepairable());
        assertEquals(BigDecimal.valueOf(145), plan.receiptsTotal());
        assertEquals(BigDecimal.valueOf(6), plan.gap());
        assertEquals(2, plan.topUps().size());
        assertTopUp(plan.topUps().get(0), 20L, 5, 7);
        assertTopUp(plan.topUps().get(1), 25L, 3, 7);
        assertEquals(23, plan.receipts().size());
        assertTrue(plan.receipts().stream().allMatch(r -> ChargePaymentReceiptView.MAPPED_BY_DUE_PERIOD.equals(r.mappedBy())));
        assertTrue(plan.receipts().stream().allMatch(r -> Integer.valueOf(23).equals(r.installmentNumber())));
    }

    /**
     * Prod loan 444453, txn 7000635: FinFlux receipts 21/2/2/2 | 22/6/6/6 | 23/6/6/6/6/6, four lowered in place, gap
     * 15.
     */
    @Test
    void loan444453Shape_reconstructsTheFinfluxReceiptsExactly() {
        final List<LoanRepaymentScheduleInstallment> installments = List.of(
                installment(loan, 6, LocalDate.of(2025, 9, 5), LocalDate.of(2025, 10, 5), 5442, 2564),
                installment(loan, 8, LocalDate.of(2025, 11, 5), LocalDate.of(2025, 12, 5), 5671, 2335),
                installment(loan, 10, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 5), 5910, 2096));
        final LoanTransaction payment = chargePayment(7000635L, loan, office, LocalDate.of(2026, 1, 30), 120);
        receipt(4337132L, payment, penaltyCharge(29302494L, LocalDate.of(2025, 9, 9), 21), 19);
        receipt(4337131L, payment, penaltyCharge(29341824L, LocalDate.of(2025, 9, 10), 2), 2);
        receipt(4337133L, payment, penaltyCharge(29382077L, LocalDate.of(2025, 9, 11), 2), 2);
        receipt(4337134L, payment, penaltyCharge(29421124L, LocalDate.of(2025, 9, 12), 2), 2);
        receipt(4337136L, payment, penaltyCharge(31615757L, LocalDate.of(2025, 11, 9), 22), 22);
        receipt(4337137L, payment, penaltyCharge(31655171L, LocalDate.of(2025, 11, 10), 6), 5);
        receipt(4337138L, payment, penaltyCharge(31695040L, LocalDate.of(2025, 11, 11), 6), 6);
        receipt(4337139L, payment, penaltyCharge(31735273L, LocalDate.of(2025, 11, 12), 6), 6);
        receipt(4337140L, payment, penaltyCharge(33958274L, LocalDate.of(2026, 1, 9), 23), 13);
        receipt(4337142L, payment, penaltyCharge(33998856L, LocalDate.of(2026, 1, 10), 6), 6);
        receipt(4337143L, payment, penaltyCharge(34038464L, LocalDate.of(2026, 1, 11), 6), 6);
        receipt(4337144L, payment, penaltyCharge(34077734L, LocalDate.of(2026, 1, 12), 6), 6);
        receipt(4337135L, payment, penaltyCharge(34117526L, LocalDate.of(2026, 1, 13), 6), 4);
        receipt(4337141L, payment, penaltyCharge(34157652L, LocalDate.of(2026, 1, 14), 6), 6);
        payment.updateComponents(Money.zero(INR), Money.zero(INR), Money.zero(INR), Money.of(INR, BigDecimal.valueOf(105)));
        payment.setOverPayments(Money.of(INR, BigDecimal.valueOf(15)));

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, installments, List.of(payment));

        assertEquals(ChargePaymentReceiptRepairClassification.CLIPPED, plan.classification());
        assertEquals(BigDecimal.valueOf(15), plan.gap());
        assertEquals(4, plan.topUps().size());
        assertTopUp(plan.topUps().get(0), 29302494L, 19, 21);
        assertTopUp(plan.topUps().get(1), 31655171L, 5, 6);
        assertTopUp(plan.topUps().get(2), 33958274L, 13, 23);
        assertTopUp(plan.topUps().get(3), 34117526L, 4, 6);
    }

    @Test
    void consistentReceipts_areNotRepairable() {
        final LoanTransaction payment = twoReceiptPayment(14, 7, 7);
        payment.updateComponents(Money.zero(INR), Money.zero(INR), Money.zero(INR), Money.of(INR, BigDecimal.valueOf(14)));

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, singleInstallment(), List.of(payment));

        assertEquals(ChargePaymentReceiptRepairClassification.CONSISTENT, plan.classification());
        assertFalse(plan.isRepairable());
        assertTrue(plan.topUps().isEmpty());
    }

    @Test
    void receiptsSumToAmountButPortionsShort_isInstallmentRoom() {
        final LoanTransaction payment = twoReceiptPayment(14, 7, 7);
        payment.updateComponents(Money.zero(INR), Money.zero(INR), Money.zero(INR), Money.of(INR, BigDecimal.valueOf(10)));
        payment.setOverPayments(Money.of(INR, BigDecimal.valueOf(4)));

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, singleInstallment(), List.of(payment));

        assertEquals(ChargePaymentReceiptRepairClassification.INSTALLMENT_ROOM, plan.classification());
        assertFalse(plan.isRepairable());
    }

    @Test
    void receiptsAboveAmount_isOver() {
        final ChargePaymentReceiptRepairPlan plan = planner.plan(twoReceiptPayment(10, 7, 7), INR, singleInstallment(), List.of());
        assertEquals(ChargePaymentReceiptRepairClassification.OVER, plan.classification());
        assertEquals(BigDecimal.valueOf(-4), plan.gap());
    }

    @Test
    void gapWithNothingToTopUp_isSurplus() {
        // both receipts already carry their full charge amount
        final ChargePaymentReceiptRepairPlan plan = planner.plan(twoReceiptPayment(20, 7, 7), INR, singleInstallment(), List.of());
        assertEquals(ChargePaymentReceiptRepairClassification.SURPLUS, plan.classification());
        assertTrue(plan.topUps().isEmpty());
    }

    @Test
    void gapLargerThanAvailableRoom_isClippedResidual_andTopUpsAreStillBounded() {
        // charges 7 + 7, receipts 5 + 7, amount 20: room is 2, gap is 8
        final ChargePaymentReceiptRepairPlan plan = planner.plan(twoReceiptPayment(20, 5, 7), INR, singleInstallment(), List.of());
        assertEquals(ChargePaymentReceiptRepairClassification.CLIPPED_RESIDUAL, plan.classification());
        assertFalse(plan.isRepairable());
        assertEquals(1, plan.topUps().size());
        assertTopUp(plan.topUps().get(0), 1L, 5, 7);
    }

    @Test
    void duplicateReceiptsOnOneCharge_shareTheChargeCap() {
        final LoanCharge charge = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 9);
        receipt(11L, payment, charge, 3);
        receipt(12L, payment, charge, 2);

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, singleInstallment(), List.of(payment));

        // gap 4, but the two receipts already carry 5 of a 7 charge: only 2 can be added, and only once
        assertEquals(ChargePaymentReceiptRepairClassification.CLIPPED_RESIDUAL, plan.classification());
        assertEquals(1, plan.topUps().size());
        assertEquals(BigDecimal.valueOf(2), plan.topUps().get(0).increment());
    }

    @Test
    void receiptsOfOtherTransactionsOnTheSameCharge_lowerTheCap() {
        final LoanCharge charge = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge other = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanTransaction earlier = chargePayment(99L, loan, office, LocalDate.of(2026, 6, 1), 3);
        receipt(10L, earlier, charge, 3); // another live transaction already pays 3 of the 7
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 11);
        receipt(11L, payment, charge, 2);
        receipt(12L, payment, other, 7);

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, singleInstallment(), List.of(earlier, payment));

        // gap 2, room on the charge is 7 - 3 (elsewhere) - 2 (own) = 2 → exact
        assertEquals(ChargePaymentReceiptRepairClassification.CLIPPED, plan.classification());
        assertTopUp(plan.topUps().get(0), 1L, 2, 4);
        assertEquals(BigDecimal.valueOf(4), plan.receipts().get(0).chargeCap());

        // with a bigger gap the same room is the ceiling
        final LoanTransaction bigger = chargePayment(101L, loan, office, PAYMENT_DATE, 14);
        receipt(13L, bigger, charge, 2);
        receipt(14L, bigger, other, 7);
        final ChargePaymentReceiptRepairPlan residual = planner.plan(bigger, INR, singleInstallment(), List.of(earlier, bigger));
        assertEquals(ChargePaymentReceiptRepairClassification.CLIPPED_RESIDUAL, residual.classification());
        assertEquals(BigDecimal.valueOf(2), residual.topUps().get(0).increment());
    }

    @Test
    void accrualReceiptsDoNotShrinkTheCap() {
        // An accrual books a receipt to record what was accrued against a charge, not paid. Counting it as paid
        // elsewhere would push the room to zero and classify a genuinely clipped payment as a surplus.
        final LoanCharge charge = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge other = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanTransaction accrual = LoanTransaction.accrueTransaction(loan, office, LocalDate.of(2026, 6, 1), BigDecimal.valueOf(7),
                null, null, BigDecimal.valueOf(7), ExternalId.empty());
        receipt(10L, accrual, charge, 7);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 12);
        receipt(11L, payment, charge, 3);
        receipt(12L, payment, other, 7);

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, singleInstallment(), List.of(accrual, payment));

        assertEquals(ChargePaymentReceiptRepairClassification.CLIPPED, plan.classification());
        assertTopUp(plan.topUps().get(0), 1L, 3, 5);
        assertEquals(BigDecimal.valueOf(7), plan.receipts().get(0).chargeCap());
    }

    @Test
    void instalmentFeeCapIsTheInstallmentShare() {
        // The charge totals 200 across two installments. Capping by the charge would let the first receipt be topped up
        // to 200, which its installment can never absorb, and the post-repair assertion would roll the loan back.
        final List<LoanRepaymentScheduleInstallment> installments = twoInstallments();
        final LoanCharge fee = ChargePaymentReceiptFixtures.instalmentFeeCharge(1L, 100, installments);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 150);
        ChargePaymentReceiptFixtures.receipt(11L, payment, fee, 60, 1);
        ChargePaymentReceiptFixtures.receipt(12L, payment, fee, 50, 2);

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, installments, List.of(payment));

        // gap 40: the first installment's share takes 40 of its remaining 40, the second is untouched
        assertEquals(ChargePaymentReceiptRepairClassification.CLIPPED, plan.classification());
        assertEquals(1, plan.topUps().size());
        assertTopUp(plan.topUps().get(0), 1L, 60, 100);
        assertEquals(BigDecimal.valueOf(100), plan.receipts().get(0).chargeCap());
    }

    @Test
    void waiverReceiptIsNotCountedTwiceAgainstTheCap() {
        // A waiver books a receipt of its own AND raises the charge's waived total. The cap must deduct that money
        // once, not twice, or a clipped receipt looks unrepairable.
        final LoanCharge charge = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        charge.setAmountWaived(BigDecimal.valueOf(2));
        charge.setAmountOutstanding(BigDecimal.valueOf(5));
        final LoanCharge other = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanTransaction waiver = LoanTransaction.waiveLoanCharge(loan, office, Money.of(INR, BigDecimal.valueOf(2)),
                LocalDate.of(2026, 6, 1), Money.zero(INR), Money.of(INR, BigDecimal.valueOf(2)), Money.zero(INR), ExternalId.empty());
        receipt(10L, waiver, charge, 2);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 12);
        receipt(11L, payment, charge, 3);
        receipt(12L, payment, other, 7);

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, singleInstallment(), List.of(waiver, payment));

        // cap = 7 - 2 waived = 5, so the 3 can be topped up by the gap of 2; counting the waiver receipt as well
        // would have left a cap of 3 and no room at all.
        assertEquals(ChargePaymentReceiptRepairClassification.CLIPPED, plan.classification());
        assertTopUp(plan.topUps().get(0), 1L, 3, 5);
        assertEquals(BigDecimal.valueOf(5), plan.receipts().get(0).chargeCap());
    }

    @Test
    void waivedPortionLowersTheCap() {
        final LoanCharge charge = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        charge.setAmountWaived(BigDecimal.valueOf(3));
        final LoanCharge other = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 11);
        receipt(11L, payment, charge, 2);
        receipt(12L, payment, other, 7);

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, singleInstallment(), List.of(payment));

        assertEquals(ChargePaymentReceiptRepairClassification.CLIPPED, plan.classification());
        assertTopUp(plan.topUps().get(0), 1L, 2, 4);
    }

    @Test
    void mixedFeeAndPenaltyReceipts_areRefused() {
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 14);
        receipt(11L, payment, penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7), 5);
        receipt(12L, payment, feeCharge(2L, LocalDate.of(2026, 5, 11), 7), 7);

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, singleInstallment(), List.of(payment));

        assertEquals(ChargePaymentReceiptRepairClassification.MIXED, plan.classification());
        assertTrue(plan.topUps().isEmpty());
    }

    @Test
    void receiptOnInactiveCharge_isRefused() {
        final LoanTransaction payment = twoReceiptPayment(14, 5, 7);
        payment.getLoanChargesPaid().iterator().next().getLoanCharge().setActive(false);

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, singleInstallment(), List.of(payment));

        assertEquals(ChargePaymentReceiptRepairClassification.RECEIPT_ON_INACTIVE_CHARGE, plan.classification());
        assertTrue(plan.receipts().stream().anyMatch(r -> !r.chargeActive()));
    }

    @Test
    void receiptWhoseChargeFallsInNoInstallment_isUnmapped() {
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 14);
        receipt(11L, payment, penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7), 5);
        receipt(12L, payment, penaltyCharge(2L, LocalDate.of(2027, 1, 1), 7), 7); // after the only installment

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, singleInstallment(), List.of(payment));

        assertEquals(ChargePaymentReceiptRepairClassification.UNMAPPED_RECEIPT, plan.classification());
        assertTrue(plan.receipts().stream().anyMatch(r -> ChargePaymentReceiptView.UNMAPPED.equals(r.mappedBy())));
    }

    @Test
    void singleReceipt_isClassifiedOnly() {
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 7);
        receipt(11L, payment, penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7), 5);

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, singleInstallment(), List.of(payment));

        assertEquals(ChargePaymentReceiptRepairClassification.SINGLE_RECEIPT, plan.classification());
        assertFalse(plan.isRepairable());
    }

    @Test
    void receiptWithInstallmentNumber_mapsByNumber_andReportsTheBucket() {
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, LocalDate.of(2026, 5, 5), LocalDate.of(2026, 6, 5), 100,
                10);
        installment.payPenaltyChargesComponent(PAYMENT_DATE, Money.zero(INR));
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 14);
        final LoanChargePaidBy first = receipt(11L, payment, penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7), 7);
        first.setInstallmentNumber(1);
        receipt(12L, payment, penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7), 7);

        final ChargePaymentReceiptRepairPlan plan = planner.plan(payment, INR, List.of(installment), List.of(payment));

        final ChargePaymentReceiptView byNumber = plan.receipts().stream().filter(r -> r.receiptId().equals(11L)).findFirst().orElseThrow();
        assertEquals(ChargePaymentReceiptView.MAPPED_BY_NUMBER, byNumber.mappedBy());
        assertEquals(1, byNumber.installmentNumber());
        assertEquals(BigDecimal.ZERO.setScale(0), byNumber.bucketAmount().setScale(0));
    }

    private LoanTransaction twoReceiptPayment(final long amount, final long firstReceipt, final long secondReceipt) {
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, amount);
        receipt(11L, payment, penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7), firstReceipt);
        receipt(12L, payment, penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7), secondReceipt);
        return payment;
    }

    private List<LoanRepaymentScheduleInstallment> twoInstallments() {
        return List.of(installment(loan, 1, LocalDate.of(2026, 5, 5), LocalDate.of(2026, 6, 5), 100, 10),
                installment(loan, 2, LocalDate.of(2026, 6, 5), LocalDate.of(2026, 7, 5), 100, 10));
    }

    private List<LoanRepaymentScheduleInstallment> singleInstallment() {
        return List.of(installment(loan, 1, LocalDate.of(2026, 5, 5), LocalDate.of(2026, 6, 5), 100, 10));
    }

    private static void assertTopUp(final ChargePaymentReceiptTopUp topUp, final long chargeId, final long from, final long to) {
        assertEquals(chargeId, topUp.receipt().getLoanCharge().getId());
        assertEquals(BigDecimal.valueOf(from), topUp.from());
        assertEquals(BigDecimal.valueOf(to), topUp.to());
    }
}
