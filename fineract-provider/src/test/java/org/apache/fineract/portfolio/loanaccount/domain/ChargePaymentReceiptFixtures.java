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

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.tax.domain.TaxComponent;
import org.apache.fineract.portfolio.tax.domain.TaxGroup;
import org.apache.fineract.portfolio.tax.domain.TaxGroupMappings;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builders for charge-payment receipt scenarios (prod shapes 386607 / 444453) without JPA. Lives in the domain package
 * because {@link LoanCharge} has no public no-arg constructor.
 */
public final class ChargePaymentReceiptFixtures {

    public static final MonetaryCurrency INR = new MonetaryCurrency("INR", 0, 1);
    /** The production currency of a taxed charge: rupees with paise, where a tax split can land off by a paisa. */
    public static final MonetaryCurrency INR_PAISE = new MonetaryCurrency("INR", 2, 1);

    private ChargePaymentReceiptFixtures() {}

    /** {@link LoanTransaction} reads the business date on construction; give the test thread one. */
    public static void setBusinessDate(final LocalDate businessDate) {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        final HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
        dates.put(BusinessDateType.BUSINESS_DATE, businessDate);
        ThreadLocalContextUtil.setBusinessDates(dates);
    }

    public static void resetContext() {
        ThreadLocalContextUtil.reset();
    }

    public static LoanCharge penaltyCharge(final long id, final LocalDate dueDate, final long amount) {
        return charge(id, dueDate, amount, true);
    }

    public static LoanCharge feeCharge(final long id, final LocalDate dueDate, final long amount) {
        return charge(id, dueDate, amount, false);
    }

    private static LoanCharge charge(final long id, final LocalDate dueDate, final long amount, final boolean penalty) {
        final LoanCharge charge = new LoanCharge();
        ReflectionTestUtils.setField(charge, "id", id);
        charge.setChargeTime(ChargeTimeType.SPECIFIED_DUE_DATE.getValue());
        charge.setChargeCalculation(ChargeCalculationType.FLAT.getValue());
        charge.setDueDate(dueDate);
        charge.setAmount(BigDecimal.valueOf(amount));
        charge.setAmountOutstanding(BigDecimal.valueOf(amount));
        charge.setAmountPaid(BigDecimal.ZERO);
        charge.setPenaltyCharge(penalty);
        charge.setActive(true);
        return charge;
    }

    /**
     * An instalment-fee charge carrying one row per installment, each for {@code amountPerInstallment}. The
     * charge-level amount is the total, which is exactly why an allocation must size itself by the installment row and
     * not by the charge.
     */
    public static LoanCharge instalmentFeeCharge(final long id, final long amountPerInstallment,
            final List<LoanRepaymentScheduleInstallment> installments) {
        final LoanCharge charge = new LoanCharge();
        ReflectionTestUtils.setField(charge, "id", id);
        charge.setChargeTime(ChargeTimeType.INSTALMENT_FEE.getValue());
        charge.setChargeCalculation(ChargeCalculationType.FLAT.getValue());
        // the per-installment amount the schedule wrapper reads for a flat instalment fee
        ReflectionTestUtils.setField(charge, "amountOrPercentage", BigDecimal.valueOf(amountPerInstallment));
        charge.setAmount(BigDecimal.valueOf(amountPerInstallment * installments.size()));
        charge.setAmountOutstanding(BigDecimal.valueOf(amountPerInstallment * installments.size()));
        charge.setAmountPaid(BigDecimal.ZERO);
        charge.setPenaltyCharge(false);
        charge.setActive(true);
        final Set<LoanInstallmentCharge> rows = new LinkedHashSet<>();
        for (final LoanRepaymentScheduleInstallment installment : installments) {
            rows.add(new LoanInstallmentCharge(BigDecimal.valueOf(amountPerInstallment), charge, installment));
        }
        ReflectionTestUtils.setField(charge, "loanInstallmentCharge", rows);
        return charge;
    }

    public static LoanChargePaidBy receipt(final long id, final LoanTransaction transaction, final LoanCharge charge, final long amount,
            final Integer installmentNumber) {
        final LoanChargePaidBy receipt = new LoanChargePaidBy(transaction, charge, BigDecimal.valueOf(amount), installmentNumber,
                RoundingMode.HALF_EVEN);
        ReflectionTestUtils.setField(receipt, "id", id);
        transaction.getLoanChargesPaid().add(receipt);
        return receipt;
    }

    public static LoanTransaction chargePayment(final long id, final Loan loan, final Office office, final LocalDate date,
            final long amount) {
        final LoanTransaction transaction = LoanTransaction.loanPayment(loan, office, Money.of(INR, BigDecimal.valueOf(amount)), null, date,
                ExternalId.empty(), LoanTransactionType.CHARGE_PAYMENT);
        ReflectionTestUtils.setField(transaction, "id", id);
        return transaction;
    }

    public static LoanChargePaidBy receipt(final long id, final LoanTransaction transaction, final LoanCharge charge, final long amount) {
        final LoanChargePaidBy receipt = new LoanChargePaidBy(transaction, charge, BigDecimal.valueOf(amount), null,
                RoundingMode.HALF_EVEN);
        ReflectionTestUtils.setField(receipt, "id", id);
        transaction.getLoanChargesPaid().add(receipt);
        return receipt;
    }

    /**
     * A GST group of two equal components (CGST + SGST), as the live charges carry. The start date is the component's
     * as well as the mapping's, and both must precede the transaction date for the split to apply.
     */
    public static TaxGroup gstGroup(final String name, final BigDecimal componentPercentage, final LocalDate startDate) {
        final Set<TaxGroupMappings> mappings = new LinkedHashSet<>();
        mappings.add(TaxGroupMappings.createTaxGroupMappings(taxComponent("CGST", componentPercentage, startDate), startDate));
        mappings.add(TaxGroupMappings.createTaxGroupMappings(taxComponent("SGST", componentPercentage, startDate), startDate));
        return TaxGroup.createTaxGroup(name, mappings);
    }

    private static TaxComponent taxComponent(final String name, final BigDecimal percentage, final LocalDate startDate) {
        try {
            final Constructor<TaxComponent> constructor = TaxComponent.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            final TaxComponent component = constructor.newInstance();
            ReflectionTestUtils.setField(component, "name", name);
            ReflectionTestUtils.setField(component, "percentage", percentage);
            ReflectionTestUtils.setField(component, "startDate", startDate);
            return component;
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("could not build a TaxComponent fixture", e);
        }
    }

    /**
     * A penalty whose amount is tax inclusive and which carries a tax group, so every write of a receipt amount
     * re-derives the receipt's tax rows.
     */
    public static LoanCharge taxedPenaltyCharge(final long id, final LocalDate dueDate, final BigDecimal amount, final Loan loan,
            final TaxGroup taxGroup) {
        final LoanCharge charge = new LoanCharge();
        ReflectionTestUtils.setField(charge, "id", id);
        charge.setChargeTime(ChargeTimeType.SPECIFIED_DUE_DATE.getValue());
        charge.setChargeCalculation(ChargeCalculationType.FLAT.getValue());
        charge.setDueDate(dueDate);
        charge.setAmount(amount);
        charge.setAmountOutstanding(amount);
        charge.setAmountPaid(BigDecimal.ZERO);
        charge.setPenaltyCharge(true);
        charge.setActive(true);
        charge.setLoan(loan);
        charge.setTaxGroup(taxGroup);
        return charge;
    }

    public static LoanTransaction chargePayment(final long id, final MonetaryCurrency currency, final Loan loan, final Office office,
            final LocalDate date, final BigDecimal amount) {
        final LoanTransaction transaction = LoanTransaction.loanPayment(loan, office, Money.of(currency, amount), null, date,
                ExternalId.empty(), LoanTransactionType.CHARGE_PAYMENT);
        ReflectionTestUtils.setField(transaction, "id", id);
        return transaction;
    }

    public static LoanChargePaidBy receipt(final long id, final LoanTransaction transaction, final LoanCharge charge,
            final BigDecimal amount) {
        return receipt(id, transaction, charge, amount, RoundingMode.HALF_EVEN);
    }

    /** The rounding mode matters only for a taxed charge: it rounds the receipt's tax components. */
    public static LoanChargePaidBy receipt(final long id, final LoanTransaction transaction, final LoanCharge charge,
            final BigDecimal amount, final RoundingMode taxRoundingMode) {
        final LoanChargePaidBy receipt = new LoanChargePaidBy(transaction, charge, amount, null, taxRoundingMode);
        ReflectionTestUtils.setField(receipt, "id", id);
        transaction.getLoanChargesPaid().add(receipt);
        return receipt;
    }

    public static LoanRepaymentScheduleInstallment installment(final Loan loan, final int number, final LocalDate fromDate,
            final LocalDate dueDate, final long principal, final long interest) {
        return new LoanRepaymentScheduleInstallment(loan, number, fromDate, dueDate, BigDecimal.valueOf(principal),
                BigDecimal.valueOf(interest), BigDecimal.ZERO, BigDecimal.ZERO, false, null, BigDecimal.ZERO);
    }
}
