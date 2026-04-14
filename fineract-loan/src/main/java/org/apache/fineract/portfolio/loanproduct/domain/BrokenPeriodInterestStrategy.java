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

import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.core.data.EnumOptionData;

/**
 * Enum representing different strategies for handling broken period interest (BPI).
 */

public enum BrokenPeriodInterestStrategy {

    /**
     * No broken period interest strategy
     */
    NONE("none", "None"),

    /**
     * Add partial interest to the first installment EMI.
     */
    ADD_TO_FIRST_INSTALLMENT_EMI("add_to_first_installment_emi", "Add to First Installment EMI"),

    /**
     * Add partial interest with principal grace period
     */
    ADD_TO_FIRST_INSTALLMENT_WITH_PRINCIPAL_GRACE("add_to_first_installment_with_principal_grace",
            "Add to First Installment with Principal Grace");

    private final String code;
    private final String displayName;

    BrokenPeriodInterestStrategy(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return this.code;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    /**
     * A static method to provide a list of all enum constants as EnumOptionData objects. This is used to populate
     * dropdowns in the UI.
     *
     * @return A list of EnumOptionData representing the enum values.
     */

    public static List<EnumOptionData> getOptionDataList() {
        final List<EnumOptionData> options = new ArrayList<>();
        for (final BrokenPeriodInterestStrategy strategy : values()) {
            options.add(new EnumOptionData((long) strategy.ordinal(), strategy.getCode(), strategy.getDisplayName()));
        }
        return options;
    }

    public static BrokenPeriodInterestStrategy fromCode(String strategyCode) {
        if (strategyCode == null) {
            return null;
        }
        for (BrokenPeriodInterestStrategy s : values()) {
            if (s.getCode().equalsIgnoreCase(strategyCode)) {
                return s;
            }
        }
        return null;
    }

    public boolean isAddToFirstInstallmentEmi() {
        return this == ADD_TO_FIRST_INSTALLMENT_EMI;
    }

    public boolean isAddToFirstInstallmentWithPrincipalGrace() {
        return this == ADD_TO_FIRST_INSTALLMENT_WITH_PRINCIPAL_GRACE;
    }

    public boolean isNone() {
        return this == NONE;
    }
}
