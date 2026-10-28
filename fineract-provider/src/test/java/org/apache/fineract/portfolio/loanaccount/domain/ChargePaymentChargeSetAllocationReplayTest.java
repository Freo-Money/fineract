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
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
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
 * Charge-set allocation (global configuration {@code charge-payment-charge-set-allocation}): replaying a multi-receipt
 * charge payment spreads its amount over the charges its receipts name, each up to what it still owes, and rewrites the
 * receipts to that split. These tests pin down the healing of a clipped receipt, the money that legitimately stays in
 * overpayment once those charges are full, and idempotence.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChargePaymentChargeSetAllocationReplayTest {

    private static final MockedStatic<MoneyHelper> MONEY_HELPER = Mockito.mockStatic(MoneyHelper.class);
    private static final MockedStatic<TemporaryConfigurationServiceContainer> CONFIGURATION = Mockito
            .mockStatic(TemporaryConfigurationServiceContainer.class);
    private static final LocalDate DISBURSEMENT = LocalDate.of(2026, 4, 5);
    private static final LocalDate FROM = LocalDate.of(2026, 5, 5);
    private static final LocalDate DUE = LocalDate.of(2026, 6, 5);
    private static final LocalDate PAYMENT_DATE = LocalDate.of(2026, 6, 30);
    private static final LocalDate LATER_PAYMENT_DATE = LocalDate.of(2026, 7, 30);

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
        CONFIGURATION.when(TemporaryConfigurationServiceContainer::isChargePaymentChargeSetAllocationEnabled).thenReturn(true);
        CONFIGURATION.when(TemporaryConfigurationServiceContainer::getTaxRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
    }

    @AfterAll
    static void destruct() {
        MONEY_HELPER.close();
        CONFIGURATION.close();
    }

    @BeforeEach
    void setUp() {
        underTest = new OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessor(
                Mockito.mock(ExternalIdFactory.class), Mockito.mock(LoanChargeValidator.class), Mockito.mock(LoanBalanceService.class));
        ChargePaymentReceiptFixtures.setBusinessDate(PAYMENT_DATE);
        Mockito.when(loan.getLoanProduct()).thenReturn(loanProduct);
        Mockito.when(loanProduct.isAccountingDisabled()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        ChargePaymentReceiptFixtures.resetContext();
    }

    @Test
    void clippedReceiptIsHealed_soNothingIsParked() {
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanCharge may12 = penaltyCharge(3L, LocalDate.of(2026, 5, 12), 2);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(installment);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 16);
        receipt(11L, payment, may10, 7);
        final LoanChargePaidBy clipped = receipt(12L, payment, may11, 5); // pre-#153 re-derivation lowered 7 to 5
        receipt(13L, payment, may12, 2);

        replay(List.of(payment), installments, charges(may10, may11, may12));

        assertEquals(BigDecimal.valueOf(16), payment.getPenaltyChargesPortion(INR).getAmount());
        assertTrue(payment.getOverPaymentPortion(INR).isZero());
        assertEquals(BigDecimal.valueOf(16), installment.getPenaltyChargesPaid(INR).getAmount());
        assertEquals(BigDecimal.valueOf(7), clipped.getAmount());
        assertEquals(BigDecimal.valueOf(16), payment.getLoanChargesPaidTotal(INR).getAmount());
        assertTrue(may10.isPaid());
        assertTrue(may11.isPaid());
        assertTrue(may12.isPaid());
    }

    @Test
    void replayIsIdempotent() {
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(installment);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 14);
        receipt(11L, payment, may10, 7);
        final LoanChargePaidBy clipped = receipt(12L, payment, may11, 4);

        replay(List.of(payment), installments, charges(may10, may11));
        final BigDecimal penaltyAfterFirst = payment.getPenaltyChargesPortion(INR).getAmount();
        final BigDecimal receiptAfterFirst = clipped.getAmount();

        replay(List.of(payment), installments, charges(may10, may11));

        assertEquals(penaltyAfterFirst, payment.getPenaltyChargesPortion(INR).getAmount());
        assertEquals(receiptAfterFirst, clipped.getAmount());
        assertEquals(BigDecimal.valueOf(14), payment.getPenaltyChargesPortion(INR).getAmount());
        assertTrue(payment.getOverPaymentPortion(INR).isZero());
    }

    @Test
    void chargeAlreadySettledByTheEarlierPayment_leavesTheDuplicateInOverpayment() {
        // Production shape of loan 634138: the July payment was collected for three penalties and the August payment
        // was billed again for one of them, because the charge-level balance was corrupt. The earlier transaction
        // takes the charge, so the duplicate has nowhere to go.
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(installment);
        final LoanTransaction first = chargePayment(100L, loan, office, PAYMENT_DATE, 14);
        receipt(11L, first, may10, 7);
        receipt(12L, first, may11, 7);
        ChargePaymentReceiptFixtures.setBusinessDate(LATER_PAYMENT_DATE);
        final LoanTransaction second = chargePayment(101L, loan, office, LATER_PAYMENT_DATE, 10);
        final LoanChargePaidBy duplicate = receipt(13L, second, may11, 3);
        receipt(14L, second, may10, 7);

        replay(List.of(first, second), installments, charges(may10, may11));

        assertEquals(BigDecimal.valueOf(14), first.getPenaltyChargesPortion(INR).getAmount());
        assertTrue(first.getOverPaymentPortion(INR).isZero());
        assertTrue(second.getPenaltyChargesPortion(INR).isZero());
        assertEquals(BigDecimal.valueOf(10), second.getOverPaymentPortion(INR).getAmount());
        // the duplicate keeps its stored amount: it is the only record that this money was collected for may11
        assertEquals(BigDecimal.valueOf(3), duplicate.getAmount());
        assertEquals(BigDecimal.valueOf(7), may10.getAmountPaid(INR).getAmount());
        assertEquals(BigDecimal.valueOf(7), may11.getAmountPaid(INR).getAmount());
    }

    @Test
    void accountingEnabledProduct_keepsTheStoredReceiptAmounts() {
        Mockito.when(loanProduct.isAccountingDisabled()).thenReturn(false);
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(installment);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 14);
        receipt(11L, payment, may10, 7);
        final LoanChargePaidBy clipped = receipt(12L, payment, may11, 5);

        replay(List.of(payment), installments, charges(may10, may11));

        assertEquals(BigDecimal.valueOf(12), payment.getPenaltyChargesPortion(INR).getAmount());
        assertEquals(BigDecimal.valueOf(2), payment.getOverPaymentPortion(INR).getAmount());
        assertEquals(BigDecimal.valueOf(5), clipped.getAmount());
    }

    @Test
    void receiptOnInactiveCharge_makesTheWholeSetNonDerivable() {
        // A receipt that cannot be sized would otherwise push its share onto a sibling charge, leaving the stored
        // receipts summing above the transaction. The transaction is replayed verbatim instead and reported.
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanCharge may12 = penaltyCharge(3L, LocalDate.of(2026, 5, 12), 7);
        may11.setActive(false);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(installment);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 14);
        receipt(11L, payment, may10, 7);
        final LoanChargePaidBy onInactive = receipt(12L, payment, may11, 7);
        final LoanChargePaidBy onMay12 = receipt(13L, payment, may12, 0);

        replay(List.of(payment), installments, charges(may10, may11, may12));

        assertEquals(BigDecimal.valueOf(14), payment.getPenaltyChargesPortion(INR).getAmount());
        assertTrue(payment.getOverPaymentPortion(INR).isZero());
        // every receipt keeps exactly what it stored, so the set still sums to the transaction amount
        assertEquals(BigDecimal.valueOf(7), onInactive.getAmount());
        assertTrue(onMay12.getAmount().compareTo(BigDecimal.ZERO) == 0);
        assertEquals(BigDecimal.valueOf(14), payment.getLoanChargesPaidTotal(INR).getAmount());
        // and marking fell back to the legacy walk, which does mark a deactivated charge
        assertEquals(BigDecimal.valueOf(7), may10.getAmountPaid(INR).getAmount());
        assertEquals(BigDecimal.valueOf(7), may11.getAmountPaid(INR).getAmount());
        assertTrue(may12.getAmountPaid(INR).isZero());
    }

    @Test
    void disbursementCharge_keepsItsStoredReceiptAmount() {
        // reprocessLoanTransactions deliberately does not reset a disbursement charge's paid amount, so its
        // outstanding is stale and must not size an allocation - and its receipt must never be rewritten from it.
        final LoanCharge disbursementFee = feeCharge(1L, LocalDate.of(2026, 5, 10), 7);
        disbursementFee.setChargeTime(ChargeTimeType.DISBURSEMENT.getValue());
        disbursementFee.setAmountOutstanding(BigDecimal.ZERO);
        disbursementFee.setAmountPaid(BigDecimal.valueOf(7));
        final LoanCharge may11 = feeCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(installment);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 14);
        final LoanChargePaidBy onDisbursementCharge = receipt(11L, payment, disbursementFee, 7);
        receipt(12L, payment, may11, 7);

        replay(List.of(payment), installments, charges(disbursementFee, may11));

        assertEquals(BigDecimal.valueOf(7), onDisbursementCharge.getAmount());
    }

    @Test
    void chargesMarkedByReceipt_onlyWhenAllocationIsDerived() {
        // The flag governs both halves: with it on, each charge is marked by its own receipt instead of by the legacy
        // earliest-unpaid walk, which is what keeps charge-level and installment-level consistent.
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanCharge may12 = penaltyCharge(3L, LocalDate.of(2026, 5, 12), 2);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(installment);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 16);
        receipt(11L, payment, may10, 7);
        receipt(12L, payment, may11, 5);
        receipt(13L, payment, may12, 2);

        replay(List.of(payment), installments, charges(may10, may11, may12));

        // every charge of the set is settled by its own receipt, and the clipped one is restored rather than the
        // earliest charge being over-marked
        assertEquals(BigDecimal.valueOf(7), may10.getAmountPaid(INR).getAmount());
        assertEquals(BigDecimal.valueOf(7), may11.getAmountPaid(INR).getAmount());
        assertEquals(BigDecimal.valueOf(2), may12.getAmountPaid(INR).getAmount());
        assertTrue(may10.isPaid());
        assertTrue(may11.isPaid());
        assertTrue(may12.isPaid());
    }

    @Test
    void chargeAlreadySettledByAnEarlierRepayment_isLeftVerbatim() {
        // Every charge payment is replayed before any repayment reaches the strategy, so at this point may10 still
        // shows its full balance even though the day-1 repayment settled part of it. Deriving a split from that would
        // persist an allocation that never happened, so this transaction must be replayed verbatim.
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(installment);
        final LoanTransaction earlierRepayment = LoanTransaction.repayment(office, Money.of(INR, BigDecimal.valueOf(4)), null,
                LocalDate.of(2026, 5, 20), ExternalId.empty());
        earlierRepayment.updateLoan(loan);
        receipt(10L, earlierRepayment, may10, 4);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 14);
        final LoanChargePaidBy clipped = receipt(11L, payment, may10, 3);
        receipt(12L, payment, may11, 7);

        replay(List.of(earlierRepayment, payment), installments, charges(may10, may11));

        assertEquals(BigDecimal.valueOf(3), clipped.getAmount());
        assertEquals(BigDecimal.valueOf(10), payment.getPenaltyChargesPortion(INR).getAmount());
        assertEquals(BigDecimal.valueOf(4), payment.getOverPaymentPortion(INR).getAmount());
    }

    @Test
    void instalmentFeeReceipt_isSizedByItsOwnInstallment_notByTheWholeCharge() {
        // A 100-per-installment fee over two installments: the charge-level balance is 200, so sizing the first
        // receipt by the charge would plan 200, let the installment take only 100, and zero the second receipt.
        final LoanRepaymentScheduleInstallment first = installment(loan, 1, FROM, DUE, 100, 10);
        final LoanRepaymentScheduleInstallment second = installment(loan, 2, DUE, DUE.plusMonths(1), 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(first, second);
        final LoanCharge fee = ChargePaymentReceiptFixtures.instalmentFeeCharge(1L, 100, installments);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 150);
        final LoanChargePaidBy onFirst = ChargePaymentReceiptFixtures.receipt(11L, payment, fee, 100, 1);
        final LoanChargePaidBy onSecond = ChargePaymentReceiptFixtures.receipt(12L, payment, fee, 50, 2);

        replay(List.of(payment), installments, charges(fee));

        assertEquals(BigDecimal.valueOf(100), onFirst.getAmount());
        assertEquals(BigDecimal.valueOf(50), onSecond.getAmount());
        assertEquals(BigDecimal.valueOf(150), payment.getFeeChargesPortion(INR).getAmount());
        assertTrue(payment.getOverPaymentPortion(INR).isZero());
    }

    @Test
    void consistentPaymentWithANonGreedySplit_isLeftExactlyAsItIs() {
        // Its receipts already sum to the amount, so the gap is zero and nothing may move - even though a fill-to-cap
        // derivation would have put all 7 on the earlier charge and rewritten both receipts.
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(installment);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 7);
        final LoanChargePaidBy onMay10 = receipt(11L, payment, may10, 2);
        final LoanChargePaidBy onMay11 = receipt(12L, payment, may11, 5);

        replay(List.of(payment), installments, charges(may10, may11));

        assertEquals(BigDecimal.valueOf(2), onMay10.getAmount());
        assertEquals(BigDecimal.valueOf(5), onMay11.getAmount());
        assertEquals(BigDecimal.valueOf(7), payment.getPenaltyChargesPortion(INR).getAmount());
        assertTrue(payment.getOverPaymentPortion(INR).isZero());
        assertEquals(BigDecimal.valueOf(2), may10.getAmountPaid(INR).getAmount());
        assertEquals(BigDecimal.valueOf(5), may11.getAmountPaid(INR).getAmount());
    }

    @Test
    void chargeSplitAcrossTwoPayments_neitherStealsTheOthersShare() {
        // may10 was genuinely paid 4 by the first payment and 3 by the second. A fill-to-cap derivation would give the
        // first payment all 7 and leave the second parking money it really collected.
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(installment);
        final LoanTransaction first = chargePayment(100L, loan, office, PAYMENT_DATE, 4);
        final LoanChargePaidBy firstOnMay10 = receipt(11L, first, may10, 4);
        ChargePaymentReceiptFixtures.setBusinessDate(LATER_PAYMENT_DATE);
        final LoanTransaction second = chargePayment(101L, loan, office, LATER_PAYMENT_DATE, 10);
        final LoanChargePaidBy secondOnMay10 = receipt(12L, second, may10, 3);
        final LoanChargePaidBy secondOnMay11 = receipt(13L, second, may11, 7);

        replay(List.of(first, second), installments, charges(may10, may11));

        assertEquals(BigDecimal.valueOf(4), firstOnMay10.getAmount());
        assertEquals(BigDecimal.valueOf(3), secondOnMay10.getAmount());
        assertEquals(BigDecimal.valueOf(7), secondOnMay11.getAmount());
        assertEquals(BigDecimal.valueOf(4), first.getPenaltyChargesPortion(INR).getAmount());
        assertEquals(BigDecimal.valueOf(10), second.getPenaltyChargesPortion(INR).getAmount());
        assertTrue(first.getOverPaymentPortion(INR).isZero());
        assertTrue(second.getOverPaymentPortion(INR).isZero());
        assertTrue(may10.isPaid());
        assertTrue(may11.isPaid());
    }

    @Test
    void clippedPaymentCannotClaimRoomAnotherPaymentsReceiptsAccountFor() {
        // A=7 and B=7. P1 collected 4 of A and 1 of B, and is clipped by 2. P2 collected the rest: 3 of A and 4 of B.
        // Sizing P1's room from the charge's running paid amount would hand it A's remaining 3, rewrite A's receipt to
        // 6 and leave P2 unable to place its own 3. The cap must see P2's stored receipts, so P1's 2 goes to B.
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(installment);
        final LoanTransaction first = chargePayment(100L, loan, office, PAYMENT_DATE, 7);
        final LoanChargePaidBy firstOnA = receipt(11L, first, may10, 4);
        final LoanChargePaidBy firstOnB = receipt(12L, first, may11, 1);
        ChargePaymentReceiptFixtures.setBusinessDate(LATER_PAYMENT_DATE);
        final LoanTransaction second = chargePayment(101L, loan, office, LATER_PAYMENT_DATE, 7);
        final LoanChargePaidBy secondOnA = receipt(13L, second, may10, 3);
        final LoanChargePaidBy secondOnB = receipt(14L, second, may11, 4);

        replay(List.of(first, second), installments, charges(may10, may11));

        assertEquals(BigDecimal.valueOf(4), firstOnA.getAmount());
        assertEquals(BigDecimal.valueOf(3), firstOnB.getAmount());
        assertEquals(BigDecimal.valueOf(3), secondOnA.getAmount());
        assertEquals(BigDecimal.valueOf(4), secondOnB.getAmount());
        assertTrue(first.getOverPaymentPortion(INR).isZero());
        assertTrue(second.getOverPaymentPortion(INR).isZero());
        assertTrue(may10.isPaid());
        assertTrue(may11.isPaid());
    }

    @Test
    void mixedFeeAndPenaltyReceipts_areNeverDerived() {
        // One transaction pays one component, so in a mixed set a receipt would be raised on disk while its charge
        // received nothing. The planner refuses the same shape.
        final LoanCharge penalty = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge fee = ChargePaymentReceiptFixtures.feeCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(installment);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 14);
        final LoanChargePaidBy onPenalty = receipt(11L, payment, penalty, 7);
        final LoanChargePaidBy onFee = receipt(12L, payment, fee, 5);

        replay(List.of(payment), installments, charges(penalty, fee));

        assertEquals(BigDecimal.valueOf(7), onPenalty.getAmount());
        assertEquals(BigDecimal.valueOf(5), onFee.getAmount());
    }

    @Test
    void transactionCopies_neverRewriteStoredReceipts() {
        // Several callers replay copies of the real transactions and discard the derived portions, while the copy
        // shares the real receipt objects. Rewriting from such a replay would leave the receipts disagreeing with the
        // real transaction for good.
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(installment);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 14);
        receipt(11L, payment, may10, 7);
        final LoanChargePaidBy clipped = receipt(12L, payment, may11, 5);
        final LoanTransaction copy = LoanTransaction.copyTransactionProperties(payment);
        Mockito.when(loan.getLoanTransactions()).thenReturn(new ArrayList<>(List.of(payment)));

        underTest.reprocessLoanTransactions(DISBURSEMENT, List.of(copy), INR, installments, charges(may10, may11));

        assertEquals(BigDecimal.valueOf(5), clipped.getAmount());
        assertEquals(BigDecimal.valueOf(12), copy.getPenaltyChargesPortion(INR).getAmount());
        assertEquals(BigDecimal.valueOf(2), copy.getOverPaymentPortion(INR).getAmount());
    }

    @Test
    void simulatedSchedule_neverRewritesStoredReceipts() {
        // The prepayment/foreclosure quote replays the real transactions against a throwaway schedule. Nothing
        // persistent may be re-derived from it.
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanRepaymentScheduleInstallment ownInstallment = installment(loan, 1, FROM, DUE, 100, 10);
        Mockito.when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(ownInstallment));
        final LoanRepaymentScheduleInstallment simulated = installment(loan, 1, FROM, DUE, 100, 10);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 14);
        receipt(11L, payment, may10, 7);
        final LoanChargePaidBy clipped = receipt(12L, payment, may11, 5);

        Mockito.when(loan.getLoanTransactions()).thenReturn(new ArrayList<>(List.of(payment)));
        underTest.reprocessLoanTransactions(DISBURSEMENT, List.of(payment), INR, List.of(simulated), charges(may10, may11));

        assertEquals(BigDecimal.valueOf(5), clipped.getAmount());
        assertEquals(BigDecimal.valueOf(12), payment.getPenaltyChargesPortion(INR).getAmount());
        assertEquals(BigDecimal.valueOf(2), payment.getOverPaymentPortion(INR).getAmount());
    }

    @Test
    void installmentThatCannotAbsorbTheAllocation_leavesTheStoredReceiptAlone() {
        // may11 is missing from the active charge set, so the installment's penalty bucket never counted it: the
        // charge offers room but the bucket cannot take it. Lowering the receipt here would destroy the only record
        // of what was collected for that charge.
        final LoanCharge may10 = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        final LoanCharge may11 = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(installment);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 14);
        receipt(11L, payment, may10, 7);
        final LoanChargePaidBy onUncountedCharge = receipt(12L, payment, may11, 7);

        replay(List.of(payment), installments, charges(may10));

        assertEquals(BigDecimal.valueOf(7), payment.getPenaltyChargesPortion(INR).getAmount());
        assertEquals(BigDecimal.valueOf(7), payment.getOverPaymentPortion(INR).getAmount());
        assertEquals(BigDecimal.valueOf(7), onUncountedCharge.getAmount());
    }

    /**
     * The processor only derives receipt amounts when the replay targets the loan's own transactions, so the mock has
     * to report them as its own, then replays them.
     */
    @Test
    void waivedPortionOfAChargeIsNeverRepaidByATopUp() {
        // 3 of the first charge's 7 was waived, so only 4 of it is still collectable: a top-up that sized itself by
        // the charge amount would re-collect the waiver and leave the loan paid 3 more than it owes.
        final LoanRepaymentScheduleInstallment installment = installment(loan, 1, FROM, DUE, 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(installment);
        final LoanCharge waivedInPart = penaltyCharge(1L, LocalDate.of(2026, 5, 10), 7);
        waivedInPart.setAmountWaived(BigDecimal.valueOf(3));
        final LoanCharge untouched = penaltyCharge(2L, LocalDate.of(2026, 5, 11), 7);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 11);
        final LoanChargePaidBy clipped = ChargePaymentReceiptFixtures.receipt(11L, payment, waivedInPart, 2);
        final LoanChargePaidBy consistent = ChargePaymentReceiptFixtures.receipt(12L, payment, untouched, 7);

        replay(List.of(payment), installments, charges(waivedInPart, untouched));

        assertEquals(BigDecimal.valueOf(4), clipped.getAmount());
        assertEquals(BigDecimal.valueOf(7), consistent.getAmount());
        assertEquals(BigDecimal.valueOf(11), payment.getPenaltyChargesPortion(INR).getAmount());
        assertTrue(payment.getOverPaymentPortion(INR).isZero());
        assertEquals(BigDecimal.valueOf(4), waivedInPart.getAmountPaid(INR).getAmount());
        assertTrue(waivedInPart.getAmountOutstanding(INR).isZero());
        // a charge settled with a waiver behind it is flagged waived, not paid - upstream's rule, pinned here because
        // the repair's post-condition reads these flags
        assertTrue(waivedInPart.isWaived());
    }

    @Test
    void twoReceiptsOnOneInstallmentOfAnInstalmentFee_shareThatInstallmentsRoom() {
        // Both receipts pay the same installment of the same fee, so they draw on ONE 100 balance. Sizing each by that
        // balance on its own would raise the pair to 200 against an installment that owes 100.
        final LoanRepaymentScheduleInstallment first = installment(loan, 1, FROM, DUE, 100, 10);
        final LoanRepaymentScheduleInstallment second = installment(loan, 2, DUE, DUE.plusMonths(1), 100, 10);
        final List<LoanRepaymentScheduleInstallment> installments = schedule(first, second);
        final LoanCharge fee = ChargePaymentReceiptFixtures.instalmentFeeCharge(1L, 100, installments);
        final LoanTransaction payment = chargePayment(100L, loan, office, PAYMENT_DATE, 140);
        final LoanChargePaidBy earlier = ChargePaymentReceiptFixtures.receipt(11L, payment, fee, 30, 1);
        final LoanChargePaidBy later = ChargePaymentReceiptFixtures.receipt(12L, payment, fee, 20, 1);

        replay(List.of(payment), installments, charges(fee));

        // the 90 gap draws the shared room down to zero and stops: 80 + 20 = the installment's 100, never 200
        assertEquals(BigDecimal.valueOf(80), earlier.getAmount());
        assertEquals(BigDecimal.valueOf(20), later.getAmount());
        assertEquals(BigDecimal.valueOf(100), payment.getFeeChargesPortion(INR).getAmount());
        assertEquals(BigDecimal.valueOf(40), payment.getOverPaymentPortion(INR).getAmount());
        assertEquals(BigDecimal.valueOf(100), first.getFeeChargesPaid(INR).getAmount());
        assertTrue(second.getFeeChargesPaid(INR).isZero());
    }

    private void replay(final List<LoanTransaction> transactions, final List<LoanRepaymentScheduleInstallment> installments,
            final Set<LoanCharge> charges) {
        Mockito.when(loan.getLoanTransactions()).thenReturn(new ArrayList<>(transactions));
        underTest.reprocessLoanTransactions(DISBURSEMENT, transactions, INR, installments, charges);
    }

    /**
     * The processor only derives receipt amounts when the replay targets the loan's OWN schedule, so the mock has to
     * report these installments as its own.
     */
    private List<LoanRepaymentScheduleInstallment> schedule(final LoanRepaymentScheduleInstallment... installments) {
        // mutable: the processor sorts the installment list when it replays a repayment-like transaction
        final List<LoanRepaymentScheduleInstallment> ownSchedule = new ArrayList<>(List.of(installments));
        Mockito.when(loan.getRepaymentScheduleInstallments()).thenReturn(ownSchedule);
        return ownSchedule;
    }

    private static Set<LoanCharge> charges(final LoanCharge... charges) {
        return new LinkedHashSet<>(List.of(charges));
    }
}
