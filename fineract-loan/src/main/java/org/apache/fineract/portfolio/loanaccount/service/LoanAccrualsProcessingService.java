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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.exception.MultiException;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.springframework.lang.NonNull;

public interface LoanAccrualsProcessingService {

    void addPeriodicAccruals(@NonNull LocalDate tillDate) throws MultiException;

    void addPeriodicAccruals(@NonNull LocalDate tillDate, @NonNull Loan loan) throws MultiException;

    /**
     * Posts periodic accruals for a single loan up to the given date (same logic as COB step / batch runaccruals for
     * that loan). The loan product must have periodic accrual accounting enabled.
     */
    void addPeriodicAccrualsForLoanId(@NonNull Long loanId, @NonNull LocalDate tillDate);

    void addAccruals(@NonNull LocalDate tillDate) throws MultiException;

    void reprocessExistingAccruals(@NonNull Loan loan, boolean addEvent);

    void processAccrualsOnInterestRecalculation(@NonNull Loan loan, boolean isInterestRecalculationEnabled, boolean addJournal);

    void addIncomePostingAndAccruals(Long loanId) throws Exception;

    void processIncomePostingAndAccruals(@NonNull Loan loan, boolean addEvent);

    void processAccrualsOnLoanClosure(@NonNull Loan loan, boolean addJournal);

    void processAccrualsOnLoanForeClosure(@NonNull Loan loan, @NonNull LocalDate foreClosureDate,
            @NonNull List<LoanTransaction> newAccrualTransactions, @NonNull Map<Long, BigDecimal> mergedChargePercentages);

    void convertAccrualToSuspenseForNpaLoans(@NonNull List<Long> loanIds);

    /**
     * Converts outstanding ACCRUAL to ACCRUAL_SUSPENSE without flipping {@code loan.is_npa} and without starting a new
     * transaction, so the caller's transaction stays atomic (used after client-NPA contagion has already set
     * {@code is_npa}).
     */
    void convertAccrualToSuspenseForAlreadyNpaLoans(@NonNull List<Long> loanIds);

    void reverseAccrualSuspenseForNonNpaLoans(@NonNull List<Long> loanIds);

    /**
     * Reverses outstanding ACCRUAL_SUSPENSE without starting a new transaction, so the caller's transaction stays
     * atomic (used after the caller has already cleared and saved {@code loan.is_npa}). A REQUIRES_NEW update here
     * would bump the loan's version behind the caller's dirty Loan entity and make the caller's own commit fail.
     */
    void reverseAccrualSuspenseForAlreadyNonNpaLoans(@NonNull List<Long> loanIds);
}
