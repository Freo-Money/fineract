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
package org.apache.fineract.portfolio.loanaccount.helper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.charge.service.ChargeReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleProcessingWrapper;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeService;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductRoundingModeService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Regression tests for the foreclosure-fee fix on {@code PERCENT_OF_PRINCIPAL_OUTSTANDING} charges.
 *
 * <p>
 * Root cause that was fixed: when the repayment schedule is re-derived (e.g. by the final reprocess inside
 * {@code handleForeClosureTransactions}), {@link LoanRepaymentScheduleProcessingWrapper} recomputed a
 * {@code PERCENT_OF_PRINCIPAL_OUTSTANDING} charge as {@code percentage x (sum of installment principals)} — which is
 * structurally the disbursed amount, not the principal outstanding. The wrapper now trusts the already-stored
 * {@code LoanCharge#getAmount} (computed against POS) for that calculation type, exactly as it does for {@code FLAT}.
 *
 * <p>
 * These tests exercise the wrapper directly (the load-bearing fix) plus
 * {@link ForeclosureChargeHelper#sumActiveForeclosureChargeAmounts} (which must ignore inactive charges).
 */
public class ForeclosureChargeHelperTest {

    private static final MockedStatic<MoneyHelper> MONEY_HELPER = mockStatic(MoneyHelper.class);
    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);

    private final MonetaryCurrency currency = MonetaryCurrency.fromCurrencyData(new CurrencyData("INR", 2, 1));

    private final ForeclosureChargeHelper underTest = new ForeclosureChargeHelper(mock(ChargeReadPlatformService.class),
            mock(ChargeRepositoryWrapper.class), mock(LoanChargeService.class), mock(ConfigurationDomainService.class),
            mock(LoanProductRoundingModeService.class));

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
    // The wrapper fix: PERCENT_OF_PRINCIPAL_OUTSTANDING uses the stored (POS-based) amount, not % x disbursed.
    // The final reprocess inside handleForeClosureTransactions runs this; we simulate it directly.
    // ================================================================================================================

    @Test
    public void reprocess_percentOfPrincipalOutstanding_usesStoredAmountAndLandsOnClosingPeriod() {
        // Reproduces the live bug shape: disbursement 2025-12-05, 6 monthly installments of 1000 each (6000 total
        // installment principal = the disbursed-amount trap). Closure 2026-02-20 falls inside installment 3's period
        // (2026-02-05, 2026-03-05]. The charge's stored amount is the POS-based 544.92 (NOT 5.9% of 6000 = 354), and
        // the bug state has 590 wrongly sitting on the last installment.
        LocalDate disbursementDate = LocalDate.of(2025, 12, 5);
        LocalDate closureDate = LocalDate.of(2026, 2, 20);
        List<LoanRepaymentScheduleInstallment> installments = monthlyInstallments(disbursementDate, 6);
        installments.get(5).setFeeChargesCharged(new BigDecimal("590.00")); // old % x disbursed, wrong row

        BigDecimal posBasedAmount = new BigDecimal("544.92");
        LoanCharge posCharge = mockCharge(closureDate, posBasedAmount, ChargeCalculationType.PERCENT_OF_PRINCIPAL_OUTSTANDING, false);

        simulateFinalReprocess(disbursementDate, installments, Set.of(posCharge));

        assertThat(installments.get(2).getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(posBasedAmount);
        assertThat(installments.get(5).getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    public void reprocess_percentOfPrincipalOutstandingPenalty_usesStoredAmount() {
        LocalDate disbursementDate = LocalDate.of(2025, 12, 5);
        LocalDate closureDate = LocalDate.of(2026, 2, 20);
        List<LoanRepaymentScheduleInstallment> installments = monthlyInstallments(disbursementDate, 6);

        BigDecimal posBasedAmount = new BigDecimal("321.40");
        LoanCharge posPenalty = mockCharge(closureDate, posBasedAmount, ChargeCalculationType.PERCENT_OF_PRINCIPAL_OUTSTANDING, true);

        simulateFinalReprocess(disbursementDate, installments, Set.of(posPenalty));

        assertThat(installments.get(2).getPenaltyChargesCharged(currency).getAmount()).isEqualByComparingTo(posBasedAmount);
    }

    @Test
    public void reprocess_percentOfAmountCharge_stillRecomputesAgainstDisbursed_noRegression() {
        // Guard: the fix is scoped to PERCENT_OF_PRINCIPAL_OUTSTANDING only. A PERCENT_OF_AMOUNT charge must still be
        // recomputed as percentage x (sum of installment principals). 6 x 1000 = 6000, 10% => 600.
        LocalDate disbursementDate = LocalDate.of(2025, 12, 5);
        LocalDate closureDate = LocalDate.of(2026, 2, 20);
        List<LoanRepaymentScheduleInstallment> installments = monthlyInstallments(disbursementDate, 6);

        // Stored amount deliberately wrong (1) to prove it is NOT used; percentage 10 drives the recompute.
        LoanCharge percentOfAmount = mockCharge(closureDate, BigDecimal.ONE, ChargeCalculationType.PERCENT_OF_AMOUNT, false);
        lenient().when(percentOfAmount.getPercentage()).thenReturn(new BigDecimal("10"));

        simulateFinalReprocess(disbursementDate, installments, Set.of(percentOfAmount));

        assertThat(installments.get(2).getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(new BigDecimal("600.00"));
    }

    // ================================================================================================================
    // The three ticket scenarios, encoded against the SET-from-truth final reprocess that the foreclosure flow always
    // runs (LoanDownPaymentHandlerServiceImpl line 163, since !loan.isForeclosure() forces the full-reprocess path).
    // Each starts from the "bug state" (inflated/wrong fee already on the schedule) and asserts the final reprocess
    // corrects placement and amount.
    // ================================================================================================================

    @Test
    public void sc1_midLoanClosingInst3Of6_finalReprocessClearsAddAndLandsCorrectAmountOnInst3() {
        LocalDate disbursementDate = LocalDate.of(2025, 12, 5);
        LocalDate closureDate = LocalDate.of(2026, 2, 20); // inst 3 period (2026-02-05, 2026-03-05]
        List<LoanRepaymentScheduleInstallment> installments = monthlyInstallments(disbursementDate, 6);
        // Bug state: Single-wrapper ADD left an inflated fee on inst 3, and the old SET landed on inst 6.
        installments.get(2).setFeeChargesCharged(new BigDecimal("888.88"));
        installments.get(5).setFeeChargesCharged(new BigDecimal("590.00"));
        BigDecimal posAmount = new BigDecimal("544.92");
        LoanCharge foreclosure = mockCharge(closureDate, posAmount, ChargeCalculationType.PERCENT_OF_PRINCIPAL_OUTSTANDING, false);

        simulateFinalReprocess(disbursementDate, installments, Set.of(foreclosure));

        assertThat(installments.get(2).getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(posAmount);
        assertThat(installments.get(5).getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    public void sc2_fullTermInst16_preExistingFeePlusForeclosure_setFromTruthSumsCanonicalCharges() {
        LocalDate disbursementDate = LocalDate.of(2025, 1, 5);
        LocalDate closureDate = LocalDate.of(2026, 4, 20); // inst 16 period (2026-04-05, 2026-05-05]
        List<LoanRepaymentScheduleInstallment> installments = monthlyInstallments(disbursementDate, 16);
        // Bug state: inst 16 inflated by an uncleared ADD.
        installments.get(15).setFeeChargesCharged(new BigDecimal("4000.00"));
        BigDecimal preExistingFee = new BigDecimal("3003.23");
        BigDecimal foreclosureAmount = new BigDecimal("996.77");
        LoanCharge legitFee = mockCharge(closureDate, preExistingFee, ChargeCalculationType.FLAT, false);
        LoanCharge foreclosure = mockCharge(closureDate, foreclosureAmount, ChargeCalculationType.PERCENT_OF_PRINCIPAL_OUTSTANDING, false);

        simulateFinalReprocess(disbursementDate, installments, new LinkedHashSet<>(List.of(legitFee, foreclosure)));

        // SET-from-truth: inst 16 = sum of active charges due in its period = 3003.23 + 996.77 (no double-count).
        assertThat(installments.get(15).getFeeChargesCharged(currency).getAmount())
                .isEqualByComparingTo(preExistingFee.add(foreclosureAmount));
    }

    @Test
    public void sc3_midLoanClosingInst15Of22_finalReprocessLandsCorrectAmountOnInst15() {
        LocalDate disbursementDate = LocalDate.of(2025, 3, 5);
        LocalDate closureDate = LocalDate.of(2026, 5, 20); // inst 15 period (2026-05-05, 2026-06-05]
        List<LoanRepaymentScheduleInstallment> installments = monthlyInstallments(disbursementDate, 22);
        installments.get(14).setFeeChargesCharged(new BigDecimal("777.77")); // bug: ADD on inst 15
        installments.get(21).setFeeChargesCharged(new BigDecimal("590.00")); // bug: SET on original last inst 22
        BigDecimal posAmount = new BigDecimal("612.34");
        LoanCharge foreclosure = mockCharge(closureDate, posAmount, ChargeCalculationType.PERCENT_OF_PRINCIPAL_OUTSTANDING, false);

        simulateFinalReprocess(disbursementDate, installments, Set.of(foreclosure));

        assertThat(installments.get(14).getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(posAmount);
        assertThat(installments.get(21).getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ================================================================================================================
    // sumActiveForeclosureChargeAmounts must only sum ACTIVE foreclosure charges.
    // ================================================================================================================

    @Test
    public void sumActiveForeclosureChargeAmounts_excludesInactiveAndNonForeclosureCharges() {
        LoanCharge activeForeclosure = mockCharge(LocalDate.of(2026, 2, 20), new BigDecimal("544.92"),
                ChargeCalculationType.PERCENT_OF_PRINCIPAL_OUTSTANDING, false);
        LoanCharge inactiveForeclosure = mockCharge(LocalDate.of(2026, 2, 20), new BigDecimal("999.00"),
                ChargeCalculationType.PERCENT_OF_PRINCIPAL_OUTSTANDING, false);
        lenient().when(inactiveForeclosure.isActive()).thenReturn(false);
        LoanCharge activeFlatFee = mockCharge(LocalDate.of(2026, 2, 20), new BigDecimal("100.00"), ChargeCalculationType.FLAT, false);
        lenient().when(activeFlatFee.getChargeTimeType()).thenReturn(ChargeTimeType.SPECIFIED_DUE_DATE);

        Loan loan = mock(Loan.class);
        lenient().when(loan.getCurrency()).thenReturn(currency);
        // getActiveCharges() filters out the inactive one (mirrors the real Loan implementation).
        Set<LoanCharge> active = new LinkedHashSet<>(List.of(activeForeclosure, activeFlatFee));
        lenient().when(loan.getActiveCharges()).thenReturn(active);

        Money total = underTest.sumActiveForeclosureChargeAmounts(loan);

        // Only the active FORECLOSURE charge counts: not the inactive foreclosure, not the active non-foreclosure fee.
        assertThat(total.getAmount()).isEqualByComparingTo(new BigDecimal("544.92"));
    }

    // ================================================================================================================
    // applyForeclosureChargeOnRepaymentSchedule placement: closure exactly on an intermediate installment due date
    // must land the fee on THAT installment (read installment-by-installment by retrieveIncomeOutstandingTillDate),
    // not on the last installment, otherwise the computed foreclosure payment is short and the loan does not close.
    // ================================================================================================================

    @Test
    public void placement_closureExactlyOnIntermediateDueDate_addsFeeToThatInstallmentNotLast() {
        LocalDate disbursementDate = LocalDate.of(2025, 12, 5);
        LocalDate closureDate = LocalDate.of(2026, 4, 5); // installment 4's exact due date (not the last)
        List<LoanRepaymentScheduleInstallment> installments = monthlyInstallments(disbursementDate, 6);
        installments.get(3).setFeeChargesCharged(new BigDecimal("100.00")); // a regular fee already due on that date

        Loan loan = mock(Loan.class);
        lenient().when(loan.getCurrency()).thenReturn(currency);
        lenient().when(loan.getRepaymentScheduleInstallments()).thenReturn(installments);

        underTest.applyForeclosureChargeOnRepaymentSchedule(loan, currency, Money.of(currency, new BigDecimal("544.92")), closureDate);

        // Closure installment keeps its already-due fee and gains the foreclosure fee on top (100 + 544.92).
        assertThat(installments.get(3).getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(new BigDecimal("644.92"));
        // The last installment must NOT receive it (that was the bug).
        assertThat(installments.get(5).getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    public void placement_midPeriodClosure_keepsFeeOnLastInstallment() {
        LocalDate disbursementDate = LocalDate.of(2025, 12, 5);
        LocalDate closureDate = LocalDate.of(2026, 5, 22); // between installment 5 (05-05) and 6 (06-05)
        List<LoanRepaymentScheduleInstallment> installments = monthlyInstallments(disbursementDate, 6);

        Loan loan = mock(Loan.class);
        lenient().when(loan.getCurrency()).thenReturn(currency);
        lenient().when(loan.getRepaymentScheduleInstallments()).thenReturn(installments);

        underTest.applyForeclosureChargeOnRepaymentSchedule(loan, currency, Money.of(currency, new BigDecimal("544.92")), closureDate);

        // No installment due date equals the closure date -> existing behaviour: fee parked on the last installment.
        // (The collected amount is computed charge-based for a mid-period closure, so this placement is harmless.)
        assertThat(installments.get(5).getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(new BigDecimal("544.92"));
        assertThat(installments.get(3).getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ================================================================================================================
    // Helpers
    // ================================================================================================================

    private void simulateFinalReprocess(LocalDate disbursementDate, List<LoanRepaymentScheduleInstallment> installments,
            Set<LoanCharge> activeCharges) {
        // Mirrors AbstractLoanRepaymentScheduleTransactionProcessor: resetDerivedComponents then wrapper.reprocess over
        // the active charge set.
        for (LoanRepaymentScheduleInstallment installment : installments) {
            installment.resetDerivedComponents();
        }
        new LoanRepaymentScheduleProcessingWrapper().reprocess(currency, disbursementDate, installments, activeCharges);
    }

    private List<LoanRepaymentScheduleInstallment> monthlyInstallments(LocalDate disbursementDate, int count) {
        List<LoanRepaymentScheduleInstallment> installments = new ArrayList<>();
        LocalDate periodStart = disbursementDate;
        for (int i = 1; i <= count; i++) {
            LocalDate periodEnd = disbursementDate.plusMonths(i);
            installments.add(new LoanRepaymentScheduleInstallment(null, i, periodStart, periodEnd, new BigDecimal("1000"), BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, false, null));
            periodStart = periodEnd;
        }
        return installments;
    }

    private LoanCharge mockCharge(LocalDate dueDate, BigDecimal amount, ChargeCalculationType calculationType, boolean penalty) {
        // Pre-compute Money values outside the stub chain (Money.of calls the static-mocked MoneyHelper, which Mockito
        // would otherwise treat as nested stubbing).
        Money amountMoney = Money.of(currency, amount);
        Money zero = Money.zero(currency);

        LoanCharge loanCharge = mock(LoanCharge.class);
        Charge chargeDef = mock(Charge.class);
        lenient().when(chargeDef.getId()).thenReturn(52L);
        lenient().when(loanCharge.getCharge()).thenReturn(chargeDef);
        lenient().when(loanCharge.isActive()).thenReturn(true);
        lenient().when(loanCharge.isFeeCharge()).thenReturn(!penalty);
        lenient().when(loanCharge.isPenaltyCharge()).thenReturn(penalty);
        lenient().when(loanCharge.isDueAtDisbursement()).thenReturn(false);
        lenient().when(loanCharge.isInstalmentFee()).thenReturn(false);
        lenient().when(loanCharge.isOverdueInstallmentCharge()).thenReturn(false);
        lenient().when(loanCharge.isSpecifiedDueDate()).thenReturn(true);
        lenient().when(loanCharge.getChargeTimeType()).thenReturn(ChargeTimeType.FORECLOSURE);
        lenient().when(loanCharge.getChargeCalculation()).thenReturn(calculationType);
        lenient().when(loanCharge.amount()).thenReturn(amount);
        lenient().when(loanCharge.getAmount(any(MonetaryCurrency.class))).thenReturn(amountMoney);
        lenient().when(loanCharge.getDueDate()).thenReturn(dueDate);
        lenient().when(loanCharge.getDueLocalDate()).thenReturn(dueDate);
        lenient().when(loanCharge.isDueInPeriod(any(LocalDate.class), any(LocalDate.class), anyBoolean())).thenAnswer(invocation -> {
            LocalDate from = invocation.getArgument(0);
            LocalDate to = invocation.getArgument(1);
            boolean isFirst = invocation.getArgument(2);
            if (isFirst) {
                return !dueDate.isBefore(from) && !dueDate.isAfter(to);
            }
            return dueDate.isAfter(from) && !dueDate.isAfter(to);
        });
        lenient().when(loanCharge.getAmountWaived(any(MonetaryCurrency.class))).thenReturn(zero);
        lenient().when(loanCharge.getAmountWrittenOff(any(MonetaryCurrency.class))).thenReturn(zero);
        return loanCharge;
    }
}
