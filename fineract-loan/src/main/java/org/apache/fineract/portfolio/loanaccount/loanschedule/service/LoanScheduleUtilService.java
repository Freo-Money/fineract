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
package org.apache.fineract.portfolio.loanaccount.loanschedule.service;

import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.DefaultScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanApplicationTerms;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.loanproduct.domain.BrokenPeriodInterestStrategy;

public class LoanScheduleUtilService {

    public static boolean isAdditionalPrincipalGracePeriodRequired(LoanApplicationTerms loanApplicationTerms) {

        final BrokenPeriodInterestStrategy brokenPeriodMethod = loanApplicationTerms.getBpiConfig().getStrategy();
        boolean isAdditionalPrincipalGracePeriodRequired = false;
        if (brokenPeriodMethod != null && brokenPeriodMethod.isAdjustmentInFirstEMIWithPrincipalGrace()) {

            final LocalDate expectedDisbursementDate = loanApplicationTerms.getExpectedDisbursementDate();
            final Integer repaymentEvery = loanApplicationTerms.getRepaymentEvery();
            final PeriodFrequencyType repaymentPeriodFrequencyType = loanApplicationTerms.getRepaymentPeriodFrequencyType();

            LocalDate seedDate = loanApplicationTerms.getExpectedDisbursementDate();
            if (loanApplicationTerms.getInterestChargedFromDate() != null
                    && DateUtils.isBefore(loanApplicationTerms.getInterestChargedFromDate(), expectedDisbursementDate)) {
                seedDate = expectedDisbursementDate.minusDays(1);
            }

            ScheduledDateGenerator scheduledDateGenerator = new DefaultScheduledDateGenerator();
            LocalDate calculatedRepaymentsStartingFromDate = scheduledDateGenerator.generateNextRepaymentDate(seedDate,
                    loanApplicationTerms, false);

            final LocalDate expectedFirstRepaymentDate = scheduledDateGenerator.getRepaymentPeriodDate(repaymentPeriodFrequencyType,
                    repaymentEvery, seedDate);
            if (expectedFirstRepaymentDate.isAfter(calculatedRepaymentsStartingFromDate)) {
                isAdditionalPrincipalGracePeriodRequired = true;
            }
        }
        return isAdditionalPrincipalGracePeriodRequired;
    }

    public static LocalDate generateNextScheduleDate(final PeriodFrequencyType frequency, final Integer repaidEvery,
            final LocalDate startDate) {
        LocalDate dueRepaymentPeriodDate = startDate;
        switch (frequency) {
            case DAYS:
                dueRepaymentPeriodDate = startDate.plusDays(repaidEvery);
            break;
            case WEEKS:
                dueRepaymentPeriodDate = startDate.plusWeeks(repaidEvery);
            break;
            case MONTHS:
                dueRepaymentPeriodDate = startDate.plusMonths(repaidEvery);
            break;
            case YEARS:
                dueRepaymentPeriodDate = startDate.plusYears(repaidEvery);
            break;
            case INVALID:
            break;
            case WHOLE_TERM:
            // TODO: Implement getRepaymentPeriodDate for WHOLE_TERM
            break;
        }
        return dueRepaymentPeriodDate;
    }

}
