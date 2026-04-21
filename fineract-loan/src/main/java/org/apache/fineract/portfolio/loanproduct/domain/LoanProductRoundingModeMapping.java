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
package org.apache.fineract.portfolio.loanproduct.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.fineract.infrastructure.core.domain.AbstractAuditableWithUTCDateTimeCustom;

@Entity
@Table(name = "fr_loan_product_rounding_mode_mapping")
@Getter
@Setter
@NoArgsConstructor
public class LoanProductRoundingModeMapping extends AbstractAuditableWithUTCDateTimeCustom<Long> {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private LoanProduct loanProduct;

    @Column(name = "rounding_mode")
    private Integer roundingMode;

    @Column(name = "installment_rounding_mode")
    private Integer installmentRoundingMode;

    @Column(name = "tax_rounding_mode")
    private Integer taxRoundingMode;

    @Column(name = "adjusted_rounding_mode")
    private Integer adjustedRoundingMode;

    public static LoanProductRoundingModeMapping of(LoanProduct loanProduct) {
        LoanProductRoundingModeMapping mapping = new LoanProductRoundingModeMapping();
        mapping.setLoanProduct(loanProduct);
        return mapping;
    }
}
