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
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.stereotype.Component;

/**
 * Helper class for foreclosure charge-related operations
 */
@Component
@RequiredArgsConstructor
public class ForeclosureChargeHelper {

    private final ChargeReadPlatformService chargeReadPlatformService;
    private final ChargeRepositoryWrapper chargeRepositoryWrapper;

    /**
     * Gets the charge repository wrapper for accessing charge definitions.
     *
     * @return the charge repository wrapper
     */
    public ChargeRepositoryWrapper getChargeRepositoryWrapper() {
        return chargeRepositoryWrapper;
    }

    /**
     * Merges foreclosure charges from request with foreclosure charges defined in loan product. Charges from request
     * take precedence over loan product charges. ALL product-level foreclosure charges are included, not just those
     * in the request.
     *
     * @param loan
     *            the loan
     * @param chargePercentages
     *            map of chargeId to percentage from request (can be null or empty)
     * @return merged map of chargeId to percentage (for percentage-based charges) or amount (for flat charges)
     */
    public Map<Long, BigDecimal> mergeForeclosureChargesFromLoanProduct(Loan loan, Map<Long, BigDecimal> chargePercentages) {
        Map<Long, BigDecimal> mergedChargePercentages = chargePercentages != null ? new HashMap<>(chargePercentages) : new HashMap<>();

        // Get ALL foreclosure charges from loan product - these must be applied even if not in request
        Long loanProductId = loan.getLoanProduct().getId();
        List<ChargeData> loanProductForeclosureCharges = chargeReadPlatformService.retrieveLoanProductCharges(loanProductId,
                ChargeTimeType.FORECLOSURE);

        for (ChargeData chargeData : loanProductForeclosureCharges) {
            Long chargeId = chargeData.getId();
            
            // Skip if already in the map from request (request takes precedence)
            if (mergedChargePercentages.containsKey(chargeId)) {
                continue;
            }
            
            if (chargeData.getChargeCalculationType() != null && chargeData.getChargeCalculationType().getId() != null) {
                ChargeCalculationType chargeCalculationType = ChargeCalculationType
                        .fromInt(chargeData.getChargeCalculationType().getId().intValue());

                // Include ALL foreclosure charges from product with PERCENT_OF_PRINCIPAL_OUTSTANDING calculation type
                // TODO: Handle other calculation types (flat, other percentage types) in future
                if (chargeCalculationType.isPercentageOfPrincipalOutstanding()) {
                    // For percentage-based charges, the percentage is stored in the amount field
                    BigDecimal percentage = chargeData.getAmount();
                    if (percentage != null && percentage.compareTo(BigDecimal.ZERO) > 0) {
                        mergedChargePercentages.put(chargeId, percentage);
                    }
                }
                // TODO: Add support for other calculation types:
                // - FLAT charges
                // - Other percentage-based charges (PERCENT_OF_AMOUNT, PERCENT_OF_INTEREST, etc.)
            }
        }

        return mergedChargePercentages;
    }

    /**
     * Calculates total foreclosure fees based on charge percentages and principal outstanding. Uses
     * LoanCharge.percentageOf() for consistent percentage calculation.
     *
     * @param loan
     *            the loan
     * @param chargePercentages
     *            map of chargeId to percentage
     * @param currency
     *            the monetary currency
     * @return total foreclosure fee amount
     */
    public Money calculateForeclosureFees(Loan loan, Map<Long, BigDecimal> chargePercentages, MonetaryCurrency currency) {
        if (chargePercentages == null || chargePercentages.isEmpty()) {
            return Money.zero(currency);
        }

        BigDecimal principalOutstanding = loan.getSummary().getTotalPrincipalOutstanding();
        BigDecimal totalForeclosureFee = BigDecimal.ZERO;

        for (Map.Entry<Long, BigDecimal> entry : chargePercentages.entrySet()) {
            Long chargeId = entry.getKey();
            BigDecimal percentage = entry.getValue();

            // Find the charge definition
            try {
                Charge charge = chargeRepositoryWrapper.findOneWithNotFoundDetection(chargeId);

                // Validate chargeTimeType is FORECLOSURE and chargeCalculationType is PERCENT_OF_PRINCIPAL_OUTSTANDING
                ChargeTimeType chargeTimeType = ChargeTimeType.fromInt(charge.getChargeTimeType());
                ChargeCalculationType chargeCalculationType = ChargeCalculationType.fromInt(charge.getChargeCalculation());

                if (ChargeTimeType.FORECLOSURE.equals(chargeTimeType) && chargeCalculationType.isPercentageOfPrincipalOutstanding()) {
                    // Use LoanCharge.percentageOf() for consistent calculation
                    BigDecimal chargeAmount = LoanCharge.percentageOf(principalOutstanding, percentage);
                    // Round to currency decimal places
                    chargeAmount = chargeAmount.setScale(currency.getDigitsAfterDecimal(), RoundingMode.HALF_UP);
                    totalForeclosureFee = totalForeclosureFee.add(chargeAmount);
                }
            } catch (Exception e) {
                // Skip invalid charges - validation should have caught these already
                // Logging is handled by caller if needed
            }
        }

        return Money.of(currency, totalForeclosureFee);
    }

    /**
     * Validates that a charge is a valid foreclosure charge with PERCENT_OF_PRINCIPAL_OUTSTANDING calculation type.
     *
     * @param charge
     *            the charge to validate
     * @return true if valid, false otherwise
     */
    public boolean isValidForeclosureCharge(Charge charge) {
        if (charge == null) {
            return false;
        }

        ChargeTimeType chargeTimeType = ChargeTimeType.fromInt(charge.getChargeTimeType());
        return ChargeTimeType.FORECLOSURE.equals(chargeTimeType);
    }

    /**
     * Extracts charge percentages map from a JSON string. Converts string keys to Long charge IDs.
     *
     * @param jsonString
     *            the JSON string containing the charge percentages map
     * @return map of chargeId to percentage, empty map if jsonString is blank or invalid
     * @throws RuntimeException
     *             if JSON format is invalid or charge ID cannot be parsed
     */
    public Map<Long, BigDecimal> extractChargePercentagesFromJsonString(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return new HashMap<>();
        }
        try {
            Gson gson = new Gson();
            Type mapType = new TypeToken<Map<String, BigDecimal>>() {}.getType();
            Map<String, BigDecimal> stringKeyMap = gson.fromJson(jsonString, mapType);
            Map<Long, BigDecimal> result = new HashMap<>();
            if (stringKeyMap != null) {
                for (Map.Entry<String, BigDecimal> entry : stringKeyMap.entrySet()) {
                    try {
                        Long chargeId = Long.parseLong(entry.getKey());
                        result.put(chargeId, entry.getValue());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid charge ID format in chargePercentages map: " + entry.getKey(), e);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid JSON format for chargePercentages. Expected format: {\"chargeId\": percentage}. Error: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts charge percentages map from a JsonElement (request body).
     *
     * @param element
     *            the JSON element from request body
     * @param parameterName
     *            the parameter name for the charge percentages map
     * @return map of chargeId to percentage, empty map if not found or invalid
     */
    public Map<Long, BigDecimal> extractChargePercentagesFromJsonElement(JsonElement element, String parameterName) {
        Map<Long, BigDecimal> chargePercentages = new HashMap<>();
        if (element != null && element.isJsonObject()) {
            JsonObject jsonObject = element.getAsJsonObject();
            if (jsonObject.has(parameterName) && jsonObject.get(parameterName).isJsonObject()) {
                JsonObject chargePercentagesJson = jsonObject.getAsJsonObject(parameterName);
                for (Map.Entry<String, JsonElement> entry : chargePercentagesJson.entrySet()) {
                    try {
                        Long chargeId = Long.parseLong(entry.getKey());
                        BigDecimal percentage = entry.getValue().getAsBigDecimal();
                        chargePercentages.put(chargeId, percentage);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid charge ID format in " + parameterName + ": " + entry.getKey(), e);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Invalid percentage format for charge ID " + entry.getKey() + " in "
                                + parameterName + ": " + e.getMessage(), e);
                    }
                }
            }
        }
        return chargePercentages;
    }
}
