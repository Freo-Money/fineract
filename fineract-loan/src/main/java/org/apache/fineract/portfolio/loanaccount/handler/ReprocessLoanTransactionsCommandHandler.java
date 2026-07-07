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
package org.apache.fineract.portfolio.loanaccount.handler;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.commands.annotation.CommandType;
import org.apache.fineract.commands.handler.NewCommandSourceHandler;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.portfolio.loanaccount.service.LoanWritePlatformService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-applies a single cumulative (classic) loan's existing transactions against its current repayment schedule
 * installments, WITHOUT regenerating the schedule. Installment due (principal/interest) amounts are preserved and only
 * the paid allocation and derived balances are re-derived; transactions whose allocation changes are reverse-replayed
 * with matching journal entries. Used to reconcile transaction allocation after out-of-band corrections, without the
 * schedule regeneration that a full recalculate would perform.
 */
@Service
@RequiredArgsConstructor
@CommandType(entity = "LOAN", action = "REPROCESSTRANSACTIONS")
public class ReprocessLoanTransactionsCommandHandler implements NewCommandSourceHandler {

    private final LoanWritePlatformService writePlatformService;

    @Transactional
    @Override
    public CommandProcessingResult processCommand(final JsonCommand command) {
        return this.writePlatformService.reprocessLoanTransactions(command.getLoanId(), command);
    }
}
