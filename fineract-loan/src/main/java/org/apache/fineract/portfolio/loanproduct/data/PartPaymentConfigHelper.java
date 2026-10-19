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
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.loanaccount.api.LoanApiConstants;
import org.apache.fineract.portfolio.loanproduct.domain.PartPaymentRecalculationStrategy;

/**
 * Extraction helper for the part-payment configuration, the counterpart of {@link BrokenPeriodConfigHelper}.
 * <p>
 * The strategy is submitted as the enum <em>name</em> ({@code REDUCED_EMI} / {@code REDUCED_TENURE}), which is the
 * convention every {@code ApiFacingEnum}-backed loan product field follows (see {@code chargeOffBehaviour} and
 * {@code capitalizedIncomeStrategy}). It is persisted as the enum <em>code</em> by
 * {@link LoanProductConfigurationWrapper#toJson()}, so the stored JSON is unchanged by this API.
 */
public final class PartPaymentConfigHelper {

    private PartPaymentConfigHelper() {
        // Utility class - prevent instantiation
    }

    /**
     * Extracts the part-payment config from a command payload, or {@code null} when the payload does not carry the
     * parameter at all. A {@code null} return means "caller said nothing about part-payment", which callers treat as
     * inherit-or-leave-alone rather than as a removal.
     */
    public static PartPaymentConfigDTO extractFromCommand(final JsonCommand command, final FromJsonHelper jsonHelper) {
        if (command == null || !command.parameterExists(LoanApiConstants.PART_PAYMENT_RECALCULATION_STRATEGY)) {
            return null;
        }
        return extractFromJsonElement(command.parsedJson(), jsonHelper);
    }

    /**
     * Extracts the part-payment config from a parsed payload, or {@code null} when the parameter is absent or blank.
     */
    public static PartPaymentConfigDTO extractFromJsonElement(final JsonElement element, final FromJsonHelper jsonHelper) {
        if (element == null || !jsonHelper.parameterExists(LoanApiConstants.PART_PAYMENT_RECALCULATION_STRATEGY, element)) {
            return null;
        }
        final String strategyName = jsonHelper.extractStringNamed(LoanApiConstants.PART_PAYMENT_RECALCULATION_STRATEGY, element);
        return toDomainDTO(strategyName);
    }

    /**
     * Resolves the submitted enum name into a domain DTO. Returns {@code null} for a blank value so that clearing the
     * field is distinguishable from selecting a strategy. The name is validated at the API boundary
     * ({@code isOneOfEnumValues}) before reaching here, so an unparseable value at this point is a programming error
     * rather than bad input.
     */
    public static PartPaymentConfigDTO toDomainDTO(final String strategyName) {
        if (StringUtils.isBlank(strategyName)) {
            return null;
        }
        return new PartPaymentConfigDTO(PartPaymentRecalculationStrategy.valueOf(strategyName.trim()));
    }

    public static PartPaymentConfigData toResponseData(final PartPaymentConfigDTO domainDto) {
        return PartPaymentConfigData.fromDomainDTO(domainDto);
    }

    /**
     * Reads the part-payment config out of a serialized configuration envelope, tolerating a null/blank or unparseable
     * envelope. Used by the read services, which must never fail a GET because one product has a malformed
     * hand-inserted config row.
     */
    public static PartPaymentConfigDTO fromConfigJson(final String configJson) {
        if (StringUtils.isBlank(configJson)) {
            return null;
        }
        try {
            final LoanProductConfigurationWrapper wrapper = LoanProductConfigurationWrapper.fromJson(configJson);
            return wrapper != null ? wrapper.getPartPaymentConfig() : null;
        } catch (final Exception e) {
            // Malformed configuration - surface no part-payment config rather than failing the whole read.
            return null;
        }
    }
}
