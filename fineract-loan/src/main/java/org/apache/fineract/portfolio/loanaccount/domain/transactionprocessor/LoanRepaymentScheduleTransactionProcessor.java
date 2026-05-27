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
package org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.loanaccount.domain.ChangedTransactionDetail;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;

public interface LoanRepaymentScheduleTransactionProcessor {

    String getCode();

    String getName();

    boolean accept(String s);

    /**
     * Provides support for processing the latest transaction (which should be the latest transaction) against the loan
     * schedule.
     *
     * @return ChangedTransactionDetail
     */
    ChangedTransactionDetail processLatestTransaction(LoanTransaction loanTransaction, TransactionCtx ctx);

    /**
     * Provides support for passing all {@link LoanTransaction}'s so it will completely re-process the entire loan
     * schedule. This is required in cases where the {@link LoanTransaction} being processed is in the past and falls
     * before existing transactions or and adjustment is made to an existing in which case the entire loan schedule
     * needs to be re-processed.
     */
    ChangedTransactionDetail reprocessLoanTransactions(LocalDate disbursementDate, List<LoanTransaction> repaymentsOrWaivers,
            MonetaryCurrency currency, List<LoanRepaymentScheduleInstallment> repaymentScheduleInstallments, Set<LoanCharge> charges);

    /**
     * Migration-cutoff-aware overload. Transactions with {@code transactionDate < migrationCutoffDate} are treated as
     * pre-migration: their stored
     * {@link org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionToRepaymentScheduleMapping} allocations
     * are honored as-is and the strategy is NOT re-run for them. Transactions on or after the cutoff (or all
     * transactions if {@code migrationCutoffDate} is {@code null}) flow through the strategy as in the original method.
     *
     * <p>
     * Default behavior delegates to the cutoff-unaware method — implementations that need migration handling override
     * this overload.
     * </p>
     */
    default ChangedTransactionDetail reprocessLoanTransactions(LocalDate disbursementDate, List<LoanTransaction> repaymentsOrWaivers,
            MonetaryCurrency currency, List<LoanRepaymentScheduleInstallment> repaymentScheduleInstallments, Set<LoanCharge> charges,
            LocalDate migrationCutoffDate) {
        return reprocessLoanTransactions(disbursementDate, repaymentsOrWaivers, currency, repaymentScheduleInstallments, charges);
    }

    Money handleRepaymentSchedule(List<LoanTransaction> transactionsPostDisbursement, MonetaryCurrency currency,
            List<LoanRepaymentScheduleInstallment> installments, Set<LoanCharge> loanCharges);

    /**
     * Used in interest recalculation to introduce new interest only installment.
     */
    boolean isInterestFirstRepaymentScheduleTransactionProcessor();
}
