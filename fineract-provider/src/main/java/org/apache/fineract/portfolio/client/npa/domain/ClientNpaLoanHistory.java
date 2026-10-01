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
package org.apache.fineract.portfolio.client.npa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.AbstractAuditableWithUTCDateTimeCustom;

@Entity
@Table(name = "fr_client_npa_loan_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClientNpaLoanHistory extends AbstractAuditableWithUTCDateTimeCustom<Long> {

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "accounting_type")
    private Integer accountingType;

    /**
     * Written only when the loan leaves client NPA, carrying the start date from the mapping row it replaces, so the
     * row is complete on insert. The loans currently under client NPA live in {@code fr_client_npa_loan_mapping}.
     */
    public ClientNpaLoanHistory(final Long clientId, final Long loanId, final LocalDate startDate, final LocalDate endDate,
            final Integer accountingType) {
        this.clientId = clientId;
        this.loanId = loanId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.accountingType = accountingType;
    }
}
