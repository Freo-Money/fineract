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
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.service.LoanAccrualsProcessingService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class LoanNpaUpdateServiceImpl implements LoanNpaUpdateService {

    private static final String SELECT_OVERDUE_SINCE = "SELECT overdue_since_date_derived FROM m_loan_arrears_aging WHERE loan_id = ?";

    private final RoutingDataSourceServiceFactory dataSourceServiceFactory;
    private final LoanAccrualsProcessingService loanAccrualsProcessingService;
    private final LoanRepositoryWrapper loanRepositoryWrapper;

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
        if (shouldBeNpa == loan.isNpa()) {
            return;
        }

        log.debug("Updating NPA status for loan id [{}] from [{}] to [{}]", loan.getId(), loan.isNpa(), shouldBeNpa);
        loan.setNpa(shouldBeNpa);
        loanRepositoryWrapper.save(loan);

        if (loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
            if (shouldBeNpa) {
                loanAccrualsProcessingService.convertAccrualToSuspenseForNpaLoans(Collections.singletonList(loan.getId()));
            } else {
                loanAccrualsProcessingService.reverseAccrualSuspenseForNonNpaLoans(Collections.singletonList(loan.getId()));
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
