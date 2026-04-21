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
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.RoundingModeEnum;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;

/**
 * Centralized rounding precedence for loan charges.
 *
 * Digits: charge override -> product/loan currency -> fallback scale -> 0 Rounding mode: charge override ->
 * configurationDomainService tax rounding mode (or passed fallback)
 */
public final class LoanChargeRoundingUtils {

    private LoanChargeRoundingUtils() {}

    public static int resolveDigitsAfterDecimal(final Charge charge, final Integer productDigits, final BigDecimal amountFallback) {
        final Integer chargeDigitsAfterDecimal = charge != null ? charge.getDigitsAfterDecimal() : null;
        if (chargeDigitsAfterDecimal != null) {
            return chargeDigitsAfterDecimal;
        }
        if (productDigits != null) {
            return productDigits;
        }
        return amountFallback != null ? amountFallback.scale() : 0;
    }

    public static int resolveDigitsAfterDecimal(final LoanCharge loanCharge, final Charge charge, final BigDecimal amountFallback) {
        final Integer productDigits = loanCharge != null && loanCharge.getLoan() != null && loanCharge.getLoan().getCurrency() != null
                ? loanCharge.getLoan().getCurrency().getDigitsAfterDecimal()
                : null;
        return resolveDigitsAfterDecimal(charge, productDigits, amountFallback);
    }

    public static RoundingMode resolveRoundingMode(final Charge charge, final RoundingMode fallbackRoundingMode) {
        if (charge == null) {
            return fallbackRoundingMode;
        }

        final Integer roundingModeValue = charge.getRoundingMode();
        final RoundingModeEnum roundingModeEnum = RoundingModeEnum.fromInt(roundingModeValue);
        return roundingModeEnum.isInvalid() ? fallbackRoundingMode : roundingModeEnum.toJavaMathRoundingMode();
    }

    public static RoundingMode resolveRoundingMode(final Charge charge, final ConfigurationDomainService configurationDomainService) {
        return resolveRoundingMode(charge, configurationDomainService.getTaxRoundingMode());
    }
}
