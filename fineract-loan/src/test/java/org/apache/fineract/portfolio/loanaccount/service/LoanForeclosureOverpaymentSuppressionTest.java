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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.core.persistence.FlushModeHandler;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanSummary;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.helper.ForeclosureChargeHelper;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

/**
 * Pins the semantics of the foreclosure overpayment suppression in
 * {@link LoanBalanceService#calculateTotalOverpayment}.
 *
 * <p>
 * The suppression zeroes a computed transactions-vs-schedule difference ONLY when all of the following hold: the loan
 * was foreclosed, the schedule is fully settled, and no transaction carries an overpayment portion. That combination
 * means the two ledgers disagree with no allocation trail - a bookkeeping defect (e.g. a double-attached foreclosure
 * payment), never genuine customer money: whenever the transaction processor genuinely cannot place money on the
 * schedule it stamps an {@code overPaymentPortion} on the transaction, and that stamp is the escape hatch that keeps
 * the loan able to transition to OVERPAID and refund through the credit balance refund flow.
 */
public class LoanForeclosureOverpaymentSuppressionTest {

    private static final MockedStatic<MoneyHelper> MONEY_HELPER = mockStatic(MoneyHelper.class);
    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);

    private final MonetaryCurrency currency = MonetaryCurrency.fromCurrencyData(new CurrencyData("INR", 2, 1));

    private final LoanBalanceService underTest = new LoanBalanceService(mock(CapitalizedIncomeBalanceService.class),
            mock(FlushModeHandler.class), mock(LoanTransactionRepository.class), mock(ForeclosureChargeHelper.class));

    @BeforeAll
    public static void init() {
        MONEY_HELPER.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
        MONEY_HELPER.when(MoneyHelper::getMathContext).thenReturn(MC);
    }

    @AfterAll
    public static void destruct() {
        MONEY_HELPER.close();
    }

    @Test
    @DisplayName("ledger mismatch on a settled foreclosure with no stamped portion -> suppressed to zero")
    void mismatchOnSettledForeclosure_isSuppressed() {
        // transactions say 1800 came in, the schedule only absorbed 1000, and no transaction knows about the
        // difference - the double-attach shape. Without suppression this fabricates Rs. 800 of refundable money.
        final Loan loan = loan("1800", "1000", true, "0", List.of());

        assertThat(underTest.calculateTotalOverpayment(loan).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("genuine overpayment (stamped portion) on a settled foreclosure -> kept, loan can go OVERPAID")
    void stampedOverpaymentOnSettledForeclosure_isKept() {
        // the processor could not place Rs. 800 and stamped it on the transaction: real customer money, refundable.
        final Loan loan = loan("1800", "1000", true, "0", List.of(transactionWithOverpaymentPortion("800")));

        assertThat(underTest.calculateTotalOverpayment(loan).getAmount()).isEqualByComparingTo(new BigDecimal("800"));
    }

    @Test
    @DisplayName("non-foreclosed loan -> never suppressed")
    void nonForeclosedLoan_isNeverSuppressed() {
        final Loan loan = loan("1800", "1000", false, "0", List.of());

        assertThat(underTest.calculateTotalOverpayment(loan).getAmount()).isEqualByComparingTo(new BigDecimal("800"));
    }

    @Test
    @DisplayName("foreclosed loan with outstanding remaining -> never suppressed")
    void foreclosedLoanWithOutstanding_isNeverSuppressed() {
        final Loan loan = loan("1800", "1000", true, "200", List.of());

        assertThat(underTest.calculateTotalOverpayment(loan).getAmount()).isEqualByComparingTo(new BigDecimal("800"));
    }

    @Test
    @DisplayName("ledgers agree -> zero overpayment without engaging the suppression at all")
    void matchingLedgers_returnZero() {
        final Loan loan = loan("1000", "1000", true, "0", List.of());

        assertThat(underTest.calculateTotalOverpayment(loan).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a reversed transaction's stamped portion does not count as the escape hatch")
    void reversedTransactionPortion_doesNotBlockSuppression() {
        final LoanTransaction reversed = transactionWithOverpaymentPortion("800");
        lenient().when(reversed.isReversed()).thenReturn(true);
        lenient().when(reversed.isNotReversed()).thenReturn(false);

        final Loan loan = loan("1800", "1000", true, "0", List.of(reversed));

        assertThat(underTest.calculateTotalOverpayment(loan).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("full refresh recomputes the summary BEFORE the suppression consults it (no stale read)")
    void refresh_updatesSummaryBeforeSuppressionReadsIt() {
        // Suppression territory, driven through the full refresh entry point rather than calculateTotalOverpayment
        // directly: the guard's is-the-schedule-settled question must be answered by the summary recomputed in THIS
        // refresh, not by the previous refresh's snapshot.
        final Loan loan = loan("1800", "1000", true, "0", List.of());
        final Money principal = Money.of(currency, new BigDecimal("1000"));
        final LoanProductRelatedDetail relatedDetail = mock(LoanProductRelatedDetail.class);
        lenient().when(relatedDetail.getPrincipal()).thenReturn(principal);
        lenient().when(loan.getLoanRepaymentScheduleDetail()).thenReturn(relatedDetail);
        lenient().when(loan.getLoanCharges()).thenReturn(Set.of());

        underTest.refreshSummaryAndBalancesForDisbursedLoan(loan);

        final InOrder inOrder = inOrder(loan.getSummary());
        inOrder.verify(loan.getSummary()).updateSummary(any(), any(), any(), any(), any(), any());
        inOrder.verify(loan.getSummary()).getTotalOutstanding(currency);
    }

    // ================================================================================================================
    // fixtures
    // ================================================================================================================

    /**
     * A loan whose transaction ledger netted {@code paidInTransactions}, whose schedule absorbed
     * {@code absorbedBySchedule}, with the given foreclosure sub-status, summary outstanding and transaction list.
     */
    private Loan loan(final String paidInTransactions, final String absorbedBySchedule, final boolean foreclosed,
            final String totalOutstanding, final List<LoanTransaction> transactions) {
        final LoanRepaymentScheduleInstallment installment = new LoanRepaymentScheduleInstallment(null, 1, LocalDate.parse("2026-01-05"),
                LocalDate.parse("2026-02-05"), new BigDecimal(absorbedBySchedule), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false,
                null);
        installment.setPrincipalCompleted(new BigDecimal(absorbedBySchedule));

        // Built ahead of the stubbing calls: Money.of consults the static-mocked MoneyHelper, and a mock invocation
        // in the middle of when(...).thenReturn(...) trips Mockito's unfinished-stubbing detection.
        final Money outstandingMoney = Money.of(currency, new BigDecimal(totalOutstanding));
        final Money paidInTransactionsMoney = Money.of(currency, new BigDecimal(paidInTransactions));

        final LoanSummary summary = mock(LoanSummary.class);
        lenient().when(summary.getTotalOutstanding(currency)).thenReturn(outstandingMoney);

        final Loan loan = mock(Loan.class);
        lenient().when(loan.getCurrency()).thenReturn(currency);
        lenient().when(loan.getTotalPaidInRepayments()).thenReturn(paidInTransactionsMoney);
        lenient().when(loan.getRepaymentScheduleInstallments()).thenReturn(new ArrayList<>(List.of(installment)));
        lenient().when(loan.getLoanTransactions()).thenReturn(new ArrayList<>(transactions));
        lenient().when(loan.isForeclosure()).thenReturn(foreclosed);
        lenient().when(loan.getSummary()).thenReturn(summary);
        return loan;
    }

    private LoanTransaction transactionWithOverpaymentPortion(final String portion) {
        final Money portionMoney = Money.of(currency, new BigDecimal(portion));
        final LoanTransaction transaction = mock(LoanTransaction.class);
        lenient().when(transaction.isReversed()).thenReturn(false);
        lenient().when(transaction.isNotReversed()).thenReturn(true);
        lenient().when(transaction.isRefund()).thenReturn(false);
        lenient().when(transaction.isRefundForActiveLoan()).thenReturn(false);
        lenient().when(transaction.isCreditBalanceRefund()).thenReturn(false);
        lenient().when(transaction.isChargeback()).thenReturn(false);
        lenient().when(transaction.getOverPaymentPortion(currency)).thenReturn(portionMoney);
        return transaction;
    }
}
