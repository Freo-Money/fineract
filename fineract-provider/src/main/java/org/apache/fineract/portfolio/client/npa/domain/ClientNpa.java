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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.fineract.infrastructure.core.domain.AbstractAuditableWithUTCDateTimeCustom;
import org.apache.fineract.portfolio.loanproduct.domain.ClientNpaExitStrategy;

@Entity
@Table(name = "fr_client_npa_status", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "client_id" }, name = "uk_fr_client_npa_status_client") })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClientNpa extends AbstractAuditableWithUTCDateTimeCustom<Long> {

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Setter
    @Column(name = "is_npa", nullable = false)
    private boolean npa;

    @Setter
    @Column(name = "npa_start_date")
    private LocalDate npaStartDate;

    @Setter
    @Column(name = "trigger_loan_id")
    private Long triggerLoanId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", length = 20)
    private ClientNpaMovementType movementType;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "exit_strategy", length = 50)
    private ClientNpaExitStrategy exitStrategy;

    public ClientNpa(final Long clientId) {
        this.clientId = clientId;
        this.npa = false;
    }
}
