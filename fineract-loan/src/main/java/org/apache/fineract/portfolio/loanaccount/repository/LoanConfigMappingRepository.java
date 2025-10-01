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
package org.apache.fineract.portfolio.loanaccount.repository;

import java.util.Optional;
import org.apache.fineract.portfolio.loanaccount.domain.LoanConfigMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository for LoanConfigMapping entity
 */
@Repository
public interface LoanConfigMappingRepository extends JpaRepository<LoanConfigMapping, Long>, JpaSpecificationExecutor<LoanConfigMapping> {

    /**
     * Find BPI configuration for a specific loan
     */
    Optional<LoanConfigMapping> findByLoanId(Long loanId);

    /**
     * Find BPI configuration for a specific loan
     */
    @Query("SELECT lc FROM LoanConfigMapping lc WHERE lc.loan.id = :loanId")
    Optional<LoanConfigMapping> findBpiConfigByLoanId(@Param("loanId") Long loanId);

    /**
     * Check if BPI configuration exists for a loan
     */
    boolean existsByLoanId(Long loanId);

    /**
     * Delete BPI configuration for a specific loan
     */
    void deleteByLoanId(Long loanId);
}
