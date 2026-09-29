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
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.exception.MultiException;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.springframework.lang.NonNull;

public interface LoanAccrualsProcessingService {

    void addPeriodicAccruals(@NonNull LocalDate tillDate) throws MultiException;

    void addPeriodicAccruals(@NonNull LocalDate tillDate, @NonNull Loan loan) throws MultiException;

    /**
     * Posts periodic accruals for a single loan up to the given date (same logic as COB step / batch runaccruals for
     * that loan), including a loan closed / overpaid by repayment. Returns the changes for the API response.
     */
    Map<String, Object> addPeriodicAccrualsForLoanId(@NonNull Long loanId, @NonNull LocalDate tillDate);

    /**
     * Batch job "Add Periodic Accrual Transactions For Closed Loans": periodic accrual continues on loans closed /
     * overpaid by repayment until their last due date.
     */
    void addPeriodicAccrualsForClosedLoans(@NonNull LocalDate tillDate) throws MultiException;

    void addAccruals(@NonNull LocalDate tillDate) throws MultiException;

    void reprocessExistingAccruals(@NonNull Loan loan, boolean addEvent);

    void processAccrualsOnInterestRecalculation(@NonNull Loan loan, boolean isInterestRecalculationEnabled, boolean addJournal);

    void addIncomePostingAndAccruals(Long loanId) throws Exception;

    void processIncomePostingAndAccruals(@NonNull Loan loan, boolean addEvent);

    void processAccrualsOnLoanClosure(@NonNull Loan loan, boolean addJournal);

    void processAccrualsOnLoanForeClosure(@NonNull Loan loan, @NonNull LocalDate foreClosureDate);

    void convertAccrualToSuspenseForNpaLoans(@NonNull List<Long> loanIds);

    void reverseAccrualSuspenseForNonNpaLoans(@NonNull List<Long> loanIds);
}
