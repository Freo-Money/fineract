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
package org.apache.fineract.portfolio.loanaccount.domain;

public enum ArrearsBasedOn {

    TOTAL_OUTSTANDING(-1, "arrearsBasedOn.totalOutstanding"), //
    PRINCIPAL_ONLY(1, "arrearsBasedOn.principalOnly"), //
    PRINCIPAL_AND_INTEREST_ONLY(2, "arrearsBasedOn.principalAndInterestOnly"); //

    private final Integer value;
    private final String code;

    ArrearsBasedOn(final Integer value, final String code) {
        this.value = value;
        this.code = code;
    }

    public Integer getValue() {
        return this.value;
    }

    public String getCode() {
        return this.code;
    }

    public static ArrearsBasedOn fromInt(final Integer value) {
        if (value == null) {
            return TOTAL_OUTSTANDING;
        }

        ArrearsBasedOn enumeration = TOTAL_OUTSTANDING;
        switch (value) {
            case 1:
                enumeration = PRINCIPAL_ONLY;
            break;
            case 2:
                enumeration = PRINCIPAL_AND_INTEREST_ONLY;
            break;
            case -1:
            default:
                enumeration = TOTAL_OUTSTANDING;
            break;
        }
        return enumeration;
    }

    public boolean isPrincipalOnly() {
        return this == PRINCIPAL_ONLY;
    }

    public boolean isPrincipalAndInterestOnly() {
        return this == PRINCIPAL_AND_INTEREST_ONLY;
    }

    public boolean isTotalOutstanding() {
        return this == TOTAL_OUTSTANDING;
    }
}
