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
package org.apache.fineract.portfolio.loanproduct.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.common.domain.DaysInMonthType;
import org.apache.fineract.portfolio.common.domain.DaysInYearType;
import org.apache.fineract.portfolio.loanproduct.domain.BrokenPeriodInterestStrategy;

/**
 * Generic wrapper for loan product configurations. This class is designed to be extensible and can hold multiple
 * configuration types in a single JSON structure.
 *
 * Example JSON structure: { "brokenPeriodConfig": { ... }, "paymentConfig": { ... }, "feeConfig": { ... } }
 */
@Getter
@Setter
@NoArgsConstructor
public class LoanProductConfigurationWrapper implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final FromJsonHelper jsonHelper = new FromJsonHelper();

    // Current configuration types
    private BrokenPeriodInterestConfigDTO brokenPeriodConfig;

    // Future configuration types can be added here
    // private PaymentConfigDTO paymentConfig;
    // private FeeConfigDTO feeConfig;
    // private RiskConfigDTO riskConfig;

    public LoanProductConfigurationWrapper(BrokenPeriodInterestConfigDTO brokenPeriodConfig) {
        this.brokenPeriodConfig = brokenPeriodConfig;
    }

    /**
     * Serialize the wrapper to JSON with all configuration types
     */
    public String toJson() {
        JsonObject wrapper = new JsonObject();

        // Add brokenPeriodConfig if present
        if (brokenPeriodConfig != null) {
            String configJson = jsonHelper.toJson(brokenPeriodConfig);
            JsonElement configElement = jsonHelper.parse(configJson);
            wrapper.add("brokenPeriodConfig", configElement);
        }

        // Future extensibility: Add more configuration types here
        // if (paymentConfig != null) {
        // String configJson = jsonHelper.toJson(paymentConfig);
        // JsonElement configElement = jsonHelper.parse(configJson);
        // wrapper.add("paymentConfig", configElement);
        // }

        return jsonHelper.toJson(wrapper);
    }

    /**
     * Deserialize JSON to configuration wrapper
     */
    public static LoanProductConfigurationWrapper fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LoanProductConfigurationWrapper();
        }

        try {
            JsonElement jsonElement = jsonHelper.parse(json);
            JsonObject jsonObject = jsonElement.getAsJsonObject();

            LoanProductConfigurationWrapper wrapper = new LoanProductConfigurationWrapper();

            // Extract brokenPeriodConfig
            JsonElement brokenPeriodConfigElement = jsonObject.get("brokenPeriodConfig");
            if (brokenPeriodConfigElement != null) {
                String brokenPeriodStrategyString = jsonHelper.extractStringNamed("strategy", brokenPeriodConfigElement);
                BrokenPeriodInterestStrategy brokenPeriodStrategy = BrokenPeriodInterestStrategy.fromCode(brokenPeriodStrategyString);
                // TODO: use locale from request
                Integer brokenPeriodDaysInMonthTypeString = jsonHelper.extractIntegerNamed("brokenPeriodDaysInMonth",
                        brokenPeriodConfigElement, Locale.getDefault());
                DaysInMonthType brokenPeriodDaysInMonthType = DaysInMonthType.fromInt(brokenPeriodDaysInMonthTypeString);
                Integer brokenPeriodDaysInYearTypeString = jsonHelper.extractIntegerNamed("brokenPeriodDaysInYear",
                        brokenPeriodConfigElement, Locale.getDefault());
                DaysInYearType brokenPeriodDaysInYearType = DaysInYearType.fromInt(brokenPeriodDaysInYearTypeString);
                wrapper.brokenPeriodConfig = new BrokenPeriodInterestConfigDTO(brokenPeriodStrategy, brokenPeriodDaysInMonthType,
                        brokenPeriodDaysInYearType);
            }

            // Future extensibility: Add more configuration types here
            // JsonElement paymentConfigElement = jsonObject.get("paymentConfig");
            // if (paymentConfigElement != null) {
            // wrapper.paymentConfig = jsonHelper.fromJson(
            // paymentConfigElement.toString(),
            // PaymentConfigDTO.class
            // );
            // }

            return wrapper;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize configuration wrapper", e);
        }
    }

    /**
     * Get all configuration types as a map for easy iteration
     */
    public Map<String, Object> getAllConfigurations() {
        Map<String, Object> configs = new HashMap<>();

        if (brokenPeriodConfig != null) {
            configs.put("brokenPeriodConfig", brokenPeriodConfig);
        }

        // Future extensibility: Add more configuration types here
        // if (paymentConfig != null) {
        // configs.put("paymentConfig", paymentConfig);
        // }

        return configs;
    }

    /**
     * Check if any configuration is present
     */
    public boolean hasAnyConfiguration() {
        return brokenPeriodConfig != null;
        // Future extensibility: Add more configuration types here
        // || paymentConfig != null;
    }
}
