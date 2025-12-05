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
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargePercentageType;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;

public final class ChargePercentageUtil {

    private static final BigDecimal DAYS_IN_YEAR = BigDecimal.valueOf(365);

    private ChargePercentageUtil() {}

    private static final MathContext MATH_CONTEXT = MoneyHelper.getMathContext();

    /**
     * Calculates the effective daily percentage rate based on charge percentage type. If charge_percentage_type = 2
     * (YEARLY), divides by 365 to get daily rate. If charge_percentage_type = 1 (FLAT) or null, returns the percentage
     * as-is (treats as daily).
     *
     * @param loanCharge
     *            the loan charge entity
     * @param percentage
     *            the percentage value to adjust
     * @return the adjusted percentage for daily calculation
     */
    public static BigDecimal getEffectiveDailyPercentage(final LoanCharge loanCharge, final BigDecimal percentage) {
        if (loanCharge == null || loanCharge.getCharge() == null || percentage == null) {
            return percentage;
        }
        return getEffectiveDailyPercentage(loanCharge.getCharge(), percentage);
    }

    /**
     * Calculates the effective daily percentage rate based on charge percentage type. If charge_percentage_type = 2
     * (YEARLY), divides by 365 to get daily rate. If charge_percentage_type = 1 (FLAT) or null, returns the percentage
     * as-is (treats as daily).
     *
     * @param charge
     *            the charge entity
     * @param percentage
     *            the percentage value to adjust
     * @return the adjusted percentage for daily calculation
     */
    public static BigDecimal getEffectiveDailyPercentage(final Charge charge, final BigDecimal percentage) {
        if (charge == null || percentage == null) {
            return percentage;
        }

        final Integer chargePercentageType = charge.getChargePercentageType();
        // If charge_percentage_type = 2 (YEARLY), divide by 365 to get daily rate
        if (ChargePercentageType.YEARLY.getValue().equals(chargePercentageType)) {
            return percentage.divide(DAYS_IN_YEAR, MATH_CONTEXT);
        }

        // If charge_percentage_type = 1 (FLAT) or null, return as-is (treat as daily)
        return percentage;
    }
}
