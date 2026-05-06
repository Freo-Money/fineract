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
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.event.business.BusinessEventListener;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;

/**
 * Re-aligns overdue-installment penalties after a {@code LoanUndo*} event. The undo itself happens at business date but
 * the undone transaction is typically historical, so reversing it changes the loan's overdue position retroactively;
 * penalties for the affected installments need to be recomputed against the loan's new state. Backdated <em>new</em>
 * transactions are handled in PRE timing via {@link LoanOverduePenaltyAlignmentService} — this listener only exists for
 * the undo case, where there is no meaningful PRE moment.
 *
 * <p>
 * Detects undo events by the established {@code LoanUndo*} event-type prefix. New undo events should keep the prefix to
 * opt in here.
 */
@Slf4j
@RequiredArgsConstructor
public class LoanBackdatedOverduePenaltyReversalEventService {

    private final BusinessEventNotifierService businessEventNotifierService;
    private final LoanOverduePenaltyAlignmentService loanOverduePenaltyAlignmentService;

    @PostConstruct
    public void addListeners() {
        // Register on the base class so any newly introduced LoanUndo* subclass is picked up automatically.
        businessEventNotifierService.addPostBusinessEventListener(LoanTransactionBusinessEvent.class, new LoanTransactionListener());
    }

    private final class LoanTransactionListener implements BusinessEventListener<LoanTransactionBusinessEvent> {

        @Override
        public void onBusinessEvent(LoanTransactionBusinessEvent event) {
            if (event == null || event.getType() == null || !event.getType().startsWith("LoanUndo")) {
                return;
            }
            final LoanTransaction transaction = event.get();
            if (transaction == null || transaction.getLoan() == null) {
                return;
            }
            // Accrual-related undos shouldn't drive penalty alignment (they're system postings, and re-aligning would
            // recurse via the accrual reversal that alignPenaltiesAsOf itself triggers).
            if (transaction.isAccrualRelated()) {
                return;
            }
            log.info("Undo of loan transaction detected; aligning overdue penalties as-of business date. loanId={}, transactionId={}",
                    transaction.getLoan().getId(), transaction.getId());
            loanOverduePenaltyAlignmentService.alignPenaltiesAsOf(transaction.getLoan(), DateUtils.getBusinessLocalDate());
        }
    }
}
