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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
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
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.service.LoanBalanceService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the global 'installment-due-window-days' configuration on the overdue/due strategies: an installment due on 5
 * Dec must be treated as due for a payment on 1 Dec when the window is 4 days.
 */
@ExtendWith(MockitoExtension.class)
public class InstallmentDueWindowTransactionProcessorTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("INR", 2, 1);
    private static final MockedStatic<MoneyHelper> MONEY_HELPER = Mockito.mockStatic(MoneyHelper.class);

    private final LocalDate paymentDate = LocalDate.of(2026, 12, 1);
    private final LocalDate novDueDate = LocalDate.of(2026, 11, 5);
    private final LocalDate decDueDate = LocalDate.of(2026, 12, 5);

    private TestableProcessor underTest;

    @Mock
    private Office office;
    @Mock
    private Loan loan;
    @Mock
    private Set<LoanCharge> charges;
    @Mock
    private LoanChargeValidator loanChargeValidator;
    @Mock
    private LoanBalanceService loanBalanceService;
    @Mock
    private ConfigurationDomainService configurationDomainService;

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
        underTest = new TestableProcessor(Mockito.mock(ExternalIdFactory.class), loanChargeValidator, loanBalanceService);
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, paymentDate)));
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    public void paymentInsideDueWindow_allocatesInterestPrincipalOfBothInstallmentsThenChargesOfBoth() {
        when(configurationDomainService.retrieveInstallmentDueWindowDays()).thenReturn(4L);
        underTest.setConfigurationDomainService(configurationDomainService);

        final LoanRepaymentScheduleInstallment nov = installment(1, novDueDate.minusMonths(1), novDueDate, BigDecimal.ONE);
        final LoanRepaymentScheduleInstallment dec = installment(2, novDueDate, decDueDate, BigDecimal.ZERO);
        // 12 + 12 (I+P of both) + 3 + 1 (fee + penalty Nov) + 3 (fee Dec)
        final Money amount = Money.of(CURRENCY, BigDecimal.valueOf(31));

        final Money remaining = underTest.handleTransactionThatIsALateRepaymentOfInstallment(nov, List.of(nov, dec), repayment(amount),
                amount, new ArrayList<>(), charges);

        final InOrder inOrder = Mockito.inOrder(nov, dec);
        inOrder.verify(nov).payInterestComponent(eq(paymentDate), any());
        inOrder.verify(nov).payPrincipalComponent(eq(paymentDate), any());
        inOrder.verify(dec).payInterestComponent(eq(paymentDate), any());
        inOrder.verify(dec).payPrincipalComponent(eq(paymentDate), any());
        inOrder.verify(nov).payFeeChargesComponent(eq(paymentDate), any());
        inOrder.verify(nov).payPenaltyChargesComponent(eq(paymentDate), any());
        inOrder.verify(dec).payFeeChargesComponent(eq(paymentDate), any());

        assertTrue(remaining.isZero());
        assertTrue(nov.getTotalOutstanding(CURRENCY).isZero());
        assertTrue(dec.getTotalOutstanding(CURRENCY).isZero());
    }

    @Test
    public void paymentBeforeDueWindow_doesNotTouchNextInstallment() {
        when(configurationDomainService.retrieveInstallmentDueWindowDays()).thenReturn(3L);
        underTest.setConfigurationDomainService(configurationDomainService);

        assertNextInstallmentUntouched();
    }

    @Test
    public void withoutConfiguration_behaviourIsUnchanged() {
        assertNextInstallmentUntouched();
    }

    @Test
    public void installmentIsNotInAdvanceInsideDueWindow() {
        final LoanRepaymentScheduleInstallment nov = installment(1, novDueDate.minusMonths(1), novDueDate, BigDecimal.ZERO);
        final LoanRepaymentScheduleInstallment dec = installment(2, novDueDate, decDueDate, BigDecimal.ZERO);
        final List<LoanRepaymentScheduleInstallment> installments = List.of(nov, dec);

        assertTrue(underTest.inAdvance(1, installments, paymentDate));
        assertFalse(underTest.late(1, installments, paymentDate));

        when(configurationDomainService.retrieveInstallmentDueWindowDays()).thenReturn(4L);
        underTest.setConfigurationDomainService(configurationDomainService);

        assertFalse(underTest.inAdvance(1, installments, paymentDate));
        assertTrue(underTest.inAdvance(1, installments, paymentDate.minusDays(1)));
        // routed through the overdue/due passes inside the window...
        assertTrue(underTest.late(1, installments, paymentDate));
        assertFalse(underTest.late(1, installments, paymentDate.minusDays(1)));
        // ...but the installment itself is still not overdue (penalties/delinquency use the actual date)
        assertFalse(dec.isOverdueOn(paymentDate));
    }

    @Test
    public void multipleInstallmentsInsideWindowWithOverdue_allocatedInDateOrder() {
        when(configurationDomainService.retrieveInstallmentDueWindowDays()).thenReturn(10L);
        underTest.setConfigurationDomainService(configurationDomainService);

        final LocalDate payment = LocalDate.of(2026, 11, 30);
        final LoanRepaymentScheduleInstallment nov24 = installment(1, LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 24),
                BigDecimal.ONE);
        final LoanRepaymentScheduleInstallment dec1 = installment(2, LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 1),
                BigDecimal.ZERO);
        final LoanRepaymentScheduleInstallment dec8 = installment(3, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 8),
                BigDecimal.ZERO);
        // 3 x 12 (I+P) + 3 x 3 (fees) + 1 (penalty Nov 24)
        final Money amount = Money.of(CURRENCY, BigDecimal.valueOf(46));

        final Money remaining = underTest.process(repayment(amount, payment), List.of(nov24, dec1, dec8), charges);

        final InOrder inOrder = Mockito.inOrder(nov24, dec1, dec8);
        inOrder.verify(nov24).payInterestComponent(eq(payment), any());
        inOrder.verify(nov24).payPrincipalComponent(eq(payment), any());
        inOrder.verify(dec1).payInterestComponent(eq(payment), any());
        inOrder.verify(dec1).payPrincipalComponent(eq(payment), any());
        inOrder.verify(dec8).payInterestComponent(eq(payment), any());
        inOrder.verify(dec8).payPrincipalComponent(eq(payment), any());
        inOrder.verify(nov24).payFeeChargesComponent(eq(payment), any());
        inOrder.verify(nov24).payPenaltyChargesComponent(eq(payment), any());
        inOrder.verify(dec1).payFeeChargesComponent(eq(payment), any());
        inOrder.verify(dec8).payFeeChargesComponent(eq(payment), any());

        assertTrue(remaining.isZero());
        assertTrue(nov24.getTotalOutstanding(CURRENCY).isZero());
        assertTrue(dec1.getTotalOutstanding(CURRENCY).isZero());
        assertTrue(dec8.getTotalOutstanding(CURRENCY).isZero());
    }

    @Test
    public void multipleInstallmentsInsideWindowWithoutOverdue_allocatedInDateOrder() {
        when(configurationDomainService.retrieveInstallmentDueWindowDays()).thenReturn(10L);
        underTest.setConfigurationDomainService(configurationDomainService);

        final LocalDate payment = LocalDate.of(2026, 11, 30);
        final LoanRepaymentScheduleInstallment dec1 = installment(1, LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 1),
                BigDecimal.ZERO);
        final LoanRepaymentScheduleInstallment dec8 = installment(2, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 8),
                BigDecimal.ZERO);
        // 24 for I+P of both first, then 3 for the Dec 1 fee; the Dec 8 fee stays outstanding
        final Money amount = Money.of(CURRENCY, BigDecimal.valueOf(27));

        final Money remaining = underTest.process(repayment(amount, payment), List.of(dec1, dec8), charges);

        final InOrder inOrder = Mockito.inOrder(dec1, dec8);
        inOrder.verify(dec1).payInterestComponent(eq(payment), any());
        inOrder.verify(dec1).payPrincipalComponent(eq(payment), any());
        inOrder.verify(dec8).payInterestComponent(eq(payment), any());
        inOrder.verify(dec8).payPrincipalComponent(eq(payment), any());
        inOrder.verify(dec1).payFeeChargesComponent(eq(payment), any());

        assertTrue(remaining.isZero());
        assertTrue(dec1.getTotalOutstanding(CURRENCY).isZero());
        assertEquals(0, BigDecimal.valueOf(3).compareTo(dec8.getTotalOutstanding(CURRENCY).getAmount()));
        assertEquals(0, BigDecimal.valueOf(3).compareTo(dec8.getFeeChargesOutstanding(CURRENCY).getAmount()));
    }

    @Test
    public void strategyNotOptedIn_ignoresDueWindow() {
        Mockito.lenient().when(configurationDomainService.retrieveInstallmentDueWindowDays()).thenReturn(4L);
        final NotOptedInProcessor notOptedIn = new NotOptedInProcessor(Mockito.mock(ExternalIdFactory.class), loanChargeValidator,
                loanBalanceService);
        notOptedIn.setConfigurationDomainService(configurationDomainService);
        final List<LoanRepaymentScheduleInstallment> installments = List.of(
                installment(1, novDueDate.minusMonths(1), novDueDate, BigDecimal.ZERO),
                installment(2, novDueDate, decDueDate, BigDecimal.ZERO));

        assertTrue(notOptedIn.inAdvance(1, installments, paymentDate));
    }

    private void assertNextInstallmentUntouched() {
        final LoanRepaymentScheduleInstallment nov = installment(1, novDueDate.minusMonths(1), novDueDate, BigDecimal.ONE);
        final LoanRepaymentScheduleInstallment dec = installment(2, novDueDate, decDueDate, BigDecimal.ZERO);
        final Money amount = Money.of(CURRENCY, BigDecimal.valueOf(31));

        final Money remaining = underTest.handleTransactionThatIsALateRepaymentOfInstallment(nov, List.of(nov, dec), repayment(amount),
                amount, new ArrayList<>(), charges);

        verify(dec, never()).payInterestComponent(any(), any());
        verify(dec, never()).payPrincipalComponent(any(), any());
        verify(dec, never()).payFeeChargesComponent(any(), any());
        assertTrue(nov.getTotalOutstanding(CURRENCY).isZero());
        assertEquals(0, remaining.getAmount().compareTo(BigDecimal.valueOf(15)));
    }

    private LoanRepaymentScheduleInstallment installment(final int number, final LocalDate fromDate, final LocalDate dueDate,
            final BigDecimal penalty) {
        // principal 10, interest 2, fee 3
        return Mockito.spy(new LoanRepaymentScheduleInstallment(loan, number, fromDate, dueDate, BigDecimal.TEN, BigDecimal.valueOf(2),
                BigDecimal.valueOf(3), penalty, false, null, BigDecimal.ZERO));
    }

    private LoanTransaction repayment(final Money amount) {
        return repayment(amount, paymentDate);
    }

    private LoanTransaction repayment(final Money amount, final LocalDate date) {
        return LoanTransaction.repayment(office, amount, null, date, ExternalId.empty());
    }

    private static final class TestableProcessor
            extends OverdueDueInterestPrincipalOverdueDueFeesPenaltiesOrderLoanRepaymentScheduleTransactionProcessor {

        TestableProcessor(final ExternalIdFactory externalIdFactory, final LoanChargeValidator loanChargeValidator,
                final LoanBalanceService loanBalanceService) {
            super(externalIdFactory, loanChargeValidator, loanBalanceService);
        }

        boolean inAdvance(final int index, final List<LoanRepaymentScheduleInstallment> installments, final LocalDate date) {
            return isTransactionInAdvanceOfInstallment(index, installments, date);
        }

        boolean late(final int index, final List<LoanRepaymentScheduleInstallment> installments, final LocalDate date) {
            return isTransactionALateRepaymentOnInstallment(index, installments, date);
        }

        Money process(final LoanTransaction transaction, final List<LoanRepaymentScheduleInstallment> installments,
                final Set<LoanCharge> charges) {
            return processTransaction(transaction, CURRENCY, installments, charges, null);
        }
    }

    /** principal-interest-penalties-fees-order-strategy is not in the opted-in list. */
    private static final class NotOptedInProcessor extends PrincipalInterestPenaltyFeesOrderLoanRepaymentScheduleTransactionProcessor {

        NotOptedInProcessor(final ExternalIdFactory externalIdFactory, final LoanChargeValidator loanChargeValidator,
                final LoanBalanceService loanBalanceService) {
            super(externalIdFactory, loanChargeValidator, loanBalanceService);
        }

        boolean inAdvance(final int index, final List<LoanRepaymentScheduleInstallment> installments, final LocalDate date) {
            return isTransactionInAdvanceOfInstallment(index, installments, date);
        }
    }
}
