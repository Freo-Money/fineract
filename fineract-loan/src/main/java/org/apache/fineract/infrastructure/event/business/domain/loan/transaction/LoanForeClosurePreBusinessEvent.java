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
package org.apache.fineract.infrastructure.event.business.domain.loan.transaction;

import java.time.LocalDate;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanBusinessEvent;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;

public class LoanForeClosurePreBusinessEvent extends LoanBusinessEvent {

    private static final String TYPE = "LoanForeClosurePreBusinessEvent";

    private final LocalDate foreclosureDate;

    public LoanForeClosurePreBusinessEvent(Loan value) {
        this(value, null);
    }

    public LoanForeClosurePreBusinessEvent(Loan value, LocalDate foreclosureDate) {
        super(value);
        this.foreclosureDate = foreclosureDate;
    }

    public LocalDate getForeclosureDate() {
        return foreclosureDate;
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
