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

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class TransactionMetaData {

    private static final FromJsonHelper JSON_HELPER = new FromJsonHelper();

    private String transactionSubType;

    public String serialize() {
        return JSON_HELPER.toJson(this);
    }

    public static TransactionMetaData deserialize(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return JSON_HELPER.fromJson(json, TransactionMetaData.class);
        } catch (Exception e) {
            return null;
        }
    }
}
