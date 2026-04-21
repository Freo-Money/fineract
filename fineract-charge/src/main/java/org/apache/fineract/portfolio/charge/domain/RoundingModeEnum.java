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

import java.math.RoundingMode;

public enum RoundingModeEnum {

    INVALID(-1, "chargeRoundingMode.invalid"), //
    UP(0, "chargeRoundingMode.up"), //
    DOWN(1, "chargeRoundingMode.down"), //
    CEILING(2, "chargeRoundingMode.ceiling"), //
    FLOOR(3, "chargeRoundingMode.floor"), //
    HALF_UP(4, "chargeRoundingMode.halfUp"), //
    HALF_DOWN(5, "chargeRoundingMode.halfDown"), //
    HALF_EVEN(6, "chargeRoundingMode.halfEven"); //

    private final Integer value;
    private final String code;

    RoundingModeEnum(final Integer value, final String code) {
        this.value = value;
        this.code = code;
    }

    public Integer getValue() {
        return this.value;
    }

    public String getCode() {
        return this.code;
    }

    public static RoundingModeEnum fromInt(final Integer value) {
        RoundingModeEnum roundingModeEnum = RoundingModeEnum.INVALID;
        if (value == null) {
            return roundingModeEnum;
        }
        switch (value) {
            case 0:
                roundingModeEnum = UP;
            break;
            case 1:
                roundingModeEnum = DOWN;
            break;
            case 2:
                roundingModeEnum = CEILING;
            break;
            case 3:
                roundingModeEnum = FLOOR;
            break;
            case 4:
                roundingModeEnum = HALF_UP;
            break;
            case 5:
                roundingModeEnum = HALF_DOWN;
            break;
            case 6:
                roundingModeEnum = HALF_EVEN;
            break;
        }
        return roundingModeEnum;
    }

    public RoundingMode toJavaMathRoundingMode() {
        return switch (this) {
            case UP -> RoundingMode.UP;
            case DOWN -> RoundingMode.DOWN;
            case CEILING -> RoundingMode.CEILING;
            case FLOOR -> RoundingMode.FLOOR;
            case HALF_UP -> RoundingMode.HALF_UP;
            case HALF_DOWN -> RoundingMode.HALF_DOWN;
            case HALF_EVEN -> RoundingMode.HALF_EVEN;
            case INVALID -> throw new IllegalStateException("INVALID rounding mode has no java.math.RoundingMode equivalent; "
                    + "callers must check isInvalid() and use a fallback before invoking toJavaMathRoundingMode()");
        };
    }

    public boolean isInvalid() {
        return this == INVALID;
    }
}
