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
import java.math.MathContext;
import java.time.LocalDate;
import java.time.ZoneId;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargePercentageCalcDaysInYearType;
import org.apache.fineract.portfolio.charge.domain.ChargePercentageType;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;

public final class ChargePercentageUtil {

    private static final BigDecimal DEFAULT_DAYS_IN_YEAR = BigDecimal.valueOf(365);

    private ChargePercentageUtil() {}

    private static final MathContext MATH_CONTEXT = MoneyHelper.getMathContext();

    public static BigDecimal getEffectiveDailyPercentage(final LoanCharge loanCharge, final BigDecimal percentage) {
        if (loanCharge == null || loanCharge.getCharge() == null || percentage == null) {
            return percentage;
        }
        LocalDate referenceDate = loanCharge.getDueLocalDate();
        if (referenceDate == null && loanCharge.getLoan() != null) {
            referenceDate = loanCharge.getLoan().getDisbursementDate();
        }
        return getEffectiveDailyPercentage(loanCharge.getCharge(), percentage, referenceDate);
    }

    public static BigDecimal getEffectiveDailyPercentage(final Charge charge, final BigDecimal percentage) {
        return getEffectiveDailyPercentage(charge, percentage, null);
    }

    public static BigDecimal getEffectiveDailyPercentage(final Charge charge, final BigDecimal percentage, final LocalDate referenceDate) {
        if (charge == null || percentage == null) {
            return percentage;
        }

        final Integer chargePercentageType = charge.getChargePercentageType();
        if (ChargePercentageType.YEARLY.getValue().equals(chargePercentageType)) {
            final Integer daysInYearType = charge.getChargePercentageCalcDaysInYearType();
            BigDecimal daysInYear = DEFAULT_DAYS_IN_YEAR;

            if (daysInYearType != null) {
                final ChargePercentageCalcDaysInYearType calcDaysInYearType = ChargePercentageCalcDaysInYearType.fromInt(daysInYearType);
                final LocalDate dateToUse = referenceDate != null ? referenceDate : LocalDate.now(ZoneId.systemDefault());
                final Integer numberOfDays = calcDaysInYearType.getNumberOfDays(dateToUse);
                if (numberOfDays != null) {
                    daysInYear = BigDecimal.valueOf(numberOfDays);
                }
            }

            return percentage.divide(daysInYear, MATH_CONTEXT);
        }

        return percentage;
    }
}
