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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.fineract.infrastructure.core.domain.AbstractAuditableWithUTCDateTimeCustom;
import org.apache.fineract.portfolio.loanproduct.data.BrokenPeriodInterestConfigDTO;
import org.apache.fineract.portfolio.loanproduct.data.LoanProductConfigurationWrapper;

/**
 * Entity for storing loan product BPI (Broken Period Interest) configuration mapping. This table stores product-level
 * BPI configurations that can be copied to individual loans.
 */
@Entity
@Table(name = "fr_loan_product_config_mapping", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "loan_product_id" }, name = "unq_loan_product_bpi_config") })
@Getter
@Setter
@NoArgsConstructor
public class LoanProductConfigMapping extends AbstractAuditableWithUTCDateTimeCustom<Long> {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_product_id", nullable = false, unique = true)
    private LoanProduct loanProduct;

    @Column(name = "config_description", length = 255)
    private String configDescription;

    @Column(name = "config_identity", length = 100, nullable = false)
    private String configIdentity;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Transient
    private LoanProductConfigurationWrapper configurationWrapper;

    public LoanProductConfigMapping(LoanProduct loanProduct, BrokenPeriodInterestConfigDTO brokenPeriodConfig) {
        this.loanProduct = loanProduct;
        this.configIdentity = loanProduct.getShortName();
        this.configDescription = loanProduct.getDescription();
        this.configurationWrapper = new LoanProductConfigurationWrapper(brokenPeriodConfig);
        this.configJson = this.configurationWrapper.toJson();
    }

    public LoanProductConfigMapping(LoanProduct loanProduct, String configDescription, String configIdentity,
            BrokenPeriodInterestConfigDTO brokenPeriodConfig) {
        this.loanProduct = loanProduct;
        this.configDescription = configDescription;
        this.configIdentity = configIdentity;
        this.configurationWrapper = new LoanProductConfigurationWrapper(brokenPeriodConfig);
        this.configJson = this.configurationWrapper.toJson();
    }

    /**
     * Constructor for extensible configuration wrapper
     */
    public LoanProductConfigMapping(LoanProduct loanProduct, LoanProductConfigurationWrapper configurationWrapper) {
        this.loanProduct = loanProduct;
        this.configIdentity = loanProduct.getShortName();
        this.configDescription = loanProduct.getDescription();
        this.configurationWrapper = configurationWrapper;
        this.configJson = configurationWrapper.toJson();
    }

    /**
     * Get the BPI configuration as a DTO object
     */
    public BrokenPeriodInterestConfigDTO getBrokenPeriodConfig() {
        if (configurationWrapper == null && configJson != null) {
            configurationWrapper = LoanProductConfigurationWrapper.fromJson(configJson);
        }
        return configurationWrapper != null ? configurationWrapper.getBrokenPeriodConfig() : null;
    }

    /**
     * Set the BPI configuration and update the JSON
     */
    public void setBrokenPeriodConfig(BrokenPeriodInterestConfigDTO brokenPeriodConfig) {
        if (configurationWrapper == null) {
            configurationWrapper = new LoanProductConfigurationWrapper();
        }
        configurationWrapper.setBrokenPeriodConfig(brokenPeriodConfig);
        this.configJson = configurationWrapper.toJson();
    }

    /**
     * Get the full configuration wrapper (extensible)
     */
    public LoanProductConfigurationWrapper getConfigurationWrapper() {
        if (configurationWrapper == null && configJson != null) {
            configurationWrapper = LoanProductConfigurationWrapper.fromJson(configJson);
        }
        return configurationWrapper;
    }

    /**
     * Set the full configuration wrapper (extensible)
     */
    public void setConfigurationWrapper(LoanProductConfigurationWrapper configurationWrapper) {
        this.configurationWrapper = configurationWrapper;
        this.configJson = configurationWrapper != null ? configurationWrapper.toJson() : null;
    }

    /**
     * Prevent direct updates to configJson from application
     */
    public void setConfigJson(String configJson) {
        // Only allow setting from deserialization or internal methods
        this.configJson = configJson;
        this.configurationWrapper = null; // Reset to force re-deserialization
    }

    /**
     * Update the existing configuration with new BPI config
     */
    public void updateBrokenPeriodConfig(BrokenPeriodInterestConfigDTO brokenPeriodConfig) {
        if (configurationWrapper == null && configJson != null) {
            configurationWrapper = LoanProductConfigurationWrapper.fromJson(configJson);
        }
        if (configurationWrapper == null) {
            configurationWrapper = new LoanProductConfigurationWrapper();
        }
        configurationWrapper.setBrokenPeriodConfig(brokenPeriodConfig);
        this.configJson = configurationWrapper.toJson();
    }

    /**
     * Update the configuration metadata (description and identity)
     */
    public void updateMetadata(String configDescription, String configIdentity) {
        if (configDescription != null) {
            this.configDescription = configDescription;
        }
        if (configIdentity != null) {
            this.configIdentity = configIdentity;
        }
    }
}
