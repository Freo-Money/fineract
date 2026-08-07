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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.core.persistence.FlushModeHandler;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanSummary;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.helper.ForeclosureChargeHelper;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Parity between the foreclosure template rounding ({@link LoanBalanceService#applyForeclosureRounding}) and the
 * rounding the actual foreclosure applies ({@link LoanBalanceService#applyForeclosureRoundingToLoan}).
 *
 * <p>
 * The two run at different points of the flow - the template on a transient payoff detail with the foreclosure fee
 * passed as {@code extraFees}, the actual on a payoff detail whose fee bucket already contains that fee - but they must
 * produce the same {@code adjustedInterestAmount} for the same payoff, across every combination of
 * {@code installmentAmountInMultiplesOf}, {@code adjustInterestForRounding} and {@code precloseEmiRounding}. Otherwise
 * the customer is quoted one amount and collected another.
 *
 * <p>
 * Also pins that the actual rounding derives its base from the payoff detail, NOT from the loan summary: at the point
 * it runs, {@code updateInstallmentsPostDate} has rebuilt the tail installment with paid amounts zeroed (same-day
 * repayments are only re-applied later), so the summary aggregates a gross figure that diverges from the quoted net
 * payoff exactly when a same-day payment exists.
 */
public class LoanForeclosureRoundingParityTest {

    private static final MockedStatic<MoneyHelper> MONEY_HELPER = mockStatic(MoneyHelper.class);
    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);

    private final MonetaryCurrency currency = MonetaryCurrency.fromCurrencyData(new CurrencyData("INR", 2, 1));

    private final LoanBalanceService underTest = spy(new LoanBalanceService(mock(CapitalizedIncomeBalanceService.class),
            mock(FlushModeHandler.class), mock(LoanTransactionRepository.class), mock(ForeclosureChargeHelper.class)));

    @BeforeAll
    public static void init() {
        MONEY_HELPER.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
        MONEY_HELPER.when(MoneyHelper::getMathContext).thenReturn(MC);
    }

    @AfterAll
    public static void destruct() {
        MONEY_HELPER.close();
    }

    // ================================================================================================================
    // Parity grid: multiplesOf x precloseEmiRounding x extraFees. Payoff base 800 + 100 + 12.37 = 912.37.
    // ================================================================================================================

    @Test
    @DisplayName("multiplesOf=50, round: template and actual both adjust by -12.37")
    void parity_multiples50_round() {
        assertParity(50, false, false, BigDecimal.ZERO, new BigDecimal("-12.37"));
    }

    @Test
    @DisplayName("multiplesOf=50, precloseEmiRounding (ceil): both adjust by +37.63")
    void parity_multiples50_ceil() {
        assertParity(50, false, true, BigDecimal.ZERO, new BigDecimal("37.63"));
    }

    @Test
    @DisplayName("multiplesOf=100, round: both adjust by -12.37")
    void parity_multiples100_round() {
        assertParity(100, false, false, BigDecimal.ZERO, new BigDecimal("-12.37"));
    }

    @Test
    @DisplayName("multiplesOf=100, ceil: both adjust by +87.63")
    void parity_multiples100_ceil() {
        assertParity(100, false, true, BigDecimal.ZERO, new BigDecimal("87.63"));
    }

    @Test
    @DisplayName("foreclosure fee outside (template extraFees) vs inside (actual fee bucket): same adjustment")
    void parity_multiples50_withForeclosureFee() {
        // 912.37 + 7.37 = 919.74 -> 900, adjustment -19.74 on both paths
        assertParity(50, false, false, new BigDecimal("7.37"), new BigDecimal("-19.74"));
    }

    @Test
    @DisplayName("no product multiplesOf but adjustInterestForRounding on: both fall back to the currency's (1)")
    void parity_currencyFallback_round() {
        assertParity(null, true, false, BigDecimal.ZERO, new BigDecimal("-0.37"));
        assertParity(-1, true, false, BigDecimal.ZERO, new BigDecimal("-0.37"));
    }

    @Test
    @DisplayName("currency fallback with ceil: both adjust by +0.63")
    void parity_currencyFallback_ceil() {
        assertParity(null, true, true, BigDecimal.ZERO, new BigDecimal("0.63"));
    }

    @Test
    @DisplayName("no multiplesOf and adjustInterestForRounding off: neither path rounds")
    void parity_ineligible_noRounding() {
        assertParity(null, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
        assertParity(-1, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("multiplesOf=0 is a no-op on both paths, not a divide-by-zero")
    void parity_multiplesZero_noOp() {
        assertParity(0, false, false, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    // ================================================================================================================
    // The actual rounding must use the payoff detail, never the (diverged) summary.
    // ================================================================================================================

    @Test
    @DisplayName("same-day payment divergence: actual rounds the payoff detail and never reads the summary total")
    void actualRounding_usesDetailBase_notSummary() {
        final LoanRepaymentScheduleInstallment schedule = installment(1, "800", "100", "12.37");
        // Schedule rebuilt gross (paid zeroed) says 1030; the quoted net payoff is 912.37 after a same-day payment.
        final Loan loan = loanWith(50, false, false, schedule);
        final LoanRepaymentScheduleInstallment detail = installment(0, "800", "100", "12.37");

        underTest.applyForeclosureRoundingToLoan(loan, detail);

        // Adjustment derived from 912.37 -> 900, matching the quote - not from any summary figure.
        assertThat(detail.getAdjustedInterestAmount()).isEqualByComparingTo(new BigDecimal("-12.37"));
        verify(loan.getSummary(), never()).getTotalOutstanding(any(MonetaryCurrency.class));
        // The summary must no longer be force-rounded onto a base that diverges from the installments.
        verify(loan.getSummary(), never()).updateTotalOutstanding(any(BigDecimal.class));
        // The earliest unpaid installment absorbs the adjustment so the final reprocess lands on the rounded payoff.
        assertThat(schedule.getInterestCharged(currency).getAmount()).isEqualByComparingTo(new BigDecimal("87.63"));
        verify(loan).updateAdjustedInterestAmount(new BigDecimal("-12.37"));
    }

    // ================================================================================================================
    // fixtures
    // ================================================================================================================

    /**
     * Runs both rounding paths on identical payoffs and asserts they land on the same adjustment. The template gets the
     * foreclosure fee as {@code extraFees}; the actual gets it folded into the detail's fee bucket, mirroring where the
     * fee lives at each point of the real flow.
     */
    private void assertParity(final Integer multiplesOf, final boolean adjustInterestForRounding, final boolean precloseEmiRounding,
            final BigDecimal foreclosureFee, final BigDecimal expectedAdjustment) {
        // Template path (quote): fee passed alongside the detail.
        final LoanRepaymentScheduleInstallment templateDetail = installment(0, "800", "100", "12.37");
        final Loan templateLoan = loanWith(multiplesOf, adjustInterestForRounding, precloseEmiRounding,
                installment(1, "800", "100", "12.37"));
        underTest.applyForeclosureRounding(templateLoan, templateDetail, Money.of(currency, foreclosureFee));

        // Actual path: by execution time the fee is a real charge inside the payoff detail's fee bucket.
        final LoanRepaymentScheduleInstallment actualDetail = installment(0, "800", "100",
                new BigDecimal("12.37").add(foreclosureFee).toPlainString());
        final Loan actualLoan = loanWith(multiplesOf, adjustInterestForRounding, precloseEmiRounding,
                installment(1, "800", "100", "12.37"));
        underTest.applyForeclosureRoundingToLoan(actualLoan, actualDetail);

        assertThat(templateDetail.getAdjustedInterestAmount()).as("template adjustment").isEqualByComparingTo(expectedAdjustment);
        assertThat(actualDetail.getAdjustedInterestAmount()).as("actual adjustment").isEqualByComparingTo(expectedAdjustment);
    }

    private LoanRepaymentScheduleInstallment installment(final int number, final String principal, final String interest,
            final String fee) {
        return new LoanRepaymentScheduleInstallment(null, number, LocalDate.parse("2026-01-05"), LocalDate.parse("2026-02-05"),
                new BigDecimal(principal), new BigDecimal(interest), new BigDecimal(fee), BigDecimal.ZERO, false, null);
    }

    private Loan loanWith(final Integer multiplesOf, final boolean adjustInterestForRounding, final boolean precloseEmiRounding,
            final LoanRepaymentScheduleInstallment scheduleInstallment) {
        final LoanProductRelatedDetail relatedDetail = mock(LoanProductRelatedDetail.class);
        lenient().when(relatedDetail.getInstallmentAmountInMultiplesOf()).thenReturn(multiplesOf);

        final LoanProduct product = mock(LoanProduct.class);
        lenient().when(product.isAdjustInterestForRounding()).thenReturn(adjustInterestForRounding);
        lenient().when(product.isPrecloseEmiRounding()).thenReturn(precloseEmiRounding);

        final LoanSummary summary = mock(LoanSummary.class);

        final Loan loan = mock(Loan.class);
        lenient().when(loan.getCurrency()).thenReturn(currency);
        lenient().when(loan.getLoanProductRelatedDetail()).thenReturn(relatedDetail);
        lenient().when(loan.getLoanProduct()).thenReturn(product);
        lenient().when(loan.getSummary()).thenReturn(summary);
        final List<LoanRepaymentScheduleInstallment> installments = new ArrayList<>();
        installments.add(scheduleInstallment);
        lenient().when(loan.getRepaymentScheduleInstallments()).thenReturn(installments);

        // The actual path refreshes the summary from the installments at the end; that traverses far more of the
        // loan aggregate than this parity test sets up, and its behaviour is not what is under test here.
        doNothing().when(underTest).refreshSummaryAndBalancesForDisbursedLoan(loan);
        return loan;
    }
}
