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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.charge.data.ChargeData;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.charge.service.ChargeReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeService;
import org.springframework.stereotype.Component;

/**
 * Helper class for handling foreclosure charge-related operations.
 */
@Component
@RequiredArgsConstructor
public class ForeclosureChargeHelper {

    private static final Gson GSON = new Gson();

    private final ChargeReadPlatformService chargeReadPlatformService;
    private final ChargeRepositoryWrapper chargeRepositoryWrapper;
    private final LoanChargeService loanChargeService;

    public Map<Long, BigDecimal> extractChargePercentagesFromJsonElement(JsonElement element, String paramName) {
        if (element == null || element.isJsonNull()) {
            return new HashMap<>();
        }

        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Charge percentages must be a JSON object");
        }

        JsonObject jsonObject = element.getAsJsonObject();
        Map<Long, BigDecimal> chargePercentages = new HashMap<>();

        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            try {
                Long chargeId = Long.parseLong(entry.getKey());
                BigDecimal percentage = entry.getValue().getAsBigDecimal();
                chargePercentages.put(chargeId, percentage);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid charge ID format in " + paramName + ": " + entry.getKey(), e);
            } catch (IllegalStateException | UnsupportedOperationException e) {
                throw new IllegalArgumentException(
                        "Invalid percentage value for charge " + entry.getKey() + " in " + paramName + ": "
                                + entry.getValue(),
                        e);
            }
        }

        return chargePercentages;
    }

    public Map<Long, BigDecimal> extractChargePercentagesFromJsonString(String chargePercentagesJson) {
        if (chargePercentagesJson == null || chargePercentagesJson.trim().isEmpty()) {
            return new HashMap<>();
        }

        try {
            JsonElement element = GSON.fromJson(chargePercentagesJson, JsonElement.class);
            return extractChargePercentagesFromJsonElement(element, "foreclosureChargePercentageMap");
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new IllegalArgumentException("Invalid JSON format for charge percentages: " + chargePercentagesJson, e);
        }
    }

    /**
     * Merges foreclosure charges from the loan product with the provided charge percentages.
     * If a charge is in the loan product but not in the provided map, it will be added with
     * the percentage from the request (if provided).
     *
     * @param loan
     *            the loan
     * @param chargePercentages
     *            the charge percentages provided in the request (may be null or empty)
     * @return merged map of charge ID to percentage
     */
    public Map<Long, BigDecimal> mergeForeclosureChargesFromLoanProduct(Loan loan,
            Map<Long, BigDecimal> chargePercentages) {
        if (chargePercentages == null || chargePercentages.isEmpty()) {
            return new HashMap<>();
        }

        Long loanProductId = loan.getLoanProduct().getId();
        List<ChargeData> loanProductForeclosureCharges = chargeReadPlatformService.retrieveLoanProductCharges(
                loanProductId, ChargeTimeType.FORECLOSURE);
        Set<Long> productChargeIds = loanProductForeclosureCharges.stream()
                .map(ChargeData::getId)
                .collect(Collectors.toSet());

        Map<Long, BigDecimal> merged = new HashMap<>();
        for (Map.Entry<Long, BigDecimal> entry : chargePercentages.entrySet()) {
            if (productChargeIds.contains(entry.getKey()) && entry.getValue() != null) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }

        return merged;
    }

    public Money calculateForeclosureFee(Loan loan, Map<Long, BigDecimal> mergedChargePercentages,
            MonetaryCurrency currency) {
        if (mergedChargePercentages == null || mergedChargePercentages.isEmpty()) {
            return Money.zero(currency);
        }

        Money totalFees = Money.zero(currency);
        for (Map.Entry<Long, BigDecimal> entry : mergedChargePercentages.entrySet()) {
            BigDecimal percentage = entry.getValue();
            if (percentage == null || percentage.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            try {
                Charge chargeDefinition = chargeRepositoryWrapper.findOneWithNotFoundDetection(entry.getKey());
                ChargeCalculationType calculationType = ChargeCalculationType.fromInt(chargeDefinition.getChargeCalculation());

                BigDecimal chargeAmount = calculationType.isPercentageBased() ? calculatePercentageBasedCharge(loan, chargeDefinition, calculationType, percentage) : percentage;
                chargeAmount = chargeAmount.setScale(currency.getDigitsAfterDecimal(), RoundingMode.HALF_UP);
                totalFees = totalFees.plus(Money.of(currency, chargeAmount));
            } catch (Exception e) {
                // Skip invalid charges
            }
        }

        return totalFees;
    }

    private BigDecimal calculatePercentageBasedCharge(Loan loan, Charge chargeDefinition,
            ChargeCalculationType calculationType, BigDecimal percentage) {
        LoanCharge tempLoanCharge = new LoanCharge();
        tempLoanCharge.setLoan(loan);
        tempLoanCharge.setCharge(chargeDefinition);
        tempLoanCharge.setChargeCalculation(calculationType.getValue());
        tempLoanCharge.setPercentage(percentage);
        BigDecimal baseAmount = loanChargeService.calculateAmountPercentageAppliedTo(loan, tempLoanCharge);
        return LoanCharge.percentageOf(baseAmount, percentage);
    }

    public ChargeRepositoryWrapper getChargeRepositoryWrapper() {
        return chargeRepositoryWrapper;
    }

}

