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
package org.apache.fineract.portfolio.loanproduct.repository;

import java.util.Optional;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductConfigMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for LoanProductConfigMapping entity
 */
@Repository
public interface LoanProductConfigMappingRepository
        extends JpaRepository<LoanProductConfigMapping, Long>, JpaSpecificationExecutor<LoanProductConfigMapping> {

    /**
     * Find BPI configuration for a specific loan product by entity
     */
    Optional<LoanProductConfigMapping> findByLoanProduct(LoanProduct loanProduct);

    /**
     * Find BPI configuration for a specific loan product
     */
    Optional<LoanProductConfigMapping> findByLoanProductId(Long loanProductId);

    /**
     * Find BPI configuration for a specific loan product
     */
    @Query("SELECT lpc FROM LoanProductConfigMapping lpc WHERE lpc.loanProduct.id = :loanProductId")
    Optional<LoanProductConfigMapping> findBpiConfigByLoanProductId(@Param("loanProductId") Long loanProductId);

    /**
     * Check if BPI configuration exists for a loan product
     */
    boolean existsByLoanProductId(Long loanProductId);

    /**
     * Delete BPI configuration for a specific loan product
     */
    void deleteByLoanProductId(Long loanProductId);
}
