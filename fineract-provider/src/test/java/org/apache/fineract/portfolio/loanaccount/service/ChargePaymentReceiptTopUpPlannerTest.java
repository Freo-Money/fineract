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

import static org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptTopUpFixtures.INR;
import static org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptTopUpFixtures.chargePayment;
import static org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptTopUpFixtures.fee;
import static org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptTopUpFixtures.penalty;
import static org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptTopUpFixtures.receipt;
import static org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptTopUpFixtures.transaction;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class ChargePaymentReceiptTopUpPlannerTest {

    private static final MockedStatic<MoneyHelper> MONEY_HELPER = Mockito.mockStatic(MoneyHelper.class);
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 5, 5);
    private static final LocalDate JAN = LocalDate.of(2027, 1, 1);
    private static final LocalDate FEB = LocalDate.of(2027, 2, 1);
    private static final LocalDate MAR = LocalDate.of(2027, 3, 1);
    private static final LocalDate PAID_ON = LocalDate.of(2027, 3, 5);

    private final Loan loan = Mockito.mock(Loan.class);

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
        ChargePaymentReceiptTopUpFixtures.setBusinessDate(BUSINESS_DATE);
    }

    @AfterEach
    void tearDown() {
        ChargePaymentReceiptTopUpFixtures.resetContext();
    }

    /** Prod loan 386607, txn 8239827: 23 penalty receipts, two lowered (5 and 3 instead of 7), gap 6. */
    @Test
    void loan386607Shape_raisesEachClippedReceiptByItsChargesRoom() {
        final LoanTransaction payment = chargePayment(8239827L, loan, LocalDate.of(2026, 6, 30), "151", "145", true);
        long receiptId = 5178008L;
        for (int day = 6; day <= 26; day++) {
            receipt(receiptId++, payment, penalty(day, LocalDate.of(2026, 5, day), "7"), day == 20 ? "5" : day == 25 ? "3" : "7");
        }
        receipt(receiptId++, payment, penalty(27L, LocalDate.of(2026, 5, 27), "2"), "2");
        receipt(receiptId, payment, penalty(28L, LocalDate.of(2026, 5, 28), "2"), "2");

        final ChargePaymentReceiptTopUpPlan plan = plan(payment, payment);

        assertEquals(ChargePaymentReceiptClassification.CLIPPED, plan.classification());
        assertAmount("145", plan.receiptsTotal());
        assertEquals(2, plan.topUps().size());
        assertTopUp(plan.topUps().get(0), 20L, "5", "7");
        assertTopUp(plan.topUps().get(1), 25L, "3", "7");
    }

    /**
     * Prod loan 444453, txn 7000635: FinFlux receipts, four lowered in place, gap 15 = the room on those four charges.
     */
    @Test
    void loan444453Shape_restoresTheFinfluxReceiptsExactly() {
        final LoanTransaction payment = chargePayment(7000635L, loan, LocalDate.of(2026, 1, 30), "120", "105", true);
        receipt(4337132L, payment, penalty(29302494L, LocalDate.of(2025, 9, 9), "21"), "19");
        receipt(4337131L, payment, penalty(29341824L, LocalDate.of(2025, 9, 10), "2"), "2");
        receipt(4337133L, payment, penalty(29382077L, LocalDate.of(2025, 9, 11), "2"), "2");
        receipt(4337134L, payment, penalty(29421124L, LocalDate.of(2025, 9, 12), "2"), "2");
        receipt(4337136L, payment, penalty(31615757L, LocalDate.of(2025, 11, 9), "22"), "22");
        receipt(4337137L, payment, penalty(31655171L, LocalDate.of(2025, 11, 10), "6"), "5");
        receipt(4337138L, payment, penalty(31695040L, LocalDate.of(2025, 11, 11), "6"), "6");
        receipt(4337139L, payment, penalty(31735273L, LocalDate.of(2025, 11, 12), "6"), "6");
        receipt(4337140L, payment, penalty(33958274L, LocalDate.of(2026, 1, 9), "23"), "13");
        receipt(4337142L, payment, penalty(33998856L, LocalDate.of(2026, 1, 10), "6"), "6");
        receipt(4337143L, payment, penalty(34038464L, LocalDate.of(2026, 1, 11), "6"), "6");
        receipt(4337144L, payment, penalty(34077734L, LocalDate.of(2026, 1, 12), "6"), "6");
        receipt(4337135L, payment, penalty(34117526L, LocalDate.of(2026, 1, 13), "6"), "4");
        receipt(4337141L, payment, penalty(34157652L, LocalDate.of(2026, 1, 14), "6"), "6");

        final ChargePaymentReceiptTopUpPlan plan = plan(payment, payment);

        assertEquals(ChargePaymentReceiptClassification.CLIPPED, plan.classification());
        assertEquals(4, plan.topUps().size());
        assertTopUp(plan.topUps().get(0), 29302494L, "19", "21");
        assertTopUp(plan.topUps().get(1), 31655171L, "5", "6");
        assertTopUp(plan.topUps().get(2), 33958274L, "13", "23");
        assertTopUp(plan.topUps().get(3), 34117526L, "4", "6");
    }

    /**
     * Review example: C=10, X=2, W=1; truly A={C:5, X:2}, B={C:5, W:1}; stored A={C:5, X:0}, B={C:3, W:1}. The 2 of
     * room on C could be either payment's, so neither is topped up (a greedy raise put A's gap on C and left X unpaid).
     */
    @Test
    void roomSharedWithAnotherShortPayment_isAmbiguous_forBothPayments() {
        final LoanCharge c = fee(1L, JAN, "10");
        final LoanCharge x = fee(2L, FEB, "2");
        final LoanCharge w = fee(3L, MAR, "1");
        final LoanTransaction a = chargePayment(101L, loan, LocalDate.of(2027, 1, 5), "7", "5", false);
        receipt(11L, a, c, "5");
        receipt(12L, a, x, "0");
        final LoanTransaction b = chargePayment(102L, loan, LocalDate.of(2027, 2, 5), "6", "4", false);
        receipt(13L, b, c, "3");
        receipt(14L, b, w, "1");

        assertNoTopUp(ChargePaymentReceiptClassification.AMBIGUOUS_ROOM, plan(a, a, b));
        assertNoTopUp(ChargePaymentReceiptClassification.AMBIGUOUS_ROOM, plan(b, a, b));
    }

    @Test
    void sharedChargeWithoutRoom_doesNotBlock_eachPaymentFillsItsOwnCharge() {
        final LoanCharge c = fee(1L, JAN, "10");
        final LoanCharge x = fee(2L, FEB, "2");
        final LoanCharge w = fee(3L, MAR, "1");
        final LoanTransaction a = chargePayment(101L, loan, LocalDate.of(2027, 1, 5), "7", "5", false);
        receipt(11L, a, c, "5");
        receipt(12L, a, x, "0");
        final LoanTransaction b = chargePayment(102L, loan, LocalDate.of(2027, 2, 5), "6", "5", false);
        receipt(13L, b, c, "5");
        receipt(14L, b, w, "0");

        final ChargePaymentReceiptTopUpPlan planA = plan(a, a, b);
        final ChargePaymentReceiptTopUpPlan planB = plan(b, a, b);

        assertEquals(ChargePaymentReceiptClassification.CLIPPED, planA.classification());
        assertEquals(1, planA.topUps().size());
        assertTopUp(planA.topUps().get(0), 2L, "0", "2");
        assertEquals(ChargePaymentReceiptClassification.CLIPPED, planB.classification());
        assertEquals(1, planB.topUps().size());
        assertTopUp(planB.topUps().get(0), 3L, "0", "1");
    }

    /**
     * A partly paid last charge leaves more room than the gap: receipts are raised in due-date order until it closes.
     */
    @Test
    void moreRoomThanTheGap_isFilledInDueDateOrder() {
        final LoanTransaction payment = chargePayment(101L, loan, PAID_ON, "300", "250", false);
        receipt(11L, payment, fee(1L, JAN, "200"), "150");
        receipt(12L, payment, fee(2L, FEB, "200"), "100");

        final ChargePaymentReceiptTopUpPlan plan = plan(payment, payment);

        assertEquals(ChargePaymentReceiptClassification.CLIPPED, plan.classification());
        assertEquals(1, plan.topUps().size());
        assertTopUp(plan.topUps().get(0), 1L, "150", "200");
    }

    @Test
    void twoReceiptsOnOneCharge_shareThatChargesRoom() {
        final LoanCharge charge = fee(1L, JAN, "200");
        final LoanTransaction payment = chargePayment(101L, loan, PAID_ON, "200", "150", false);
        receipt(11L, payment, charge, "100");
        receipt(12L, payment, charge, "50");

        final ChargePaymentReceiptTopUpPlan plan = plan(payment, payment);

        assertEquals(ChargePaymentReceiptClassification.CLIPPED, plan.classification());
        assertEquals(1, plan.topUps().size());
        assertTopUp(plan.topUps().get(0), 1L, "100", "150");
    }

    /** A charge waived after the receipts were lowered: its room is gone, so only part of the gap can be restored. */
    @Test
    void lessRoomThanTheGap_isClippedResidual() {
        final LoanCharge waived = fee(2L, FEB, "200");
        waived.setAmountWaived(new BigDecimal("160"));
        final LoanTransaction payment = chargePayment(101L, loan, PAID_ON, "400", "160", false);
        receipt(11L, payment, fee(1L, JAN, "200"), "120");
        receipt(12L, payment, waived, "120");

        assertNoTopUp(ChargePaymentReceiptClassification.CLIPPED_RESIDUAL, plan(payment, payment));
    }

    @Test
    void noRoomLeft_isSurplus() {
        final LoanTransaction payment = chargePayment(101L, loan, PAID_ON, "410", "400", false);
        receipt(11L, payment, fee(1L, JAN, "200"), "200");
        receipt(12L, payment, fee(2L, FEB, "200"), "200");

        assertNoTopUp(ChargePaymentReceiptClassification.SURPLUS, plan(payment, payment));
    }

    @Test
    void receiptsAboveTheAmount_isOver() {
        final LoanTransaction payment = chargePayment(101L, loan, PAID_ON, "400", "400", false);
        receipt(11L, payment, fee(1L, JAN, "200"), "210");
        receipt(12L, payment, fee(2L, FEB, "200"), "200");

        assertNoTopUp(ChargePaymentReceiptClassification.OVER, plan(payment, payment));
    }

    @Test
    void fullyAbsorbedReceipts_areConsistent() {
        final LoanTransaction payment = chargePayment(101L, loan, PAID_ON, "400", "400", false);
        receipt(11L, payment, fee(1L, JAN, "200"), "200");
        receipt(12L, payment, fee(2L, FEB, "200"), "200");

        assertNoTopUp(ChargePaymentReceiptClassification.CONSISTENT, plan(payment, payment));
    }

    @Test
    void receiptsTheInstallmentsDidNotAbsorb_areInstallmentRoom_withOrWithoutAGap() {
        final LoanTransaction noGap = chargePayment(101L, loan, PAID_ON, "400", "200", false);
        receipt(11L, noGap, fee(1L, JAN, "200"), "200");
        receipt(12L, noGap, fee(2L, FEB, "200"), "200");
        final LoanTransaction withGap = chargePayment(102L, loan, PAID_ON, "200", "60", true);
        receipt(13L, withGap, penalty(3L, JAN, "100"), "60");
        receipt(14L, withGap, penalty(4L, FEB, "100"), "60");

        assertNoTopUp(ChargePaymentReceiptClassification.INSTALLMENT_ROOM, plan(noGap, noGap));
        assertNoTopUp(ChargePaymentReceiptClassification.INSTALLMENT_ROOM, plan(withGap, withGap));
    }

    @Test
    void receiptOnAnInactiveCharge_winsOverTheSingleReceiptClass() {
        final LoanCharge deactivated = penalty(1L, JAN, "200");
        deactivated.setActive(false);
        final LoanTransaction payment = chargePayment(101L, loan, PAID_ON, "200", "0", true);
        receipt(11L, payment, deactivated, "200");

        assertNoTopUp(ChargePaymentReceiptClassification.RECEIPT_ON_INACTIVE_CHARGE, plan(payment, payment));
    }

    @Test
    void singleReceipt_andNoReceipts_areReportedOnly() {
        final LoanTransaction single = chargePayment(101L, loan, PAID_ON, "200", "120", false);
        receipt(11L, single, fee(1L, JAN, "200"), "120");
        final LoanTransaction none = chargePayment(102L, loan, PAID_ON, "100", "0", false);

        assertNoTopUp(ChargePaymentReceiptClassification.SINGLE_RECEIPT, plan(single, single));
        assertNoTopUp(ChargePaymentReceiptClassification.NO_RECEIPTS, plan(none, none));
    }

    @Test
    void feeAndPenaltyReceipts_areMixed_andADisbursementCharge_isUnsupported() {
        final LoanTransaction mixed = chargePayment(101L, loan, PAID_ON, "400", "240", false);
        receipt(11L, mixed, fee(1L, JAN, "200"), "120");
        receipt(12L, mixed, penalty(2L, FEB, "200"), "120");
        final LoanCharge atDisbursement = fee(3L, JAN, "200");
        atDisbursement.setChargeTime(ChargeTimeType.DISBURSEMENT.getValue());
        final LoanTransaction unsupported = chargePayment(102L, loan, PAID_ON, "400", "240", false);
        receipt(13L, unsupported, atDisbursement, "120");
        receipt(14L, unsupported, fee(4L, FEB, "200"), "120");

        assertNoTopUp(ChargePaymentReceiptClassification.MIXED, plan(mixed, mixed));
        assertNoTopUp(ChargePaymentReceiptClassification.UNSUPPORTED_CHARGE, plan(unsupported, unsupported));
    }

    @Test
    void settledByCharge_countsOnlyPositiveReceiptsOfLiveSettlingTransactions() {
        final LoanCharge charge = fee(1L, JAN, "200");
        final LoanTransaction payment = chargePayment(101L, loan, PAID_ON, "120", "120", false);
        receipt(11L, payment, charge, "120");
        final LoanTransaction repayment = transaction(102L, LoanTransactionType.REPAYMENT, loan, PAID_ON, "50");
        receipt(12L, repayment, charge, "50");
        final LoanTransaction reversed = transaction(103L, LoanTransactionType.REPAYMENT, loan, PAID_ON, "40");
        receipt(13L, reversed, charge, "40");
        reversed.reverse();
        final LoanTransaction waiver = transaction(104L, LoanTransactionType.WAIVE_CHARGES, loan, PAID_ON, "30");
        receipt(14L, waiver, charge, "30");
        final LoanTransaction accrual = transaction(105L, LoanTransactionType.ACCRUAL, loan, PAID_ON, "200");
        receipt(15L, accrual, charge, "200");
        final LoanTransaction suspenseReverse = transaction(106L, LoanTransactionType.ACCRUAL_SUSPENSE_REVERSE, loan, PAID_ON, "200");
        receipt(16L, suspenseReverse, charge, "200");
        final LoanTransaction chargeRefund = transaction(107L, LoanTransactionType.CHARGE_REFUND, loan, PAID_ON, "50");
        receipt(17L, chargeRefund, charge, "-50");

        final Map<Long, Money> settled = ChargePaymentReceiptTopUpPlanner
                .settledByCharge(List.of(payment, repayment, reversed, waiver, accrual, suspenseReverse, chargeRefund), INR);

        assertAmount("170", settled.get(1L).getAmount());
    }

    @Test
    void shortPaymentIdsByCharge_listsOnlyPaymentsWhoseReceiptsSumBelowTheirAmount() {
        final LoanCharge c = fee(1L, JAN, "10");
        final LoanCharge x = fee(2L, FEB, "2");
        final LoanTransaction shortPayment = chargePayment(101L, loan, PAID_ON, "7", "5", false);
        receipt(11L, shortPayment, c, "5");
        receipt(12L, shortPayment, x, "0");
        final LoanTransaction fullPayment = chargePayment(102L, loan, PAID_ON, "5", "5", false);
        receipt(13L, fullPayment, c, "5");

        final Map<Long, Set<Long>> byCharge = ChargePaymentReceiptTopUpPlanner.shortPaymentIdsByCharge(List.of(shortPayment, fullPayment),
                INR);

        assertEquals(Map.of(1L, Set.of(101L), 2L, Set.of(101L)), byCharge);
    }

    private ChargePaymentReceiptTopUpPlan plan(final LoanTransaction payment, final LoanTransaction... liveTransactions) {
        final List<LoanTransaction> live = List.of(liveTransactions);
        final List<LoanTransaction> chargePayments = live.stream().filter(LoanTransaction::isChargePayment).toList();
        return ChargePaymentReceiptTopUpPlanner.plan(payment, INR, ChargePaymentReceiptTopUpPlanner.settledByCharge(live, INR),
                ChargePaymentReceiptTopUpPlanner.shortPaymentIdsByCharge(chargePayments, INR));
    }

    private static void assertNoTopUp(final ChargePaymentReceiptClassification expected, final ChargePaymentReceiptTopUpPlan plan) {
        assertEquals(expected, plan.classification());
        assertTrue(plan.topUps().isEmpty());
    }

    private static void assertTopUp(final ChargePaymentReceiptTopUp topUp, final long chargeId, final String from, final String to) {
        assertEquals(chargeId, topUp.receipt().getLoanCharge().getId());
        assertAmount(from, topUp.from());
        assertAmount(to, topUp.to());
    }

    private static void assertAmount(final String expected, final BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }
}
