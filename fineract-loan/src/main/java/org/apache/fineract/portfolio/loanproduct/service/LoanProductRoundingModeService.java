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
package org.apache.fineract.portfolio.loanproduct.service;

import com.google.gson.JsonObject;
import java.math.MathContext;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.portfolio.loanproduct.data.LoanProductRoundingModeData;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRoundingModeMapping;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRoundingModeMappingRepository;
import org.apache.fineract.portfolio.loanproduct.exception.InvalidLoanProductRoundingModeException;
import org.apache.fineract.portfolio.loanproduct.exception.LoanProductNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LoanProductRoundingModeService {

    private static final String ROUNDING_MODE = "roundingMode";
    private static final String INSTALLMENT_ROUNDING_MODE = "installmentRoundingMode";
    private static final String TAX_ROUNDING_MODE = "taxRoundingMode";
    private static final String ADJUSTED_ROUNDING_MODE = "adjustedRoundingMode";

    private final LoanProductRepository loanProductRepository;
    private final LoanProductRoundingModeMappingRepository mappingRepository;
    private final ConfigurationDomainService configurationDomainService;
    private final FromJsonHelper fromJsonHelper;

    public LoanProductRoundingModeData retrieve(Long productId) {
        loanProductRepository.findById(productId).orElseThrow(() -> new LoanProductNotFoundException(productId));
        Integer effectiveRoundingMode = resolveRoundingMode(productId);
        return mappingRepository.findByLoanProductId(productId)
                .map(mapping -> new LoanProductRoundingModeData(productId, mapping.getRoundingMode(), mapping.getInstallmentRoundingMode(),
                        mapping.getTaxRoundingMode(), mapping.getAdjustedRoundingMode(), effectiveRoundingMode,
                        mapping.getLastModifiedDate().orElse(null)))
                .orElseGet(() -> new LoanProductRoundingModeData(productId, null, null, null, null, effectiveRoundingMode, null));
    }

    public LoanProductRoundingModeData upsert(Long productId, String json) {
        JsonObject jsonObject = fromJsonHelper.parse(json).getAsJsonObject();
        LoanProduct product = loanProductRepository.findById(productId).orElseThrow(() -> new LoanProductNotFoundException(productId));
        LoanProductRoundingModeMapping mapping = mappingRepository.findByLoanProductId(productId)
                .orElseGet(() -> LoanProductRoundingModeMapping.of(product));

        if (fromJsonHelper.parameterExists(ROUNDING_MODE, jsonObject)) {
            Integer roundingMode = fromJsonHelper.extractIntegerWithLocaleNamed(ROUNDING_MODE, jsonObject);
            validateRoundingMode(roundingMode, ROUNDING_MODE);
            mapping.setRoundingMode(roundingMode);
        }
        if (fromJsonHelper.parameterExists(INSTALLMENT_ROUNDING_MODE, jsonObject)) {
            Integer installmentRoundingMode = fromJsonHelper.extractIntegerWithLocaleNamed(INSTALLMENT_ROUNDING_MODE, jsonObject);
            validateRoundingMode(installmentRoundingMode, INSTALLMENT_ROUNDING_MODE);
            mapping.setInstallmentRoundingMode(installmentRoundingMode);
        }
        if (fromJsonHelper.parameterExists(TAX_ROUNDING_MODE, jsonObject)) {
            Integer taxRoundingMode = fromJsonHelper.extractIntegerWithLocaleNamed(TAX_ROUNDING_MODE, jsonObject);
            validateRoundingMode(taxRoundingMode, TAX_ROUNDING_MODE);
            mapping.setTaxRoundingMode(taxRoundingMode);
        }
        if (fromJsonHelper.parameterExists(ADJUSTED_ROUNDING_MODE, jsonObject)) {
            Integer adjustedRoundingMode = fromJsonHelper.extractIntegerWithLocaleNamed(ADJUSTED_ROUNDING_MODE, jsonObject);
            validateRoundingMode(adjustedRoundingMode, ADJUSTED_ROUNDING_MODE);
            mapping.setAdjustedRoundingMode(adjustedRoundingMode);
        }

        mapping = mappingRepository.save(mapping);
        Integer effectiveRoundingMode = mapping.getRoundingMode() != null ? mapping.getRoundingMode()
                : configurationDomainService.getRoundingMode();
        return new LoanProductRoundingModeData(productId, mapping.getRoundingMode(), mapping.getInstallmentRoundingMode(),
                mapping.getTaxRoundingMode(), mapping.getAdjustedRoundingMode(), effectiveRoundingMode,
                mapping.getLastModifiedDate().orElse(null));
    }

    public Integer resolveRoundingMode(Long productId) {
        if (productId == null) {
            return configurationDomainService.getRoundingMode();
        }
        return mappingRepository.findByLoanProductId(productId).map(LoanProductRoundingModeMapping::getRoundingMode)
                .filter(this::isValidRoundingMode).orElse(configurationDomainService.getRoundingMode());
    }

    public MathContext resolveMathContext(Long productId) {
        return MoneyHelper.createMathContext(RoundingMode.valueOf(resolveRoundingMode(productId)));
    }

    public RoundingMode resolveTaxRoundingMode(Long productId) {
        RoundingMode globalTaxRoundingMode = configurationDomainService.getTaxRoundingMode();
        if (productId == null) {
            return globalTaxRoundingMode;
        }
        return mappingRepository.findByLoanProductId(productId).map(mapping -> {
            if (mapping.getTaxRoundingMode() != null && isValidRoundingMode(mapping.getTaxRoundingMode())) {
                return RoundingMode.valueOf(mapping.getTaxRoundingMode());
            }
            if (mapping.getRoundingMode() != null && isValidRoundingMode(mapping.getRoundingMode())) {
                return RoundingMode.valueOf(mapping.getRoundingMode());
            }
            return globalTaxRoundingMode;
        }).orElse(globalTaxRoundingMode);
    }

    public LoanProductRoundingModeData resolveAll(Long productId) {
        Integer globalRoundingMode = configurationDomainService.getRoundingMode();
        if (productId == null) {
            return new LoanProductRoundingModeData(null, globalRoundingMode, globalRoundingMode, globalRoundingMode, globalRoundingMode,
                    globalRoundingMode, null);
        }
        return mappingRepository.findByLoanProductId(productId).map(mapping -> {
            Integer effectiveRoundingMode = (mapping.getRoundingMode() != null && isValidRoundingMode(mapping.getRoundingMode()))
                    ? mapping.getRoundingMode()
                    : globalRoundingMode;
            return new LoanProductRoundingModeData(productId, effectiveRoundingMode,
                    resolveWithFallback(mapping.getInstallmentRoundingMode(), effectiveRoundingMode),
                    resolveWithFallback(mapping.getTaxRoundingMode(), effectiveRoundingMode),
                    resolveWithFallback(mapping.getAdjustedRoundingMode(), effectiveRoundingMode), effectiveRoundingMode,
                    mapping.getLastModifiedDate().orElse(null));
        }).orElseGet(() -> new LoanProductRoundingModeData(productId, globalRoundingMode, globalRoundingMode, globalRoundingMode,
                globalRoundingMode, globalRoundingMode, null));
    }

    private Integer resolveWithFallback(Integer value, Integer fallback) {
        return (value != null && isValidRoundingMode(value)) ? value : fallback;
    }

    private void validateRoundingMode(Integer value, String parameterName) {
        if (value != null && !isValidRoundingMode(value)) {
            throw new InvalidLoanProductRoundingModeException(parameterName, value);
        }
    }

    private boolean isValidRoundingMode(Integer value) {
        return value != null && value >= 0 && value <= 6;
    }
}
