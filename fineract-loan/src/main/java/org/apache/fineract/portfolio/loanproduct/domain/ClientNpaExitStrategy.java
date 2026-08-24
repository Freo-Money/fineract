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

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.ApiFacingEnum;

@Getter
@RequiredArgsConstructor
public enum ClientNpaExitStrategy implements ApiFacingEnum<ClientNpaExitStrategy> {

    ANY_NPA_LOAN_EXISTS("clientNpaExitStrategy.anyNpaLoanExists",
            "Exit when no active loan is independently NPA (arrears below NPA threshold may remain)"), //
    ALL_ARREARS_CLEARED("clientNpaExitStrategy.allArrearsCleared", "Exit when no active loan has outstanding arrears"), //
    STAY_NPA("clientNpaExitStrategy.stayNpa", "Never auto-exit; manual regularization only"); //

    private final String code;
    private final String humanReadableName;
}
