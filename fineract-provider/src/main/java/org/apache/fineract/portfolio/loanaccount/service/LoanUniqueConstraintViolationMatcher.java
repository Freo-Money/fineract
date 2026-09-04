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

import org.apache.commons.lang3.StringUtils;

/**
 * Classifies unique-constraint violations from loan persistence by the constraint name embedded in the database error
 * message. The single home for the per-dialect name lists, so a new dialect or a renamed constraint is added in one
 * place instead of drifting across catch blocks.
 *
 * <p>
 * Constraint names differ per database: {@code *_UNIQUE} is the legacy pre-Liquibase MySQL name; PostgreSQL defaults to
 * table_column_key (e.g. {@code m_loan_external_id_key}) for the Liquibase inline unique constraints; MySQL 8 (8.0.19+)
 * reports its auto-generated index as table.column (e.g. {@code m_loan.external_id}). MariaDB and older MySQL report
 * the bare column name ("for key 'account_no'"), which cannot be scoped to a table and is deliberately not matched —
 * those fall through to the caller's generic error handling. The passed cause (and its message) may be null.
 */
public final class LoanUniqueConstraintViolationMatcher {

    private LoanUniqueConstraintViolationMatcher() {}

    public static boolean isDuplicateLoanAccountNo(final Throwable cause) {
        return messageContains(cause, "loan_account_no_UNIQUE", "m_loan_account_no_key", "m_loan.account_no");
    }

    public static boolean isDuplicateLoanExternalId(final Throwable cause) {
        return messageContains(cause, "loan_externalid_UNIQUE", "m_loan_external_id_key", "m_loan.external_id");
    }

    public static boolean isDuplicateLoanTransactionExternalId(final Throwable cause) {
        return messageContains(cause, "external_id_unique", "m_loan_transaction_external_id_key", "m_loan_transaction.external_id");
    }

    private static boolean messageContains(final Throwable cause, final String... needles) {
        return cause != null && StringUtils.containsAnyIgnoreCase(cause.getMessage(), needles);
    }
}
