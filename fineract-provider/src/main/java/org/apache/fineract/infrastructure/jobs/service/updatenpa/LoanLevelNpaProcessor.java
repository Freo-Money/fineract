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

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.common.AccountingRuleType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.loanaccount.service.LoanAccrualsProcessingService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepositoryWrapper;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Loan-level NPA: updates {@code m_loan.is_npa} from arrears aging + product overdue days. Selected for the nightly
 * UPDATE_NPA job only while {@code enable-client-npa} is off — client-level NPA supersedes it there, applying the same
 * arrears rule per client and propagating the result to every loan of that client.
 * <p>
 * This gate covers the job alone. The UPDATE_NPA COB business step always evaluates each loan against the loan-level
 * rule, whatever {@code enable-client-npa} is set to, so a loan crossing the threshold is flagged (and its accruals
 * suspended) on that COB night rather than waiting for the job. Client propagation to sibling loans stays with the job.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class LoanLevelNpaProcessor implements NpaProcessor {

    private final ConfigurationDomainService configurationDomainService;
    private final DatabaseSpecificSQLGenerator sqlGenerator;
    private final LoanAccrualsProcessingService loanAccrualsProcessingService;
    private final PlatformSecurityContext context;
    private final AppUserRepositoryWrapper appUserRepositoryWrapper;

    /**
     * Within the job, loan-level and client-level NPA are mutually exclusive: running both reconciles would have two
     * passes writing {@code m_loan.is_npa} from the same arrears data under different scopes.
     */
    @Override
    public boolean isEnabled() {
        return !configurationDomainService.isClientNpaEnabled();
    }

    @Override
    public void reconcile(final JdbcTemplate jdbcTemplate) {
        AppUser user = context.getAuthenticatedUserIfPresent();
        if (user == null) {
            user = appUserRepositoryWrapper.fetchSystemUser();
        }
        final int accrualPeriodicType = AccountingRuleType.ACCRUAL_PERIODIC.getValue();

        // Loans that should move out of NPA
        final String baseMoveOutSql = "SELECT loan2.id FROM m_loan loan2 " + "INNER JOIN m_product_loan mpl ON mpl.id = loan2.product_id "
                + "  AND mpl.overdue_days_for_npa IS NOT NULL " + "LEFT JOIN m_loan_arrears_aging laa ON laa.loan_id = loan2.id "
                + "WHERE loan2.loan_status_id = 300 " + "  AND loan2.is_npa = true " + "  AND mpl.accounting_type ";
        final String moveOutCondition = " AND (" + "  (mpl.account_moves_out_of_npa_only_on_arrears_completion = false "
                + "   AND (laa.overdue_since_date_derived IS NULL OR laa.overdue_since_date_derived > "
                + sqlGenerator.subDate(sqlGenerator.currentBusinessDate(), "mpl.overdue_days_for_npa", "day") + ")) "
                + "  OR (mpl.account_moves_out_of_npa_only_on_arrears_completion = true "
                + "      AND laa.overdue_since_date_derived IS NULL)" + ")"
                // Loans under a stored client-level NPA keep is_npa until client exit recalculates them; the flag can
                // outlive enable-client-npa being switched off, so the guard stays even though the two are exclusive.
                + " AND NOT EXISTS (SELECT 1 FROM fr_client_npa_status cn WHERE cn.client_id = loan2.client_id AND cn.is_npa = true)";
        final List<Long> loansToMoveOutBulk = jdbcTemplate.queryForList(baseMoveOutSql + "!= " + accrualPeriodicType + moveOutCondition,
                Long.class);
        final List<Long> loansToMoveOutAccrual = jdbcTemplate.queryForList(baseMoveOutSql + "= " + accrualPeriodicType + moveOutCondition,
                Long.class);

        // Loans that should move to NPA
        final String baseMoveToSql = "SELECT loan.id FROM m_loan loan " + "INNER JOIN m_loan_arrears_aging laa ON laa.loan_id = loan.id "
                + "INNER JOIN m_product_loan mpl ON mpl.id = loan.product_id " + "  AND mpl.overdue_days_for_npa IS NOT NULL "
                + "WHERE loan.loan_status_id = 300 " + "  AND loan.is_npa = false " + "  AND mpl.accounting_type ";
        final String moveToCondition = " AND laa.overdue_since_date_derived < "
                + sqlGenerator.subDate(sqlGenerator.currentBusinessDate(), "mpl.overdue_days_for_npa", "day") + " GROUP BY loan.id";
        final List<Long> loansToMoveToBulk = jdbcTemplate.queryForList(baseMoveToSql + "!= " + accrualPeriodicType + moveToCondition,
                Long.class);
        final List<Long> loansToMoveToAccrual = jdbcTemplate.queryForList(baseMoveToSql + "= " + accrualPeriodicType + moveToCondition,
                Long.class);

        if (!loansToMoveToBulk.isEmpty()) {
            updateLoansNpaStatusBulk(jdbcTemplate, loansToMoveToBulk, true, user);
        }
        if (!loansToMoveToAccrual.isEmpty()) {
            loanAccrualsProcessingService.convertAccrualToSuspenseForNpaLoans(loansToMoveToAccrual);
        }
        if (!loansToMoveOutBulk.isEmpty()) {
            updateLoansNpaStatusBulk(jdbcTemplate, loansToMoveOutBulk, false, user);
        }
        if (!loansToMoveOutAccrual.isEmpty()) {
            loanAccrualsProcessingService.reverseAccrualSuspenseForNonNpaLoans(loansToMoveOutAccrual);
        }
    }

    private void updateLoansNpaStatusBulk(final JdbcTemplate jdbcTemplate, final List<Long> loanIds, final boolean setNpa,
            final AppUser user) {
        if (loanIds.isEmpty()) {
            return;
        }
        final StringBuilder inClause = new StringBuilder();
        for (int i = 0; i < loanIds.size(); i++) {
            if (i > 0) {
                inClause.append(",");
            }
            inClause.append("?");
        }
        final String sql = "UPDATE m_loan SET is_npa = " + setNpa + ", last_modified_by = ?, last_modified_on_utc = ? WHERE id IN ("
                + inClause + ")";
        final List<Object> params = new ArrayList<>();
        params.add(user.getId());
        params.add(DateUtils.getAuditOffsetDateTime());
        params.addAll(loanIds);
        jdbcTemplate.update(sql, params.toArray());
    }
}
