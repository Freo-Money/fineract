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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransactionMetaDataTest {

    @Mock
    private LoanTransaction transaction;

    @Test
    void stampNpaFieldsThenMergeSubTypePreservesBothFields() {
        final String[] storedJson = new String[1];
        when(transaction.getTransactionMetaData()).thenAnswer(inv -> storedJson[0]);
        doAnswer(inv -> {
            storedJson[0] = inv.getArgument(0);
            return null;
        }).when(transaction).updateTransactionMetaData(any());

        TransactionMetaData.stampNpaFields(transaction, true, "mifos-standard-strategy");
        TransactionMetaData.mergeSubType(transaction, TransactionSubType.FORECLOSURE);

        final TransactionMetaData meta = TransactionMetaData.deserialize(storedJson[0]);
        assertTrue(meta.getTxnInNpa());
        assertEquals("mifos-standard-strategy", meta.getNpaTransactionProcessingStrategy());
        assertEquals(TransactionSubType.FORECLOSURE, meta.getTransactionSubType());
    }

    @Test
    void mergeSubTypeAloneDoesNotWriteTxnInNpaFalse() {
        final String[] storedJson = new String[1];
        when(transaction.getTransactionMetaData()).thenAnswer(inv -> storedJson[0]);
        doAnswer(inv -> {
            storedJson[0] = inv.getArgument(0);
            return null;
        }).when(transaction).updateTransactionMetaData(any());

        TransactionMetaData.mergeSubType(transaction, TransactionSubType.FORECLOSURE);

        final TransactionMetaData meta = TransactionMetaData.deserialize(storedJson[0]);
        assertNull(meta.getTxnInNpa());
        assertEquals(TransactionSubType.FORECLOSURE, meta.getTransactionSubType());
    }

    @Test
    void serializeIfPresentReturnsNullWhenEmpty() {
        final TransactionMetaData meta = new TransactionMetaData();
        assertNull(meta.serializeIfPresent());
    }

    @Test
    void subTypeIsPersistedByConstantName() {
        // The constant name is the on-disk form in m_loan_transaction.transaction_meta_data, including on rows
        // written while this field was still a plain String — renaming a constant would orphan them.
        assertTrue(new TransactionMetaData(TransactionSubType.FORECLOSURE).serialize().contains("\"transactionSubType\":\"FORECLOSURE\""));
        assertEquals(TransactionSubType.FORECLOSURE,
                TransactionMetaData.deserialize("{\"transactionSubType\":\"FORECLOSURE\"}").getTransactionSubType());
    }

    @Test
    void unrecognisedSubTypeReadsAsNullWithoutLosingTheRestOfTheMetaData() {
        final TransactionMetaData meta = TransactionMetaData
                .deserialize("{\"transactionSubType\":\"NOT_A_KNOWN_SUB_TYPE\",\"txnInNpa\":true}");

        assertNull(meta.getTransactionSubType());
        assertTrue(meta.getTxnInNpa());
    }
}
