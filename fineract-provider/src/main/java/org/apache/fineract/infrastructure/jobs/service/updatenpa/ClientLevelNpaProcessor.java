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

import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.portfolio.client.npa.service.ClientNpaWritePlatformService;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Client-level NPA propagation, active only while {@code enable-client-npa} is on — within the nightly UPDATE_NPA job
 * it replaces {@link LoanLevelNpaProcessor} rather than running alongside it. Nightly reconciliation (formerly CL_UNPA)
 * is folded into UPDATE_NPA via {@link #reconcile(JdbcTemplate)}.
 * <p>
 * Client NPA is a job-only concern: the UPDATE_NPA COB business step keeps running the loan-level rule for every loan
 * regardless of this flag, and never enters this processor.
 */
@Component
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class ClientLevelNpaProcessor implements NpaProcessor {

    private final ClientNpaWritePlatformService clientNpaWritePlatformService;
    private final ConfigurationDomainService configurationDomainService;
    private final DatabaseSpecificSQLGenerator sqlGenerator;

    /**
     * Supersedes {@link LoanLevelNpaProcessor}'s reconcile when on: the client sweep applies the same arrears rule per
     * client and propagates the result to every loan, so exactly one of the two ever reconciles in the job.
     */
    @Override
    public boolean isEnabled() {
        return configurationDomainService.isClientNpaEnabled();
    }

    @Override
    public void reconcile(final JdbcTemplate jdbcTemplate) {
        // Set-based candidate detection, mirroring the loan tasklet's move-to/move-out SQL: only clients whose current
        // state disagrees with their recorded client-NPA state are evaluated in Java, so nightly work is proportional
        // to state changes, not the NPA client stock. Detection is state-based (current arrears vs current business
        // date), so skipped nights self-heal on the next run. reEvaluateClient remains the single authority — a false
        // positive here only costs one evaluation; the unions below must merely avoid false negatives.
        final String keepNpaLoanExists = "SELECT 1 FROM m_loan l " + "INNER JOIN m_product_loan mpl ON mpl.id = l.product_id "
                + "  AND mpl.overdue_days_for_npa IS NOT NULL " + "INNER JOIN m_loan_arrears_aging laa ON laa.loan_id = l.id "
                + "WHERE l.client_id = c.client_id AND l.loan_status_id = 300 "
                + "  AND (mpl.account_moves_out_of_npa_only_on_arrears_completion = true OR laa.overdue_since_date_derived <= "
                + sqlGenerator.subDate(sqlGenerator.currentBusinessDate(), "mpl.overdue_days_for_npa", "day") + ")";

        final Set<Long> clientIds = new HashSet<>();
        // Entry (becoming NPA): client has a loan whose arrears reach the product NPA threshold but is not client-NPA
        // yet. Read from arrears aging, not m_loan.is_npa, so a loan the COB step has not reached yet is still seen.
        // Boundary is inclusive so a loan sitting exactly on the threshold is still offered to reEvaluateClient, which
        // applies the authoritative entry rule.
        clientIds.addAll(jdbcTemplate.queryForList(
                "SELECT DISTINCT l.client_id FROM m_loan l "
                        + "INNER JOIN m_product_loan mpl ON mpl.id = l.product_id AND mpl.overdue_days_for_npa IS NOT NULL "
                        + "INNER JOIN m_loan_arrears_aging laa ON laa.loan_id = l.id "
                        + "WHERE l.loan_status_id = 300 AND l.client_id IS NOT NULL AND laa.overdue_since_date_derived <= "
                        + sqlGenerator.subDate(sqlGenerator.currentBusinessDate(), "mpl.overdue_days_for_npa", "day")
                        + " AND NOT EXISTS (SELECT 1 FROM fr_client_npa_status cn WHERE cn.client_id = l.client_id AND cn.is_npa = true)",
                Long.class));
        // Entry (already NPA): client has a loan the COB step flagged is_npa but is not client-NPA yet. The arrears
        // query above misses this when a run was skipped on the crossing night and arrears have since dropped back
        // under the threshold while the keep rule (arrears-completion products) holds the loan in NPA — without this
        // union that client would never enter. A stale flag only costs one reEvaluateClient call.
        clientIds.addAll(jdbcTemplate.queryForList(
                "SELECT DISTINCT l.client_id FROM m_loan l WHERE l.loan_status_id = 300 AND l.is_npa = true AND l.client_id IS NOT NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM fr_client_npa_status cn WHERE cn.client_id = l.client_id AND cn.is_npa = true)",
                Long.class));
        // Exit: client-NPA but no active loan still satisfies the keep-NPA rule
        clientIds.addAll(jdbcTemplate.queryForList(
                "SELECT c.client_id FROM fr_client_npa_status c WHERE c.is_npa = true AND NOT EXISTS (" + keepNpaLoanExists + ")",
                Long.class));
        // Trigger change: the recorded trigger loan is no longer active or no longer independently NPA
        clientIds.addAll(jdbcTemplate.queryForList("SELECT c.client_id FROM fr_client_npa_status c "
                + "INNER JOIN m_loan tl ON tl.id = c.trigger_loan_id " + "LEFT JOIN m_product_loan tp ON tp.id = tl.product_id "
                + "LEFT JOIN m_loan_arrears_aging ta ON ta.loan_id = tl.id "
                + "WHERE c.is_npa = true AND (tl.loan_status_id <> 300 OR tp.overdue_days_for_npa IS NULL "
                + "OR ta.overdue_since_date_derived IS NULL "
                + "OR (tp.account_moves_out_of_npa_only_on_arrears_completion = false AND ta.overdue_since_date_derived > "
                + sqlGenerator.subDate(sqlGenerator.currentBusinessDate(), "tp.overdue_days_for_npa", "day") + "))", Long.class));
        // Drift: an active loan of a client-NPA client is missing its contagion flag or mapping row (missed hook)
        clientIds.addAll(jdbcTemplate.queryForList(
                "SELECT DISTINCT l.client_id FROM m_loan l "
                        + "INNER JOIN fr_client_npa_status c ON c.client_id = l.client_id AND c.is_npa = true "
                        + "WHERE l.loan_status_id = 300 AND (l.is_npa = false OR NOT EXISTS "
                        + "(SELECT 1 FROM fr_client_npa_loan_mapping m WHERE m.client_id = l.client_id AND m.loan_id = l.id))",
                Long.class));

        log.info("Client NPA reconcile evaluating {} clients", clientIds.size());
        for (final Long clientId : clientIds) {
            try {
                // Isolated transaction per client so one failure does not poison the shared UPDATE_NPA tasklet
                // transaction
                clientNpaWritePlatformService.reEvaluateClientInNewTransaction(clientId);
            } catch (final Exception ex) {
                log.error("Failed to evaluate client NPA for clientId={}", clientId, ex);
            }
        }
    }
}
