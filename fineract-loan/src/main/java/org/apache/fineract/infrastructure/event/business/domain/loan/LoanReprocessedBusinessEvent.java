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
package org.apache.fineract.infrastructure.event.business.domain.loan;

import org.apache.fineract.portfolio.loanaccount.domain.Loan;

/**
 * Raised once per successful {@code command=reprocessLoan}, after the loan has been rewritten, its schedule regenerated
 * and its transactions replayed.
 * <p>
 * A reprocess reverses the disbursement transactions and re-creates them at the new date. Reporting the individual
 * transaction moves as adjustments would be misleading: upstream only ever raises
 * {@link LoanAdjustTransactionBusinessEvent} for repayment-like transactions, so a {@code DISBURSEMENT} arriving as
 * "the transaction to adjust" would be read by consumers as something it is not. This event says the one true thing
 * instead - the loan was rewritten from inception - and carries the resulting state.
 * <p>
 * No dedicated Avro schema: extending {@link LoanBusinessEvent} means {@code LoanBusinessEventSerializer} already
 * handles it and emits the whole account as {@code LoanAccountDataV1}, regenerated repayment schedule included. That is
 * the post-state a consumer needs to re-derive whatever it tracks; what specifically changed is recorded against the
 * command in {@code m_portfolio_command_source}.
 */
public class LoanReprocessedBusinessEvent extends LoanBusinessEvent {

    private static final String TYPE = "LoanReprocessedBusinessEvent";

    public LoanReprocessedBusinessEvent(final Loan value) {
        super(value);
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
