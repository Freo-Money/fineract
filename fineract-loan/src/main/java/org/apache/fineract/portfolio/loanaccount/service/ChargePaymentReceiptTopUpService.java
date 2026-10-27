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

import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;

/**
 * Optional step of {@code POST /loans/{loanId}?command=reprocessTransactions}: with
 * {@code {"topUpClippedChargeReceipts": true}} the loan's clipped charge-payment receipts are raised before the
 * transactions are reprocessed, all in one database transaction. See {@link ChargePaymentReceiptTopUpPlanner} for the
 * rules.
 */
public interface ChargePaymentReceiptTopUpService {

    String TOP_UP_CLIPPED_CHARGE_RECEIPTS_PARAM = "topUpClippedChargeReceipts";

    boolean isRequested(JsonCommand command);

    CommandProcessingResult reprocessWithTopUp(Long loanId, JsonCommand command);
}
