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
package org.apache.fineract.portfolio.loanaccount.serialization;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.api.LoanApiConstants;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.exception.LoanForeclosureException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public final class LoanForeclosureValidator {

    private final ChargeRepositoryWrapper chargeRepositoryWrapper;

    public void validateForForeclosureTemplate(final Loan loan, final LocalDate transactionDate) {
        validate(loan, transactionDate, true);
    }

    public void validateForForeclosure(final Loan loan, final LocalDate transactionDate) {
        validate(loan, transactionDate, false);
    }

    private void validate(final Loan loan, final LocalDate transactionDate, final boolean allowFutureDate) {
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            final String defaultUserMessage = "The loan with interest recalculation enabled cannot be foreclosed.";
            throw new LoanForeclosureException("loan.with.interest.recalculation.enabled.cannot.be.foreclosured", defaultUserMessage,
                    loan.getId());
        }

        if (!allowFutureDate && DateUtils.isDateInTheFuture(transactionDate)) {
            final String defaultUserMessage = "The transactionDate cannot be in the future.";
            throw new LoanForeclosureException("loan.foreclosure.transaction.date.is.in.future", defaultUserMessage, transactionDate);
        }

        if (DateUtils.isBefore(transactionDate, loan.getLastUserTransactionDate())) {
            final String defaultUserMessage = "The transactionDate cannot be earlier than the last transaction date.";
            throw new LoanForeclosureException("loan.foreclosure.transaction.date.cannot.before.the.last.transaction.date",
                    defaultUserMessage, transactionDate);
        }
    }

    /**
     * Validates the foreclosure charges supplied in the request. A charge is accepted only when it exists, is a
     * FORECLOSURE-time charge, its percentage is positive and (when configured) within the charge's min/max cap. This
     * is invoked from both the foreclosure template and the actual foreclosure so the preview and the execution enforce
     * the same rules.
     */
    public void validateForeclosureChargePercentages(final Loan loan, final Map<Long, BigDecimal> chargePercentages) {
        if (chargePercentages == null || chargePercentages.isEmpty()) {
            return;
        }

        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors).resource("loan");

        for (Map.Entry<Long, BigDecimal> entry : chargePercentages.entrySet()) {
            Long chargeId = entry.getKey();
            BigDecimal percentage = entry.getValue();

            // Early validation: percentage must be positive (before DB lookup)
            if (percentage == null || percentage.compareTo(BigDecimal.ZERO) <= 0) {
                baseDataValidator.reset().parameter(LoanApiConstants.foreclosureChargePercentageMapParamName).value(percentage)
                        .failWithCode("error.msg.charge.percentage.must.be.positive",
                                "Percentage for charge ID " + chargeId + " must be positive");
                continue;
            }

            // Find the charge definition
            Charge charge;
            try {
                charge = this.chargeRepositoryWrapper.findOneWithNotFoundDetection(chargeId);
            } catch (Exception e) {
                baseDataValidator.reset().parameter(LoanApiConstants.foreclosureChargePercentageMapParamName).value(chargeId)
                        .failWithCode("error.msg.charge.not.found", "Charge with ID " + chargeId + " not found");
                continue;
            }

            // Validate charge is a foreclosure charge
            ChargeTimeType chargeTimeType = ChargeTimeType.fromInt(charge.getChargeTimeType());
            if (!ChargeTimeType.FORECLOSURE.equals(chargeTimeType)) {
                baseDataValidator.reset().parameter(LoanApiConstants.foreclosureChargePercentageMapParamName).value(chargeId).failWithCode(
                        "error.msg.charge.not.foreclosure.type",
                        "Charge with ID " + chargeId + " is not a foreclosure charge (chargeTimeType must be FORECLOSURE)");
                continue;
            }

            // Validate percentage is within min and max range (inclusive) if defined
            BigDecimal minCap = charge.getMinCap();
            BigDecimal maxCap = charge.getMaxCap();
            if (minCap != null && percentage.compareTo(minCap) < 0) {
                baseDataValidator.reset().parameter(LoanApiConstants.foreclosureChargePercentageMapParamName).value(percentage)
                        .failWithCode("error.msg.charge.percentage.below.minimum", "Percentage " + percentage + " for charge ID " + chargeId
                                + " is below the minimum allowed value of " + minCap);
                continue;
            }
            if (maxCap != null && percentage.compareTo(maxCap) > 0) {
                baseDataValidator.reset().parameter(LoanApiConstants.foreclosureChargePercentageMapParamName).value(percentage)
                        .failWithCode("error.msg.charge.percentage.above.maximum", "Percentage " + percentage + " for charge ID " + chargeId
                                + " is above the maximum allowed value of " + maxCap);
                continue;
            }
        }

        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException("validation.msg.validation.errors.exist", "Validation errors exist.",
                    dataValidationErrors);
        }
    }
}
