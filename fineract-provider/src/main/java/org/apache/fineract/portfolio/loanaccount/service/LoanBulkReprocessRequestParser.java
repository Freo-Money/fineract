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

import com.google.gson.JsonElement;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.loanaccount.data.LoanBulkReprocessRequestData;
import org.springframework.stereotype.Component;

/**
 * Parses and validates the JSON body for async bulk loan reprocess runs.
 */
@Component
@RequiredArgsConstructor
public class LoanBulkReprocessRequestParser {

    private final FromJsonHelper fromJsonHelper;

    public LoanBulkReprocessRequestData parse(final String apiRequestBodyAsJson) {
        final JsonElement element = this.fromJsonHelper.parse(apiRequestBodyAsJson);
        final Long[] loanIdArray = this.fromJsonHelper.extractLongArrayNamed("loanIds", element);
        if (loanIdArray == null) {
            throw new PlatformApiDataValidationException(
                    List.of(ApiParameterError.parameterError("validation.msg.missing.parameter", "loanIds is required", "loanIds")));
        }
        final List<Long> loanIds = Arrays.asList(loanIdArray);
        if (loanIds.isEmpty()) {
            throw new PlatformApiDataValidationException(List.of(
                    ApiParameterError.parameterError("validation.msg.loanIds.not.empty", "loanIds must be a non-empty array", "loanIds")));
        }
        if (loanIds.stream().anyMatch(id -> id == null || id <= 0L)) {
            throw new PlatformApiDataValidationException(List.of(ApiParameterError.parameterError("validation.msg.loanIds.invalid",
                    "loanIds must contain only positive numeric ids", "loanIds")));
        }

        final List<Long> distinctLoanIds = loanIds.stream().distinct().toList();
        if (distinctLoanIds.size() > LoanBulkReprocessConstants.MAX_LOAN_IDS_PER_REQUEST) {
            throw new PlatformApiDataValidationException(List.of(ApiParameterError.parameterError("validation.msg.loanIds.too.many",
                    "At most " + LoanBulkReprocessConstants.MAX_LOAN_IDS_PER_REQUEST + " distinct loan ids per request", "loanIds")));
        }

        final Integer batchSize = this.fromJsonHelper.extractIntegerSansLocaleNamed("batchSize", element);
        if (batchSize != null
                && (batchSize < LoanBulkReprocessConstants.MIN_BATCH_SIZE || batchSize > LoanBulkReprocessConstants.MAX_BATCH_SIZE)) {
            throw new PlatformApiDataValidationException(
                    List.of(ApiParameterError.parameterError("validation.msg.batchSize.range", "batchSize must be between "
                            + LoanBulkReprocessConstants.MIN_BATCH_SIZE + " and " + LoanBulkReprocessConstants.MAX_BATCH_SIZE,
                            "batchSize")));
        }
        return new LoanBulkReprocessRequestData(distinctLoanIds, batchSize);
    }
}
