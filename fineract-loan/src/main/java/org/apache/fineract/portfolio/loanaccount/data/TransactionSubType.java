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
package org.apache.fineract.portfolio.loanaccount.data;

/**
 * Narrows what a loan transaction is, beyond its
 * {@link org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType}, when the type alone cannot tell two
 * cases apart. Held in {@link TransactionMetaData#getTransactionSubType()}.
 * <p>
 * The constant <strong>name</strong> is what gets persisted in {@code m_loan_transaction.transaction_meta_data} (Gson
 * writes enums by name), so renaming a constant orphans every row already carrying it. Add constants freely; rename
 * them only alongside a data migration.
 */
public enum TransactionSubType {

    /**
     * The repayment that settles a loan being foreclosed, as opposed to a repayment the borrower made.
     */
    FORECLOSURE,

    /**
     * The broken period interest repayment raised by the disbursement itself. Posted as a plain {@code REPAYMENT}, so
     * nothing else distinguishes it; it always allocates under the loan product strategy, never the NPA transaction
     * processing strategy.
     */
    BPI
}
