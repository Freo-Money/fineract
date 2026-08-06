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
package org.apache.fineract.portfolio.loanaccount.serialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.junit.jupiter.api.Test;

/**
 * Guards the scope of the {@code allow-refund-on-closed-loans} configuration.
 *
 * <p>
 * The predicate is deliberately narrower than {@link LoanStatus#isClosed()}, which also covers written-off and
 * rescheduled loans. Those closures are not payment-driven, so a refund must never unwind them - widening this
 * accidentally would let a refund silently un-write-off a loan.
 */
class ClosedLoanRefundPolicyTest {

    private final ConfigurationDomainService configurationDomainService = mock(ConfigurationDomainService.class);
    private final ClosedLoanRefundPolicy policy = new ClosedLoanRefundPolicy(configurationDomainService);

    @Test
    void allowsRefundOnLoanClosedByObligationsMetWhenConfigurationEnabled() {
        when(configurationDomainService.isRefundOnClosedLoansEnabled()).thenReturn(true);

        assertThat(policy.isRefundAllowedOnClosedLoan(loanWith(LoanStatus.CLOSED_OBLIGATIONS_MET))).isTrue();
    }

    @Test
    void rejectsWhenConfigurationDisabled() {
        when(configurationDomainService.isRefundOnClosedLoansEnabled()).thenReturn(false);

        assertThat(policy.isRefundAllowedOnClosedLoan(loanWith(LoanStatus.CLOSED_OBLIGATIONS_MET))).isFalse();
    }

    @Test
    void rejectsWrittenOffLoanEvenThoughItReportsAsClosed() {
        when(configurationDomainService.isRefundOnClosedLoansEnabled()).thenReturn(true);

        assertThat(LoanStatus.CLOSED_WRITTEN_OFF.isClosed()).isTrue();
        assertThat(policy.isRefundAllowedOnClosedLoan(loanWith(LoanStatus.CLOSED_WRITTEN_OFF))).isFalse();
    }

    @Test
    void rejectsRescheduledLoanEvenThoughItReportsAsClosed() {
        when(configurationDomainService.isRefundOnClosedLoansEnabled()).thenReturn(true);

        assertThat(LoanStatus.CLOSED_RESCHEDULE_OUTSTANDING_AMOUNT.isClosed()).isTrue();
        assertThat(policy.isRefundAllowedOnClosedLoan(loanWith(LoanStatus.CLOSED_RESCHEDULE_OUTSTANDING_AMOUNT))).isFalse();
    }

    @Test
    void rejectsOverpaidLoanWhichHasItsOwnCreditBalanceRefundPath() {
        when(configurationDomainService.isRefundOnClosedLoansEnabled()).thenReturn(true);

        assertThat(policy.isRefundAllowedOnClosedLoan(loanWith(LoanStatus.OVERPAID))).isFalse();
    }

    @Test
    void rejectsActiveLoanSoTheOrdinaryActiveLoanPathIsUnaffected() {
        when(configurationDomainService.isRefundOnClosedLoansEnabled()).thenReturn(true);

        assertThat(policy.isRefundAllowedOnClosedLoan(loanWith(LoanStatus.ACTIVE))).isFalse();
    }

    /**
     * A loan closed by the explicit {@code close} command with nothing outstanding also lands on
     * {@code CLOSED_OBLIGATIONS_MET} ({@code DefaultLoanLifecycleStateMachine} resolves it through
     * {@code closeObligationsMetTransition}), so this policy admits it just as it admits one closed by a final
     * repayment.
     * <p>
     * That is intended rather than incidental: the status means the obligations were met however that came about, and
     * whether any money is actually refundable is decided downstream by the paid-in-advance check, not here.
     */
    @Test
    void allowsLoanClosedByTheExplicitCloseCommandBecauseItIsAlsoObligationsMet() {
        when(configurationDomainService.isRefundOnClosedLoansEnabled()).thenReturn(true);

        assertThat(policy.isRefundAllowedOnClosedLoan(loanWith(LoanStatus.CLOSED_OBLIGATIONS_MET))).isTrue();
    }

    @Test
    void rejectsChargedOffLoan() {
        when(configurationDomainService.isRefundOnClosedLoansEnabled()).thenReturn(true);
        final Loan loan = loanWith(LoanStatus.CLOSED_OBLIGATIONS_MET);
        when(loan.isChargedOff()).thenReturn(true);

        assertThat(policy.isRefundAllowedOnClosedLoan(loan)).isFalse();
    }

    @Test
    void rejectsForeclosedLoanMatchingTheTransactionAdjustmentPath() {
        when(configurationDomainService.isRefundOnClosedLoansEnabled()).thenReturn(true);
        final Loan loan = loanWith(LoanStatus.CLOSED_OBLIGATIONS_MET);
        when(loan.isForeclosure()).thenReturn(true);

        assertThat(policy.isRefundAllowedOnClosedLoan(loan)).isFalse();
    }

    @Test
    void rejectsContractTerminatedLoan() {
        when(configurationDomainService.isRefundOnClosedLoansEnabled()).thenReturn(true);
        final Loan loan = loanWith(LoanStatus.CLOSED_OBLIGATIONS_MET);
        when(loan.isContractTermination()).thenReturn(true);

        assertThat(policy.isRefundAllowedOnClosedLoan(loan)).isFalse();
    }

    private Loan loanWith(final LoanStatus status) {
        final Loan loan = mock(Loan.class);
        when(loan.getStatus()).thenReturn(status);
        when(loan.isChargedOff()).thenReturn(false);
        when(loan.isForeclosure()).thenReturn(false);
        when(loan.isContractTermination()).thenReturn(false);
        return loan;
    }
}
