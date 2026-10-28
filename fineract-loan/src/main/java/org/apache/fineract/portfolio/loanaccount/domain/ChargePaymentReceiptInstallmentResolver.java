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

import java.util.List;

/**
 * Resolves the installment a charge-payment receipt ({@link LoanChargePaidBy}) replays into: by the receipt's
 * installment number when present (instalment fees), otherwise by the period the charge is due in. Shared by the
 * transaction processor's replay and the receipt-repair planner so both agree on the mapping; returns null when neither
 * rule matches (the processor then applies its own latest-due fallback).
 */
public final class ChargePaymentReceiptInstallmentResolver {

    private ChargePaymentReceiptInstallmentResolver() {}

    public static LoanRepaymentScheduleInstallment resolve(final List<LoanRepaymentScheduleInstallment> installments,
            final int firstNormalInstallmentNumber, final LoanChargePaidBy receipt) {
        final LoanRepaymentScheduleInstallment byNumber = byInstallmentNumber(installments, receipt.getInstallmentNumber());
        if (byNumber != null) {
            return byNumber;
        }
        return receipt.getLoanCharge() == null ? null : byDuePeriod(installments, firstNormalInstallmentNumber, receipt.getLoanCharge());
    }

    public static LoanRepaymentScheduleInstallment byInstallmentNumber(final List<LoanRepaymentScheduleInstallment> installments,
            final Integer installmentNumber) {
        if (installmentNumber == null) {
            return null;
        }
        for (final LoanRepaymentScheduleInstallment installment : installments) {
            if (installmentNumber.equals(installment.getInstallmentNumber())) {
                return installment;
            }
        }
        return null;
    }

    public static LoanRepaymentScheduleInstallment byDuePeriod(final List<LoanRepaymentScheduleInstallment> installments,
            final int firstNormalInstallmentNumber, final LoanCharge charge) {
        for (final LoanRepaymentScheduleInstallment installment : installments) {
            final boolean isFirstPeriod = installment.getInstallmentNumber().equals(firstNormalInstallmentNumber);
            if (charge.isDueInPeriod(installment.getFromDate(), installment.getDueDate(), isFirstPeriod)) {
                return installment;
            }
        }
        return null;
    }
}
