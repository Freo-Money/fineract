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

import java.math.BigDecimal;
import java.util.List;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;

/**
 * The planner's verdict for one charge payment, as it stood before any top-up was applied. Only a
 * {@link ChargePaymentReceiptClassification#CLIPPED} plan carries top-ups.
 */
public record ChargePaymentReceiptTopUpPlan(LoanTransaction transaction, ChargePaymentReceiptClassification classification,
        BigDecimal amount, BigDecimal receiptsTotal, BigDecimal overpaymentPortion, List<ChargePaymentReceiptTopUp> topUps) {

    public boolean hasTopUps() {
        return !topUps.isEmpty();
    }
}
