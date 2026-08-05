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

import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.springframework.stereotype.Component;

/**
 * Decides whether a refund may be accepted on a loan that has already closed.
 * <p>
 * A loan can close prematurely when a duplicate payment settles installments that were not yet due. Refunding the
 * excess un-allocates those installments and reopens the loan, which is the correct treatment while the money is still
 * an advance. This is gated by the {@code allow-refund-on-closed-loans} global configuration and is disabled by
 * default; the transaction adjustment (reversal) flow is used otherwise.
 * <p>
 * Note the deliberately narrow scope. Only {@code CLOSED_OBLIGATIONS_MET} qualifies - {@code LoanStatus#isClosed()}
 * also covers written-off and rescheduled loans, and neither closure is payment-driven, so a refund must never unwind
 * them. Charged-off and foreclosed loans are excluded for the same reason, which also keeps this consistent with the
 * transaction adjustment path, where reopening a foreclosed loan is rejected outright.
 * <p>
 * Whether the money is still an advance is a separate question, answered downstream by the paid-in-advance check as of
 * the refund's own transaction date. Once the prepaid installment has fallen due the money is earned, and refunding it
 * would leave the loan in arrears for a period that has already elapsed - that case must use the reversal flow.
 */
@Component
@RequiredArgsConstructor
public class ClosedLoanRefundPolicy {

    private final ConfigurationDomainService configurationDomainService;

    public boolean isRefundAllowedOnClosedLoan(final Loan loan) {
        return configurationDomainService.isRefundOnClosedLoansEnabled() //
                && loan.getStatus().isClosedObligationsMet() //
                && !loan.isChargedOff() //
                && !loan.isForeclosure() //
                && !loan.isContractTermination();
    }
}
