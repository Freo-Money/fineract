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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.JsonParser;
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
import java.util.function.Predicate;
import org.apache.fineract.infrastructure.configuration.service.TemporaryConfigurationServiceContainer;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRelation;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRelationTypeEnum;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductRoundingModeService;
import org.apache.fineract.portfolio.tax.domain.TaxComponent;
import org.apache.fineract.portfolio.tax.domain.TaxGroup;
import org.apache.fineract.portfolio.tax.domain.TaxGroupMappings;
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
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private LoanAccountDomainService loanAccountDomainService;

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
                loanTransactionRepository, jdbcTemplate, loanAccountDomainService);
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
        when(loanProduct.isAccountingDisabled()).thenReturn(true);
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

    /** A near miss of the flag would otherwise commit a plain reprocess; other unknown keys are still ignored. */
    @Test
    void isRequested_rejectsANearMissOfTheFlag_butIgnoresOtherKeys() {
        for (final String key : List.of("TopUpClippedChargeReceipts", " topUpClippedChargeReceipts ", "top_up_clipped_charge_receipts",
                "topUpClippedChargeReceipt", "tupUpClippedChargeReceipts", "clippedChargeReceiptsTopUp", "top.upClippedChargeReceipts",
                "\u00A0topUpClippedChargeReceipts")) {
            final PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                    () -> underTest.isRequested(command("{\"" + key + "\": true}")), key);
            assertEquals(key, exception.getErrors().get(0).getParameterName());
        }
        assertFalse(underTest.isRequested(command("{\"foo\": true}")));
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
        verify(loanAccountDomainService).setLoanDelinquencyTag(eq(loan), any(LocalDate.class));
    }

    /**
     * Inside a transaction the top-ups are logged on commit only, so a rolled-back maker-checker or batch run logs
     * none.
     */
    @Test
    void appliedTopUps_areLoggedOnCommitOnly() {
        reprocessDoes(this::absorbReceipts);
        final Logger logger = (Logger) LoggerFactory.getLogger(ChargePaymentReceiptTopUpServiceImpl.class.getName());
        final Level level = logger.getLevel();
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        TransactionSynchronizationManager.initSynchronization();
        try {
            underTest.reprocessWithTopUp(LOAN_ID, topUpCommand());
            assertTrue(appender.list.isEmpty());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

            assertEquals(2, appender.list.size());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            logger.detachAppender(appender);
            logger.setLevel(level);
        }
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
        verify(loanProductRoundingModeService, never()).resolveTaxRoundingMode(any());
        verify(loanAccountDomainService, never()).setLoanDelinquencyTag(any(Loan.class), any(LocalDate.class));
    }

    /** A raised receipt's GST rows are rebuilt for the new amount, with the product's tax rounding mode. */
    @Test
    void raisedReceipt_rebuildsItsGstRows() {
        final TaxComponent gst = TaxComponent.createTaxComponent("GST", new BigDecimal("18"), null, null, null, null,
                LocalDate.of(2020, 1, 1));
        feeX.setTaxGroup(TaxGroup.createTaxGroup("GST 18", Set.of(TaxGroupMappings.createTaxGroupMappings(gst, LocalDate.of(2020, 1, 1)))));
        reprocessDoes(this::absorbReceipts);

        try (MockedStatic<TemporaryConfigurationServiceContainer> config = Mockito
                .mockStatic(TemporaryConfigurationServiceContainer.class)) {
            config.when(TemporaryConfigurationServiceContainer::getTaxRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
            underTest.reprocessWithTopUp(LOAN_ID, topUpCommand());
        }

        // 200 with 18% GST inside: base 169.49, GST 30.51.
        assertEquals(1, receiptX.getLoanChargeTaxDetailsPaidBy().size());
        assertAmount("30.51", receiptX.getLoanChargeTaxDetailsPaidBy().get(0).getAmount());
        verify(loanProductRoundingModeService).resolveTaxRoundingMode(PRODUCT_ID);
    }

    /** Already overpaid before the top-up, so staying overpaid is not refused as newly overpaid. */
    @Test
    void overpaidLoan_isToppedUp_andNotRefusedAsNewlyOverpaid() {
        status.set(LoanStatus.OVERPAID);
        reprocessDoes(this::absorbReceipts);

        final Map<String, Object> changes = underTest.reprocessWithTopUp(LOAN_ID, topUpCommand()).getChanges();

        assertEquals("TOPPED_UP", changes.get("action"));
        assertEquals("OVERPAID", changes.get("statusBefore"));
        assertEquals("OVERPAID", changes.get("statusAfter"));
    }

    /** A top-up that clears the last outstanding fee closes the loan: statuses reported, tag recalculated. */
    @Test
    void closingTopUp_reportsBothStatuses_andRecalculatesTheDelinquencyTag() {
        reprocessDoes(() -> {
            absorbReceipts();
            status.set(LoanStatus.CLOSED_OBLIGATIONS_MET);
        });

        final Map<String, Object> changes = underTest.reprocessWithTopUp(LOAN_ID, topUpCommand()).getChanges();

        assertEquals("ACTIVE", changes.get("statusBefore"));
        assertEquals("CLOSED_OBLIGATIONS_MET", changes.get("statusAfter"));
        verify(loanAccountDomainService).setLoanDelinquencyTag(eq(loan), any(LocalDate.class));
    }

    @Test
    void closedLoan_isToppedUp() {
        status.set(LoanStatus.CLOSED_OBLIGATIONS_MET);
        reprocessDoes(this::absorbReceipts);

        assertEquals("TOPPED_UP", underTest.reprocessWithTopUp(LOAN_ID, topUpCommand()).getChanges().get("action"));
    }

    /** When something is topped up, a consistent payment on the loan must also end fully absorbed. */
    @Test
    void rollsBack_whenAConsistentPaymentStillParksAfterATopUp() {
        final LoanCharge z = charge(fee(3L, LocalDate.of(2027, 3, 1), "100"));
        final LoanCharge v = charge(fee(4L, LocalDate.of(2027, 3, 2), "50"));
        final LoanTransaction consistent = live(chargePayment(102L, loan, PAID_ON.plusDays(1), "150", "150", false));
        receipt(13L, consistent, z, "100");
        receipt(14L, consistent, v, "50");
        reprocessDoes(() -> {
            absorbReceipts();
            replayed(consistent, new BigDecimal("100"), false);
        });

        assertRolledBack("not.reconciled");
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
    void refusesLoanWithALiveRefundForAnActiveLoan() {
        live(transaction(301L, LoanTransactionType.REFUND_FOR_ACTIVE_LOAN, loan, BUSINESS_DATE, "100"));
        assertRefused("refund.for.active.loan");
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

    /** With nothing topped up the message says so, names the repayment and does not suggest a refund. */
    @Test
    void rollsBack_whenParkedOverpaymentGrowsWithoutATopUp() {
        consistentPayment();
        final LoanTransaction repayment = live(transaction(301L, LoanTransactionType.REPAYMENT, loan, PAID_ON, "1000"));
        reprocessDoes(() -> {
            absorbReceipts();
            repayment.setOverPayments(Money.of(INR, new BigDecimal("50")));
        });

        final String message = assertRolledBack("overpayment.moved").getDefaultUserMessage();

        assertTrue(message.startsWith("Nothing was topped up"), message);
        assertTrue(message.contains("301 REPAYMENT 0.00 -> 50.00"), message);
        assertTrue(message.endsWith("review it."), message);
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
        // Built before stubbing: Money.of calls the mocked MoneyHelper, which would interrupt the stubbing.
        final Money overpaid = Money.of(INR, new BigDecimal("50"));
        when(loan.getTotalOverpaidAsMoney()).thenReturn(overpaid);
        reprocessDoes(() -> {
            absorbReceipts();
            status.set(LoanStatus.OVERPAID);
        });

        final String message = assertRolledBack("became.overpaid").getDefaultUserMessage();

        assertTrue(message.contains("would become overpaid by INR 50.00"), message);
    }

    /** H6b: the top-up claims room a repayment had paid, so the repayment's money would move to principal. */
    @Test
    void rollsBack_whenRepaymentsWouldPayLessFeeAfterTheTopUp() {
        final LoanTransaction repayment = live(repaymentWithFee(301L, "50"));
        reprocessDoes(() -> {
            absorbReceipts();
            movesFeeToPrincipal(repayment);
        });

        final String message = assertRolledBack("repayment.charges.moved").getDefaultUserMessage();

        assertTrue(message.startsWith("After topping up 2 receipt(s) on 1 charge payment(s)"), message);
        assertTrue(message.contains("301 REPAYMENT 50.00 -> 0.00"), message);
        assertTrue(message.endsWith("review it for refund."), message);
    }

    @Test
    void rollsBack_whenRepaymentsWouldPayLessFee_evenWithNothingToppedUp() {
        consistentPayment();
        final LoanTransaction repayment = live(repaymentWithFee(301L, "50"));
        reprocessDoes(() -> {
            absorbReceipts();
            movesFeeToPrincipal(repayment);
        });

        final String message = assertRolledBack("repayment.charges.moved").getDefaultUserMessage();

        assertTrue(message.startsWith("Nothing was topped up"), message);
        assertTrue(message.endsWith("review it."), message);
    }

    /** The repayment's freed money pays a penalty instead: fee and penalty together are unchanged, but its fee fell. */
    @Test
    void rollsBack_whenARepaymentsFeeWouldMoveToPenalty() {
        final LoanTransaction repayment = live(repaymentWithFee(301L, "50"));
        reprocessDoes(() -> {
            absorbReceipts();
            repayment.resetDerivedComponents();
            repayment.updateComponents(Money.of(INR, new BigDecimal("950")), Money.zero(INR), Money.zero(INR),
                    Money.of(INR, new BigDecimal("50")));
        });

        final String message = assertRolledBack("repayment.charges.moved").getDefaultUserMessage();

        assertTrue(message.contains("Paying less fee: 301 REPAYMENT 50.00 -> 0.00"), message);
    }

    /**
     * One repayment's fee falls while another's rises by as much: the total hides it, the per-repayment check does not.
     */
    @Test
    void rollsBack_whenOneRepaymentsFeeFalls_evenIfAnothersRisesByAsMuch() {
        final LoanTransaction first = live(repaymentWithFee(301L, "30"));
        final LoanTransaction second = live(repaymentWithFee(302L, "0"));
        reprocessDoes(() -> {
            absorbReceipts();
            movesFeeToPrincipal(first);
            second.resetDerivedComponents();
            second.updateComponents(Money.of(INR, new BigDecimal("970")), Money.zero(INR), Money.of(INR, new BigDecimal("30")),
                    Money.zero(INR));
        });

        final String message = assertRolledBack("repayment.charges.moved").getDefaultUserMessage();

        assertTrue(message.contains("Paying less fee: 301 REPAYMENT 30.00 -> 0.00"), message);
    }

    /** The reprocess replaces a changed repayment under a new id; the message pairs the replacement with it. */
    @Test
    void rollbackMessage_pairsAReplayedRepaymentWithTheOneItReplaced() {
        final LoanTransaction repayment = live(repaymentWithFee(301L, "50"));
        reprocessDoes(() -> {
            absorbReceipts();
            repayment.reverse();
            final LoanTransaction replacement = live(repaymentWithFee(901L, "0"));
            LoanTransactionRelation.linkToTransaction(replacement, repayment, LoanTransactionRelationTypeEnum.REPLAYED);
        });

        final String message = assertRolledBack("repayment.charges.moved").getDefaultUserMessage();

        assertTrue(message.contains("301 REPAYMENT 50.00 -> 0.00 (replayed)"), message);
    }

    /** The replay marked the raise on another of the payment's charges, which then no longer matches its receipts. */
    @Test
    void rollsBack_whenAnotherChargeOfAToppedUpPaymentIsMarkedPaidOtherThanItsReceipts() {
        final LoanCharge z = charge(fee(3L, LocalDate.of(2027, 3, 1), "100"));
        receipt(13L, payment, z, "100");
        replayed(payment, new BigDecimal("340"), false);
        reprocessDoes(() -> {
            absorbReceipts();
            z.setAmountPaid(new BigDecimal("90"));
        });

        final String message = assertRolledBack("charge.mismatch").getDefaultUserMessage();

        assertTrue(message.startsWith("Charge 3 "), message);
    }

    /** Its journal entry booked overpayment, so raising its receipts in place would leave the ledger out of step. */
    @Test
    void paymentWithOverpaymentInItsJournalEntries_isNotToppedUp_whileTheOthersAre() {
        when(loanProduct.isAccountingDisabled()).thenReturn(false);
        final LoanCharge z = charge(fee(3L, LocalDate.of(2027, 3, 1), "100"));
        final LoanCharge v = charge(fee(4L, LocalDate.of(2027, 3, 2), "10"));
        final LoanTransaction other = live(chargePayment(102L, loan, PAID_ON.plusDays(1), "110", "70", false));
        final LoanChargePaidBy onZ = receipt(13L, other, z, "60");
        receipt(14L, other, v, "10");
        when(jdbcTemplate.queryForObject(anyString(), eq(BigDecimal.class), eq(101L), eq(PRODUCT_ID))).thenReturn(new BigDecimal("160"));
        when(jdbcTemplate.queryForObject(anyString(), eq(BigDecimal.class), eq(102L), eq(PRODUCT_ID))).thenReturn(BigDecimal.ZERO);
        reprocessDoes(this::absorbReceipts);

        final CommandProcessingResult result = underTest.reprocessWithTopUp(LOAN_ID, topUpCommand());

        assertEquals("TOPPED_UP", result.getChanges().get("action"));
        assertEquals(Map.of("LEDGER_OVERPAYMENT", 1L, "CLIPPED", 1L), result.getChanges().get("chargePaymentCounts"));
        assertAmount("120", receiptX.getAmount());
        assertAmount("120", receiptY.getAmount());
        assertAmount("100", onZ.getAmount());
    }

    @Test
    void accountingDisabled_neverReadsTheLedger() {
        reprocessDoes(this::absorbReceipts);

        underTest.reprocessWithTopUp(LOAN_ID, topUpCommand());

        verifyNoInteractions(jdbcTemplate);
    }

    /** A residual on another payment stays visible after a successful top-up. */
    @Test
    void result_showsWhatChargePaymentsStillPark() {
        final LoanCharge w = charge(fee(3L, LocalDate.of(2027, 3, 1), "60"));
        final LoanCharge v = charge(fee(4L, LocalDate.of(2027, 3, 2), "10"));
        final LoanTransaction residual = live(chargePayment(102L, loan, PAID_ON.plusDays(1), "100", "60", false));
        receipt(13L, residual, w, "50");
        receipt(14L, residual, v, "10");
        reprocessDoes(this::absorbReceipts);

        final CommandProcessingResult result = underTest.reprocessWithTopUp(LOAN_ID, topUpCommand());

        assertEquals("TOPPED_UP", result.getChanges().get("action"));
        assertAmount("40", (BigDecimal) result.getChanges().get("parkedOnChargePaymentsAfter"));
        final Map<String, Object> listedResidual = chargePayments(result).get(1);
        assertEquals("CLIPPED_RESIDUAL", listedResidual.get("classification"));
        assertAmount("40", (BigDecimal) listedResidual.get("overpaymentAfter"));
    }

    @Test
    void result_listsAtMostOneHundredPayments_andCountsTheRest() {
        transactions.clear();
        for (long id = 1; id <= 101; id++) {
            live(chargePayment(1000L + id, loan, PAID_ON, "10", "0", false));
        }
        reprocessDoes(ChargePaymentReceiptTopUpServiceImplTest::leaveAsStored);

        final CommandProcessingResult result = underTest.reprocessWithTopUp(LOAN_ID, topUpCommand());

        assertEquals(100, chargePayments(result).size());
        assertEquals(1, result.getChanges().get("chargePaymentsNotListed"));
    }

    @Test
    void result_listsAtMostTwoHundredTopUps_andCountsTheRest() {
        transactions.clear();
        final LoanTransaction many = live(chargePayment(201L, loan, PAID_ON, "402", "201", false));
        for (long id = 1; id <= 201; id++) {
            receipt(5000L + id, many, charge(fee(100L + id, LocalDate.of(2027, 1, 1), "2")), "1");
        }
        reprocessDoes(this::absorbReceipts);

        final CommandProcessingResult result = underTest.reprocessWithTopUp(LOAN_ID, topUpCommand());

        final Map<String, Object> listed = chargePayments(result).get(0);
        assertEquals(200, ((List<?>) listed.get("topUps")).size());
        assertEquals(1, listed.get("topUpsNotListed"));
    }

    /** A repayment whose stored portions put {@code fee} on fees and the rest of its 1000 on principal. */
    private LoanTransaction repaymentWithFee(final long id, final String fee) {
        final LoanTransaction repayment = transaction(id, LoanTransactionType.REPAYMENT, loan, PAID_ON, "1000");
        repayment.updateComponents(Money.of(INR, new BigDecimal("1000").subtract(new BigDecimal(fee))), Money.zero(INR),
                Money.of(INR, new BigDecimal(fee)), Money.zero(INR));
        return repayment;
    }

    /** What the replay does to such a repayment once a top-up has claimed its charge: all of it goes to principal. */
    private static void movesFeeToPrincipal(final LoanTransaction repayment) {
        repayment.resetDerivedComponents();
        repayment.updateComponents(Money.of(INR, new BigDecimal("1000")), Money.zero(INR), Money.zero(INR), Money.zero(INR));
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

    private GeneralPlatformDomainRuleException assertRolledBack(final String reason) {
        final GeneralPlatformDomainRuleException exception = assertThrows(GeneralPlatformDomainRuleException.class,
                () -> underTest.reprocessWithTopUp(LOAN_ID, topUpCommand()));
        assertEquals("error.msg.loan.charge.payment.receipt.topup." + reason, exception.getGlobalisationMessageCode());
        verify(loanAccountDomainService, never()).setLoanDelinquencyTag(any(Loan.class), any(LocalDate.class));
        return exception;
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
