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
package org.apache.fineract.portfolio.loanaccount.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.JdbcSupport;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.portfolio.loanaccount.data.LoanTransactionBasicData;
import org.apache.fineract.portfolio.loanaccount.data.LoanTransactionEnumData;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.exception.LoanTransactionNotFoundException;
import org.apache.fineract.portfolio.loanproduct.service.LoanEnumerations;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LoanTransactionReadService {

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseSpecificSQLGenerator sqlGenerator;

    @PersistenceContext
    private EntityManager entityManager;

    public List<LoanTransaction> fetchLoanTransactionsByType(final Long loanId, final String externalId,
            final LoanTransactionType transactionType) {
        final List<LoanTransactionType> transactionTypes = new ArrayList<>();
        transactionTypes.add(transactionType);
        return fetchLoanTransactionsByTypes(loanId, externalId, transactionTypes);
    }

    public List<LoanTransaction> fetchLoanTransactionsByTypes(final Long loanId, final String externalId,
            final List<LoanTransactionType> transactionTypes) {

        final CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        final CriteriaQuery<LoanTransaction> query = cb.createQuery(LoanTransaction.class);

        final Root<LoanTransaction> root = query.from(LoanTransaction.class);
        root.fetch("loan", JoinType.INNER);
        final Path<Loan> loan = root.join("loan", JoinType.INNER);

        Predicate loanPredicate = cb.equal(loan.get("id"), loanId);
        if (externalId != null) {
            loanPredicate = cb.equal(loan.get("externalId"), externalId);
        }

        query.select(root)
                .where(cb.and(loanPredicate, root.get("typeOf").in(transactionTypes), cb.equal(root.get("reversed"), Boolean.FALSE)));

        final List<Order> orders = new ArrayList<>();
        orders.add(cb.desc(root.get("dateOf")));
        orders.add(cb.desc(root.get("createdDate")));
        orders.add(cb.desc(root.get("id")));
        query.orderBy(orders);

        final TypedQuery<LoanTransaction> queryToExecute = entityManager.createQuery(query);
        return queryToExecute.getResultList();
    }

    public LoanTransactionBasicData retrieveTransactionByExternalId(final String externalIdStr) {
        try {
            final LoanTransactionBasicDataMapper rm = new LoanTransactionBasicDataMapper(sqlGenerator);
            final String sql = "SELECT " + rm.schema() + " WHERE tr.external_id = ?";
            return this.jdbcTemplate.queryForObject(sql, rm, externalIdStr);
        } catch (EmptyResultDataAccessException e) {
            ExternalId externalId = ExternalIdFactory.produce(externalIdStr);
            throw new LoanTransactionNotFoundException(externalId, e);
        }
    }

    private static final class LoanTransactionBasicDataMapper implements RowMapper<LoanTransactionBasicData> {

        private final DatabaseSpecificSQLGenerator sqlGenerator;

        LoanTransactionBasicDataMapper(DatabaseSpecificSQLGenerator sqlGenerator) {
            this.sqlGenerator = sqlGenerator;
        }

        public String schema() {
            return " tr.id as id, tr.transaction_type_enum as transactionType, tr.transaction_date as " + sqlGenerator.escape("date")
                    + ", tr.amount as total, tr.principal_portion_derived as principal, tr.interest_portion_derived as interest, "
                    + " tr.fee_charges_portion_derived as fees, tr.penalty_charges_portion_derived as penalties, tr.loan_id as loanId, "
                    + " tr.overpayment_portion_derived as overpayment, tr.excess_payment_amount as excessPaymentPortion, "
                    + " tr.unrecognized_income_portion as unrecognizedIncome, "
                    + " tr.manually_adjusted_or_reversed as manuallyReversed, tr.external_id as externalId "
                    + " from m_loan_transaction tr ";
        }

        @Override
        public LoanTransactionBasicData mapRow(final ResultSet rs, @SuppressWarnings("unused") final int rowNum) throws SQLException {

            final Long id = rs.getLong("id");
            final Long loanId = rs.getLong("loanId");
            final int transactionTypeInt = JdbcSupport.getInteger(rs, "transactionType");
            final LoanTransactionEnumData transactionType = LoanEnumerations.transactionType(transactionTypeInt);
            final boolean manuallyReversed = rs.getBoolean("manuallyReversed");
            final LocalDate date = JdbcSupport.getLocalDate(rs, "date");
            final BigDecimal totalAmount = JdbcSupport.getBigDecimalDefaultToZeroIfNull(rs, "total");
            final BigDecimal principalPortion = JdbcSupport.getBigDecimalDefaultToZeroIfNull(rs, "principal");
            final BigDecimal interestPortion = JdbcSupport.getBigDecimalDefaultToZeroIfNull(rs, "interest");
            final BigDecimal feeChargesPortion = JdbcSupport.getBigDecimalDefaultToZeroIfNull(rs, "fees");
            final BigDecimal penaltyChargesPortion = JdbcSupport.getBigDecimalDefaultToZeroIfNull(rs, "penalties");
            final BigDecimal overPaymentPortion = JdbcSupport.getBigDecimalDefaultToZeroIfNull(rs, "overpayment");
            final BigDecimal excessPaymentPortion = JdbcSupport.getBigDecimalDefaultToZeroIfNull(rs, "excessPaymentPortion");
            final BigDecimal unrecognizedIncomePortion = JdbcSupport.getBigDecimalDefaultToZeroIfNull(rs, "unrecognizedIncome");
            final String externalIdStr = rs.getString("externalId");
            return new LoanTransactionBasicData(id, loanId, transactionType, date, totalAmount, principalPortion, interestPortion,
                    feeChargesPortion, penaltyChargesPortion, overPaymentPortion, excessPaymentPortion, unrecognizedIncomePortion,
                    externalIdStr, manuallyReversed);
        }
    }

}
