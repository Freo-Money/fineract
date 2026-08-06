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
package org.apache.fineract.portfolio.loanaccount.exception;

import org.apache.fineract.infrastructure.core.exception.AbstractPlatformDomainRuleException;

/**
 * {@link AbstractPlatformDomainRuleException} thrown an action to transition a loan from one state to another violates
 * a domain rule.
 */
public class InvalidPaidInAdvanceAmountException extends AbstractPlatformDomainRuleException {

    /** Original key, one argument: the available amount. */
    public static final String INVALID_AMOUNT = "error.msg.loan.refund.amount.invalid";
    /** New key, two arguments: requested then available. */
    public static final String EXCEEDS_PAID_IN_ADVANCE = "error.msg.loan.refund.amount.exceeds.paid.in.advance";

    /**
     * The original single-argument contract: {@value #INVALID_AMOUNT} carrying one argument, the available amount.
     * <p>
     * Retained unchanged so any client translating that key keeps rendering the number it has always been given. The
     * two-argument form deliberately uses a different key rather than redefining this one - see
     * {@link #InvalidPaidInAdvanceAmountException(String, String)}.
     */
    public InvalidPaidInAdvanceAmountException(final String refundAmountString) {
        super(INVALID_AMOUNT, "The refund amount `" + refundAmountString + "`" + "` is invalid or loan is not paid in advance.",
                new Object[] { refundAmountString });
    }

    /**
     * Reports the requested amount alongside the available one. Any amount up to the available figure may be refunded,
     * so an operator needs both numbers to correct the request - the single-argument form reports only the available
     * amount, which reads as though the refund had to match it exactly.
     * <p>
     * Uses its own key. Reusing {@value #INVALID_AMOUNT} would keep the key but change its arguments from
     * {@code [available]} to {@code [requested, available]}, so any client interpolating that key would silently render
     * the wrong number.
     */
    public InvalidPaidInAdvanceAmountException(final String requestedAmountString, final String availableAmountString) {
        super(EXCEEDS_PAID_IN_ADVANCE,
                "The refund amount `" + requestedAmountString + "` exceeds the amount paid in advance `" + availableAmountString
                        + "`. Any amount up to the available amount may be refunded.",
                new Object[] { requestedAmountString, availableAmountString });
    }

}
