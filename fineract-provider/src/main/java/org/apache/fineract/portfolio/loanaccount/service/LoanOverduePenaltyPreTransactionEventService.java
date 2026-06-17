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

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.event.business.BusinessEventListener;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanApplyOverduePenaltiesThroughBusinessDateBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanChargePaymentPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanChargePaymentPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanForeClosurePreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionMakeRepaymentPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionMakeRepaymentPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.event.business.service.ExternalBusinessEventConfigurationService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;

/**
 * When {@link LoanApplyOverduePenaltiesThroughBusinessDateBusinessEvent} is enabled in external-event configuration,
 * applies overdue-installment penalties through the business date before repayment, charge payment, or foreclosure so
 * same-day penalties are not missed when COB has not yet run.
 */
@Slf4j
@RequiredArgsConstructor
public class LoanOverduePenaltyPreTransactionEventService {

    private final BusinessEventNotifierService businessEventNotifierService;
    private final LoanChargeWritePlatformService loanChargeWritePlatformService;
    private final ExternalBusinessEventConfigurationService externalBusinessEventConfigurationService;

    @PostConstruct
    public void addListeners() {
        businessEventNotifierService.addPreBusinessEventListener(LoanTransactionMakeRepaymentPreBusinessEvent.class,
                new MakeRepaymentListener());
        businessEventNotifierService.addPreBusinessEventListener(LoanChargePaymentPreBusinessEvent.class, new ChargePaymentListener());
        businessEventNotifierService.addPreBusinessEventListener(LoanForeClosurePreBusinessEvent.class, new ForeClosureListener());
        businessEventNotifierService.addPostBusinessEventListener(LoanTransactionMakeRepaymentPostBusinessEvent.class,
                new MakeRepaymentPostListener());
        businessEventNotifierService.addPostBusinessEventListener(LoanChargePaymentPostBusinessEvent.class,
                new ChargePaymentPostListener());
    }

    /**
     * Before a repayment / charge payment / foreclosure, reconcile the loan's overdue penalties to the transaction
     * date: apply any due up to it and remove any applied beyond it, so the loan (and the amount paid) reflect exactly
     * the penalties due as of the transaction date. Honors grace / wait period and does not skip NPA loans.
     */
    private void reconcileOverduePenaltiesUpToDate(final Loan loan, final LocalDate asOfDate) {
        if (loan == null || loan.getId() == null || !loan.isOpen() || loan.isChargedOff()) {
            return;
        }
        if (!externalBusinessEventConfigurationService
                .isExternalEventConfiguredForPosting(new LoanApplyOverduePenaltiesThroughBusinessDateBusinessEvent(loan))) {
            return;
        }
        log.debug("Reconciling overdue penalties up to {} before loan transaction, loanId={}", asOfDate, loan.getId());
        loanChargeWritePlatformService.reconcileOverduePenaltiesAsOf(loan.getId(), asOfDate);
    }

    /**
     * After a backdated repayment / charge payment, the outstanding from the transaction date forward is reduced, so
     * the overdue penalties for periods after that date must be recomputed on the new outstanding through the business
     * date.
     */
    private void recalculateOverduePenaltiesForBackdatedTransaction(final LoanTransaction transaction) {
        if (transaction == null || transaction.isReversed()) {
            return;
        }
        final Loan loan = transaction.getLoan();
        if (loan == null || loan.getId() == null || !loan.isOpen() || loan.isChargedOff()) {
            return;
        }
        // Same-day transactions already use the current outstanding; only backdated ones need recomputation.
        if (!DateUtils.isBeforeBusinessDate(transaction.getTransactionDate())) {
            return;
        }
        if (!externalBusinessEventConfigurationService
                .isExternalEventConfiguredForPosting(new LoanApplyOverduePenaltiesThroughBusinessDateBusinessEvent(loan))) {
            return;
        }
        loanChargeWritePlatformService.recalculateOverduePenaltiesAfterBackdatedTransaction(loan.getId(), transaction.getTransactionDate());
    }

    private LocalDate dateOrBusinessDate(final LocalDate date) {
        return date != null ? date : DateUtils.getBusinessLocalDate();
    }

    private final class MakeRepaymentListener implements BusinessEventListener<LoanTransactionMakeRepaymentPreBusinessEvent> {

        @Override
        public void onBusinessEvent(final LoanTransactionMakeRepaymentPreBusinessEvent event) {
            reconcileOverduePenaltiesUpToDate(event.get(), dateOrBusinessDate(event.getTransactionDate()));
        }
    }

    private final class MakeRepaymentPostListener implements BusinessEventListener<LoanTransactionMakeRepaymentPostBusinessEvent> {

        @Override
        public void onBusinessEvent(final LoanTransactionMakeRepaymentPostBusinessEvent event) {
            recalculateOverduePenaltiesForBackdatedTransaction(event.get());
        }
    }

    private final class ChargePaymentListener implements BusinessEventListener<LoanChargePaymentPreBusinessEvent> {

        @Override
        public void onBusinessEvent(final LoanChargePaymentPreBusinessEvent event) {
            reconcileOverduePenaltiesUpToDate(event.get(), dateOrBusinessDate(event.getTransactionDate()));
        }
    }

    private final class ChargePaymentPostListener implements BusinessEventListener<LoanChargePaymentPostBusinessEvent> {

        @Override
        public void onBusinessEvent(final LoanChargePaymentPostBusinessEvent event) {
            recalculateOverduePenaltiesForBackdatedTransaction(event.get());
        }
    }

    private final class ForeClosureListener implements BusinessEventListener<LoanForeClosurePreBusinessEvent> {

        @Override
        public void onBusinessEvent(final LoanForeClosurePreBusinessEvent event) {
            reconcileOverduePenaltiesUpToDate(event.get(), dateOrBusinessDate(event.getForeclosureDate()));
        }
    }
}
