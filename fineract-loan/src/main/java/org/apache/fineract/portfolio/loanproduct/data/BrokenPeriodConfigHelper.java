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
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.common.domain.DaysInMonthType;
import org.apache.fineract.portfolio.common.domain.DaysInYearType;
import org.apache.fineract.portfolio.loanaccount.api.LoanApiConstants;
import org.apache.fineract.portfolio.loanproduct.domain.BrokenPeriodInterestStrategy;

public final class BrokenPeriodConfigHelper {

    private BrokenPeriodConfigHelper() {
        // Utility class - prevent instantiation
    }

    public static BrokenPeriodInterestConfigDTO extractFromCommand(final JsonCommand command, final FromJsonHelper jsonHelper) {
        if (command == null || !command.parameterExists(LoanApiConstants.BROKEN_PERIOD_METHOD_TYPE)) {
            return null;
        }
        final JsonElement element = command.parsedJson();
        return extractFromJsonElement(element, jsonHelper);
    }

    public static BrokenPeriodInterestConfigDTO extractFromJsonElement(final JsonElement element, final FromJsonHelper jsonHelper) {
        if (element == null || !jsonHelper.parameterExists(LoanApiConstants.BROKEN_PERIOD_METHOD_TYPE, element)) {
            return null;
        }

        final String brokenPeriodMethodType = jsonHelper.extractStringNamed(LoanApiConstants.BROKEN_PERIOD_METHOD_TYPE, element);
        Integer daysInYear = jsonHelper.extractIntegerWithLocaleNamed(LoanApiConstants.BROKEN_PERIOD_DAYS_IN_YEAR, element);
        Integer daysInMonth = jsonHelper.extractIntegerWithLocaleNamed(LoanApiConstants.BROKEN_PERIOD_DAYS_IN_MONTH, element);

        // Apply defaults
        if (daysInYear == null || daysInYear == 0) {
            daysInYear = DaysInYearType.DAYS_365.getValue();
        }
        if (daysInMonth == null || daysInMonth == 0) {
            daysInMonth = DaysInMonthType.ACTUAL.getValue();
        }

        return toDomainDTO(brokenPeriodMethodType, daysInYear, daysInMonth);
    }

    public static BrokenPeriodInterestConfigDTO toDomainDTO(final String brokenPeriodMethodType, final Integer daysInYear,
            final Integer daysInMonth) {
        final BrokenPeriodInterestStrategy strategy = BrokenPeriodInterestStrategy.fromCode(brokenPeriodMethodType);
        final DaysInYearType daysInYearType = DaysInYearType.fromInt(daysInYear);
        final DaysInMonthType daysInMonthType = DaysInMonthType.fromInt(daysInMonth);

        return new BrokenPeriodInterestConfigDTO(strategy, daysInMonthType, daysInYearType);
    }

    public static BrokenPeriodConfigData toResponseData(final BrokenPeriodInterestConfigDTO domainDto) {
        return BrokenPeriodConfigData.fromDomainDTO(domainDto);
    }

    public static boolean isValidDaysInYear(final Integer days) {
        if (days == null) {
            return false;
        }
        return days == 1 || days == 360 || days == 364 || days == 365;
    }

    public static boolean isValidDaysInMonth(final Integer days) {
        if (days == null) {
            return false;
        }
        return days == 1 || days == 30;
    }

    public static boolean isValidStrategyCode(final String code) {
        if (code == null || code.trim().isEmpty()) {
            return false;
        }
        try {
            BrokenPeriodInterestStrategy.fromCode(code);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
