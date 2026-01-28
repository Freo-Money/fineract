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
package org.apache.fineract.portfolio.loanaccount.service.strategy;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;

/**
 * Default strategy that uses the loan disbursement date as the cutoff date. This ensures charges are only applied from
 * the disbursement date forward, allowing retroactive application of overdue charges from the loan's disbursement date.
 */
@RequiredArgsConstructor
public class DisbursementDateCutoffDateStrategy implements OverdueChargeCutoffDateStrategy {

    @Override
    public LocalDate getCutoffDate(final Loan loan) {
        LocalDate disbursementDate = loan.getDisbursementDate();
        // Fallback to business date if disbursement date is null (edge case for non-disbursed loans)
        if (disbursementDate == null) {
            return DateUtils.getBusinessLocalDate();
        }
        return disbursementDate;
    }
}
