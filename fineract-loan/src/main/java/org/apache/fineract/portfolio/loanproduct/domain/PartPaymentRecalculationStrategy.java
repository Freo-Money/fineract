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

import org.apache.fineract.infrastructure.core.api.ApiFacingEnum;

public enum PartPaymentRecalculationStrategy implements ApiFacingEnum<PartPaymentRecalculationStrategy> {

    REDUCED_EMI(1, "partPaymentRecalculationStrategy.reducedEmi", "Reduce EMI"), //
    REDUCED_TENURE(2, "partPaymentRecalculationStrategy.reducedTenure", "Reduce Tenure"), //
    ;

    /**
     * The strategy a part-payment falls back on when neither the loan nor its product names one, and the value every
     * lenient lookup below coerces an unrecognised input to. Keeping it in one place means the product-wide default is
     * a single-constant change rather than a hunt through the fallbacks.
     */
    public static final PartPaymentRecalculationStrategy DEFAULT = REDUCED_TENURE;

    private final Integer value;
    private final String code;
    private final String humanReadableName;

    PartPaymentRecalculationStrategy(final Integer value, final String code, final String humanReadableName) {
        this.value = value;
        this.code = code;
        this.humanReadableName = humanReadableName;
    }

    public Integer getValue() {
        return this.value;
    }

    @Override
    public String getCode() {
        return this.code;
    }

    @Override
    public String getHumanReadableName() {
        return this.humanReadableName;
    }

    public static PartPaymentRecalculationStrategy fromInt(final Integer value) {
        if (value == null) {
            return DEFAULT;
        }
        return switch (value) {
            case 1 -> PartPaymentRecalculationStrategy.REDUCED_EMI;
            case 2 -> PartPaymentRecalculationStrategy.REDUCED_TENURE;
            default -> DEFAULT;
        };
    }

    public static PartPaymentRecalculationStrategy fromCode(final String code) {
        if (code == null) {
            return DEFAULT;
        }
        for (PartPaymentRecalculationStrategy s : values()) {
            if (s.getCode().equalsIgnoreCase(code)) {
                return s;
            }
        }
        return DEFAULT;
    }

    public boolean isReducedEmi() {
        return this == REDUCED_EMI;
    }

    public boolean isReducedTenure() {
        return this == REDUCED_TENURE;
    }
}
