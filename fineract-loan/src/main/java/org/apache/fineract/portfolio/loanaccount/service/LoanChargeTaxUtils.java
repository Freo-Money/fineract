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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeTaxDetails;
import org.apache.fineract.portfolio.tax.domain.TaxComponent;
import org.apache.fineract.portfolio.tax.domain.TaxGroup;
import org.apache.fineract.portfolio.tax.domain.TaxGroupMappings;

public final class LoanChargeTaxUtils {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private LoanChargeTaxUtils() {}

    public static void calculateAndSetTaxDetails(final LoanCharge loanCharge, final Charge chargeDefinition, final LocalDate chargeDate,
            final RoundingMode taxRoundingMode) {
        TaxGroup taxGroup = chargeDefinition.getTaxGroup();
        BigDecimal totalAmountInclusive = loanCharge.getAmount();

        final int effectiveCurrencyDigits = LoanChargeRoundingUtils.resolveDigitsAfterDecimal(loanCharge, chargeDefinition,
                totalAmountInclusive);
        final RoundingMode effectiveRoundingMode = LoanChargeRoundingUtils.resolveRoundingMode(chargeDefinition, taxRoundingMode);

        if (taxGroup == null || totalAmountInclusive == null || totalAmountInclusive.compareTo(BigDecimal.ZERO) <= 0) {
            resetTaxFields(loanCharge);
            roundChargeAmountToCurrency(loanCharge, effectiveCurrencyDigits, effectiveRoundingMode);
            return;
        }

        LocalDate taxCalculationDate = getTaxCalculationDate(chargeDate, loanCharge);
        loanCharge.setTaxGroup(taxGroup);
        calculateAndApplyTax(loanCharge, taxGroup, totalAmountInclusive, taxCalculationDate, effectiveCurrencyDigits,
                effectiveRoundingMode);
    }

    public static void calculateAndSetTaxDetails(final LoanCharge loanCharge, final Charge chargeDefinition, final LocalDate chargeDate,
            final int currencyDigits, final RoundingMode taxRoundingMode) {
        TaxGroup taxGroup = chargeDefinition.getTaxGroup();
        BigDecimal totalAmountInclusive = loanCharge.getAmount();

        final int effectiveCurrencyDigits = LoanChargeRoundingUtils.resolveDigitsAfterDecimal(chargeDefinition, currencyDigits,
                totalAmountInclusive);
        final RoundingMode effectiveRoundingMode = LoanChargeRoundingUtils.resolveRoundingMode(chargeDefinition, taxRoundingMode);

        if (taxGroup == null || totalAmountInclusive == null || totalAmountInclusive.compareTo(BigDecimal.ZERO) <= 0) {
            resetTaxFields(loanCharge);
            roundChargeAmountToCurrency(loanCharge, effectiveCurrencyDigits, effectiveRoundingMode);
            return;
        }

        LocalDate taxCalculationDate = getTaxCalculationDate(chargeDate, loanCharge);
        loanCharge.setTaxGroup(taxGroup);
        calculateAndApplyTax(loanCharge, taxGroup, totalAmountInclusive, taxCalculationDate, effectiveCurrencyDigits,
                effectiveRoundingMode);
    }

    public static void roundChargeAmountToCurrency(final LoanCharge loanCharge, final int currencyDigits,
            final RoundingMode taxRoundingMode) {
        if (loanCharge.getAmount() != null) {
            loanCharge.setAmount(loanCharge.getAmount().setScale(currencyDigits, taxRoundingMode));
            loanCharge.setAmountOutstanding(loanCharge.calculateOutstanding());
        }
    }

    public static void calculateAndSetTaxDetails(final LoanCharge loanCharge, final LocalDate transactionDate,
            final BigDecimal taxInclusiveAmount, final RoundingMode taxRoundingMode) {
        if (loanCharge.getCharge() == null) {
            resetTaxFields(loanCharge);
            return;
        }
        final Charge chargeDefinition = loanCharge.getCharge();
        TaxGroup taxGroup = chargeDefinition.getTaxGroup();
        final int effectiveCurrencyDigits = LoanChargeRoundingUtils.resolveDigitsAfterDecimal(loanCharge, chargeDefinition,
                taxInclusiveAmount);
        final RoundingMode effectiveRoundingMode = LoanChargeRoundingUtils.resolveRoundingMode(chargeDefinition, taxRoundingMode);
        if (taxInclusiveAmount == null || taxInclusiveAmount.compareTo(BigDecimal.ZERO) <= 0) {
            resetTaxFields(loanCharge);
            roundChargeAmountToCurrency(loanCharge, effectiveCurrencyDigits, effectiveRoundingMode);
            return;
        }
        if (taxGroup == null) {
            resetTaxFields(loanCharge);
            roundChargeAmountToCurrency(loanCharge, effectiveCurrencyDigits, effectiveRoundingMode);
            return;
        }

        loanCharge.setTaxGroup(taxGroup);
        calculateAndApplyTax(loanCharge, taxGroup, taxInclusiveAmount, transactionDate, effectiveCurrencyDigits, effectiveRoundingMode);
    }

    private static void calculateAndApplyTax(final LoanCharge loanCharge, final TaxGroup taxGroup, final BigDecimal taxInclusiveAmount,
            final LocalDate transactionDate, final int currencyDigits, final RoundingMode taxRoundingMode) {
        final Set<TaxGroupMappings> taxGroupMappings = taxGroup.getTaxGroupMappings();
        final BigDecimal inclusiveRounded = taxInclusiveAmount.setScale(currencyDigits, taxRoundingMode);

        if (taxGroupMappings == null || taxGroupMappings.isEmpty()) {
            loanCharge.setAmount(inclusiveRounded);
            loanCharge.setAmountOutstanding(loanCharge.calculateOutstanding());
            loanCharge.setAmountSansTax(inclusiveRounded);
            loanCharge.setTaxAmount(null);
            loanCharge.getLoanChargeTaxDetails().clear();
            return;
        }

        // Required calculation sequence:
        // 1) sansTaxRaw = inclusive * 100 / (100 + sum(componentPercentages))
        // 2) taxComponent_i = sansTaxRaw * component% / 100
        // 3) round each component with currencyDigits + roundingMode
        // 4) totalTax = sum(roundedComponents)
        // 5) sansTax = roundedInclusive - totalTax

        // Single pass: resolve each mapping once, collect (component, percentage) pairs and their sum.
        // This halves the getApplicablePercentage/occursOnDay calls vs. separate total + split loops,
        // and applies a consistent filter to both the sum and the per-component split.
        final List<Map.Entry<TaxComponent, BigDecimal>> activeComponents = new ArrayList<>(taxGroupMappings.size());
        BigDecimal totalPercentage = BigDecimal.ZERO;
        for (TaxGroupMappings groupMappings : taxGroupMappings) {
            if (groupMappings == null || !groupMappings.occursOnDayFromAndUpToAndIncluding(transactionDate)) {
                continue;
            }
            final TaxComponent component = groupMappings.getTaxComponent();
            if (component == null) {
                continue;
            }
            final BigDecimal percentage = component.getApplicablePercentage(transactionDate);
            if (percentage == null || percentage.signum() <= 0) {
                continue;
            }
            activeComponents.add(Map.entry(component, percentage));
            totalPercentage = totalPercentage.add(percentage);
        }

        // Use a higher intermediate scale to avoid drift before rounding each component.
        final int intermediateScale = Math.max(taxInclusiveAmount.scale(), currencyDigits) + 6;

        final BigDecimal amountSansTaxRaw = totalPercentage.signum() <= 0 ? taxInclusiveAmount
                : taxInclusiveAmount.multiply(HUNDRED).divide(HUNDRED.add(totalPercentage), intermediateScale, taxRoundingMode);

        final Map<TaxComponent, BigDecimal> taxSplitRaw = new HashMap<>(activeComponents.size());
        final Map<TaxComponent, BigDecimal> taxSplitRounded = new HashMap<>(activeComponents.size());
        BigDecimal totalTaxAmount = BigDecimal.ZERO;
        for (Map.Entry<TaxComponent, BigDecimal> entry : activeComponents) {
            final BigDecimal raw = amountSansTaxRaw.multiply(entry.getValue()).divide(HUNDRED, intermediateScale, taxRoundingMode);
            final BigDecimal rounded = raw.setScale(currencyDigits, taxRoundingMode);
            if (rounded.signum() > 0) {
                taxSplitRaw.put(entry.getKey(), raw);
                taxSplitRounded.put(entry.getKey(), rounded);
                totalTaxAmount = totalTaxAmount.add(rounded);
            }
        }

        loanCharge.setAmount(inclusiveRounded);
        loanCharge.setAmountOutstanding(loanCharge.calculateOutstanding());

        if (totalTaxAmount.signum() > 0) {
            loanCharge.setTaxAmount(totalTaxAmount);
            loanCharge.setAmountSansTax(inclusiveRounded.subtract(totalTaxAmount));
            createTaxDetails(loanCharge, taxSplitRounded, taxSplitRaw);
        } else {
            loanCharge.setAmountSansTax(inclusiveRounded);
            loanCharge.setTaxAmount(null);
            loanCharge.getLoanChargeTaxDetails().clear();
        }
    }

    private static LocalDate getTaxCalculationDate(final LocalDate chargeDate, final LoanCharge loanCharge) {
        return chargeDate != null ? chargeDate
                : (loanCharge.getSubmittedOnDate() != null ? loanCharge.getSubmittedOnDate() : DateUtils.getBusinessLocalDate());
    }

    private static void resetTaxFields(final LoanCharge loanCharge) {
        loanCharge.setTaxGroup(null);
        loanCharge.setAmountSansTax(null);
        loanCharge.setTaxAmount(null);
        loanCharge.getLoanChargeTaxDetails().clear();
    }

    private static void createTaxDetails(final LoanCharge loanCharge, final Map<TaxComponent, BigDecimal> taxSplit,
            final Map<TaxComponent, BigDecimal> taxSplitRaw) {
        loanCharge.getLoanChargeTaxDetails().clear();
        taxSplit.entrySet().stream().filter(entry -> entry.getValue() != null && entry.getValue().compareTo(BigDecimal.ZERO) > 0)
                .forEach(entry -> loanCharge.getLoanChargeTaxDetails()
                        .add(new LoanChargeTaxDetails(loanCharge, entry.getKey(), entry.getValue(), taxSplitRaw.get(entry.getKey()))));

    }

}
