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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.AbstractAuditableWithUTCDateTimeCustom;

@Entity
@Table(name = "fr_client_npa_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClientNpaHistory extends AbstractAuditableWithUTCDateTimeCustom<Long> {

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "trigger_loan_id")
    private Long triggerLoanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", length = 20)
    private ClientNpaMovementType movementType;

    @Column(name = "exit_reason", length = 100)
    private String exitReason;

    /**
     * A history row is written only when the NPA spell ends, so it is complete from the moment it exists — there is no
     * open row to find and close later. The current spell lives in {@code fr_client_npa_status}.
     */
    public ClientNpaHistory(final Long clientId, final LocalDate startDate, final LocalDate endDate, final Long triggerLoanId,
            final ClientNpaMovementType movementType, final String exitReason) {
        this.clientId = clientId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.triggerLoanId = triggerLoanId;
        this.movementType = movementType;
        this.exitReason = exitReason;
    }
}
