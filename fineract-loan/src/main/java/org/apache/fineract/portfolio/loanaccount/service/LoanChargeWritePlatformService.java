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
import java.util.Collection;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.loanschedule.data.OverdueLoanScheduleData;

public interface LoanChargeWritePlatformService {

    CommandProcessingResult addLoanCharge(Long loanId, JsonCommand command);

    CommandProcessingResult loanChargeRefund(Long loanId, JsonCommand command);

    CommandProcessingResult undoWaiveLoanCharge(JsonCommand command);

    CommandProcessingResult updateLoanCharge(Long loanId, Long loanChargeId, JsonCommand command);

    CommandProcessingResult waiveLoanCharge(Long loanId, Long loanChargeId, JsonCommand command);

    CommandProcessingResult deleteLoanCharge(Long loanId, Long loanChargeId, JsonCommand command);

    CommandProcessingResult payLoanCharge(Long loanId, Long loanChargeId, JsonCommand command, boolean isChargeIdIncludedInJson);

    CommandProcessingResult adjustmentForLoanCharge(Long loanId, Long loanChargeId, JsonCommand command);

    CommandProcessingResult deactivateOverdueLoanCharge(Long loanId, JsonCommand command);

    void applyOverdueChargesForLoan(Long loanId, Collection<OverdueLoanScheduleData> overdueLoanScheduleDataList);

    /**
     * Applies overdue-installment penalty charges for a single loan (same logic as COB step
     * APPLY_CHARGE_TO_OVERDUE_LOANS). Use this for on-demand penalty application via API. NPA loans are skipped.
     */
    CommandProcessingResult applyOverdueChargesForLoanByLoanId(Long loanId);

    /**
     * Applies overdue-installment penalty charges for a single loan. When {@code skipNpaLoans} is {@code true} (COB /
     * API behaviour) loans classified as NPA are skipped; when {@code false} penalties are applied even for NPA loans
     * (used before a repayment/foreclosure transaction so same-day penalties are not missed).
     */
    CommandProcessingResult applyOverdueChargesForLoanByLoanId(Long loanId, boolean skipNpaLoans);

    CommandProcessingResult applyOverdueChargesForLoanByLoanId(Long loanId, boolean skipNpaLoans, LocalDate asOfDate);

    /**
     * Total overdue-installment penalty due up to {@code asOfDate} (applied + not-yet-applied) for installments that
     * are penalize-able as of that date — i.e. only installments past their penalty-wait-period trigger contribute, and
     * for those, all days from the day after their due date up to {@code asOfDate} are included. Used by templates to
     * show the penalty due as of the transaction date.
     */
    BigDecimal calculateOverduePenaltyAmountTillDate(Long loanId, LocalDate asOfDate);

    /**
     * Same as {@link #calculateOverduePenaltyAmountTillDate(Long, LocalDate)} but for callers that already hold the
     * loan (e.g. template building), avoiding a redundant re-assembly.
     */
    BigDecimal calculateOverduePenaltyAmountTillDate(Loan loan, LocalDate asOfDate);

    /**
     * Reconciles overdue-installment penalties so the loan reflects exactly the penalties due up to {@code asOfDate}:
     * removes any penalties dated after it (excess) and applies any that are due up to it but not yet applied. Honors
     * grace-on-penalty-posting and penalty-wait-period and does not skip NPA loans. Used before a repayment / charge
     * payment / foreclosure transaction (with the transaction date) and to compute the template amount.
     */
    void reconcileOverduePenaltiesAsOf(Long loanId, LocalDate asOfDate);

    /**
     * After a backdated repayment-like transaction on a cumulative-schedule loan, recomputes the overdue penalties for
     * periods after the transaction date on the new (reduced) outstanding, posting them through the business date. The
     * pre-transaction {@link #reconcileOverduePenaltiesAsOf(Long, LocalDate)} has already removed the stale penalties
     * dated after the transaction date. No-op for progressive-schedule loans (handled by transaction reprocessing).
     */
    void recalculateOverduePenaltiesAfterBackdatedTransaction(Long loanId, LocalDate transactionDate);

    CommandProcessingResult payByChargeId(Long loanId, Long chargeId, JsonCommand command);

    CommandProcessingResult waiveBulkLoanCharges(Long loanId, JsonCommand command);
}
