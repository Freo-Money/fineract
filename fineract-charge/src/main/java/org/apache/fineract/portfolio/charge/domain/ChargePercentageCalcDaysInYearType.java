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
package org.apache.fineract.portfolio.charge.domain;

import java.time.LocalDate;

public enum ChargePercentageCalcDaysInYearType {

    ACTUAL(1, "DaysInYearType.actual"), //
    DAYS_240(240, "DaysInYearType.days240"), //
    DAYS_360(360, "DaysInYearType.days360"), //
    DAYS_364(364, "DaysInYearType.days364"), //
    DAYS_365(365, "DaysInYearType.days365"); //

    private final Integer value;
    private final String code;

    ChargePercentageCalcDaysInYearType(final Integer value, final String code) {
        this.value = value;
        this.code = code;
    }

    public Integer getValue() {
        return this.value;
    }

    public String getCode() {
        return this.code;
    }

    public static ChargePercentageCalcDaysInYearType fromInt(final Integer value) {
        if (value == null) {
            return ACTUAL;
        }
        ChargePercentageCalcDaysInYearType type = null;
        switch (value) {
            case 1:
                type = ACTUAL;
            break;
            case 240:
                type = DAYS_240;
            break;
            case 360:
                type = DAYS_360;
            break;
            case 364:
                type = DAYS_364;
            break;
            case 365:
                type = DAYS_365;
            break;
            default:
                type = ACTUAL;
            break;
        }
        return type;
    }

    public Integer getNumberOfDays(final LocalDate referenceDate) {
        if (referenceDate == null) {
            return null;
        }
        return this == ACTUAL ? referenceDate.lengthOfYear() : this.getValue();
    }
}
