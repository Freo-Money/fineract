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
package org.apache.fineract.portfolio.loanaccount.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Getter;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;

/**
 * Lightweight data class containing only the essential loan fields needed for schedule operations. This avoids loading
 * the full LoanAccountData object when only minimal loan information is required. Note: This is LOAN data (not schedule
 * data) - it contains basic loan fields used FOR retrieving/building schedules.
 */
@Getter
@Builder
public class LoanBasicDataForSchedule {

    private final LocalDate expectedDisbursementDate;
    private final LocalDate actualDisbursementDate;
    private final CurrencyData currency;
    private final BigDecimal principal;
    private final BigDecimal inArrearsTolerance;
    private final BigDecimal feeChargesAtDisbursementCharged;
    private final LoanScheduleType loanScheduleType;
}
