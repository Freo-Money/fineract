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

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.fineract.infrastructure.core.data.StringEnumOptionData;
import org.apache.fineract.portfolio.loanproduct.domain.PartPaymentRecalculationStrategy;

/**
 * Response DTO for the part-payment recalculation configuration. Mirrors {@link BrokenPeriodConfigData}: the domain DTO
 * holds the enum, this one holds the API-facing representation.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PartPaymentConfigData implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Strategy governing how the schedule is re-amortised after a part-payment. Serialized as a
     * {@link StringEnumOptionData} so the UI gets the enum name (submitted back on create/update), the code and a
     * human-readable label in one object - the same shape as {@code chargeOffBehaviour}.
     */
    private StringEnumOptionData partPaymentRecalculationStrategy;

    /**
     * Factory method to create the response DTO from the domain DTO.
     *
     * @param domainDto
     *            the domain {@link PartPaymentConfigDTO}
     * @return PartPaymentConfigData, or null when the input or its strategy is null
     */
    public static PartPaymentConfigData fromDomainDTO(final PartPaymentConfigDTO domainDto) {
        if (domainDto == null || domainDto.getStrategy() == null) {
            return null;
        }
        return new PartPaymentConfigData(domainDto.getStrategy().getValueAsStringEnumOptionData());
    }

    /**
     * Factory method used where only the resolved enum is at hand.
     */
    public static PartPaymentConfigData from(final PartPaymentRecalculationStrategy strategy) {
        if (strategy == null) {
            return null;
        }
        return new PartPaymentConfigData(strategy.getValueAsStringEnumOptionData());
    }
}
