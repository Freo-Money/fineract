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

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Slf4j
public class TransactionMetaData {

    private static final FromJsonHelper JSON_HELPER = new FromJsonHelper();

    private TransactionSubType transactionSubType;

    /**
     * Whether the loan was effectively NPA ({@code loan.isNpa} or client NPA) when this transaction was posted.
     */
    private Boolean txnInNpa;

    /**
     * Repayment allocation strategy code from {@code ref_loan_transaction_processing_strategy}, frozen at posting time
     * when {@link #txnInNpa} is true. Value of global config {@code npa-transaction-processing-strategy}.
     */
    private String npaTransactionProcessingStrategy;

    public TransactionMetaData(final TransactionSubType transactionSubType) {
        this.transactionSubType = transactionSubType;
    }

    public boolean hasPersistableContent() {
        return transactionSubType != null || Boolean.TRUE.equals(txnInNpa) || npaTransactionProcessingStrategy != null;
    }

    public String serializeIfPresent() {
        return hasPersistableContent() ? serialize() : null;
    }

    public static TransactionMetaData fromJson(final String json) {
        return deserialize(json);
    }

    public static void mergeSubType(final LoanTransaction transaction, final TransactionSubType transactionSubType) {
        if (transaction == null || transactionSubType == null) {
            return;
        }
        TransactionMetaData meta = deserialize(transaction.getTransactionMetaData());
        if (meta == null) {
            meta = new TransactionMetaData();
        }
        meta.setTransactionSubType(transactionSubType);
        transaction.updateTransactionMetaData(meta.serializeIfPresent());
    }

    /**
     * Carries the frozen NPA stamp ({@code txnInNpa} and strategy) from a transaction being replaced onto its
     * replacement, so an adjustment allocates under the same strategy as the original. Copies nothing when the source
     * was never stamped; never overwrites a stamp already present on the target.
     */
    public static void copyNpaFields(final LoanTransaction source, final LoanTransaction target) {
        if (source == null || target == null) {
            return;
        }
        final TransactionMetaData sourceMeta = deserialize(source.getTransactionMetaData());
        if (sourceMeta == null || sourceMeta.getTxnInNpa() == null) {
            return;
        }
        TransactionMetaData targetMeta = deserialize(target.getTransactionMetaData());
        if (targetMeta == null) {
            targetMeta = new TransactionMetaData();
        }
        if (targetMeta.getTxnInNpa() != null) {
            return;
        }
        targetMeta.setTxnInNpa(sourceMeta.getTxnInNpa());
        targetMeta.setNpaTransactionProcessingStrategy(sourceMeta.getNpaTransactionProcessingStrategy());
        target.updateTransactionMetaData(targetMeta.serializeIfPresent());
    }

    public static void stampNpaFields(final LoanTransaction transaction, final boolean effectiveNpa, final String npaStrategy) {
        if (transaction == null || !effectiveNpa) {
            return;
        }
        TransactionMetaData meta = deserialize(transaction.getTransactionMetaData());
        if (meta == null) {
            meta = new TransactionMetaData();
        }
        if (meta.getTxnInNpa() != null) {
            return;
        }
        meta.setTxnInNpa(true);
        if (npaStrategy != null) {
            meta.setNpaTransactionProcessingStrategy(npaStrategy);
        }
        transaction.updateTransactionMetaData(meta.serializeIfPresent());
    }

    public String serialize() {
        return JSON_HELPER.toJson(this);
    }

    public static TransactionMetaData deserialize(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return JSON_HELPER.fromJson(json, TransactionMetaData.class);
        } catch (Exception e) {
            // Null means "no metadata": a frozen NPA stamp silently degrades to the product strategy downstream, so
            // corruption must at least be visible in the logs.
            log.warn("Unparseable transaction_meta_data dropped; any frozen NPA stamp in it is lost: {}", json, e);
            return null;
        }
    }
}
