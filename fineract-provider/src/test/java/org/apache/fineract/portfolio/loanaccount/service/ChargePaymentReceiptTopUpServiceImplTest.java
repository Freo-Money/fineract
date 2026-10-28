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
import static org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptTopUpFixtures.receipt;
import static org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptTopUpFixtures.replayed;
import static org.apache.fineract.portfolio.loanaccount.service.ChargePaymentReceiptTopUpFixtures.transaction;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductRoundingModeService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChargePaymentReceiptTopUpServiceImplTest {

    private static final MockedStatic<MoneyHelper> MONEY_HELPER = Mockito.mockStatic(MoneyHelper.class);
    private static final Long LOAN_ID = 835L;
    private static final Long PRODUCT_ID = 196L;
    private static final String FLAG = ChargePaymentReceiptTopUpService.TOP_UP_CLIPPED_CHARGE_RECEIPTS_PARAM;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 5, 5);
    private static final LocalDate PAID_ON = LocalDate.of(2027, 3, 5);

    @Mock
    private LoanAssembler loanAssembler;
    @Mock
    private LoanWritePlatformService loanWritePlatformService;
    @Mock
    private LoanProductRoundingModeService loanProductRoundingModeService;
    @Mock
    private LoanTransactionRepository loanTransactionRepository;
    @Mock
    private Loan loan;
    @Mock
    private LoanProduct loanProduct;

    private final List<LoanTransaction> transactions = new ArrayList<>();
    private final List<LoanCharge> charges = new ArrayList<>();
    private final AtomicReference<LoanStatus> status = new AtomicReference<>(LoanStatus.ACTIVE);
    private ChargePaymentReceiptTopUpServiceImpl underTest;
    private LoanCharge feeX;
    private LoanTransaction payment;
    private LoanChargePaidBy receiptX;
    private LoanChargePaidBy receiptY;

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
        underTest = new ChargePaymentReceiptTopUpServiceImpl(loanAssembler, loanWritePlatformService, loanProductRoundingModeService,
                loanTransactionRepository);
        // A clipped payment: two fees of 200 paid with 400, receipts lowered to 120 each, the replay parked 160.
        feeX = charge(fee(1L, LocalDate.of(2027, 1, 1), "200"));
        final LoanCharge y = charge(fee(2L, LocalDate.of(2027, 2, 1), "200"));
        payment = live(chargePayment(101L, loan, PAID_ON, "400", "240", false));
        receiptX = receipt(11L, payment, feeX, "120");
        receiptY = receipt(12L, payment, y, "120");

        when(loanAssembler.assembleFrom(LOAN_ID)).thenReturn(loan);
        when(loan.getId()).thenReturn(LOAN_ID);
        when(loan.getCurrency()).thenReturn(INR);
        when(loan.getStatus()).thenAnswer(invocation -> status.get());
        when(loan.getExternalId()).thenReturn(ExternalId.empty());
        when(loan.getLoanProduct()).thenReturn(loanProduct);
        when(loanProduct.getId()).thenReturn(PRODUCT_ID);
        when(loan.getLoanTransactions()).thenReturn(transactions);
        when(loan.getLoanTransactions(ArgumentMatchers.<Predicate<LoanTransaction>>any()))
                .thenAnswer(invocation -> transactions.stream().filter(invocation.<Predicate<LoanTransaction>>getArgument(0)).toList());
        when(loanProductRoundingModeService.resolveTaxRoundingMode(PRODUCT_ID)).thenReturn(RoundingMode.HALF_EVEN);
        when(loanTransactionRepository.findTransactionIdsByLoan(loan))
                .thenAnswer(invocation -> transactions.stream().map(LoanTransaction::getId).toList());
        when(loanTransactionRepository.findReversedTransactionIdsByLoan(loan))
                .thenAnswer(invocation -> transactions.stream().filter(LoanTransaction::isReversed).map(LoanTransaction::getId).toList());
    }

    @AfterEach
    void tearDown() {
        ChargePaymentReceiptTopUpFixtures.resetContext();
    }

    @Test
    void isRequested_readsTheFlag_andIgnoresBodiesWithoutIt() {
        assertFalse(underTest.isRequested(command(null)));
        assertFalse(underTest.isRequested(command("[]")));
        assertFalse(underTest.isRequested(command("{}")));
        assertFalse(underTest.isRequested(command("{\"" + FLAG + "\": false}")));
        assertTrue(underTest.isRequested(command("{\"" + FLAG + "\": true}")));
        assertTrue(underTest.isRequested(command("{\"" + FLAG + "\": \" TRUE \"}")));
    }

    @Test
    void isRequested_rejectsAnythingButABoolean() {
        for (final String value : List.of("\"yes\"", "1", "null", "{}")) {
            final PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                    () -> underTest.isRequested(command("{\"" + FLAG + "\": " + value + "}")), value);
            assertEquals(FLAG, exception.getErrors().get(0).getParameterName());
        }
    }

    @Test
    void clippedPayment_isToppedUp_reprocessed_andReported() {
        reprocessDoes(this::absorbReceipts);

        final CommandProcessingResult result = underTest.reprocessWithTopUp(LOAN_ID, topUpCommand());

        assertAmount("200", receiptX.getAmount());
        assertAmount("200", receiptY.getAmount());
        verify(loanWritePlatformService).reprocessLoanTransactions(eq(LOAN_ID), any(JsonCommand.class));
        final Map<String, Object> changes = result.getChanges();
        assertEquals("TOPPED_UP", changes.get("action"));
        assertEquals(Map.of("CLIPPED", 1L), changes.get("chargePaymentCounts"));
        assertAmount("160", (BigDecimal) changes.get("parkedOverpaymentBefore"));
        assertAmount("0", (BigDecimal) changes.get("parkedOverpaymentAfter"));
        assertEquals(2, ((List<?>) chargePayments(result).get(0).get("topUps")).size());
    }

    @Test
    void nothingToTopUp_stillReprocesses_andReportsNoTopUp() {
        consistentPayment();
        reprocessDoes(this::absorbReceipts);

        final CommandProcessingResult result = underTest.reprocessWithTopUp(LOAN_ID, topUpCommand());

        verify(loanWritePlatformService).reprocessLoanTransactions(eq(LOAN_ID), any(JsonCommand.class));
        assertEquals("NO_TOP_UP", result.getChanges().get("action"));
        assertEquals(Map.of("CONSISTENT", 1L), result.getChanges().get("chargePaymentCounts"));
        assertTrue(chargePayments(result).isEmpty());
    }

    /** Review example: the room on the shared charge could be either payment's, so nothing is raised. */
    @Test
    void roomSharedBetweenShortPayments_isReported_andNothingIsRaised() {
        transactions.clear();
        final LoanCharge chargeC = charge(fee(3L, LocalDate.of(2027, 1, 1), "10"));
        final LoanCharge chargeX = charge(fee(4L, LocalDate.of(2027, 2, 1), "2"));
        final LoanCharge chargeW = charge(fee(5L, LocalDate.of(2027, 3, 1), "1"));
        final LoanTransaction a = live(chargePayment(201L, loan, LocalDate.of(2027, 1, 5), "7", "5", false));
        final LoanChargePaidBy aOnC = receipt(21L, a, chargeC, "5");
        final LoanChargePaidBy aOnX = receipt(22L, a, chargeX, "0");
        final LoanTransaction b = live(chargePayment(202L, loan, LocalDate.of(2027, 2, 5), "6", "4", false));
        final LoanChargePaidBy bOnC = receipt(23L, b, chargeC, "3");
        receipt(24L, b, chargeW, "1");
        reprocessDoes(ChargePaymentReceiptTopUpServiceImplTest::leaveAsStored);

        final CommandProcessingResult result = underTest.reprocessWithTopUp(LOAN_ID, topUpCommand());

        assertEquals("NO_TOP_UP", result.getChanges().get("action"));
        assertEquals(Map.of("AMBIGUOUS_ROOM", 2L), result.getChanges().get("chargePaymentCounts"));
        assertAmount("5", aOnC.getAmount());
        assertAmount("0", aOnX.getAmount());
        assertAmount("3", bOnC.getAmount());
    }

    @Test
    void refusesLoanThatIsNotActiveClosedOrOverpaid() {
        status.set(LoanStatus.SUBMITTED_AND_PENDING_APPROVAL);
        assertRefused("status");
    }

    @Test
    void refusesProgressiveLoan() {
        when(loan.isProgressiveSchedule()).thenReturn(true);
        assertRefused("progressive.schedule");
    }

    @Test
    void refusesChargedOffLoan() {
        when(loan.isChargedOff()).thenReturn(true);
        assertRefused("charged.off");
    }

    @Test
    void refusesLoanWithALiveCreditBalanceRefund() {
        live(transaction(301L, LoanTransactionType.CREDIT_BALANCE_REFUND, loan, BUSINESS_DATE, "100"));
        assertRefused("credit.balance.refund");
    }

    @Test
    void reversedCreditBalanceRefund_doesNotBlock() {
        live(transaction(301L, LoanTransactionType.CREDIT_BALANCE_REFUND, loan, BUSINESS_DATE, "100")).reverse();
        reprocessDoes(this::absorbReceipts);

        final CommandProcessingResult result = underTest.reprocessWithTopUp(LOAN_ID, topUpCommand());

        assertEquals("TOPPED_UP", result.getChanges().get("action"));
    }

    @Test
    void rollsBack_whenTheToppedUpPaymentStillParksMoney() {
        reprocessDoes(ChargePaymentReceiptTopUpServiceImplTest::leaveAsStored);
        assertRolledBack("not.reconciled");
    }

    /** A payment that is not topped up must not park more than its stored overpayment (stale pre-#153 portions). */
    @Test
    void rollsBack_whenAnotherPaymentParksMoreThanBefore() {
        final LoanCharge z = charge(fee(3L, LocalDate.of(2027, 3, 1), "100"));
        final LoanTransaction stale = live(chargePayment(102L, loan, LocalDate.of(2027, 3, 6), "150", "150", false));
        receipt(13L, stale, z, "100");
        reprocessDoes(this::absorbReceipts);
        assertRolledBack("not.reconciled");
    }

    @Test
    void rollsBack_whenAToppedUpChargeIsMarkedPaidOtherThanItsReceipts() {
        reprocessDoes(() -> {
            absorbReceipts();
            feeX.setAmountPaid(new BigDecimal("150"));
        });
        assertRolledBack("charge.mismatch");
    }

    @Test
    void rollsBack_whenParkedOverpaymentGrowsWithoutATopUp() {
        consistentPayment();
        final LoanTransaction repayment = live(transaction(301L, LoanTransactionType.REPAYMENT, loan, PAID_ON, "1000"));
        reprocessDoes(() -> {
            absorbReceipts();
            repayment.setOverPayments(Money.of(INR, new BigDecimal("50")));
        });
        assertRolledBack("overpayment.moved");
    }

    /**
     * The loan-wide figure alone would pass: the stale charge payment parks 50 less while the repayment parks 50 more.
     */
    @Test
    void rollsBack_whenARepaymentParksMore_evenIfAStaleChargePaymentParksLess() {
        final LoanCharge z = charge(fee(3L, LocalDate.of(2027, 3, 1), "100"));
        final LoanTransaction stale = live(chargePayment(102L, loan, LocalDate.of(2027, 3, 6), "100", "50", false));
        receipt(13L, stale, z, "100");
        final LoanTransaction repayment = live(transaction(301L, LoanTransactionType.REPAYMENT, loan, PAID_ON, "1000"));
        reprocessDoes(() -> {
            absorbReceipts();
            repayment.setOverPayments(Money.of(INR, new BigDecimal("50")));
        });
        assertRolledBack("overpayment.moved");
    }

    @Test
    void rollsBack_whenTheLoanWouldBecomeOverpaid() {
        consistentPayment();
        reprocessDoes(() -> {
            absorbReceipts();
            status.set(LoanStatus.OVERPAID);
        });
        assertRolledBack("became.overpaid");
    }

    private LoanCharge charge(final LoanCharge charge) {
        charges.add(charge);
        return charge;
    }

    private LoanTransaction live(final LoanTransaction transaction) {
        transactions.add(transaction);
        return transaction;
    }

    private void consistentPayment() {
        receiptX.setAmount(new BigDecimal("200"), RoundingMode.HALF_EVEN);
        receiptY.setAmount(new BigDecimal("200"), RoundingMode.HALF_EVEN);
        replayed(payment, new BigDecimal("400"), false);
    }

    /**
     * What the standard reprocess does with receipts it can absorb: fee = receipts, rest parked, charges paid =
     * receipts.
     */
    private void absorbReceipts() {
        final Map<Long, BigDecimal> paid = new HashMap<>();
        for (final LoanTransaction transaction : transactions) {
            if (!transaction.isChargePayment()) {
                continue;
            }
            BigDecimal receipts = BigDecimal.ZERO;
            for (final LoanChargePaidBy receipt : transaction.getLoanChargesPaid()) {
                receipts = receipts.add(receipt.getAmount());
                paid.merge(receipt.getLoanCharge().getId(), receipt.getAmount(), BigDecimal::add);
            }
            replayed(transaction, receipts, false);
        }
        charges.forEach(charge -> charge.setAmountPaid(paid.getOrDefault(charge.getId(), BigDecimal.ZERO)));
    }

    /** A reprocess that changes nothing: the stored portions stay as they are. */
    private static void leaveAsStored() {}

    private void reprocessDoes(final Runnable effect) {
        doAnswer(invocation -> {
            effect.run();
            return null;
        }).when(loanWritePlatformService).reprocessLoanTransactions(eq(LOAN_ID), any(JsonCommand.class));
    }

    private void assertRefused(final String reason) {
        final GeneralPlatformDomainRuleException exception = assertThrows(GeneralPlatformDomainRuleException.class,
                () -> underTest.reprocessWithTopUp(LOAN_ID, topUpCommand()));
        assertEquals("error.msg.loan.charge.payment.receipt.topup." + reason, exception.getGlobalisationMessageCode());
        verify(loanWritePlatformService, never()).reprocessLoanTransactions(any(), any());
        assertAmount("120", receiptX.getAmount());
    }

    private void assertRolledBack(final String reason) {
        final GeneralPlatformDomainRuleException exception = assertThrows(GeneralPlatformDomainRuleException.class,
                () -> underTest.reprocessWithTopUp(LOAN_ID, topUpCommand()));
        assertEquals("error.msg.loan.charge.payment.receipt.topup." + reason, exception.getGlobalisationMessageCode());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> chargePayments(final CommandProcessingResult result) {
        return (List<Map<String, Object>>) result.getChanges().get("chargePayments");
    }

    private static JsonCommand topUpCommand() {
        return command("{\"" + FLAG + "\": true}");
    }

    private static JsonCommand command(final String json) {
        return JsonCommand.fromJsonElement(LOAN_ID, json == null ? null : JsonParser.parseString(json));
    }

    private static void assertAmount(final String expected, final BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }
}
