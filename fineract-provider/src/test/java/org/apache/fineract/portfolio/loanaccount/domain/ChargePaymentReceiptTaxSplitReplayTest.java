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
package org.apache.fineract.portfolio.loanaccount.domain;

import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.INR_PAISE;
import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.chargePayment;
import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.gstGroup;
import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.installment;
import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.receipt;
import static org.apache.fineract.portfolio.loanaccount.domain.ChargePaymentReceiptFixtures.taxedPenaltyCharge;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.apache.fineract.infrastructure.configuration.service.TemporaryConfigurationServiceContainer;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.impl.OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.service.LoanBalanceService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.tax.domain.TaxGroup;
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

/**
 * A receipt's amount is the input of its GST split: {@link LoanChargePaidBy#setAmount} drops the stored tax rows and
 * re-derives them whenever the amount changes. These tests pin that the charge-set replay writes receipt amounts
 * through that path - so a topped-up receipt carries the split of what it really collected, identical to the split a
 * freshly created receipt of the same amount would get - and that a receipt it does not touch keeps the rows it has.
 */
@ExtendWith(MockitoExtension.class)
class ChargePaymentReceiptTaxSplitReplayTest {

    private static final MockedStatic<MoneyHelper> MONEY_HELPER = Mockito.mockStatic(MoneyHelper.class);
    private static final MockedStatic<TemporaryConfigurationServiceContainer> TEMP_CONFIG = Mockito
            .mockStatic(TemporaryConfigurationServiceContainer.class);

    private static final LocalDate GST_FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate DISBURSEMENT = LocalDate.of(2024, 7, 31);
    private static final LocalDate PAYMENT_DATE = LocalDate.of(2026, 6, 30);
    /** 9% + 9%: the tax-inclusive 118.00 of a charge is 100.00 of income and 9.00 of each component. */
    private static final BigDecimal NINE_PERCENT = new BigDecimal("9");

    @Mock
    private Loan loan;
    @Mock
    private Office office;
    @Mock
    private LoanProduct loanProduct;

    private OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessor processor;

    @BeforeAll
    static void init() {
        MONEY_HELPER.when(MoneyHelper::getMathContext).thenReturn(new MathContext(12, RoundingMode.HALF_EVEN));
        MONEY_HELPER.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
        // TaxUtils reads the rounding mode of the component split from this holder, not from the argument it is given
        TEMP_CONFIG.when(TemporaryConfigurationServiceContainer::getTaxRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
    }

    @AfterAll
    static void destruct() {
        MONEY_HELPER.close();
        TEMP_CONFIG.close();
    }

    @BeforeEach
    void setUp() {
        processor = new OverdueDueAdvInterestPrincipalOverdueDuePenaltiesFeesOrderLoanRepaymentScheduleTransactionProcessor(
                Mockito.mock(ExternalIdFactory.class), Mockito.mock(LoanChargeValidator.class), Mockito.mock(LoanBalanceService.class));
        ChargePaymentReceiptFixtures.setBusinessDate(LocalDate.of(2026, 10, 14));
        TEMP_CONFIG.when(TemporaryConfigurationServiceContainer::isChargePaymentChargeSetAllocationEnabled).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        ChargePaymentReceiptFixtures.resetContext();
        TEMP_CONFIG.when(TemporaryConfigurationServiceContainer::isChargePaymentChargeSetAllocationEnabled).thenReturn(false);
    }

    /**
     * Two taxed penalties of 118.00 paid by one 236.00 charge payment whose receipts the old code clipped to 59.00:
     * each stored receipt therefore carries the GST of half a charge. The replay restores both amounts and, with them,
     * the component rows.
     */
    @Test
    void clippedReceiptsOnTaxedCharges_areToppedUpWithTheirTaxSplitRebuilt() {
        final TaxGroup gst = gstGroup("GST 18", NINE_PERCENT, GST_FROM);
        final LoanRepaymentScheduleInstallment period = installment(loan, 23, LocalDate.of(2026, 5, 5), LocalDate.of(2026, 6, 5), 5866,
                2734);
        final List<LoanRepaymentScheduleInstallment> installments = List.of(period);
        final LoanCharge first = taxedPenaltyCharge(1L, LocalDate.of(2026, 5, 6), new BigDecimal("118.00"), loan, gst);
        final LoanCharge second = taxedPenaltyCharge(2L, LocalDate.of(2026, 5, 7), new BigDecimal("118.00"), loan, gst);
        final Set<LoanCharge> charges = new LinkedHashSet<>(List.of(first, second));

        final LoanTransaction payment = chargePayment(100L, INR_PAISE, loan, office, PAYMENT_DATE, new BigDecimal("236.00"));
        final LoanChargePaidBy clippedFirst = receipt(10L, payment, first, new BigDecimal("59.00"));
        final LoanChargePaidBy clippedSecond = receipt(11L, payment, second, new BigDecimal("59.00"));

        // as stored: half the charge, so half the GST
        assertEquals(List.of("4.50", "4.50"), componentAmounts(clippedFirst));
        assertEquals(List.of("4.50", "4.50"), componentAmounts(clippedSecond));

        replay(installments, List.of(payment), charges);

        assertEquals(new BigDecimal("118.00"), clippedFirst.getAmount());
        assertEquals(new BigDecimal("118.00"), clippedSecond.getAmount());
        assertEquals(List.of("9.00", "9.00"), componentAmounts(clippedFirst));
        assertEquals(List.of("9.00", "9.00"), componentAmounts(clippedSecond));
        assertEquals(new BigDecimal("236.00"), payment.getPenaltyChargesPortion(INR_PAISE).getAmount());
        assertTrue(payment.getOverPaymentPortion(INR_PAISE).isZero());
        assertTrue(first.isPaid());
        assertTrue(second.isPaid());
    }

    /**
     * The replay rewrites only the receipts it allocated something to and absorbed in full. A receipt that already
     * holds the right amount must keep its stored rows rather than have them dropped and re-derived, which on a rate
     * that has changed since the payment would silently re-split it at today's percentage.
     */
    @Test
    void consistentTaxedReceipts_keepTheStoredTaxRows() {
        final TaxGroup gst = gstGroup("GST 18", NINE_PERCENT, GST_FROM);
        final LoanRepaymentScheduleInstallment period = installment(loan, 23, LocalDate.of(2026, 5, 5), LocalDate.of(2026, 6, 5), 5866,
                2734);
        final List<LoanRepaymentScheduleInstallment> installments = List.of(period);
        final LoanCharge first = taxedPenaltyCharge(1L, LocalDate.of(2026, 5, 6), new BigDecimal("118.00"), loan, gst);
        final LoanCharge second = taxedPenaltyCharge(2L, LocalDate.of(2026, 5, 7), new BigDecimal("118.00"), loan, gst);
        final Set<LoanCharge> charges = new LinkedHashSet<>(List.of(first, second));

        final LoanTransaction payment = chargePayment(100L, INR_PAISE, loan, office, PAYMENT_DATE, new BigDecimal("236.00"));
        final LoanChargePaidBy intact = receipt(10L, payment, first, new BigDecimal("118.00"));
        final LoanChargePaidBy alsoIntact = receipt(11L, payment, second, new BigDecimal("118.00"));
        final LoanChargeTaxDetailsPaidBy rowOfIntact = intact.getLoanChargeTaxDetailsPaidBy().get(0);
        final LoanChargeTaxDetailsPaidBy rowOfAlsoIntact = alsoIntact.getLoanChargeTaxDetailsPaidBy().get(0);

        replay(installments, List.of(payment), charges);

        assertEquals(new BigDecimal("118.00"), intact.getAmount());
        assertEquals(new BigDecimal("118.00"), alsoIntact.getAmount());
        assertSame(rowOfIntact, intact.getLoanChargeTaxDetailsPaidBy().get(0));
        assertSame(rowOfAlsoIntact, alsoIntact.getLoanChargeTaxDetailsPaidBy().get(0));
        assertTrue(payment.getOverPaymentPortion(INR_PAISE).isZero());
    }

    /**
     * A charge amount that does not divide cleanly by the tax-inclusive rate: the split of a topped-up receipt must be
     * the very split the create path produces for that amount, down to the paisa, or the ledger of a repaired loan
     * would differ from the ledger of the same payment taken today.
     */
    @Test
    void taxSplitOfAToppedUpReceipt_matchesAFreshReceiptOfTheSameAmount() {
        final TaxGroup gst = gstGroup("GST 18", NINE_PERCENT, GST_FROM);
        final LoanRepaymentScheduleInstallment period = installment(loan, 23, LocalDate.of(2026, 5, 5), LocalDate.of(2026, 6, 5), 5866,
                2734);
        final List<LoanRepaymentScheduleInstallment> installments = List.of(period);
        final LoanCharge repaired = taxedPenaltyCharge(1L, LocalDate.of(2026, 5, 6), new BigDecimal("205.10"), loan, gst);
        final LoanCharge reference = taxedPenaltyCharge(2L, LocalDate.of(2026, 5, 7), new BigDecimal("205.10"), loan, gst);
        final Set<LoanCharge> charges = new LinkedHashSet<>(List.of(repaired, reference));

        final LoanTransaction payment = chargePayment(100L, INR_PAISE, loan, office, PAYMENT_DATE, new BigDecimal("410.20"));
        final LoanChargePaidBy clipped = receipt(10L, payment, repaired, new BigDecimal("100.00"));
        final LoanChargePaidBy fresh = receipt(11L, payment, reference, new BigDecimal("205.10"));

        // the uneven case: 100.00 inclusive splits 7.63 / 7.63 and leaves a paisa of income drift
        assertEquals(List.of("7.63", "7.63"), componentAmounts(clipped));

        replay(installments, List.of(payment), charges);

        assertEquals(new BigDecimal("205.10"), clipped.getAmount());
        assertEquals(List.of("15.64", "15.64"), componentAmounts(clipped));
        assertEquals(componentAmounts(fresh), componentAmounts(clipped));
        assertTrue(payment.getOverPaymentPortion(INR_PAISE).isZero());
    }

    /**
     * The rewrite must round a receipt's tax components with the SAME mode the charge payment was created with - the
     * global tax rounding mode. Resolving it per product instead made a repaired receipt's GST differ by a paisa from
     * its untouched sibling in the very same transaction (live: 91.53 against 91.52 on two identical 1180.00 charges).
     * The percentage and mode here are chosen so the two resolutions disagree: 9.18% of 1180.00 is 91.520784, which is
     * 91.53 rounded UP and 91.52 rounded HALF_EVEN.
     */
    @Test
    void receiptRewriteRoundsTaxWithTheGlobalMode_soARepairedReceiptMatchesItsUntouchedSibling() {
        TEMP_CONFIG.when(TemporaryConfigurationServiceContainer::getTaxRoundingMode).thenReturn(RoundingMode.UP);
        try {
            final TaxGroup gst = gstGroup("GST 18.36", new BigDecimal("9.18"), GST_FROM);
            final LoanRepaymentScheduleInstallment period = installment(loan, 23, LocalDate.of(2026, 5, 5), LocalDate.of(2026, 6, 5), 5866,
                    2734);
            final List<LoanRepaymentScheduleInstallment> installments = List.of(period);
            final LoanCharge toRepair = taxedPenaltyCharge(1L, LocalDate.of(2026, 5, 6), new BigDecimal("1180.00"), loan, gst);
            final LoanCharge sibling = taxedPenaltyCharge(2L, LocalDate.of(2026, 5, 7), new BigDecimal("1180.00"), loan, gst);
            final Set<LoanCharge> charges = new LinkedHashSet<>(List.of(toRepair, sibling));

            final LoanTransaction payment = chargePayment(100L, INR_PAISE, loan, office, PAYMENT_DATE, new BigDecimal("2360.00"));
            final LoanChargePaidBy clipped = receipt(10L, payment, toRepair, new BigDecimal("400.00"), RoundingMode.UP);
            final LoanChargePaidBy untouched = receipt(11L, payment, sibling, new BigDecimal("1180.00"), RoundingMode.UP);

            replay(installments, List.of(payment), charges);

            assertEquals(new BigDecimal("1180.00"), clipped.getAmount());
            assertEquals(List.of("91.53", "91.53"), componentAmounts(untouched));
            assertEquals(componentAmounts(untouched), componentAmounts(clipped));
            assertTrue(payment.getOverPaymentPortion(INR_PAISE).isZero());
        } finally {
            TEMP_CONFIG.when(TemporaryConfigurationServiceContainer::getTaxRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
        }
    }

    private void replay(final List<LoanRepaymentScheduleInstallment> installments, final List<LoanTransaction> transactions,
            final Set<LoanCharge> charges) {
        Mockito.when(loan.getLoanProduct()).thenReturn(loanProduct);
        Mockito.when(loanProduct.isAccountingDisabled()).thenReturn(true);
        Mockito.when(loan.getRepaymentScheduleInstallments()).thenReturn(installments);
        Mockito.when(loan.getLoanTransactions()).thenReturn(new ArrayList<>(transactions));
        processor.reprocessLoanTransactions(DISBURSEMENT, transactions, INR_PAISE, installments, charges);
    }

    /** The component rows of a receipt, by component name, so the assertion does not depend on map iteration order. */
    private static List<String> componentAmounts(final LoanChargePaidBy receipt) {
        final TreeMap<String, String> byComponent = new TreeMap<>();
        for (final LoanChargeTaxDetailsPaidBy detail : receipt.getLoanChargeTaxDetailsPaidBy()) {
            byComponent.put(detail.getTaxComponent().getName(), detail.getAmount().toPlainString());
        }
        return List.copyOf(byComponent.values());
    }
}
