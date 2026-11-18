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
package org.apache.fineract.portfolio.loanaccount.domain;

import java.util.Comparator;
import org.apache.fineract.infrastructure.core.service.DateUtils;

/**
 * Sort loan transactions by transaction date, submitted date, created date, transaction type placing, lastly compare
 * ids
 */
public class LoanTransactionComparator implements Comparator<LoanTransaction> {

    public static final LoanTransactionComparator INSTANCE = new LoanTransactionComparator();

    @Override
    public int compare(final LoanTransaction o1, final LoanTransaction o2) {
        int result = DateUtils.compareWithNullsLast(o1.getTransactionDate(), o2.getTransactionDate());
        if (result != 0) {
            return result;
        }
        // Accrual activity as last
        if (o1.isAccrualActivity() != o2.isAccrualActivity()) {
            return o1.isAccrualActivity() ? 1 : -1;
        }
        // On the same date a sweep of the parked excess (REPAYMENT_FROM_EXCESS_AMOUNT) comes after the
        // transactions that
        // can fund or settle installments: it can only spend money parked by repayments dated on or before it, and a
        // repayment replaced by a replay gets a later submitted date that would otherwise sort it after the sweep it
        // funded. Credit balance refunds and chargebacks come after the sweep, because they pay out the overpayment a
        // payoff sweep reclassifies. These are whole-type ranks ahead of the submitted/created dates to keep the
        // ordering transitive.
        result = Integer.compare(sameDateRank(o1), sameDateRank(o2));
        if (result != 0) {
            return result;
        }
        result = DateUtils.compareWithNullsLast(o1.getSubmittedOnDate(), o2.getSubmittedOnDate());
        if (result != 0) {
            return result;
        }
        result = DateUtils.compareWithNullsLast(o1.getCreatedDate(), o2.getCreatedDate());
        if (result != 0) {
            return result;
        }
        // income posting takes priority
        if (o1.isIncomePosting() != o2.isIncomePosting()) {
            return o1.isIncomePosting() ? -1 : 1;
        }
        // waive takes priority
        if (o1.isWaiver() != o2.isWaiver()) {
            return o1.isWaiver() ? -1 : 1;
        }
        result = o1.getId() != null ? (o2.getId() != null ? Long.compare(o1.getId(), o2.getId()) : -1) : (o2.getId() != null ? 1 : 0);
        if (result != 0) {
            return result;
        }
        return 0;
    }

    private static int sameDateRank(final LoanTransaction transaction) {
        final LoanTransactionType type = transaction.getTypeOf();
        if (type == LoanTransactionType.REPAYMENT_FROM_EXCESS_AMOUNT) {
            return 1;
        }
        // Only on parking loans (all transactions compared belong to the same loan, so the rank stays consistent);
        // other loans keep their existing same-date order for refunds and chargebacks.
        // A write-off is booked after that day's sweep too: it writes off what the sweep left open.
        // A recovery repayment shares its rank, so a same-day write-off and recovery keep their posting
        // order (write-off first).
        if ((type == LoanTransactionType.CREDIT_BALANCE_REFUND || type == LoanTransactionType.CHARGEBACK
                || type == LoanTransactionType.WRITEOFF || type == LoanTransactionType.RECOVERY_REPAYMENT)
                && isExcessParkingLoan(transaction.getLoan())) {
            return 2;
        }
        return 0;
    }

    private static boolean isExcessParkingLoan(final Loan loan) {
        return loan != null && loan.getLoanProductRelatedDetail() != null
                && loan.getLoanProductRelatedDetail().isEnableExcessPaymentParking();
    }
}
