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
package org.apache.fineract.portfolio.loanproduct.data;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.fineract.portfolio.common.domain.DaysInMonthType;
import org.apache.fineract.portfolio.common.domain.DaysInYearType;
import org.apache.fineract.portfolio.loanproduct.domain.BrokenPeriodInterestStrategy;

/**
 * DTO for Broken Period Interest configuration details. This represents the structured data within the config_json
 * field.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BrokenPeriodInterestConfigDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private BrokenPeriodInterestStrategy strategy;
    private DaysInMonthType daysInMonthType;
    private DaysInYearType daysInYearType;

    public static BrokenPeriodInterestConfigDTO createDefault() {
        return new BrokenPeriodInterestConfigDTO(BrokenPeriodInterestStrategy.ADD_TO_FIRST_INSTALLMENT_EMI, DaysInMonthType.DAYS_30,
                DaysInYearType.DAYS_365);
    }
}
