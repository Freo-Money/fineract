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

public enum ChargePercentagePeriod {

    DAILY(1, "chargePercentagePeriod.daily"), //
    SAME_AS_FREQUENCY(2, "chargePercentagePeriod.sameAsFrequency"); //

    private final Integer value;
    private final String code;

    ChargePercentagePeriod(final Integer value, final String code) {
        this.value = value;
        this.code = code;
    }

    public Integer getValue() {
        return this.value;
    }

    public String getCode() {
        return this.code;
    }

    public static ChargePercentagePeriod fromInt(final Integer chargePercentagePeriod) {
        ChargePercentagePeriod period = ChargePercentagePeriod.DAILY;
        switch (chargePercentagePeriod) {
            case 1:
                period = DAILY;
            break;
            case 2:
                period = SAME_AS_FREQUENCY;
            break;
            default:
                period = DAILY;
            break;
        }
        return period;
    }

    public boolean isDaily() {
        return this.value.equals(ChargePercentagePeriod.DAILY.getValue());
    }

    public boolean isSameAsFrequency() {
        return this.value.equals(ChargePercentagePeriod.SAME_AS_FREQUENCY.getValue());
    }
}
