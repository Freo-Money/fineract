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
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.core.service.database.RoutingDataSourceServiceFactory;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.loanaccount.service.LoanAccrualsProcessingService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

@Slf4j
@RequiredArgsConstructor
public class UpdateNpaTasklet implements Tasklet {

    private final RoutingDataSourceServiceFactory dataSourceServiceFactory;
    private final DatabaseSpecificSQLGenerator sqlGenerator;
    private final PlatformSecurityContext context;
    private final LoanAccrualsProcessingService loanAccrualsProcessingService;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        AppUser user = context.getAuthenticatedUserIfPresent();
        final JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSourceServiceFactory.determineDataSourceService().retrieveDataSource());

        // Query loans that should move out of NPA
        int accrualPeriodicType = AccountingRuleType.ACCRUAL_PERIODIC.getValue();
        String baseMoveOutSql = "SELECT loan2.id FROM m_loan loan2 " + "left join m_loan_arrears_aging laa on laa.loan_id = loan2.id "
                + "inner join m_product_loan mpl on mpl.id = loan2.product_id and mpl.overdue_days_for_npa is not null "
                + "WHERE loan2.loan_status_id = 300 and loan2.is_npa = true and mpl.accounting_type ";
        String moveOutCondition = " and (mpl.account_moves_out_of_npa_only_on_arrears_completion = false"
                + " or (mpl.account_moves_out_of_npa_only_on_arrears_completion = true" + " and laa.overdue_since_date_derived is null))";
        List<Long> loansToMoveOutBulk = jdbcTemplate.queryForList(baseMoveOutSql + "!= " + accrualPeriodicType + moveOutCondition,
                Long.class);
        List<Long> loansToMoveOutAccrual = jdbcTemplate.queryForList(baseMoveOutSql + "= " + accrualPeriodicType + moveOutCondition,
                Long.class);

        // Query loans that should move to NPA
        String baseMoveToSql = "SELECT loan.id FROM m_loan_arrears_aging laa " + "INNER JOIN m_loan loan on laa.loan_id = loan.id "
                + "INNER JOIN m_product_loan mpl on mpl.id = loan.product_id AND mpl.overdue_days_for_npa is not null "
                + "WHERE loan.loan_status_id = 300 and loan.is_npa = false and mpl.accounting_type ";
        String moveToCondition = " and laa.overdue_since_date_derived < "
                + sqlGenerator.subDate(sqlGenerator.currentBusinessDate(), "COALESCE(mpl.overdue_days_for_npa, 0)", "day")
                + " group by loan.id";
        List<Long> loansToMoveToBulk = jdbcTemplate.queryForList(baseMoveToSql + "!= " + accrualPeriodicType + moveToCondition, Long.class);
        List<Long> loansToMoveToAccrual = jdbcTemplate.queryForList(baseMoveToSql + "= " + accrualPeriodicType + moveToCondition,
                Long.class);
        // Process loans moving TO NPA
        if (!loansToMoveToBulk.isEmpty()) {
            updateLoansNpaStatusBulk(jdbcTemplate, loansToMoveToBulk, true, user);
        }
        if (!loansToMoveToAccrual.isEmpty()) {
            loanAccrualsProcessingService.convertAccrualToSuspenseForNpaLoans(loansToMoveToAccrual);
        }
        // Process loans moving OUT OF NPA
        if (!loansToMoveOutBulk.isEmpty()) {
            updateLoansNpaStatusBulk(jdbcTemplate, loansToMoveOutBulk, false, user);
        }
        if (!loansToMoveOutAccrual.isEmpty()) {
            loanAccrualsProcessingService.reverseAccrualSuspenseForNonNpaLoans(loansToMoveOutAccrual);
        }

        log.debug("{}: Records affected by updateNPA", ThreadLocalContextUtil.getTenant().getName());
        return RepeatStatus.FINISHED;
    }

    private void updateLoansNpaStatusBulk(JdbcTemplate jdbcTemplate, List<Long> loanIds, boolean setNpa, AppUser user) {
        if (loanIds.isEmpty()) {
            return;
        }

        StringBuilder inClause = new StringBuilder();
        for (int i = 0; i < loanIds.size(); i++) {
            if (i > 0) {
                inClause.append(",");
            }
            inClause.append("?");
        }

        String sql = setNpa
                ? "UPDATE m_loan SET is_npa = true, last_modified_by = ?, last_modified_on_utc = ? WHERE id IN (" + inClause + ")"
                : "UPDATE m_loan SET is_npa = false, last_modified_by = ?, last_modified_on_utc = ? WHERE id IN (" + inClause + ")";

        List<Object> params = new ArrayList<>(loanIds);
        params.add(user.getId());
        params.add(DateUtils.getAuditOffsetDateTime());

        jdbcTemplate.update(sql, params.toArray());
    }

}
