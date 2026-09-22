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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaRepository;
import org.apache.fineract.portfolio.loanaccount.data.TransactionMetaData;
import org.apache.fineract.portfolio.loanaccount.data.TransactionSubType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleTransactionProcessorFactory;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanproduct.LoanProductConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NpaTransactionProcessingStrategyResolverImplTest {

    private static final String PRODUCT_STRATEGY = "product-strategy";
    private static final String NPA_STRATEGY = "mifos-standard-strategy";

    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private LoanRepaymentScheduleTransactionProcessorFactory transactionProcessorFactory;
    @Mock
    private ClientNpaRepository clientNpaRepository;
    @Mock
    private Loan loan;
    @Mock
    private LoanTransaction transaction;

    @InjectMocks
    private NpaTransactionProcessingStrategyResolverImpl underTest;

    private static String metaJson(final String npaStrategy) {
        final TransactionMetaData meta = new TransactionMetaData();
        meta.setTxnInNpa(true);
        meta.setNpaTransactionProcessingStrategy(npaStrategy);
        return meta.serialize();
    }

    @Test
    void resolveReturnsFrozenStrategyWhenRegistered() {
        when(transaction.getTransactionMetaData()).thenReturn(metaJson(NPA_STRATEGY));
        when(transactionProcessorFactory.isRegisteredStrategy(NPA_STRATEGY)).thenReturn(true);

        assertEquals(NPA_STRATEGY, underTest.resolve(loan, transaction));
    }

    @Test
    void resolveFallsBackToProductStrategyWhenFrozenStrategyNotRegistered() {
        when(transaction.getTransactionMetaData()).thenReturn(metaJson("typo-strategy"));
        when(transactionProcessorFactory.isRegisteredStrategy("typo-strategy")).thenReturn(false);
        when(loan.getTransactionProcessingStrategyCode()).thenReturn(PRODUCT_STRATEGY);

        assertEquals(PRODUCT_STRATEGY, underTest.resolve(loan, transaction));
    }

    @Test
    void resolveReturnsProductStrategyWhenNotNpaTransaction() {
        when(transaction.getTransactionMetaData()).thenReturn(null);
        when(loan.getTransactionProcessingStrategyCode()).thenReturn(PRODUCT_STRATEGY);

        assertEquals(PRODUCT_STRATEGY, underTest.resolve(loan, transaction));
    }

    @Test
    void stampStampsWhenNpaAndStrategyRegistered() {
        when(transaction.getTransactionMetaData()).thenReturn(null);
        when(loan.isNpa()).thenReturn(true);
        when(configurationDomainService.isNpaTransactionProcessingStrategyEnabled()).thenReturn(true);
        when(configurationDomainService.retrieveNpaTransactionProcessingStrategy()).thenReturn(NPA_STRATEGY);
        when(transactionProcessorFactory.isRegisteredStrategy(NPA_STRATEGY)).thenReturn(true);

        underTest.stampIfAbsent(loan, transaction);

        verify(transaction).updateTransactionMetaData(any());
    }

    @Test
    void stampDoesNotStampWhenConfiguredStrategyNotRegistered() {
        when(transaction.getTransactionMetaData()).thenReturn(null);
        when(loan.isNpa()).thenReturn(true);
        when(configurationDomainService.isNpaTransactionProcessingStrategyEnabled()).thenReturn(true);
        when(configurationDomainService.retrieveNpaTransactionProcessingStrategy()).thenReturn("typo-strategy");
        when(transactionProcessorFactory.isRegisteredStrategy("typo-strategy")).thenReturn(false);

        underTest.stampIfAbsent(loan, transaction);

        verify(transaction, never()).updateTransactionMetaData(any());
    }

    @Test
    void stampDoesNotStampWhenLoanNotNpa() {
        when(transaction.getTransactionMetaData()).thenReturn(null);
        when(loan.isNpa()).thenReturn(false);

        underTest.stampIfAbsent(loan, transaction);

        verify(transaction, never()).updateTransactionMetaData(any());
    }

    @Test
    void stampStampsWhenLoanUnflaggedButClientNpa() {
        // Contagion drift window: client is NPA but this loan's is_npa was not (yet) set — the transaction must
        // still be stamped, or it would allocate under the product strategy and stay frozen that way
        when(transaction.getTransactionMetaData()).thenReturn(null);
        when(loan.isNpa()).thenReturn(false);
        when(loan.getClientId()).thenReturn(10L);
        when(clientNpaRepository.existsByClientIdAndNpaTrue(10L)).thenReturn(true);
        when(configurationDomainService.isNpaTransactionProcessingStrategyEnabled()).thenReturn(true);
        when(configurationDomainService.retrieveNpaTransactionProcessingStrategy()).thenReturn(NPA_STRATEGY);
        when(transactionProcessorFactory.isRegisteredStrategy(NPA_STRATEGY)).thenReturn(true);

        underTest.stampIfAbsent(loan, transaction);

        verify(transaction).updateTransactionMetaData(any());
    }

    @Test
    void isEffectiveLoanNpaShortCircuitsOnLoanFlagWithoutClientLookup() {
        when(loan.isNpa()).thenReturn(true);

        assertTrue(underTest.isEffectiveLoanNpa(loan));
        // Flagged loans must not pay a per-call DB lookup on hot paths
        verify(clientNpaRepository, never()).existsByClientIdAndNpaTrue(anyLong());
    }

    @Test
    void isEffectiveLoanNpaFalseWhenNeitherFlaggedNorClientNpa() {
        when(loan.isNpa()).thenReturn(false);
        when(loan.getClientId()).thenReturn(10L);
        when(clientNpaRepository.existsByClientIdAndNpaTrue(10L)).thenReturn(false);

        assertFalse(underTest.isEffectiveLoanNpa(loan));
        // Group/no-client loans skip the lookup entirely
        when(loan.getClientId()).thenReturn(null);
        assertFalse(underTest.isEffectiveLoanNpa(loan));
    }

    @Test
    void stampDoesNotStampAlreadyPersistedTransaction() {
        // A transaction that already has an id is being reprocessed, not posted for the first time; it must never be
        // retroactively stamped as NPA.
        when(transaction.getId()).thenReturn(500L);

        underTest.stampIfAbsent(loan, transaction);

        verify(transaction, never()).updateTransactionMetaData(any());
    }

    @Test
    void stampDoesNotStampWhenNpaStrategyCrossesProcessorFamily() {
        // Progressive (advanced-payment-allocation) and cumulative processors are not interchangeable
        when(transaction.getTransactionMetaData()).thenReturn(null);
        when(loan.isNpa()).thenReturn(true);
        when(loan.getTransactionProcessingStrategyCode()).thenReturn(LoanProductConstants.ADVANCED_PAYMENT_ALLOCATION_STRATEGY);
        when(configurationDomainService.isNpaTransactionProcessingStrategyEnabled()).thenReturn(true);
        when(configurationDomainService.retrieveNpaTransactionProcessingStrategy()).thenReturn(NPA_STRATEGY);
        when(transactionProcessorFactory.isRegisteredStrategy(NPA_STRATEGY)).thenReturn(true);

        underTest.stampIfAbsent(loan, transaction);

        verify(transaction, never()).updateTransactionMetaData(any());
    }

    @Test
    void resolveFallsBackToProductStrategyWhenFrozenStrategyCrossesProcessorFamily() {
        when(transaction.getTransactionMetaData()).thenReturn(metaJson(NPA_STRATEGY));
        when(transactionProcessorFactory.isRegisteredStrategy(NPA_STRATEGY)).thenReturn(true);
        when(loan.getTransactionProcessingStrategyCode()).thenReturn(LoanProductConstants.ADVANCED_PAYMENT_ALLOCATION_STRATEGY);

        assertEquals(LoanProductConstants.ADVANCED_PAYMENT_ALLOCATION_STRATEGY, underTest.resolve(loan, transaction));
    }

    // Transactions raised by the disbursement itself always follow the loan product strategy, whatever the NPA
    // transaction processing strategy is configured to be.

    private void givenNpaLoanWithConfiguredNpaStrategy() {
        when(loan.isNpa()).thenReturn(true);
        when(configurationDomainService.isNpaTransactionProcessingStrategyEnabled()).thenReturn(true);
        when(configurationDomainService.retrieveNpaTransactionProcessingStrategy()).thenReturn(NPA_STRATEGY);
        when(transactionProcessorFactory.isRegisteredStrategy(NPA_STRATEGY)).thenReturn(true);
    }

    private static String bpiMetaJson(final boolean stampedAsNpa) {
        final TransactionMetaData meta = new TransactionMetaData();
        meta.setTransactionSubType(TransactionSubType.BPI);
        if (stampedAsNpa) {
            meta.setTxnInNpa(true);
            meta.setNpaTransactionProcessingStrategy(NPA_STRATEGY);
        }
        return meta.serialize();
    }

    @Test
    void stampDoesNotStampDownPayment() {
        when(transaction.getTransactionMetaData()).thenReturn(null);
        when(transaction.getTypeOf()).thenReturn(LoanTransactionType.DOWN_PAYMENT);
        givenNpaLoanWithConfiguredNpaStrategy();

        underTest.stampIfAbsent(loan, transaction);

        verify(transaction, never()).updateTransactionMetaData(any());
    }

    @Test
    void stampDoesNotStampRepaymentAtDisbursement() {
        when(transaction.getTransactionMetaData()).thenReturn(null);
        when(transaction.getTypeOf()).thenReturn(LoanTransactionType.REPAYMENT_AT_DISBURSEMENT);
        givenNpaLoanWithConfiguredNpaStrategy();

        underTest.stampIfAbsent(loan, transaction);

        verify(transaction, never()).updateTransactionMetaData(any());
    }

    @Test
    void stampDoesNotStampBrokenPeriodInterestRepayment() {
        // BPI is posted as a plain REPAYMENT, so it is recognised by the sub type the disbursement marks it with
        when(transaction.getTransactionMetaData()).thenReturn(bpiMetaJson(false));
        when(transaction.getTypeOf()).thenReturn(LoanTransactionType.REPAYMENT);
        givenNpaLoanWithConfiguredNpaStrategy();

        underTest.stampIfAbsent(loan, transaction);

        verify(transaction, never()).updateTransactionMetaData(any());
    }

    @Test
    void resolveReturnsProductStrategyForDownPaymentEvenWhenStamped() {
        when(transaction.getTransactionMetaData()).thenReturn(metaJson(NPA_STRATEGY));
        when(transaction.getTypeOf()).thenReturn(LoanTransactionType.DOWN_PAYMENT);
        when(loan.getTransactionProcessingStrategyCode()).thenReturn(PRODUCT_STRATEGY);

        assertEquals(PRODUCT_STRATEGY, underTest.resolve(loan, transaction));
    }

    @Test
    void resolveReturnsProductStrategyForBrokenPeriodInterestRepaymentEvenWhenStamped() {
        when(transaction.getTransactionMetaData()).thenReturn(bpiMetaJson(true));
        when(transaction.getTypeOf()).thenReturn(LoanTransactionType.REPAYMENT);
        when(loan.getTransactionProcessingStrategyCode()).thenReturn(PRODUCT_STRATEGY);

        assertEquals(PRODUCT_STRATEGY, underTest.resolve(loan, transaction));
    }
}
