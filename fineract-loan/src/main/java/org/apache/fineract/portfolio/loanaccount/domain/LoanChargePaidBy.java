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
package org.apache.fineract.portfolio.loanaccount.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeRoundingUtils;
import org.apache.fineract.portfolio.tax.domain.TaxComponent;
import org.apache.fineract.portfolio.tax.service.TaxUtils;

@Entity
@Table(name = "m_loan_charge_paid_by")
public class LoanChargePaidBy extends AbstractPersistableCustom<Long> {

    @ManyToOne
    @JoinColumn(name = "loan_transaction_id", nullable = false)
    private LoanTransaction loanTransaction;

    @ManyToOne(optional = false)
    @JoinColumn(name = "loan_charge_id", nullable = false)
    private LoanCharge loanCharge;

    @Column(name = "amount", scale = 6, precision = 19, nullable = false)
    private BigDecimal amount;

    @Column(name = "installment_number", nullable = true)
    private Integer installmentNumber;

    @OneToMany(cascade = CascadeType.ALL, mappedBy = "loanChargePaidBy", orphanRemoval = true, fetch = FetchType.LAZY)
    private List<LoanChargeTaxDetailsPaidBy> loanChargeTaxDetailsPaidBy = new ArrayList<>();

    protected LoanChargePaidBy() {

    }

    @Deprecated(since = "1.14.0", forRemoval = false)
    public LoanChargePaidBy(final LoanTransaction loanTransaction, final LoanCharge loanCharge, final BigDecimal amount,
            Integer installmentNumber) {
        this(loanTransaction, loanCharge, amount, installmentNumber, RoundingMode.HALF_EVEN);
    }

    public LoanChargePaidBy(final LoanTransaction loanTransaction, final LoanCharge loanCharge, final BigDecimal amount,
            Integer installmentNumber, final RoundingMode roundingMode) {
        this.loanTransaction = loanTransaction;
        this.loanCharge = loanCharge;
        this.amount = amount;
        this.installmentNumber = installmentNumber;
        createTaxDetailsPaidBy(loanTransaction.getTransactionDate(), roundingMode);
    }

    public List<LoanChargeTaxDetailsPaidBy> getLoanChargeTaxDetailsPaidBy() {
        return this.loanChargeTaxDetailsPaidBy;
    }

    private void createTaxDetailsPaidBy(LocalDate transactionDate, RoundingMode roundingMode) {
        if (this.loanCharge.getTaxGroup() != null && this.amount.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal incomeAmount = BigDecimal.ZERO;
            final int currencyScale = LoanChargeRoundingUtils.resolveDigitsAfterDecimal(this.loanCharge, this.loanCharge.getCharge(),
                    this.amount);
            Map<TaxComponent, BigDecimal> taxComponentsRaw = TaxUtils.splitTaxForLoanCharge(this.amount, transactionDate,
                    this.loanCharge.getTaxGroup().getTaxGroupMappings(), this.amount.scale(), roundingMode);
            Map<TaxComponent, BigDecimal> taxComponents = new HashMap<>(taxComponentsRaw.size());
            BigDecimal totalTaxAmount = BigDecimal.ZERO;
            for (Map.Entry<TaxComponent, BigDecimal> entry : taxComponentsRaw.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                BigDecimal rounded = entry.getValue().setScale(currencyScale, roundingMode);
                if (rounded.compareTo(BigDecimal.ZERO) > 0) {
                    taxComponents.put(entry.getKey(), rounded);
                    totalTaxAmount = totalTaxAmount.add(rounded);
                }
            }
            if (totalTaxAmount.compareTo(BigDecimal.ZERO) > 0) {
                incomeAmount = this.amount;
                for (Map.Entry<TaxComponent, BigDecimal> entry : taxComponents.entrySet()) {
                    this.loanChargeTaxDetailsPaidBy.add(new LoanChargeTaxDetailsPaidBy(this, entry.getKey(), entry.getValue()));
                    incomeAmount = incomeAmount.subtract(entry.getValue());
                }
            }
        }
    }

    public LoanTransaction getLoanTransaction() {
        return this.loanTransaction;
    }

    public LoanCharge getLoanCharge() {
        return this.loanCharge;
    }

    public BigDecimal getAmount() {
        return this.amount;
    }

    @Deprecated(since = "1.14.0", forRemoval = false)
    public void setAmount(final BigDecimal amount) {
        setAmount(amount, RoundingMode.HALF_EVEN);
    }

    public void setAmount(final BigDecimal amount, final RoundingMode roundingMode) {
        boolean amountChanged = this.amount == null || this.amount.compareTo(amount) != 0;
        this.amount = amount;
        if (amountChanged && this.loanTransaction != null) {
            // Amount changed, need to recalculate tax details
            this.loanChargeTaxDetailsPaidBy.clear();
            createTaxDetailsPaidBy(this.loanTransaction.getTransactionDate(), roundingMode);
        }
    }

    public Integer getInstallmentNumber() {
        return this.installmentNumber;
    }

    public void setInstallmentNumber(Integer installmentNumber) {
        this.installmentNumber = installmentNumber;
    }
}
