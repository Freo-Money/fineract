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
package org.apache.fineract.portfolio.loanaccount.mapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.config.MapstructMapperConfig;
import org.apache.fineract.portfolio.loanaccount.data.LoanChargePaidByData;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeTaxDetailsPaidBy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(config = MapstructMapperConfig.class)
public interface LoanChargePaidByMapper {

    @Mapping(target = "transactionId", source = "source.loanTransaction.id")
    @Mapping(target = "chargeId", source = "source.loanCharge.id")
    @Mapping(target = "name", source = "source.loanCharge.charge.name")
    @Mapping(target = "taxDetails", expression = "java(mapTaxDetails(source))")
    LoanChargePaidByData map(LoanChargePaidBy source);

    List<LoanChargePaidByData> map(List<LoanChargePaidBy> sources);

    default List<Map<String, Object>> mapTaxDetails(LoanChargePaidBy source) {
        List<LoanChargeTaxDetailsPaidBy> taxDetailsPaidBy = source.getLoanChargeTaxDetailsPaidBy();
        if (taxDetailsPaidBy == null || taxDetailsPaidBy.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> taxDetails = new ArrayList<>(taxDetailsPaidBy.size());
        for (LoanChargeTaxDetailsPaidBy taxDetailPaidBy : taxDetailsPaidBy) {
            Map<String, Object> taxDetail = new LinkedHashMap<>();
            taxDetail.put("amount", taxDetailPaidBy.getAmount());
            taxDetail.put("taxComponentId", taxDetailPaidBy.getTaxComponent().getId());
            if (taxDetailPaidBy.getTaxComponent().getDebitAcount() != null) {
                taxDetail.put("debitAccountId", taxDetailPaidBy.getTaxComponent().getDebitAcount().getId());
            }
            if (taxDetailPaidBy.getTaxComponent().getCreditAcount() != null) {
                taxDetail.put("creditAccountId", taxDetailPaidBy.getTaxComponent().getCreditAcount().getId());
            }
            taxDetails.add(taxDetail);
        }
        return taxDetails;
    }

}
