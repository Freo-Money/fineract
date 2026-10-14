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

/**
 * Read-only view of one receipt as the planner saw it: the receipt, the charge it pays, the installment it replays into
 * and that installment's component bucket. Serialised into the command response for diagnosis.
 * <p>
 * {@code chargeCap} is the balance this receipt competes for - the charge, or one installment's share of an instalment
 * fee - net of waivers, write-offs and what other transactions have settled on it: the most this transaction may carry.
 */
public record ChargePaymentReceiptView(Long receiptId, Long chargeId, BigDecimal amount, BigDecimal chargeCap, boolean chargeActive,
        Boolean feeCharge, Integer installmentNumber, String mappedBy, BigDecimal bucketAmount, BigDecimal bucketCompleted) {

    public static final String MAPPED_BY_NUMBER = "INSTALLMENT_NUMBER";
    public static final String MAPPED_BY_DUE_PERIOD = "DUE_PERIOD";
    public static final String UNMAPPED = "UNMAPPED";
}
