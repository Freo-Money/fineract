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

/**
 * Response DTO for Broken Period Interest configuration. This represents the API-facing structure with string codes and
 * integer values for mobile-friendly consumption.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BrokenPeriodConfigData implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Strategy code for BPI calculation. Possible values: - "add_to_first_installment_emi" (implemented) -
     * "add_to_first_installment_with_principal_grace" (planned, accepted but not yet functional)
     */
    private String brokenPeriodMethodType;

    /**
     * Days in year for BPI calculation. Possible values: - 1 (ACTUAL - actual days in year 365/366) - 360 (DAYS_360 -
     * fixed 360 days) - 364 (DAYS_364 - fixed 364 days) - 365 (DAYS_365 - fixed 365 days, default)
     */
    private Integer brokenPeriodDaysInYear;

    /**
     * Days in month for BPI calculation. Possible values: - 1 (ACTUAL - actual days in month 28-31) - 30 (DAYS_30 -
     * fixed 30 days, default)
     */
    private Integer brokenPeriodDaysInMonth;

    /**
     * Factory method to create response DTO from domain DTO
     *
     * @param domainDto
     *            The domain BrokenPeriodInterestConfigDTO
     * @return BrokenPeriodConfigData or null if input is null
     */
    public static BrokenPeriodConfigData fromDomainDTO(final BrokenPeriodInterestConfigDTO domainDto) {
        if (domainDto == null) {
            return null;
        }

        String methodType = domainDto.getStrategy() != null ? domainDto.getStrategy().getCode() : null;
        Integer daysInYear = domainDto.getDaysInYearType() != null ? domainDto.getDaysInYearType().getValue() : null;
        Integer daysInMonth = domainDto.getDaysInMonthType() != null ? domainDto.getDaysInMonthType().getValue() : null;

        return new BrokenPeriodConfigData(methodType, daysInYear, daysInMonth);
    }
}
