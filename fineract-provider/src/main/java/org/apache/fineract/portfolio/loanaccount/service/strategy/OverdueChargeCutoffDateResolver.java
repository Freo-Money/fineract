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
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.springframework.stereotype.Service;

/**
 * Resolves the appropriate cutoff date strategy for a loan based on configuration. If migration configuration is set
 * and the loan ID is within the migrated range, uses MigrationDateCutoffDateStrategy; otherwise uses
 * DisbursementDateCutoffDateStrategy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverdueChargeCutoffDateResolver {

    private final ConfigurationDomainService configurationDomainService;

    /**
     * Resolves the appropriate cutoff date strategy for the given loan.
     *
     * @param loan
     *            the loan for which to resolve the strategy
     * @return the appropriate strategy implementation
     */
    public OverdueChargeCutoffDateStrategy resolveStrategy(final Loan loan) {
        final LocalDate migrationCutoffDate = configurationDomainService.retrieveMigrationCutoffDate();
        final Long lastImportedLoanId = configurationDomainService.retrieveMigrationLastImportedLoanId();

        if (migrationCutoffDate != null && lastImportedLoanId != null) {
            final Long loanId = loan.getId();
            if (loanId != null && loanId <= lastImportedLoanId) {
                log.debug("Using migration cutoff date strategy for loan ID: {}", loanId);
                return new MigrationDateCutoffDateStrategy(migrationCutoffDate);
            }
        }

        return new DisbursementDateCutoffDateStrategy();
    }

    /**
     * Gets the cutoff date for the given loan using the appropriate strategy.
     *
     * @param loan
     *            the loan for which to get the cutoff date
     * @return the cutoff date (inclusive)
     */
    public LocalDate getCutoffDate(final Loan loan) {
        return resolveStrategy(loan).getCutoffDate(loan);
    }
}
