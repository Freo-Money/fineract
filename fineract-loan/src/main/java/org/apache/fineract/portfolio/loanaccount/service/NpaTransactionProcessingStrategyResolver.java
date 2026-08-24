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

import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;

/**
 * Resolves which repayment transaction processing strategy to use for a loan transaction. When the loan is effectively
 * NPA at posting time, the global {@code npa-transaction-processing-strategy} is stamped into transaction metadata and
 * used for processing and reprocessing.
 */
public interface NpaTransactionProcessingStrategyResolver {

    /**
     * Resolves strategy for processing. If metadata has {@code txnInNpa=true}, returns frozen
     * {@code npaTransactionProcessingStrategy}; otherwise returns the loan product strategy.
     */
    String resolve(Loan loan, LoanTransaction transaction);

    /**
     * Stamps {@code txnInNpa} and, when applicable, {@code npaTransactionProcessingStrategy} on new transactions.
     */
    void stampIfAbsent(Loan loan, LoanTransaction transaction);

    /**
     * Returns true when the loan is loan-level NPA ({@code loan.is_npa}) or its client has an active client-level NPA
     * status. The flag answers first; the client lookup runs only for unflagged loans, covering windows where contagion
     * has not (yet) flagged a loan of an NPA client. Independent of {@code enable-client-npa}: stored client NPA stays
     * effective while the feature flag is off.
     */
    boolean isEffectiveLoanNpa(Loan loan);
}
