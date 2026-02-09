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
package org.apache.fineract.accounting.accrual.service;

import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;

public interface AccrualAccountingWritePlatformService {

    CommandProcessingResult executeLoansPeriodicAccrual(JsonCommand command);

    /**
     * Posts periodic accruals for a single loan up to the given date. Same logic as runaccruals but for one loan.
     *
     * @param loanId
     *            the loan id
     * @param command
     *            JSON containing tillDate (and optionally dateFormat, locale)
     * @return result with resource id
     */
    CommandProcessingResult executeLoanPeriodicAccrual(Long loanId, JsonCommand command);

}
