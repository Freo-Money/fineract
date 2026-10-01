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

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.portfolio.client.npa.domain.ClientNpaRepository;
import org.apache.fineract.portfolio.loanaccount.data.TransactionMetaData;
import org.apache.fineract.portfolio.loanaccount.data.TransactionSubType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleTransactionProcessorFactory;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanproduct.LoanProductConstants;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class NpaTransactionProcessingStrategyResolverImpl implements NpaTransactionProcessingStrategyResolver {

    private final ConfigurationDomainService configurationDomainService;
    private final LoanRepaymentScheduleTransactionProcessorFactory transactionProcessorFactory;
    private final ClientNpaRepository clientNpaRepository;

    public NpaTransactionProcessingStrategyResolverImpl(final ConfigurationDomainService configurationDomainService,
            @Lazy final LoanRepaymentScheduleTransactionProcessorFactory transactionProcessorFactory,
            final ClientNpaRepository clientNpaRepository) {
        this.configurationDomainService = configurationDomainService;
        this.transactionProcessorFactory = transactionProcessorFactory;
        this.clientNpaRepository = clientNpaRepository;
    }

    @Override
    public String resolve(final Loan loan, final LoanTransaction transaction) {
        if (loan == null || transaction == null) {
            throw new IllegalArgumentException("Loan and transaction are required to resolve transaction processing strategy");
        }
        final TransactionMetaData meta = TransactionMetaData.deserialize(transaction.getTransactionMetaData());
        if (isRaisedByDisbursement(transaction, meta)) {
            return loan.getTransactionProcessingStrategyCode();
        }
        if (meta != null && Boolean.TRUE.equals(meta.getTxnInNpa())) {
            final String frozenStrategy = meta.getNpaTransactionProcessingStrategy();
            if (StringUtils.isNotBlank(frozenStrategy) && transactionProcessorFactory.isRegisteredStrategy(frozenStrategy)
                    && !isCrossFamily(frozenStrategy, loan.getTransactionProcessingStrategyCode())) {
                return frozenStrategy;
            }
            log.warn(
                    "Loan transaction id={} on loan id={} has txnInNpa=true but npaTransactionProcessingStrategy [{}] is "
                            + "blank, not a registered strategy, or not compatible with product strategy [{}]; falling back "
                            + "to product strategy",
                    transaction.getId(), loan.getId(), frozenStrategy, loan.getTransactionProcessingStrategyCode());
        }
        return loan.getTransactionProcessingStrategyCode();
    }

    /**
     * Down payments, the broken-period-interest repayment and repayments at disbursement are raised by the disbursement
     * itself, not by the borrower servicing an NPA account. They always allocate under the loan product strategy,
     * whatever {@code npa-transaction-processing-strategy} is set to. The check is on the transaction type rather than
     * {@code LoanTransaction#isDownPayment()} so that a reversed transaction is judged the same way as a live one; BPI
     * carries no distinguishing type, so it is marked with a metadata sub type when it is created.
     */
    private static boolean isRaisedByDisbursement(final LoanTransaction transaction, final TransactionMetaData meta) {
        if (transaction.getTypeOf() != null
                && (transaction.getTypeOf().isDownPayment() || transaction.getTypeOf().isRepaymentAtDisbursement())) {
            return true;
        }
        return meta != null && TransactionSubType.BPI == meta.getTransactionSubType();
    }

    /**
     * The advanced-payment-allocation (progressive) processor and the cumulative processors are not interchangeable —
     * routing a loan across families corrupts allocation, and the progressive processor's own reprocess path bypasses
     * the frozen-strategy replay. The NPA strategy override is therefore only applied within the same family.
     */
    private static boolean isCrossFamily(final String npaStrategy, final String productStrategy) {
        return LoanProductConstants.ADVANCED_PAYMENT_ALLOCATION_STRATEGY
                .equals(npaStrategy) != LoanProductConstants.ADVANCED_PAYMENT_ALLOCATION_STRATEGY.equals(productStrategy);
    }

    @Override
    public void stampIfAbsent(final Loan loan, final LoanTransaction transaction) {
        if (loan == null || transaction == null) {
            return;
        }
        // Only stamp brand-new transactions at posting time; never retroactively stamp an already-persisted (pre-NPA)
        // transaction that flows back through here during a reprocess.
        if (transaction.getId() != null) {
            return;
        }
        final TransactionMetaData existing = TransactionMetaData.deserialize(transaction.getTransactionMetaData());
        if (existing != null && existing.getTxnInNpa() != null) {
            return;
        }
        // Leaving these unstamped is what makes resolve() fall through to the product strategy on every later
        // reprocess as well, instead of relying on the order in which the disbursement saves and processes them
        if (isRaisedByDisbursement(transaction, existing)) {
            return;
        }
        if (!isEffectiveLoanNpa(loan)) {
            return;
        }
        if (!configurationDomainService.isNpaTransactionProcessingStrategyEnabled()) {
            return;
        }
        final String npaStrategy = configurationDomainService.retrieveNpaTransactionProcessingStrategy();
        if (StringUtils.isBlank(npaStrategy) || !transactionProcessorFactory.isRegisteredStrategy(npaStrategy)) {
            log.warn("Loan id={} is effectively NPA but npa-transaction-processing-strategy [{}] is not configured or not a "
                    + "registered strategy; using product strategy", loan.getId(), npaStrategy);
            return;
        }
        if (isCrossFamily(npaStrategy, loan.getTransactionProcessingStrategyCode())) {
            log.warn(
                    "Loan id={} is effectively NPA but npa-transaction-processing-strategy [{}] and product strategy [{}] belong "
                            + "to different processor families; using product strategy",
                    loan.getId(), npaStrategy, loan.getTransactionProcessingStrategyCode());
            return;
        }
        TransactionMetaData.stampNpaFields(transaction, true, npaStrategy);
    }

    @Override
    public boolean isEffectiveLoanNpa(final Loan loan) {
        if (loan == null) {
            return false;
        }
        if (loan.isNpa()) {
            return true;
        }
        // Contagion normally keeps loan.is_npa in sync with client NPA, so the flag answers first and the direct
        // status lookup runs only for unflagged loans. It covers the windows where a loan of an NPA client is not
        // flagged yet (drift before the nightly repair, a hook path missed on reopen). Deliberately independent of
        // enable-client-npa: stored client NPA stays effective while the flag is off.
        return loan.getClientId() != null && clientNpaRepository.existsByClientIdAndNpaTrue(loan.getClientId());
    }
}
