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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.event.business.BusinessEventListener;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanApplyOverduePenaltiesThroughBusinessDateBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanChargePaymentPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanForeClosurePreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionMakeRepaymentPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.event.business.service.ExternalBusinessEventConfigurationService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;

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
    }

    private void applyOverduePenaltiesThroughBusinessDate(final Loan loan) {
        if (loan == null || loan.getId() == null || !loan.isOpen() || loan.isChargedOff()) {
            return;
        }
        if (!externalBusinessEventConfigurationService
                .isExternalEventConfiguredForPosting(new LoanApplyOverduePenaltiesThroughBusinessDateBusinessEvent(loan))) {
            return;
        }
        log.debug("Applying overdue penalties through business date before loan transaction, loanId={}", loan.getId());
        loanChargeWritePlatformService.applyOverdueChargesForLoanByLoanId(loan.getId());
    }

    private final class MakeRepaymentListener implements BusinessEventListener<LoanTransactionMakeRepaymentPreBusinessEvent> {

        @Override
        public void onBusinessEvent(final LoanTransactionMakeRepaymentPreBusinessEvent event) {
            applyOverduePenaltiesThroughBusinessDate(event.get());
        }
    }

    private final class ChargePaymentListener implements BusinessEventListener<LoanChargePaymentPreBusinessEvent> {

        @Override
        public void onBusinessEvent(final LoanChargePaymentPreBusinessEvent event) {
            applyOverduePenaltiesThroughBusinessDate(event.get());
        }
    }

    private final class ForeClosureListener implements BusinessEventListener<LoanForeClosurePreBusinessEvent> {

        @Override
        public void onBusinessEvent(final LoanForeClosurePreBusinessEvent event) {
            applyOverduePenaltiesThroughBusinessDate(event.get());
        }
    }
}
