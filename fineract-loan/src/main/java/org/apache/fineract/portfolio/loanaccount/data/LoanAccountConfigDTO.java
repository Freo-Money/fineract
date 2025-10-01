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
package org.apache.fineract.portfolio.loanaccount.data;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.fineract.portfolio.loanproduct.data.BrokenPeriodInterestConfigDTO;

/**
 * DTO for Loan Account Configuration mapping. Represents the configuration stored at the loan level.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LoanAccountConfigDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long loanId;
    private String configIdentity;
    private String configJson;
    private BrokenPeriodInterestConfigDTO brokenPeriodInterestConfig;

    public LoanAccountConfigDTO(Long id, Long loanId, String configIdentity, String configJson) {
        this.id = id;
        this.loanId = loanId;
        this.configIdentity = configIdentity;
        this.configJson = configJson;
    }
}
