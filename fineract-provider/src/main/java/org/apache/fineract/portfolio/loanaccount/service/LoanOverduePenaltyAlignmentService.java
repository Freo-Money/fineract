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

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanOverduePenaltyAlignmentService {

    private final ConfigurationDomainService configurationDomainService;
    private final LoanOverduePenaltyBackdatedTransactionService loanOverduePenaltyBackdatedTransactionService;

    public boolean isEnabled() {
        return configurationDomainService.isBackdatedOverduePenaltyAlignmentEnabled();
    }

    public void alignPenaltiesAsOf(final Loan loan, final LocalDate asOfDate) {
        if (!isEnabled()) {
            return;
        }
        if (loan == null || asOfDate == null || !loan.isOpen()) {
            return;
        }
        if (loan.isChargedOff()) {
            return;
        }
        loanOverduePenaltyBackdatedTransactionService.alignPenaltiesAsOf(loan, asOfDate);
    }

    public void applyMissingPenaltiesAsOfToday(final Loan loan) {
        if (!isEnabled()) {
            return;
        }
        if (loan == null || !loan.isOpen() || loan.isChargedOff()) {
            return;
        }
        loanOverduePenaltyBackdatedTransactionService.applyMissingPenaltiesUpTo(loan, DateUtils.getBusinessLocalDate());
    }
}
