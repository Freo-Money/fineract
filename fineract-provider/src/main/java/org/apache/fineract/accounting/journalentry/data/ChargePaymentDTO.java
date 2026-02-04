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
package org.apache.fineract.accounting.journalentry.data;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import lombok.Getter;

@Getter
public class ChargePaymentDTO {

    private final Long chargeId;
    private final BigDecimal amount;
    private final Long loanChargeId;
    private final BigDecimal amountSansTax;
    private final BigDecimal taxAmount;
    private final Map<Long, BigDecimal> taxComponentAmounts;

    public ChargePaymentDTO(final Long chargeId, final BigDecimal amount, final Long loanChargeId) {
        this(chargeId, amount, loanChargeId, null, null, null);
    }

    public ChargePaymentDTO(final Long chargeId, final BigDecimal amount, final Long loanChargeId, final BigDecimal amountSansTax,
            final BigDecimal taxAmount) {
        this(chargeId, amount, loanChargeId, amountSansTax, taxAmount, null);
    }

    public ChargePaymentDTO(final Long chargeId, final BigDecimal amount, final Long loanChargeId, final BigDecimal amountSansTax,
            final BigDecimal taxAmount, final Map<Long, BigDecimal> taxComponentAmounts) {
        this.chargeId = chargeId;
        this.amount = amount;
        this.loanChargeId = loanChargeId;
        this.amountSansTax = amountSansTax;
        this.taxAmount = taxAmount;
        if (taxComponentAmounts == null || taxComponentAmounts.isEmpty()) {
            this.taxComponentAmounts = null;
        } else {
            this.taxComponentAmounts = new HashMap<>(taxComponentAmounts);
        }
    }
}
