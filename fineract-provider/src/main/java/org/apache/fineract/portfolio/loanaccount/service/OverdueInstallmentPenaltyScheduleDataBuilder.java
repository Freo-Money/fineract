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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.charge.service.ChargeUtils;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.loanschedule.data.OverdueLoanScheduleData;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OverdueInstallmentPenaltyScheduleDataBuilder {

    /**
     * @param existingPenaltyAmountForDefinition
     *            Total of already-applied active penalty {@link LoanCharge#amount()} for this overdue-installment
     *            charge definition (only populated when a cumulative cap exists; otherwise null).
     */
    public record OverdueInstallmentPenaltyScheduleData(Charge penaltyChargeDefinition, List<OverdueLoanScheduleData> overdueInstallments,
            BigDecimal existingPenaltyAmountForDefinition) {
    }

    private final ChargeUtils chargeUtils;

    public OverdueInstallmentPenaltyScheduleData build(final Loan loan, final LocalDate asOfDate) {
        if (loan == null || asOfDate == null || !loan.isOpen() || loan.getLoanProduct() == null
                || loan.getLoanProduct().getCharges() == null || loan.getRepaymentScheduleInstallments() == null) {
            return new OverdueInstallmentPenaltyScheduleData(null, List.of(), null);
        }

        final Optional<Charge> optPenaltyCharge = loan.getLoanProduct().getCharges().stream()
                .filter(e -> e != null && ChargeTimeType.OVERDUE_INSTALLMENT.getValue().equals(e.getChargeTimeType()) && e.isLoanCharge())
                .findFirst();
        if (optPenaltyCharge.isEmpty()) {
            return new OverdueInstallmentPenaltyScheduleData(null, List.of(), null);
        }
        final Charge penaltyCharge = optPenaltyCharge.get();

        // If cumulative penalty cap is already reached, there's nothing to build.
        final var maxCumulativePenaltyCap = penaltyCharge.getMaxCumulativePenaltyCap();
        BigDecimal existingPenaltyAmount = null;
        if (maxCumulativePenaltyCap != null) {
            existingPenaltyAmount = loan.getCharges() == null ? BigDecimal.ZERO
                    : loan.getCharges().stream().filter(Objects::nonNull).filter(LoanCharge::isPenaltyCharge).filter(LoanCharge::isActive)
                            .filter(c -> c.getCharge() != null && penaltyCharge.getId() != null
                                    && penaltyCharge.getId().equals(c.getCharge().getId()))
                            .map(LoanCharge::amount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (existingPenaltyAmount.compareTo(maxCumulativePenaltyCap) >= 0) {
                return new OverdueInstallmentPenaltyScheduleData(penaltyCharge, List.of(), existingPenaltyAmount);
            }
        }

        final long penaltyWaitPeriodDays = chargeUtils.retrievePenaltyWaitPeriodDays(penaltyCharge);

        // Note: this builder intentionally does NOT consult `backdate-penalties-enabled`. That flag gates the daily
        // COB tasklet (ApplyChargeToOverdueLoanInstallmentTasklet) from picking up older overdue installments and is
        // honoured at its own read path. All callers of this builder belong to the as-of-date alignment feature, where
        // the whole point is to bring penalties up to `asOfDate` regardless of how old the overdue installments are.
        final List<OverdueLoanScheduleData> list = new ArrayList<>();
        for (LoanRepaymentScheduleInstallment installment : loan.getRepaymentScheduleInstallments()) {
            if (installment == null || installment.isObligationsMet() || installment.isRecalculatedInterestComponent()
                    || installment.isAdditional() || installment.getDueDate() == null) {
                continue;
            }

            final boolean isPenaltyDue = installment.isOverdueOn(asOfDate.minusDays(penaltyWaitPeriodDays).plusDays(1));
            if (!isPenaltyDue) {
                continue;
            }

            list.add(new OverdueLoanScheduleData(loan.getId(), penaltyCharge.getId(),
                    DateUtils.DEFAULT_DATE_FORMATTER.format(installment.getDueDate()), penaltyCharge.getAmount(),
                    DateUtils.DEFAULT_DATE_FORMAT, Locale.ENGLISH.toLanguageTag(),
                    installment.getPrincipalOutstanding(loan.getCurrency()).getAmount(),
                    installment.getInterestOutstanding(loan.getCurrency()).getAmount(), installment.getInstallmentNumber()));
        }

        return new OverdueInstallmentPenaltyScheduleData(penaltyCharge, list, existingPenaltyAmount);
    }
}
