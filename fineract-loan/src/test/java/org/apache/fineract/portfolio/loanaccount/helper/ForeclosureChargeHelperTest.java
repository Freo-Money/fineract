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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import org.apache.fineract.portfolio.loanaccount.domain.LoanSummary;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeService;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeTaxUtils;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductRoundingModeService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Regression tests guarding the charge-52 foreclosure schedule-drift fix.
 *
 * <p>
 * The bug: {@link ForeclosureChargeHelper#syncForeclosureFeeOnRepaymentSchedule} historically targeted
 * {@code getLastNonDownPaymentInstallment()} and SET {@code feeChargesCharged} to a value that ignored any legitimate
 * pre-existing fees. For mid-loan foreclosures the SET landed on the original last installment, not the installment
 * whose period contained the foreclosure date, so the Single-wrapper ADD performed inside {@code addLoanCharge} on the
 * closing installment was never cleared.
 *
 * <p>
 * These tests pin the post-fix invariant: the closing installment is resolved by date, fees are derived from the
 * canonical set of active {@code LoanCharge} rows (SET-from-truth), and the closing installment's fee portion is marked
 * as paid.
 */
public class ForeclosureChargeHelperTest {

    private static final MockedStatic<MoneyHelper> MONEY_HELPER = mockStatic(MoneyHelper.class);
    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_EVEN);

    private final MonetaryCurrency currency = MonetaryCurrency.fromCurrencyData(new CurrencyData("INR", 2, 1));

    private final ChargeRepositoryWrapper chargeRepositoryWrapper = mock(ChargeRepositoryWrapper.class);
    private final LoanChargeService loanChargeService = mock(LoanChargeService.class);
    private final ConfigurationDomainService configurationDomainService = mock(ConfigurationDomainService.class);
    private final LoanProductRoundingModeService loanProductRoundingModeService = mock(LoanProductRoundingModeService.class);

    private final ForeclosureChargeHelper underTest = new ForeclosureChargeHelper(mock(ChargeReadPlatformService.class),
            chargeRepositoryWrapper, loanChargeService, configurationDomainService, loanProductRoundingModeService);

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
    public void syncForeclosureFee_midLoanForeclosure_landsFeeOnClosingInstallmentNotLast() {
        // Scenario 1 (TS_2603170946461944-shaped): 6-installment loan, foreclosure date 2026-05-20 falls strictly
        // inside installment 3's period (2026-05-14, 2026-06-14]. Original maturedon_date 2026-09-14 = installment 6.
        // Pre-fix behaviour: fee landed on installment 6 (getLastNonDownPaymentInstallment).
        // Post-fix behaviour: fee lands on installment 3 (the period containing foreClosureDate).
        LocalDate disbursementDate = LocalDate.of(2026, 3, 14);
        LocalDate foreClosureDate = LocalDate.of(2026, 5, 20);
        List<LoanRepaymentScheduleInstallment> installments = sixMonthlyInstallments(disbursementDate);
        BigDecimal foreclosureChargeAmount = new BigDecimal("422.43");
        LoanCharge foreclosureCharge = mockFlatFeeCharge(foreClosureDate, foreclosureChargeAmount);

        Loan loan = mockLoan(disbursementDate, installments, Set.of(foreclosureCharge));

        underTest.syncForeclosureFeeOnRepaymentSchedule(loan, Money.of(currency, foreclosureChargeAmount), foreClosureDate);

        LoanRepaymentScheduleInstallment installment3 = installments.get(2);
        LoanRepaymentScheduleInstallment lastInstallment = installments.get(5);

        assertThat(installment3.getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(foreclosureChargeAmount);
        assertThat(installment3.getFeeChargesPaid(currency).getAmount()).isEqualByComparingTo(foreclosureChargeAmount);
        assertThat(lastInstallment.getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(lastInstallment.getFeeChargesPaid(currency).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    public void syncForeclosureFee_fullTermClose_landsFeeOnLastInstallment() {
        // Scenario 2 (TS_1000614925-shaped): 16-installment loan closed at the final installment's period.
        // Closing installment == last installment, so fee lands there. SET-from-truth means the value equals the
        // canonical sum of LoanCharge.amount due in that period (here, the single foreclosure charge).
        // Schedule periods are exclusive on start: inst 16 covers (2026-04-15, 2026-05-15] so closure on 2026-05-10
        // sits strictly inside that period.
        LocalDate disbursementDate = LocalDate.of(2025, 1, 15);
        LocalDate foreClosureDate = LocalDate.of(2026, 5, 10);
        List<LoanRepaymentScheduleInstallment> installments = monthlyInstallments(disbursementDate, 16);
        BigDecimal foreclosureChargeAmount = new BigDecimal("294.05");
        LoanCharge foreclosureCharge = mockFlatFeeCharge(foreClosureDate, foreclosureChargeAmount);

        Loan loan = mockLoan(disbursementDate, installments, Set.of(foreclosureCharge));

        underTest.syncForeclosureFeeOnRepaymentSchedule(loan, Money.of(currency, foreclosureChargeAmount), foreClosureDate);

        LoanRepaymentScheduleInstallment last = installments.get(15);
        assertThat(last.getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(foreclosureChargeAmount);
        assertThat(last.getFeeChargesPaid(currency).getAmount()).isEqualByComparingTo(foreclosureChargeAmount);
        // No other installment should have inherited the foreclosure fee.
        for (int i = 0; i < 15; i++) {
            assertThat(installments.get(i).getFeeChargesCharged(currency).getAmount()).as("Installment %s should carry no fee", i + 1)
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    public void syncForeclosureFee_partialTermForeclosure_landsFeeOnClosingPeriodNotOriginalLast() {
        // Scenario 3 (TS_1000638562-shaped): 22-planned-installment loan foreclosed at installment 15 (closure
        // date 2026-05-20 falls strictly inside installment 15's period; original maturity = installment 22).
        LocalDate disbursementDate = LocalDate.of(2025, 3, 15);
        LocalDate foreClosureDate = LocalDate.of(2026, 5, 20);
        List<LoanRepaymentScheduleInstallment> installments = monthlyInstallments(disbursementDate, 22);
        BigDecimal foreclosureChargeAmount = new BigDecimal("570.12");
        LoanCharge foreclosureCharge = mockFlatFeeCharge(foreClosureDate, foreclosureChargeAmount);

        Loan loan = mockLoan(disbursementDate, installments, Set.of(foreclosureCharge));

        underTest.syncForeclosureFeeOnRepaymentSchedule(loan, Money.of(currency, foreclosureChargeAmount), foreClosureDate);

        LoanRepaymentScheduleInstallment closing = installments.get(14); // installment 15
        LoanRepaymentScheduleInstallment originalLast = installments.get(21); // installment 22
        assertThat(closing.getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(foreclosureChargeAmount);
        assertThat(closing.getFeeChargesPaid(currency).getAmount()).isEqualByComparingTo(foreclosureChargeAmount);
        assertThat(originalLast.getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(originalLast.getFeeChargesPaid(currency).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    public void syncForeclosureFee_preExistingFeeOnClosingInstallment_setFromTruthSumsCanonicalCharges() {
        // Closing installment already carries a legitimate prior fee (e.g. a previously-applied installment fee that
        // matured on the same period). After SET-from-truth the fee must equal the sum of all active charges due in
        // that period - the prior fee + the foreclosure fee - not just the foreclosure amount and not the doubled
        // value produced by Site-1 ADD + Site-2 single-amount SET pre-fix.
        // Inst 6 covers (2026-04-15, 2026-05-15] for a Nov-15 disbursement; closure on 2026-05-10 sits inside it.
        LocalDate disbursementDate = LocalDate.of(2025, 11, 15);
        LocalDate foreClosureDate = LocalDate.of(2026, 5, 10);
        List<LoanRepaymentScheduleInstallment> installments = monthlyInstallments(disbursementDate, 6);
        BigDecimal foreclosureChargeAmount = new BigDecimal("570.12");
        BigDecimal priorFeeAmount = new BigDecimal("3003.23");

        LoanCharge foreclosureCharge = mockFlatFeeCharge(foreClosureDate, foreclosureChargeAmount);
        LoanCharge priorFeeCharge = mockFlatFeeCharge(foreClosureDate, priorFeeAmount);

        Loan loan = mockLoan(disbursementDate, installments, new LinkedHashSet<>(List.of(priorFeeCharge, foreclosureCharge)));

        underTest.syncForeclosureFeeOnRepaymentSchedule(loan, Money.of(currency, foreclosureChargeAmount), foreClosureDate);

        LoanRepaymentScheduleInstallment closing = installments.get(5);
        assertThat(closing.getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(priorFeeAmount.add(foreclosureChargeAmount));
        assertThat(closing.getFeeChargesPaid(currency).getAmount()).isEqualByComparingTo(priorFeeAmount.add(foreclosureChargeAmount));
    }

    @Test
    public void syncForeclosureFee_zeroFee_isNoOp() {
        LocalDate disbursementDate = LocalDate.of(2026, 3, 14);
        LocalDate foreClosureDate = LocalDate.of(2026, 5, 20);
        List<LoanRepaymentScheduleInstallment> installments = sixMonthlyInstallments(disbursementDate);
        Loan loan = mockLoan(disbursementDate, installments, Set.of());

        underTest.syncForeclosureFeeOnRepaymentSchedule(loan, Money.zero(currency), foreClosureDate);

        for (LoanRepaymentScheduleInstallment installment : installments) {
            assertThat(installment.getFeeChargesCharged(currency).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(installment.getFeeChargesPaid(currency).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ============================================================================================================
    // Items 1, 2, 3 -- de-dup branch coverage on updateForeclosureCharges.
    // ============================================================================================================

    @Test
    public void updateForeclosureCharges_dedupBranch_recomputesTaxOnExistingCharge() {
        // Item 1: when a retried foreclosure reuses an existing LoanCharge, tax breakdown must be recomputed so a
        // changed percentage does not leave amount_sans_tax / tax_amount frozen at the original values.
        LocalDate disbursementDate = LocalDate.of(2026, 3, 14);
        LocalDate foreClosureDate = LocalDate.of(2026, 5, 20);
        List<LoanRepaymentScheduleInstallment> installments = sixMonthlyInstallments(disbursementDate);
        LoanCharge existing = mockFlatFeeCharge(foreClosureDate, new BigDecimal("422.43"));

        Loan loan = mockLoanWithSummary(disbursementDate, installments, new LinkedHashSet<>(List.of(existing)), new BigDecimal("7159.84"));
        stubChargeRepoFor(52L, ChargeCalculationType.FLAT);
        when(configurationDomainService.getTaxRoundingMode()).thenReturn(RoundingMode.HALF_EVEN);
        // The production path passes existing.getCharge() to the tax method, not the chargeDefinition fetched from
        // the repository. Capture the embedded Charge reference for the assertion.
        Charge embeddedCharge = existing.getCharge();

        try (MockedStatic<LoanChargeTaxUtils> taxUtils = mockStatic(LoanChargeTaxUtils.class)) {
            underTest.updateForeclosureCharges(loan, Map.of(52L, new BigDecimal("500.00")), foreClosureDate);
            taxUtils.verify(() -> LoanChargeTaxUtils.calculateAndSetTaxDetails(eq(existing), eq(embeddedCharge), eq(foreClosureDate),
                    eq(RoundingMode.HALF_EVEN)), times(1));
        }
        // No new charge should be created via loanChargeService.
        verify(loanChargeService, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(loanChargeService, never()).addLoanCharge(any(), any());
    }

    @Test
    public void updateForeclosureCharges_dedupBranch_deactivatesSecondaryDuplicates() {
        // Item 2: when more than one active foreclosure LoanCharge exists for the same charge_id (legacy duplicates
        // from before this fix), only the first is updated in place; the rest are deactivated so reprocess() does
        // not sum them into the closing installment's fee_charges_amount.
        LocalDate disbursementDate = LocalDate.of(2026, 3, 14);
        LocalDate foreClosureDate = LocalDate.of(2026, 5, 20);
        List<LoanRepaymentScheduleInstallment> installments = sixMonthlyInstallments(disbursementDate);
        LoanCharge primary = mockFlatFeeCharge(foreClosureDate, new BigDecimal("422.43"));
        LoanCharge dup1 = mockFlatFeeCharge(foreClosureDate, new BigDecimal("422.43"));
        LoanCharge dup2 = mockFlatFeeCharge(foreClosureDate, new BigDecimal("422.43"));

        Loan loan = mockLoanWithSummary(disbursementDate, installments, new LinkedHashSet<>(List.of(primary, dup1, dup2)),
                new BigDecimal("7159.84"));
        stubChargeRepoFor(52L, ChargeCalculationType.FLAT);
        when(configurationDomainService.getTaxRoundingMode()).thenReturn(RoundingMode.HALF_EVEN);

        try (MockedStatic<LoanChargeTaxUtils> taxUtils = mockStatic(LoanChargeTaxUtils.class)) {
            underTest.updateForeclosureCharges(loan, Map.of(52L, new BigDecimal("500.00")), foreClosureDate);
        }

        verify(dup1, times(1)).setActive(false);
        verify(dup2, times(1)).setActive(false);
        verify(primary, never()).setActive(false);
    }

    @Test
    public void updateForeclosureCharges_clampsClosingInstallmentFeePaidWhenItExceedsCharged() {
        // Item 3: if the closing installment carries pre-existing feeChargesPaid larger than the new
        // feeChargesCharged (e.g. an earlier foreclosure attempt marked a higher fee paid and this retry shrinks it),
        // outstanding would go negative and corrupt retrieveIncomeOutstandingTillDate's payment-total computation.
        // The clamp keeps outstanding non-negative.
        LocalDate disbursementDate = LocalDate.of(2026, 3, 14);
        LocalDate foreClosureDate = LocalDate.of(2026, 5, 20);
        List<LoanRepaymentScheduleInstallment> installments = sixMonthlyInstallments(disbursementDate);
        // Pre-set the closing installment (inst 3) with feeChargesPaid larger than what the new foreclosure fee will
        // produce. New fee = 100.00; pre-existing paid = 500.00. Without clamp, outstanding = 100 - 500 = -400.
        installments.get(2).setFeeChargesPaid(new BigDecimal("500.00"));
        LoanCharge existing = mockFlatFeeCharge(foreClosureDate, new BigDecimal("100.00"));

        Loan loan = mockLoanWithSummary(disbursementDate, installments, new LinkedHashSet<>(List.of(existing)), new BigDecimal("7159.84"));
        stubChargeRepoFor(52L, ChargeCalculationType.FLAT);
        when(configurationDomainService.getTaxRoundingMode()).thenReturn(RoundingMode.HALF_EVEN);

        try (MockedStatic<LoanChargeTaxUtils> taxUtils = mockStatic(LoanChargeTaxUtils.class)) {
            underTest.updateForeclosureCharges(loan, Map.of(52L, new BigDecimal("100.00")), foreClosureDate);
        }

        LoanRepaymentScheduleInstallment closing = installments.get(2);
        // After clamp, paid == charged so outstanding == 0 (non-negative).
        assertThat(closing.getFeeChargesPaid(currency).getAmount())
                .isEqualByComparingTo(closing.getFeeChargesCharged(currency).getAmount());
        assertThat(closing.getFeeChargesOutstanding(currency).getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private Charge stubChargeRepoFor(Long chargeId, ChargeCalculationType calcType) {
        Charge chargeDef = mock(Charge.class);
        when(chargeDef.getId()).thenReturn(chargeId);
        when(chargeDef.getChargeCalculation()).thenReturn(calcType.getValue());
        lenient().when(chargeDef.getDigitsAfterDecimal()).thenReturn(null);
        lenient().when(chargeDef.getRoundingMode()).thenReturn(null);
        lenient().when(chargeDef.getTaxGroup()).thenReturn(null);
        when(chargeRepositoryWrapper.findOneWithNotFoundDetection(chargeId)).thenReturn(chargeDef);
        return chargeDef;
    }

    private Loan mockLoanWithSummary(LocalDate disbursementDate, List<LoanRepaymentScheduleInstallment> installments,
            Set<LoanCharge> charges, BigDecimal totalPrincipalOutstanding) {
        Loan loan = mockLoan(disbursementDate, installments, charges);
        LoanSummary summary = mock(LoanSummary.class);
        when(summary.getTotalPrincipalOutstanding()).thenReturn(totalPrincipalOutstanding);
        lenient().when(loan.getSummary()).thenReturn(summary);
        return loan;
    }

    private Loan mockLoan(LocalDate disbursementDate, List<LoanRepaymentScheduleInstallment> installments, Set<LoanCharge> charges) {
        Loan loan = mock(Loan.class);
        lenient().when(loan.getCurrency()).thenReturn(currency);
        lenient().when(loan.getDisbursementDate()).thenReturn(disbursementDate);
        lenient().when(loan.getRepaymentScheduleInstallments()).thenReturn(installments);
        lenient().when(loan.getLoanCharges()).thenReturn(charges);
        return loan;
    }

    private List<LoanRepaymentScheduleInstallment> sixMonthlyInstallments(LocalDate disbursementDate) {
        return monthlyInstallments(disbursementDate, 6);
    }

    private List<LoanRepaymentScheduleInstallment> monthlyInstallments(LocalDate disbursementDate, int count) {
        List<LoanRepaymentScheduleInstallment> installments = new ArrayList<>();
        LocalDate periodStart = disbursementDate;
        for (int i = 1; i <= count; i++) {
            LocalDate periodEnd = disbursementDate.plusMonths(i);
            LoanRepaymentScheduleInstallment installment = new LoanRepaymentScheduleInstallment(null, i, periodStart, periodEnd,
                    new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, null);
            installments.add(installment);
            periodStart = periodEnd;
        }
        return installments;
    }

    private LoanCharge mockFlatFeeCharge(LocalDate dueDate, BigDecimal amount) {
        // Pre-compute Money values outside the stub chain. Money.of(...) internally calls the static-mocked
        // MoneyHelper.getMathContext(), which Mockito would otherwise interpret as nested stubbing inside
        // when(...).thenReturn(...) and throw UnfinishedStubbingException.
        Money chargeAmountMoney = Money.of(currency, amount);
        Money zero = Money.zero(currency);

        LoanCharge loanCharge = mock(LoanCharge.class);
        Charge chargeDef = mock(Charge.class);
        when(chargeDef.getId()).thenReturn(52L);
        lenient().when(loanCharge.getCharge()).thenReturn(chargeDef);
        lenient().when(loanCharge.isActive()).thenReturn(true);
        lenient().when(loanCharge.isFeeCharge()).thenReturn(true);
        lenient().when(loanCharge.isPenaltyCharge()).thenReturn(false);
        lenient().when(loanCharge.isDueAtDisbursement()).thenReturn(false);
        lenient().when(loanCharge.isInstalmentFee()).thenReturn(false);
        lenient().when(loanCharge.isOverdueInstallmentCharge()).thenReturn(false);
        lenient().when(loanCharge.isSpecifiedDueDate()).thenReturn(true);
        lenient().when(loanCharge.getChargeTimeType()).thenReturn(ChargeTimeType.FORECLOSURE);
        lenient().when(loanCharge.getChargeCalculation()).thenReturn(ChargeCalculationType.FLAT);
        lenient().when(loanCharge.amount()).thenReturn(amount);
        lenient().when(loanCharge.getAmount(any(MonetaryCurrency.class))).thenReturn(chargeAmountMoney);
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
