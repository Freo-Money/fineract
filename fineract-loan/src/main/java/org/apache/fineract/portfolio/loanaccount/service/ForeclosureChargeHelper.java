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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.springframework.stereotype.Component;

@Component
public class ForeclosureChargeHelper {

    public List<Charge> getForeclosureChargeDefinitions(Loan loan) {
        if (loan.loanProduct() == null || loan.loanProduct().getCharges() == null) {
            return Collections.emptyList();
        }
        return loan.loanProduct().getCharges().stream().filter(Objects::nonNull).filter(Charge::isActive)
                .filter(charge -> ChargeTimeType.fromInt(charge.getChargeTimeType()).equals(ChargeTimeType.FORECLOSURE)).toList();
    }

    public List<LoanCharge> createForeclosureChargesForLoan(Loan loan) {
        List<Charge> chargeDefinitions = getForeclosureChargeDefinitions(loan);
        if (chargeDefinitions.isEmpty()) {
            return Collections.emptyList();
        }

        if (loan.getLoanCharges() == null) {
            loan.setCharges(new HashSet<>());
        }

        return chargeDefinitions.stream().map(chargeDefinition -> {
            LoanCharge loanCharge = createForeclosureCharge(loan, chargeDefinition, loan.getPrincipal().getAmount());
            loan.getLoanCharges().add(loanCharge);
            return loanCharge;
        }).toList();
    }

    public BigDecimal extractForeclosurePercentage(LoanCharge charge) {
        BigDecimal percentage = charge.getPercentage();
        if (percentage != null) {
            return percentage;
        }
        if (charge.getChargeCalculation().isPercentageBased()) {
            BigDecimal amountOrPercentage = charge.amountOrPercentage();
            if (amountOrPercentage != null) {
                return amountOrPercentage;
            }
        }
        return null;
    }

    public BigDecimal determineForeclosureChargePercentageFromProduct(Loan loan) {
        return getForeclosureChargeDefinitions(loan).stream().map(this::extractForeclosurePercentage).filter(Objects::nonNull).findFirst()
                .orElse(null);
    }

    public Money calculateForeclosureChargeAmountForDefinition(final Loan loan, final Charge chargeDefinition,
            final MonetaryCurrency currency, final Money principalOutstanding, final BigDecimal foreclosureChargePercentage) {
        ChargeCalculationType calculationType = ChargeCalculationType.fromInt(chargeDefinition.getChargeCalculation());
        if (calculationType.isPercentageBased()) {
            BigDecimal percentageToUse = foreclosureChargePercentage != null ? foreclosureChargePercentage : chargeDefinition.getAmount();
            if (percentageToUse == null) {
                return Money.zero(currency);
            }
            BigDecimal percentageAmount = LoanCharge.percentageOf(principalOutstanding.getAmount(), percentageToUse);
            BigDecimal cappedAmount = applyMinAndMaxCaps(chargeDefinition, loan, percentageAmount);
            return Money.of(currency, cappedAmount);
        }
        BigDecimal amount = chargeDefinition.getAmount();
        if (amount == null) {
            return Money.zero(currency);
        }
        return Money.of(currency, amount);
    }

    public LoanCharge createForeclosureCharge(Loan loan, Charge chargeDefinition, BigDecimal principalAmount) {
        LoanCharge loanCharge = new LoanCharge();
        loanCharge.setLoan(loan);
        loanCharge.setCharge(chargeDefinition);
        loanCharge.setSubmittedOnDate(DateUtils.getBusinessLocalDate());
        loanCharge.setPenaltyCharge(chargeDefinition.isPenalty());
        loanCharge.setMinCap(chargeDefinition.getMinCap());
        loanCharge.setMaxCap(chargeDefinition.getMaxCap());
        loanCharge.setChargeTime(chargeDefinition.getChargeTimeType());
        loanCharge.setChargeCalculation(chargeDefinition.getChargeCalculation());
        loanCharge.setChargePaymentMode(chargeDefinition.getChargePaymentMode());
        loanCharge.setDueDate(null);
        loanCharge.setExternalId(ExternalId.empty());

        BigDecimal chargeAmount = chargeDefinition.getAmount();
        if (chargeAmount == null) {
            chargeAmount = BigDecimal.ZERO;
        }

        initialiseForeclosureChargeAmounts(loanCharge, principalAmount, chargeAmount, chargeDefinition.getChargeCalculation());
        loanCharge.setWaived(false);
        loanCharge.setActive(true);

        return loanCharge;
    }

    public void initialiseForeclosureChargeAmounts(LoanCharge loanCharge, BigDecimal amountPercentageAppliedTo, BigDecimal chargeAmount,
            Integer chargeCalculationType) {
        ChargeCalculationType calculationType = ChargeCalculationType.fromInt(chargeCalculationType);
        if (calculationType.isPercentageBased()) {
            loanCharge.setPercentage(chargeAmount);
            loanCharge.setAmountPercentageAppliedTo(amountPercentageAppliedTo);
        } else {
            loanCharge.setPercentage(null);
            loanCharge.setAmountPercentageAppliedTo(null);
        }
        loanCharge.setAmountOrPercentage(chargeAmount);
        loanCharge.setAmount(BigDecimal.ZERO);
        loanCharge.setAmountOutstanding(BigDecimal.ZERO);
        loanCharge.setAmountPaid(BigDecimal.ZERO);
        loanCharge.setAmountWaived(BigDecimal.ZERO);
        loanCharge.setAmountWrittenOff(BigDecimal.ZERO);
        loanCharge.setPaid(false);
    }

    private BigDecimal extractForeclosurePercentage(Charge chargeDefinition) {
        ChargeCalculationType calculationType = ChargeCalculationType.fromInt(chargeDefinition.getChargeCalculation());
        if (calculationType.isPercentageBased()) {
            return chargeDefinition.getAmount();
        }
        return null;
    }

    private BigDecimal applyMinAndMaxCaps(final Charge chargeDefinition, final Loan loan, final BigDecimal amount) {
        BigDecimal result = amount;
        if (chargeDefinition.getMinCap() != null && result.compareTo(chargeDefinition.getMinCap()) < 0) {
            result = chargeDefinition.getMinCap();
        }
        if (chargeDefinition.getMaxCap() != null && result.compareTo(chargeDefinition.getMaxCap()) > 0) {
            result = chargeDefinition.getMaxCap();
        }
        return Money.of(loan.getCurrency(), result).getAmount();
    }
}
