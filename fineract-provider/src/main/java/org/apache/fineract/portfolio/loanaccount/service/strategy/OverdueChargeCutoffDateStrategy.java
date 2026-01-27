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
package org.apache.fineract.portfolio.loanaccount.service.strategy;

import java.time.LocalDate;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;

/**
 * Strategy interface for determining the cutoff date for applying overdue charges. This allows for different cutoff
 * date logic based on loan characteristics (e.g., migrated vs. new loans).
 */
public interface OverdueChargeCutoffDateStrategy {

    /**
     * Determines the cutoff date for applying overdue charges to a loan. Charges will only be applied for dates on or
     * after the cutoff date.
     *
     * @param loan
     *            the loan for which to determine the cutoff date
     * @return the cutoff date (inclusive); charges before this date will not be applied
     */
    LocalDate getCutoffDate(Loan loan);
}
