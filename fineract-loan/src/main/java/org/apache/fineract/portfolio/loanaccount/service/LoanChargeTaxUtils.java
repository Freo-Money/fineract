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
import java.util.Map;
import java.util.Set;
import org.apache.fineract.infrastructure.configuration.service.TemporaryConfigurationServiceContainer;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeTaxDetails;
import org.apache.fineract.portfolio.tax.domain.TaxComponent;
import org.apache.fineract.portfolio.tax.domain.TaxGroup;
import org.apache.fineract.portfolio.tax.domain.TaxGroupMappings;
import org.apache.fineract.portfolio.tax.service.TaxUtils;

public final class LoanChargeTaxUtils {

    private LoanChargeTaxUtils() {}

    public static void calculateAndSetTaxDetails(final LoanCharge loanCharge, final Charge chargeDefinition, final LocalDate chargeDate) {
        TaxGroup taxGroup = chargeDefinition.getTaxGroup();
        BigDecimal totalAmountInclusive = loanCharge.getAmount();

        if (taxGroup == null || totalAmountInclusive == null || totalAmountInclusive.compareTo(BigDecimal.ZERO) <= 0) {
            resetTaxFields(loanCharge);
            return;
        }

        LocalDate taxCalculationDate = getTaxCalculationDate(chargeDate, loanCharge);
        loanCharge.setTaxGroup(taxGroup);
        calculateAndApplyTax(loanCharge, taxGroup, totalAmountInclusive, taxCalculationDate);
    }

    public static void calculateAndSetTaxDetails(final LoanCharge loanCharge, final LocalDate transactionDate,
            final BigDecimal taxInclusiveAmount) {
        if (loanCharge.getCharge() == null) {
            resetTaxFields(loanCharge);
            return;
        }
        TaxGroup taxGroup = loanCharge.getCharge().getTaxGroup();
        if (taxInclusiveAmount == null || taxInclusiveAmount.compareTo(BigDecimal.ZERO) <= 0) {
            resetTaxFields(loanCharge);
            return;
        }
        if (taxGroup == null) {
            resetTaxFields(loanCharge);
            return;
        }

        loanCharge.setTaxGroup(taxGroup);
        calculateAndApplyTax(loanCharge, taxGroup, taxInclusiveAmount, transactionDate);
    }

    private static void calculateAndApplyTax(final LoanCharge loanCharge, final TaxGroup taxGroup, final BigDecimal taxInclusiveAmount,
            final LocalDate transactionDate) {
        int scale = loanCharge.getLoan().getCurrency().getDigitsAfterDecimal();
        Set<TaxGroupMappings> taxGroupMappings = taxGroup.getTaxGroupMappings();

        RoundingMode taxRoundingMode = TemporaryConfigurationServiceContainer.getTaxRoundingMode();

        Map<TaxComponent, BigDecimal> taxSplit = TaxUtils.splitTaxForLoanCharge(taxInclusiveAmount, transactionDate, taxGroupMappings,
                scale, taxRoundingMode);
        BigDecimal totalTaxAmount = TaxUtils.totalTaxAmount(taxSplit);

        if (totalTaxAmount.compareTo(BigDecimal.ZERO) > 0) {
            loanCharge.setTaxAmount(totalTaxAmount);
            loanCharge.setAmountSansTax(taxInclusiveAmount.subtract(totalTaxAmount));
            createTaxDetails(loanCharge, taxSplit);
        } else {
            loanCharge.setAmountSansTax(taxInclusiveAmount);
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

    private static void createTaxDetails(final LoanCharge loanCharge, final Map<TaxComponent, BigDecimal> taxSplit) {
        loanCharge.getLoanChargeTaxDetails().clear();
        taxSplit.entrySet().stream().filter(entry -> entry.getValue() != null && entry.getValue().compareTo(BigDecimal.ZERO) > 0).forEach(
                entry -> loanCharge.getLoanChargeTaxDetails().add(new LoanChargeTaxDetails(loanCharge, entry.getKey(), entry.getValue())));
    }

}
