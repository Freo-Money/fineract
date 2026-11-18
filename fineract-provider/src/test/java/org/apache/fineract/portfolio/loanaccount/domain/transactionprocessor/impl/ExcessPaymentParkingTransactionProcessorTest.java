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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionComparator;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.AbstractLoanRepaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.MoneyHolder;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.TransactionCtx;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.service.LoanBalanceService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Excess payment parking at transaction-processor level, for the three strategies parking supports (mifos-standard, OD
 * and ADV). Schedule: disbursed 01-01, two installments of 3,000 principal + 120 interest due 02-01 and 03-01.
 */
class ExcessPaymentParkingTransactionProcessorTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("INR", 2, 1);
    private static final MockedStatic<MoneyHelper> MONEY_HELPER = Mockito.mockStatic(MoneyHelper.class);

    private static final LocalDate DISBURSED = LocalDate.of(2027, 1, 1);
    private static final LocalDate DUE_1 = LocalDate.of(2027, 2, 1);
    private static final LocalDate DUE_2 = LocalDate.of(2027, 3, 1);
    private static final BigDecimal EMI = BigDecimal.valueOf(3120);

    private final AtomicReference<BigDecimal> pool = new AtomicReference<>();
    private Loan loan;
    private Office office;

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
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, DUE_2)));
        office = Mockito.mock(Office.class);
        loan = Mockito.mock(Loan.class);
        final LoanProductRelatedDetail detail = Mockito.mock(LoanProductRelatedDetail.class);
        lenient().when(detail.isEnableExcessPaymentParking()).thenReturn(true);
        lenient().when(loan.getLoanProductRelatedDetail()).thenReturn(detail);
        lenient().when(loan.getCurrency()).thenReturn(CURRENCY);
        lenient().when(loan.getDisbursementDate()).thenReturn(DISBURSED);
        pool.set(null);
        lenient().when(loan.getTotalExcessPaymentAmount()).thenAnswer(invocation -> pool.get());
        lenient().doAnswer(invocation -> {
            pool.set(nz(pool.get()).add(((Money) invocation.getArgument(0)).getAmount()));
            return null;
        }).when(loan).addToTotalExcessPaymentAmount(any());
        lenient().doAnswer(invocation -> {
            pool.set(nz(pool.get()).subtract(((Money) invocation.getArgument(0)).getAmount()));
            return null;
        }).when(loan).subtractFromTotalExcessPaymentAmount(any());
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    static Stream<Arguments> strategies() {
        final ExternalIdFactory externalIdFactory = Mockito.mock(ExternalIdFactory.class);
        final LoanChargeValidator chargeValidator = Mockito.mock(LoanChargeValidator.class);
        final LoanBalanceService balanceService = Mockito.mock(LoanBalanceService.class);
        return Stream.of(
                Arguments.of("MIF",
                        new FineractStyleLoanRepaymentScheduleTransactionProcessor(externalIdFactory, chargeValidator, balanceService)),
                Arguments.of("OD",
                        new OverdueDueInterestPrincipalOverdueDueFeesPenaltiesOrderLoanRepaymentScheduleTransactionProcessor(
                                externalIdFactory, chargeValidator, balanceService)),
                Arguments.of("ADV", new OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessor(
                        externalIdFactory, chargeValidator, balanceService)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void earlyPaymentParksTheWholeAmount(final String name, final AbstractLoanRepaymentScheduleTransactionProcessor processor) {
        final List<LoanRepaymentScheduleInstallment> installments = schedule();
        final LoanTransaction repayment = repayment(LocalDate.of(2027, 1, 10), BigDecimal.valueOf(6240));

        final Money overpaid = process(processor, repayment, installments);

        assertMoney(BigDecimal.ZERO, overpaid.getAmount());
        assertMoney(BigDecimal.valueOf(6240), repayment.getExcessPayment(CURRENCY).getAmount());
        assertMoney(BigDecimal.valueOf(6240), pool.get());
        assertMoney(EMI, outstanding(installments.get(0)));
        assertMoney(EMI, outstanding(installments.get(1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void paymentOnDueDateSettlesThatInstallmentAndParksTheRest(final String name,
            final AbstractLoanRepaymentScheduleTransactionProcessor processor) {
        final List<LoanRepaymentScheduleInstallment> installments = schedule();
        final LoanTransaction repayment = repayment(DUE_1, BigDecimal.valueOf(5000));

        process(processor, repayment, installments);

        assertMoney(BigDecimal.ZERO, outstanding(installments.get(0)));
        // Nothing is paid ahead into the next installment (ADV included): the rest is parked.
        assertMoney(EMI, outstanding(installments.get(1)));
        assertMoney(BigDecimal.valueOf(1880), repayment.getExcessPayment(CURRENCY).getAmount());
        assertMoney(BigDecimal.valueOf(1880), pool.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void replayHonoursTheParkedMarkerAfterSettlingWhatIsDue(final String name,
            final AbstractLoanRepaymentScheduleTransactionProcessor processor) {
        final List<LoanRepaymentScheduleInstallment> installments = schedule();
        final LoanTransaction repayment = repayment(DUE_1, BigDecimal.valueOf(5000));
        repayment.setExcessPayment(Money.of(CURRENCY, BigDecimal.valueOf(1880)));

        process(processor, repayment, installments);

        assertMoney(BigDecimal.ZERO, outstanding(installments.get(0)));
        assertMoney(BigDecimal.valueOf(1880), repayment.getExcessPayment(CURRENCY).getAmount());
        assertMoney(BigDecimal.valueOf(1880), pool.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void sweepSettlesTheDueInstallmentFromThePool(final String name, final AbstractLoanRepaymentScheduleTransactionProcessor processor) {
        final List<LoanRepaymentScheduleInstallment> installments = schedule();
        pool.set(BigDecimal.valueOf(6240));
        final LoanTransaction sweep = sweep(DUE_1, EMI);

        final Money overpaid = process(processor, sweep, installments);

        assertMoney(BigDecimal.ZERO, overpaid.getAmount());
        assertMoney(BigDecimal.ZERO, outstanding(installments.get(0)));
        assertMoney(EMI, outstanding(installments.get(1)));
        assertMoney(BigDecimal.ZERO, sweep.getExcessPayment(CURRENCY).getAmount());
        assertMoney(EMI, pool.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void sweepReParksWhatItCannotApplyWhileAnInstallmentIsOpen(final String name,
            final AbstractLoanRepaymentScheduleTransactionProcessor processor) {
        final List<LoanRepaymentScheduleInstallment> installments = schedule();
        pool.set(BigDecimal.valueOf(5000));
        final LoanTransaction sweep = sweep(DUE_1, BigDecimal.valueOf(5000));

        final Money overpaid = process(processor, sweep, installments);

        assertMoney(BigDecimal.ZERO, overpaid.getAmount());
        assertMoney(BigDecimal.ZERO, outstanding(installments.get(0)));
        assertMoney(EMI, outstanding(installments.get(1)));
        assertMoney(BigDecimal.valueOf(1880), sweep.getExcessPayment(CURRENCY).getAmount());
        assertMoney(BigDecimal.valueOf(1880), pool.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void sweepWithNothingOpenAfterItTurnsTheSurplusIntoOverpayment(final String name,
            final AbstractLoanRepaymentScheduleTransactionProcessor processor) {
        final List<LoanRepaymentScheduleInstallment> installments = new ArrayList<>(List.of(installment(1, DISBURSED, DUE_1)));
        pool.set(BigDecimal.valueOf(3500));
        final LoanTransaction sweep = sweep(DUE_1, BigDecimal.valueOf(3500));

        final Money overpaid = process(processor, sweep, installments);

        assertMoney(BigDecimal.valueOf(380), overpaid.getAmount());
        assertMoney(BigDecimal.ZERO, outstanding(installments.get(0)));
        assertMoney(BigDecimal.ZERO, sweep.getExcessPayment(CURRENCY).getAmount());
        assertMoney(BigDecimal.ZERO, pool.get());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("strategies")
    void sweepLargerThanThePoolIsRefused(final String name, final AbstractLoanRepaymentScheduleTransactionProcessor processor) {
        final List<LoanRepaymentScheduleInstallment> installments = schedule();
        pool.set(BigDecimal.valueOf(1000));
        final LoanTransaction sweep = sweep(DUE_1, EMI);

        final GeneralPlatformDomainRuleException exception = assertThrows(GeneralPlatformDomainRuleException.class,
                () -> process(processor, sweep, installments));
        assertEquals("error.msg.loan.excess.sweep.unfunded.on.replay", exception.getGlobalisationMessageCode());
    }

    @Test
    void bpiRepaymentCollectedAtDisbursementIsNotParked() {
        Mockito.when(loan.isBpiCollectedAtDisbursement()).thenReturn(true);
        Mockito.when(loan.getBrokenPeriodInterest()).thenReturn(BigDecimal.valueOf(200));
        final List<LoanRepaymentScheduleInstallment> installments = schedule();
        final LoanTransaction bpi = repayment(DISBURSED, BigDecimal.valueOf(200));

        process(strategy("MIF"), bpi, installments);

        assertMoney(BigDecimal.ZERO, bpi.getExcessPayment(CURRENCY).getAmount());
        assertMoney(BigDecimal.valueOf(2920), outstanding(installments.get(0)));
        assertTrue(pool.get() == null || pool.get().signum() == 0);
    }

    @Test
    void sameDateOrderIsPaymentThenSweepThenRefundAndWriteOff() {
        final LoanTransaction refund = transaction(LoanTransactionType.CREDIT_BALANCE_REFUND, DUE_1, BigDecimal.TEN);
        final LoanTransaction writeOff = transaction(LoanTransactionType.WRITEOFF, DUE_1, BigDecimal.TEN);
        final LoanTransaction sweep = sweep(DUE_1, BigDecimal.TEN);
        final LoanTransaction payment = repayment(DUE_1, BigDecimal.TEN);
        final List<LoanTransaction> transactions = new ArrayList<>(List.of(writeOff, refund, sweep, payment));

        transactions.sort(LoanTransactionComparator.INSTANCE);

        assertEquals(List.of(payment, sweep), transactions.subList(0, 2));
        assertTrue(transactions.subList(2, 4).containsAll(List.of(refund, writeOff)));
    }

    @Test
    void sameDateRecoverySortsAfterTheSweepAndKeepsItsOrderWithTheWriteOff() {
        final LoanTransaction recovery = transaction(LoanTransactionType.RECOVERY_REPAYMENT, DUE_1, BigDecimal.TEN);
        final LoanTransaction sweep = sweep(DUE_1, BigDecimal.TEN);
        final LoanTransaction writeOff = transaction(LoanTransactionType.WRITEOFF, DUE_1, BigDecimal.TEN);

        // Same rank: neither sorts ahead of the other on type alone, and both come after the sweep.
        assertEquals(0, LoanTransactionComparator.INSTANCE.compare(writeOff, recovery));
        final List<LoanTransaction> transactions = new ArrayList<>(List.of(recovery, writeOff, sweep));
        transactions.sort(LoanTransactionComparator.INSTANCE);
        assertEquals(sweep, transactions.get(0));
    }

    private Money process(final AbstractLoanRepaymentScheduleTransactionProcessor processor, final LoanTransaction transaction,
            final List<LoanRepaymentScheduleInstallment> installments) {
        final MoneyHolder overpaymentHolder = new MoneyHolder(Money.zero(CURRENCY));
        processor.processLatestTransaction(transaction,
                new TransactionCtx(CURRENCY, installments, new HashSet<>(), overpaymentHolder, null));
        return overpaymentHolder.getMoneyObject();
    }

    private static AbstractLoanRepaymentScheduleTransactionProcessor strategy(final String name) {
        return strategies().filter(arguments -> name.equals(arguments.get()[0])).map(arguments -> arguments.get()[1])
                .map(AbstractLoanRepaymentScheduleTransactionProcessor.class::cast).findFirst().orElseThrow();
    }

    private List<LoanRepaymentScheduleInstallment> schedule() {
        return new ArrayList<>(List.of(installment(1, DISBURSED, DUE_1), installment(2, DUE_1, DUE_2)));
    }

    private LoanRepaymentScheduleInstallment installment(final int number, final LocalDate from, final LocalDate due) {
        return new LoanRepaymentScheduleInstallment(loan, number, from, due, BigDecimal.valueOf(3000), BigDecimal.valueOf(120),
                BigDecimal.ZERO, BigDecimal.ZERO, false, null, BigDecimal.ZERO);
    }

    private LoanTransaction repayment(final LocalDate date, final BigDecimal amount) {
        return transaction(LoanTransactionType.REPAYMENT, date, amount);
    }

    private LoanTransaction sweep(final LocalDate date, final BigDecimal amount) {
        return transaction(LoanTransactionType.REPAYMENT_FROM_EXCESS_AMOUNT, date, amount);
    }

    private LoanTransaction transaction(final LoanTransactionType type, final LocalDate date, final BigDecimal amount) {
        final LoanTransaction transaction = LoanTransaction.repaymentType(type, office, Money.of(CURRENCY, amount), null, date,
                ExternalId.empty(), null);
        transaction.updateLoan(loan);
        return transaction;
    }

    private static BigDecimal outstanding(final LoanRepaymentScheduleInstallment installment) {
        return installment.getTotalOutstanding(CURRENCY).getAmount();
    }

    private static BigDecimal nz(final BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static void assertMoney(final BigDecimal expected, final BigDecimal actual) {
        assertEquals(0, expected.compareTo(nz(actual)), () -> "expected " + expected + " but was " + actual);
    }
}
