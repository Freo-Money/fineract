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
package org.apache.fineract.infrastructure.jobs.service.updatenpa;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.database.RoutingDataSourceServiceFactory;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaRepository;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.service.LoanAccrualsProcessingService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Loan-level NPA only. Client-level NPA is handled by {@link ClientLevelNpaProcessor} via {@link NpaProcessorFactory}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanNpaUpdateServiceImpl implements LoanNpaUpdateService {

    private static final String SELECT_OVERDUE_SINCE = "SELECT overdue_since_date_derived FROM m_loan_arrears_aging WHERE loan_id = ?";

    private final RoutingDataSourceServiceFactory dataSourceServiceFactory;
    private final LoanAccrualsProcessingService loanAccrualsProcessingService;
    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final ClientNpaRepository clientNpaRepository;

    @Override
    public void updateNpaStatusForLoan(Loan loan, LocalDate businessDate) {
        LoanProduct product = loan.loanProduct();
        if (product == null || product.getOverdueDaysForNPA() == null) {
            return;
        }
        if (!loan.getStatus().isActive()) {
            return;
        }

        LocalDate overdueSinceDate = getOverdueSinceDate(loan.getId());
        Integer npaDays = product.getOverdueDaysForNPA();
        LocalDate threshold = businessDate.minusDays(npaDays);

        boolean shouldBeNpa = shouldLoanBeNpa(loan, overdueSinceDate, threshold, product);
        // While client is client-level NPA, keep loan.is_npa true until client exit recalculates each loan
        if (!shouldBeNpa && loan.isNpa() && isClientNpa(loan.getClientId())) {
            return;
        }
        if (shouldBeNpa == loan.isNpa()) {
            return;
        }

        log.debug("Updating NPA status for loan id [{}] from [{}] to [{}]", loan.getId(), loan.isNpa(), shouldBeNpa);
        loan.setNpa(shouldBeNpa);
        loanRepositoryWrapper.save(loan);

        if (loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
            // Both callers (COB business step, client-NPA exit) run inside a transaction that has already dirtied
            // this Loan, so the suspense work must join it: an isolated REQUIRES_NEW update of the same row would
            // commit a version bump the caller's own flush cannot survive, leaving the suspense posting committed
            // while the caller rolls back.
            if (shouldBeNpa) {
                loanAccrualsProcessingService.convertAccrualToSuspenseForAlreadyNpaLoans(Collections.singletonList(loan.getId()));
            } else {
                loanAccrualsProcessingService.reverseAccrualSuspenseForAlreadyNonNpaLoans(Collections.singletonList(loan.getId()));
            }
        }
    }

    private boolean shouldLoanBeNpa(Loan loan, LocalDate overdueSinceDate, LocalDate threshold, LoanProduct product) {
        if (loan.isNpa()) {
            return !shouldMoveOutOfNpa(overdueSinceDate, threshold, product);
        } else {
            return shouldMoveToNpa(overdueSinceDate, threshold);
        }
    }

    private boolean shouldMoveToNpa(LocalDate overdueSinceDate, LocalDate threshold) {
        return overdueSinceDate != null && overdueSinceDate.isBefore(threshold);
    }

    private boolean shouldMoveOutOfNpa(LocalDate overdueSinceDate, LocalDate threshold, LoanProduct product) {
        if (product.isAccountMovesOutOfNPAOnlyOnArrearsCompletion()) {
            return overdueSinceDate == null;
        }
        return overdueSinceDate == null || overdueSinceDate.isAfter(threshold);
    }

    @Override
    public boolean wouldBeLoanLevelNpa(final Loan loan, final LocalDate businessDate) {
        final LoanProduct product = loan.loanProduct();
        if (product == null || product.getOverdueDaysForNPA() == null || !loan.getStatus().isActive()) {
            return false;
        }
        final LocalDate overdueSinceDate = getOverdueSinceDate(loan.getId());
        if (overdueSinceDate == null) {
            return false;
        }
        final LocalDate threshold = businessDate.minusDays(product.getOverdueDaysForNPA());
        // Same entry/keep asymmetry as m_loan.is_npa. Pure entry semantics on an already-flagged loan disagree with
        // keep on the exact threshold boundary and can flap client exit→re-entry. loan.is_npa may be set by client
        // contagion; that is a deliberate conservative choice (arrears-completion siblings with any arrears stay
        // "independently NPA") until per-loan independent-vs-contagion cause is modeled.
        return shouldLoanBeNpa(loan, overdueSinceDate, threshold, product);
    }

    private boolean isClientNpa(final Long clientId) {
        return clientId != null && clientNpaRepository.existsByClientIdAndNpaTrue(clientId);
    }

    private LocalDate getOverdueSinceDate(Long loanId) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSourceServiceFactory.determineDataSourceService().retrieveDataSource());
        return jdbcTemplate.query(SELECT_OVERDUE_SINCE, new RowMapper<LocalDate>() {

            @Override
            public LocalDate mapRow(ResultSet rs, int rowNum) throws SQLException {
                java.sql.Date date = rs.getDate(1);
                return date != null ? date.toLocalDate() : null;
            }
        }, loanId).stream().findFirst().orElse(null);
    }
}
