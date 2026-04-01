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

import static org.mockito.ArgumentMatchers.eq;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
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

@ExtendWith(MockitoExtension.class)
public class OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessorTest {

    private static final MonetaryCurrency MONETARY_CURRENCY = new MonetaryCurrency("USD", 2, 1);
    private static final MockedStatic<MoneyHelper> MONEY_HELPER = Mockito.mockStatic(MoneyHelper.class);

    private final LocalDate transactionDate = LocalDate.of(2023, 7, 15);
    private final LocalDate fromDate1 = transactionDate.minusDays(30);
    private final LocalDate fromDate2 = transactionDate.minusDays(30);
    private final LocalDate dueDate1 = transactionDate.minusDays(1);
    private final LocalDate dueDate2 = transactionDate.plusDays(10);

    private final Money nineteen = Money.of(MONETARY_CURRENCY, BigDecimal.valueOf(19));
    private final Money forty = Money.of(MONETARY_CURRENCY, BigDecimal.valueOf(40));
    private final Money sixteen = Money.of(MONETARY_CURRENCY, BigDecimal.valueOf(16));
    private final Money fourteen = Money.of(MONETARY_CURRENCY, BigDecimal.valueOf(14));
    private final Money seven = Money.of(MONETARY_CURRENCY, BigDecimal.valueOf(7));
    private final Money one = Money.of(MONETARY_CURRENCY, BigDecimal.valueOf(1));
    private final Money thirtySeven = Money.of(MONETARY_CURRENCY, BigDecimal.valueOf(37));
    private final Money thirtyFive = Money.of(MONETARY_CURRENCY, BigDecimal.valueOf(35));
    private final Money twentyEight = Money.of(MONETARY_CURRENCY, BigDecimal.valueOf(28));
    private final Money twentyTwo = Money.of(MONETARY_CURRENCY, BigDecimal.valueOf(22));
    private final Money eighteen = Money.of(MONETARY_CURRENCY, BigDecimal.valueOf(18));
    private final Money thirteen = Money.of(MONETARY_CURRENCY, BigDecimal.valueOf(13));
    private final Money five = Money.of(MONETARY_CURRENCY, BigDecimal.valueOf(5));

    private OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessor underTest;

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
        ThreadLocalContextUtil.setBusinessDates(new java.util.HashMap<>(java.util.Map.of(BusinessDateType.BUSINESS_DATE, transactionDate)));
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    public void normalRepayment_InterestPlusPrincipalAcrossAllInstallments_thenPartialPenaltyOnly() {
        final LoanRepaymentScheduleInstallment installment1 = Mockito.spy(new LoanRepaymentScheduleInstallment(loan, 1, fromDate1, dueDate1,
                BigDecimal.valueOf(2), BigDecimal.valueOf(3), BigDecimal.valueOf(5), BigDecimal.valueOf(4), false, null, BigDecimal.ZERO));

        final LoanRepaymentScheduleInstallment installment2 = Mockito.spy(new LoanRepaymentScheduleInstallment(loan, 2, fromDate2, dueDate2,
                BigDecimal.valueOf(6), BigDecimal.valueOf(7), BigDecimal.valueOf(9), BigDecimal.valueOf(8), false, null, BigDecimal.ZERO));

        final List<LoanRepaymentScheduleInstallment> installments = List.of(installment1, installment2);

        final LoanTransaction loanTransaction = Mockito
                .spy(LoanTransaction.repayment(office, nineteen, null, transactionDate, ExternalId.empty()));

        final List<LoanTransactionToRepaymentScheduleMapping> transactionMappings = new ArrayList<>();

        underTest.handleTransactionThatIsALateRepaymentOfInstallment(installment1, installments, loanTransaction, nineteen,
                transactionMappings, charges);

        // I+P for inst1: interest(19) then principal(16); I+P for inst2: interest(14) then principal(7); penalty inst1
        // (1)
        final org.mockito.InOrder inOrder = Mockito.inOrder(installment1, installment2);
        inOrder.verify(installment1).payInterestComponent(eq(transactionDate), org.mockito.ArgumentMatchers.refEq(nineteen));
        inOrder.verify(installment1).payPrincipalComponent(eq(transactionDate), org.mockito.ArgumentMatchers.refEq(sixteen));
        inOrder.verify(installment2).payInterestComponent(eq(transactionDate), org.mockito.ArgumentMatchers.refEq(fourteen));
        inOrder.verify(installment2).payPrincipalComponent(eq(transactionDate), org.mockito.ArgumentMatchers.refEq(seven));
        inOrder.verify(installment1).payPenaltyChargesComponent(eq(transactionDate), org.mockito.ArgumentMatchers.refEq(one));

        Mockito.verify(installment1, Mockito.never()).payFeeChargesComponent(eq(transactionDate), Mockito.any());

        Mockito.verify(installment2, Mockito.never()).payPenaltyChargesComponent(eq(transactionDate), Mockito.any());
        Mockito.verify(installment2, Mockito.never()).payFeeChargesComponent(eq(transactionDate), Mockito.any());
    }

    @Test
    public void normalRepayment_InterestPlusPrincipalAcrossAllInstallments_thenPenaltiesFeesAcrossAllInstallments() {
        final LoanRepaymentScheduleInstallment installment1 = Mockito.spy(new LoanRepaymentScheduleInstallment(loan, 1, fromDate1, dueDate1,
                BigDecimal.valueOf(2), BigDecimal.valueOf(3), BigDecimal.valueOf(5), BigDecimal.valueOf(4), false, null, BigDecimal.ZERO));

        final LoanRepaymentScheduleInstallment installment2 = Mockito.spy(new LoanRepaymentScheduleInstallment(loan, 2, fromDate2, dueDate2,
                BigDecimal.valueOf(6), BigDecimal.valueOf(7), BigDecimal.valueOf(9), BigDecimal.valueOf(8), false, null, BigDecimal.ZERO));

        final List<LoanRepaymentScheduleInstallment> installments = List.of(installment1, installment2);

        final LoanTransaction loanTransaction = Mockito
                .spy(LoanTransaction.repayment(office, forty, null, transactionDate, ExternalId.empty()));
        final List<LoanTransactionToRepaymentScheduleMapping> transactionMappings = new ArrayList<>();

        final org.mockito.InOrder inOrder = Mockito.inOrder(installment1, installment2);
        underTest.handleTransactionThatIsALateRepaymentOfInstallment(installment1, installments, loanTransaction, forty,
                transactionMappings, charges);

        inOrder.verify(installment1).payInterestComponent(eq(transactionDate), org.mockito.ArgumentMatchers.refEq(forty));
        inOrder.verify(installment1).payPrincipalComponent(eq(transactionDate), org.mockito.ArgumentMatchers.refEq(thirtySeven));
        inOrder.verify(installment2).payInterestComponent(eq(transactionDate), org.mockito.ArgumentMatchers.refEq(thirtyFive));
        inOrder.verify(installment2).payPrincipalComponent(eq(transactionDate), org.mockito.ArgumentMatchers.refEq(twentyEight));

        inOrder.verify(installment1).payPenaltyChargesComponent(eq(transactionDate), org.mockito.ArgumentMatchers.refEq(twentyTwo));
        inOrder.verify(installment1).payFeeChargesComponent(eq(transactionDate), org.mockito.ArgumentMatchers.refEq(eighteen));
        inOrder.verify(installment2).payPenaltyChargesComponent(eq(transactionDate), org.mockito.ArgumentMatchers.refEq(thirteen));
        inOrder.verify(installment2).payFeeChargesComponent(eq(transactionDate), org.mockito.ArgumentMatchers.refEq(five));
    }
}
