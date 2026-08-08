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
import org.apache.fineract.portfolio.loanaccount.reprocess.service.LoanReprocessService;
import org.springframework.stereotype.Service;

/**
 * Rewrites one or more loan parameters that invalidate the repayment schedule, regenerates the schedule from inception
 * and replays the existing transactions against it.
 * <p>
 * Not to be confused with {@link ReprocessLoanTransactionsCommandHandler}, which re-allocates transactions against the
 * schedule as it currently stands and never regenerates it. The transaction boundary is owned by the service
 * implementation, so a failure at any step leaves the loan exactly as it was.
 */
@Service
@RequiredArgsConstructor
@CommandType(entity = "LOAN", action = "REPROCESSLOAN")
public class ReprocessLoanCommandHandler implements NewCommandSourceHandler {

    private final LoanReprocessService loanReprocessService;

    @Override
    public CommandProcessingResult processCommand(final JsonCommand command) {
        return this.loanReprocessService.reprocessLoan(command.getLoanId(), command);
    }
}
