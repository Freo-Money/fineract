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

import java.time.LocalDate;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;

/**
 * Service to evaluate and update NPA (Non-Performing Asset) status for a single loan. Used by the UPDATE_NPA COB
 * business step when processing loans in the loan COB job.
 */
public interface LoanNpaUpdateService {

    /**
     * Evaluates whether the loan should be in NPA based on product configuration and arrears aging, and updates the
     * loan's NPA status and accrual entries if needed.
     *
     * @param loan
     *            the loan to evaluate (must be active and have product with overdueDaysForNPA set to participate)
     * @param businessDate
     *            the COB business date
     */
    void updateNpaStatusForLoan(Loan loan, LocalDate businessDate);

    /**
     * Whether the loan would be treated as NPA under loan-level entry/keep rules for the given business date.
     * <p>
     * Uses the <em>entry</em> rule ({@code overdue_since < threshold}) when {@code loan.is_npa} is false, and the
     * <em>keep</em> rule when it is true (including boundary hysteresis and arrears-completion "any arrears"). Client
     * NPA contagion also sets {@code loan.is_npa}, so under contagion this predicate is deliberately conservative:
     * arrears-completion siblings with any arrears count as independently NPA and can block client exit under
     * {@code ANY_NPA_LOAN_EXISTS}. Distinguishing independently entered vs contagion-flagged loans would require
     * per-loan cause data the model does not store.
     */
    boolean wouldBeLoanLevelNpa(Loan loan, LocalDate businessDate);
}
