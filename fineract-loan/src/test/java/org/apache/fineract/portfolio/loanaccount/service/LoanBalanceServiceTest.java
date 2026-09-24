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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.core.persistence.FlushModeHandler;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanSummary;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.helper.ForeclosureChargeHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Guards {@link LoanBalanceService#calculateTotalOverpayment(Loan)}: the paid side must include CHARGE_PAYMENT
 * transactions, because the installment side already counts the fee/penalty components they settled. Without that,
 * every charge payment reads as a shortfall and a genuinely overpaid loan closes as CLOSED_OBLIGATIONS_MET.
 */
class LoanBalanceServiceTest {

    private static final MonetaryCurrency INR = new MonetaryCurrency("INR", 2, 0);

    private final LoanBalanceService service = new LoanBalanceService(mock(CapitalizedIncomeBalanceService.class),
            mock(FlushModeHandler.class), mock(LoanTransactionRepository.class), mock(ForeclosureChargeHelper.class));

    private MockedStatic<MoneyHelper> moneyHelperStatic;
    private Loan loan;
    private final List<LoanTransaction> transactions = new ArrayList<>();
    private final List<LoanRepaymentScheduleInstallment> installments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // Money.of resolves the rounding mode through the tenant context; there is none in a plain unit test.
        moneyHelperStatic = Mockito.mockStatic(MoneyHelper.class);
        moneyHelperStatic.when(MoneyHelper::getMathContext).thenReturn(new MathContext(19, RoundingMode.HALF_EVEN));
        moneyHelperStatic.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);

        loan = mock(Loan.class);
        when(loan.getCurrency()).thenReturn(INR);
        when(loan.getLoanTransactions()).thenReturn(transactions);
        when(loan.getRepaymentScheduleInstallments()).thenReturn(installments);
        when(loan.getCreditAllocationRules()).thenReturn(new ArrayList<>());
        when(loan.isForeclosure()).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        moneyHelperStatic.close();
    }

    // --- prod loan 554146 replica -------------------------------------------------------------------------------

    @Test
    void chargePaymentsCountOnThePaidSideSoGenuineExcessSurfaces() {
        // repayment-type transactions: 7 + 1144*3 + 1132 + 17.89 + 4.00
        repaymentsTotal("4592.89");
        transactions.add(chargePayment("1593.00"));
        transactions.add(chargePayment("14.08"));
        // installment completed: principal 4300 + interest 271 + fees 1593 + penalties 29.21
        installments.add(installment("4300.00", "271.00", "1593.00", "29.21"));

        assertThat(service.calculateTotalOverpayment(loan).getAmount()).isEqualByComparingTo("6.76");
        assertThat(service.isOverPaid(loan)).isTrue();
    }

    @Test
    void exactSettlementThroughChargePaymentsIsNotOverpaid() {
        repaymentsTotal("4000.00");
        transactions.add(chargePayment("500.00"));
        installments.add(installment("3800.00", "200.00", "500.00", "0"));

        assertThat(service.calculateTotalOverpayment(loan).getAmount()).isEqualByComparingTo("0");
        assertThat(service.isOverPaid(loan)).isFalse();
    }

    @Test
    void reversedChargePaymentIsIgnored() {
        repaymentsTotal("4000.00");
        transactions.add(reversed(chargePayment("500.00")));
        installments.add(installment("3800.00", "200.00", "0", "0"));

        assertThat(service.calculateTotalOverpayment(loan).getAmount()).isEqualByComparingTo("0");
        assertThat(service.isOverPaid(loan)).isFalse();
    }

    @Test
    void chargePaymentRemainderStampedAsOverpaymentSurfaces() {
        repaymentsTotal("4000.00");
        // 100 charge payment, only 60 could be allocated; processor stamped 40 as overpayment portion
        final LoanTransaction cp = chargePayment("100.00");
        final Money stampedOverpayment = money("40.00");
        when(cp.getOverPaymentPortion(INR)).thenReturn(stampedOverpayment);
        transactions.add(cp);
        installments.add(installment("3800.00", "200.00", "60.00", "0"));

        assertThat(service.calculateTotalOverpayment(loan).getAmount()).isEqualByComparingTo("40.00");
    }

    @Test
    void repaymentOnlyLoanIsUnchanged() {
        repaymentsTotal("4010.00");
        installments.add(installment("3800.00", "200.00", "0", "0"));

        assertThat(service.calculateTotalOverpayment(loan).getAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void settledForeclosureWithoutStampedOverpaymentStillReportsZero() {
        final LoanSummary summary = mock(LoanSummary.class);
        when(loan.isForeclosure()).thenReturn(true);
        when(loan.getSummary()).thenReturn(summary);
        final Money zero = money("0");
        when(summary.getTotalOutstanding(INR)).thenReturn(zero);
        repaymentsTotal("4003.00");
        transactions.add(chargePayment("500.00"));
        installments.add(installment("3800.00", "200.00", "500.00", "0"));

        assertThat(service.calculateTotalOverpayment(loan).getAmount()).isEqualByComparingTo("0");
    }

    @Test
    void refundForActiveLoanIsStillSubtractedAlongsideChargePayments() {
        repaymentsTotal("4100.00");
        transactions.add(refundForActiveLoan("100.00"));
        transactions.add(chargePayment("500.00"));
        installments.add(installment("3800.00", "200.00", "500.00", "0"));

        assertThat(service.calculateTotalOverpayment(loan).getAmount()).isEqualByComparingTo("0");
    }

    @Test
    void repaymentAtDisbursementIsNotCounted() {
        // disbursement charges never sit in an installment, so counting the type-5 transaction would fabricate excess
        repaymentsTotal("4000.00");
        transactions.add(repaymentAtDisbursement("463.92"));
        transactions.add(chargePayment("500.00"));
        installments.add(installment("3800.00", "200.00", "500.00", "0"));

        assertThat(service.calculateTotalOverpayment(loan).getAmount()).isEqualByComparingTo("0");
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    // Money.of goes through the (statically mocked) MoneyHelper, so every value is built BEFORE the when(...) call:
    // touching another mock inside an open stubbing is what Mockito reports as "unfinished stubbing".

    private void repaymentsTotal(final String amount) {
        final Money total = money(amount);
        when(loan.getTotalPaidInRepayments()).thenReturn(total);
    }

    private static Money money(final String amount) {
        return Money.of(INR, new BigDecimal(amount));
    }

    private static LoanTransaction transaction(final String amount) {
        final Money amountMoney = money(amount);
        final Money zero = money("0");
        final LoanTransaction transaction = mock(LoanTransaction.class);
        when(transaction.isReversed()).thenReturn(false);
        when(transaction.isNotReversed()).thenReturn(true);
        when(transaction.getAmount(INR)).thenReturn(amountMoney);
        when(transaction.getOverPaymentPortion(INR)).thenReturn(zero);
        when(transaction.getPrincipalPortion(INR)).thenReturn(zero);
        return transaction;
    }

    private static LoanTransaction chargePayment(final String amount) {
        final LoanTransaction transaction = transaction(amount);
        when(transaction.isChargePayment()).thenReturn(true);
        return transaction;
    }

    private static LoanTransaction refundForActiveLoan(final String amount) {
        final LoanTransaction transaction = transaction(amount);
        when(transaction.isRefundForActiveLoan()).thenReturn(true);
        return transaction;
    }

    private static LoanTransaction repaymentAtDisbursement(final String amount) {
        final LoanTransaction transaction = transaction(amount);
        when(transaction.isRepaymentAtDisbursement()).thenReturn(true);
        return transaction;
    }

    private static LoanTransaction reversed(final LoanTransaction transaction) {
        when(transaction.isReversed()).thenReturn(true);
        when(transaction.isNotReversed()).thenReturn(false);
        // mirrors LoanTransaction#isChargePayment, which is false once reversed
        when(transaction.isChargePayment()).thenReturn(false);
        return transaction;
    }

    private static LoanRepaymentScheduleInstallment installment(final String principal, final String interest, final String fees,
            final String penalties) {
        final Money principalMoney = money(principal);
        final Money interestMoney = money(interest);
        final Money feesMoney = money(fees);
        final Money penaltiesMoney = money(penalties);
        final Money zero = money("0");
        final LoanRepaymentScheduleInstallment installment = mock(LoanRepaymentScheduleInstallment.class);
        when(installment.getPrincipalCompleted(INR)).thenReturn(principalMoney);
        when(installment.getInterestPaid(INR)).thenReturn(interestMoney);
        when(installment.getFeeChargesPaid(INR)).thenReturn(feesMoney);
        when(installment.getPenaltyChargesPaid(INR)).thenReturn(penaltiesMoney);
        when(installment.getInterestWaived(INR)).thenReturn(zero);
        return installment;
    }
}
