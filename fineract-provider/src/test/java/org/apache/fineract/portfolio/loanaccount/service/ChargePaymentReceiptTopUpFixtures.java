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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builders for charge-payment receipt top-up scenarios without JPA: real charges, transactions and receipts, with the
 * portions a replay would have stored set by hand.
 */
final class ChargePaymentReceiptTopUpFixtures {

    static final MonetaryCurrency INR = new MonetaryCurrency("INR", 2, 1);

    private ChargePaymentReceiptTopUpFixtures() {}

    /** {@link LoanTransaction} reads the business date on construction; give the test thread one. */
    static void setBusinessDate(final LocalDate businessDate) {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        final HashMap<BusinessDateType, LocalDate> dates = new HashMap<>();
        dates.put(BusinessDateType.BUSINESS_DATE, businessDate);
        ThreadLocalContextUtil.setBusinessDates(dates);
    }

    static void resetContext() {
        ThreadLocalContextUtil.reset();
    }

    static LoanCharge fee(final long id, final LocalDate dueDate, final String amount) {
        return charge(id, dueDate, amount, false);
    }

    static LoanCharge penalty(final long id, final LocalDate dueDate, final String amount) {
        return charge(id, dueDate, amount, true);
    }

    private static LoanCharge charge(final long id, final LocalDate dueDate, final String amount, final boolean penalty) {
        final LoanCharge charge = new LoanCharge();
        ReflectionTestUtils.setField(charge, "id", id);
        charge.setChargeTime(ChargeTimeType.SPECIFIED_DUE_DATE.getValue());
        charge.setChargeCalculation(ChargeCalculationType.FLAT.getValue());
        charge.setDueDate(dueDate);
        charge.setAmount(new BigDecimal(amount));
        charge.setAmountOutstanding(new BigDecimal(amount));
        charge.setAmountPaid(BigDecimal.ZERO);
        charge.setPenaltyCharge(penalty);
        charge.setActive(true);
        return charge;
    }

    static LoanTransaction transaction(final long id, final LoanTransactionType type, final Loan loan, final LocalDate date,
            final String amount) {
        final LoanTransaction transaction = LoanTransaction.loanPayment(loan, null, Money.of(INR, new BigDecimal(amount)), null, date,
                ExternalId.empty(), type);
        ReflectionTestUtils.setField(transaction, "id", id);
        return transaction;
    }

    /**
     * A charge payment as the replay left it: {@code charged} absorbed by fee (or penalty) and the rest of the amount
     * parked as overpayment.
     */
    static LoanTransaction chargePayment(final long id, final Loan loan, final LocalDate date, final String amount, final String charged,
            final boolean penalty) {
        final LoanTransaction payment = transaction(id, LoanTransactionType.CHARGE_PAYMENT, loan, date, amount);
        replayed(payment, new BigDecimal(charged), penalty);
        return payment;
    }

    /** Sets the portions a replay stores: {@code charged} to fee or penalty, the rest of the amount parked. */
    static void replayed(final LoanTransaction payment, final BigDecimal charged, final boolean penalty) {
        payment.resetDerivedComponents();
        payment.updateComponents(Money.zero(INR), Money.zero(INR), penalty ? Money.zero(INR) : Money.of(INR, charged),
                penalty ? Money.of(INR, charged) : Money.zero(INR));
        payment.setOverPayments(Money.of(INR, payment.getAmount().subtract(charged)));
    }

    static LoanChargePaidBy receipt(final long id, final LoanTransaction transaction, final LoanCharge charge, final String amount) {
        final LoanChargePaidBy receipt = new LoanChargePaidBy(transaction, charge, new BigDecimal(amount), null, RoundingMode.HALF_EVEN);
        ReflectionTestUtils.setField(receipt, "id", id);
        transaction.getLoanChargesPaid().add(receipt);
        return receipt;
    }
}
