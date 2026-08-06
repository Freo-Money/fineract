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
package org.apache.fineract.portfolio.loanaccount.reprocess;

import com.google.gson.reflect.TypeToken;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import org.apache.fineract.infrastructure.core.api.JsonCommand;

/**
 * Parsed, immutable view of a {@code reprocessLoan} payload.
 * <p>
 * Every correctable parameter is boxed and nullable: null means "leave as is". That distinction matters - a
 * {@code principal} of null and a {@code principal} of zero are very different requests.
 */
@Getter
@Builder
public class LoanReprocessRequest {

    private final LocalDate actualDisbursementDate;
    private final BigDecimal principal;
    private final LocalDate expectedFirstRepaymentOnDate;
    private final BigDecimal interestRatePerPeriod;
    private final Integer numberOfRepayments;

    private final String note;

    public static LoanReprocessRequest from(final JsonCommand command) {
        // Reject unknown keys before parsing. Without this a misspelled parameter is silently dropped, which would
        // contradict the whole point of the not-yet-implemented gate: no requested change may ever be ignored.
        command.checkForUnsupportedParameters(new TypeToken<Map<String, Object>>() {}.getType(), command.json(),
                LoanReprocessApiConstants.SUPPORTED_PARAMETERS);
        return LoanReprocessRequest.builder() //
                .actualDisbursementDate(command.localDateValueOfParameterNamed(LoanReprocessApiConstants.actualDisbursementDateParamName)) //
                .principal(command.bigDecimalValueOfParameterNamed(LoanReprocessApiConstants.principalParamName)) //
                .expectedFirstRepaymentOnDate(
                        command.localDateValueOfParameterNamed(LoanReprocessApiConstants.expectedFirstRepaymentOnDateParamName)) //
                .interestRatePerPeriod(command.bigDecimalValueOfParameterNamed(LoanReprocessApiConstants.interestRatePerPeriodParamName)) //
                .numberOfRepayments(command.integerValueOfParameterNamed(LoanReprocessApiConstants.numberOfRepaymentsParamName)) //
                .note(command.stringValueOfParameterNamed(LoanReprocessApiConstants.noteParamName)) //
                .build();
    }

    /**
     * Names of the correctable parameters actually supplied, in the order they would be applied. Drives both the
     * not-yet-implemented gate and the {@code changes} map returned to the caller.
     */
    public Set<String> requestedParameters() {
        final Set<String> requested = new LinkedHashSet<>();
        if (actualDisbursementDate != null) {
            requested.add(LoanReprocessApiConstants.actualDisbursementDateParamName);
        }
        if (principal != null) {
            requested.add(LoanReprocessApiConstants.principalParamName);
        }
        if (expectedFirstRepaymentOnDate != null) {
            requested.add(LoanReprocessApiConstants.expectedFirstRepaymentOnDateParamName);
        }
        if (interestRatePerPeriod != null) {
            requested.add(LoanReprocessApiConstants.interestRatePerPeriodParamName);
        }
        if (numberOfRepayments != null) {
            requested.add(LoanReprocessApiConstants.numberOfRepaymentsParamName);
        }
        return requested;
    }

    public boolean hasAnyChange() {
        return !requestedParameters().isEmpty();
    }

    /** Parameters that are declared on the contract but whose apply step does not exist yet. */
    public Set<String> unimplementedParameters() {
        final Set<String> unimplemented = new LinkedHashSet<>(requestedParameters());
        unimplemented.removeAll(LoanReprocessApiConstants.IMPLEMENTED_PARAMETERS);
        return unimplemented;
    }
}
