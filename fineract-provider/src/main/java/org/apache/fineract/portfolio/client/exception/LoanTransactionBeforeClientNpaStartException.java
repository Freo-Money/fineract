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
package org.apache.fineract.portfolio.client.exception;

import java.time.LocalDate;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformDomainRuleException;

/**
 * Raised when a backdated repayment-like transaction is posted for a loan of a client who is in client-level NPA, and
 * the transaction date falls before the client's NPA start date (i.e. the client entered NPA after that date). Such a
 * transaction would rewrite pre-NPA history while the client is currently NPA, so it is rejected.
 */
public class LoanTransactionBeforeClientNpaStartException extends AbstractPlatformDomainRuleException {

    public LoanTransactionBeforeClientNpaStartException(final Long clientId, final Long loanId, final LocalDate transactionDate,
            final LocalDate npaStartDate) {
        super("error.msg.loan.transaction.date.before.client.npa.start.date", "Transaction dated " + transactionDate + " for loan " + loanId
                + " is not allowed because client " + clientId + " entered NPA on " + npaStartDate, transactionDate, npaStartDate, loanId,
                clientId);
    }
}
