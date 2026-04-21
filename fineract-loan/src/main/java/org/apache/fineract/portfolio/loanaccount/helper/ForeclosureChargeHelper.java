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
package org.apache.fineract.portfolio.loanaccount.helper;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.charge.data.ChargeData;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.charge.service.ChargeReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.data.TransactionMetaData;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeRoundingUtils;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Helper class for handling foreclosure charge-related operations.
 */
@Component
public class ForeclosureChargeHelper {

    private static final Gson GSON = new Gson();

    private final ChargeReadPlatformService chargeReadPlatformService;
    private final ChargeRepositoryWrapper chargeRepositoryWrapper;
    private final LoanChargeService loanChargeService;
    private final ConfigurationDomainService configurationDomainService;

    public ForeclosureChargeHelper(ChargeReadPlatformService chargeReadPlatformService, ChargeRepositoryWrapper chargeRepositoryWrapper,
            @Lazy LoanChargeService loanChargeService, ConfigurationDomainService configurationDomainService) {
        this.chargeReadPlatformService = chargeReadPlatformService;
        this.chargeRepositoryWrapper = chargeRepositoryWrapper;
        this.loanChargeService = loanChargeService;
        this.configurationDomainService = configurationDomainService;
    }

    public Map<Long, BigDecimal> extractChargePercentagesFromJsonElement(JsonElement element, String paramName) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return new HashMap<>();
        }

        JsonObject jsonObject = element.getAsJsonObject();
        if (!jsonObject.has(paramName)) {
            return new HashMap<>();
        }

        return extractChargePercentagesFromJsonObject(jsonObject.get(paramName), paramName);
    }

    private Map<Long, BigDecimal> extractChargePercentagesFromJsonObject(JsonElement element, String paramName) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return new HashMap<>();
        }

        JsonObject jsonObject = element.getAsJsonObject();
        Map<Long, BigDecimal> chargePercentages = new HashMap<>();

        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            try {
                Long chargeId = Long.parseLong(entry.getKey());
                BigDecimal percentage = entry.getValue().getAsBigDecimal();
                chargePercentages.put(chargeId, percentage);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid charge ID format in " + paramName + ": " + entry.getKey(), e);
            } catch (IllegalStateException | UnsupportedOperationException e) {
                throw new IllegalArgumentException(
                        "Invalid percentage value for charge " + entry.getKey() + " in " + paramName + ": " + entry.getValue(), e);
            }
        }

        return chargePercentages;
    }

    public Map<Long, BigDecimal> extractChargePercentagesFromJsonString(String chargePercentagesJson) {
        if (chargePercentagesJson == null || chargePercentagesJson.trim().isEmpty()) {
            return new HashMap<>();
        }

        try {
            JsonElement element = GSON.fromJson(chargePercentagesJson, JsonElement.class);
            return extractChargePercentagesFromJsonObject(element, "foreclosureChargePercentageMap");
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new IllegalArgumentException("Invalid JSON format for charge percentages: " + chargePercentagesJson, e);
        }
    }

    /**
     * Merges foreclosure charges from the loan product with the provided charge percentages. Charges from the request
     * override the default charge definition values. Charges in the loan product but not in the request are added with
     * their default charge definition values.
     */
    public Map<Long, BigDecimal> mergeForeclosureChargesFromLoanProduct(Loan loan, Map<Long, BigDecimal> chargePercentages) {
        Long loanProductId = loan.getLoanProduct().getId();
        List<ChargeData> loanProductForeclosureCharges = chargeReadPlatformService.retrieveLoanProductCharges(loanProductId,
                ChargeTimeType.FORECLOSURE);

        Set<Long> requestChargeIds = chargePercentages != null ? new HashSet<>(chargePercentages.keySet()) : new HashSet<>();
        Map<Long, BigDecimal> merged = new HashMap<>();

        for (ChargeData chargeData : loanProductForeclosureCharges) {
            Long chargeId = chargeData.getId();
            if (chargePercentages != null && requestChargeIds.contains(chargeId)) {
                merged.put(chargeId, chargePercentages.get(chargeId));
            } else {
                try {
                    Charge chargeDefinition = chargeRepositoryWrapper.findOneWithNotFoundDetection(chargeId);
                    merged.put(chargeId, chargeDefinition.getAmount());
                } catch (Exception e) {
                    // Skip charges that cannot be loaded
                }
            }
        }

        return merged;
    }

    public Money calculateForeclosureFee(Loan loan, Map<Long, BigDecimal> mergedChargePercentages, MonetaryCurrency currency) {
        if (mergedChargePercentages == null || mergedChargePercentages.isEmpty()) {
            return Money.zero(currency);
        }

        Money totalFees = Money.zero(currency);
        for (Map.Entry<Long, BigDecimal> entry : mergedChargePercentages.entrySet()) {
            BigDecimal percentage = entry.getValue();
            if (percentage == null || percentage.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            try {
                Charge chargeDefinition = chargeRepositoryWrapper.findOneWithNotFoundDetection(entry.getKey());
                ChargeCalculationType calculationType = ChargeCalculationType.fromInt(chargeDefinition.getChargeCalculation());

                BigDecimal chargeAmount = calculationType.isPercentageBased()
                        ? calculatePercentageBasedCharge(loan, chargeDefinition, calculationType, percentage)
                        : percentage;
                final int effectiveDigits = LoanChargeRoundingUtils.resolveDigitsAfterDecimal(chargeDefinition,
                        currency.getDigitsAfterDecimal(), chargeAmount);
                final RoundingMode effectiveRoundingMode = LoanChargeRoundingUtils.resolveRoundingMode(chargeDefinition,
                        configurationDomainService);
                chargeAmount = chargeAmount.setScale(effectiveDigits, effectiveRoundingMode);
                totalFees = totalFees.plus(Money.of(currency, chargeAmount));
            } catch (Exception e) {
                // Skip invalid charges
            }
        }

        return totalFees;
    }

    private BigDecimal calculatePercentageBasedCharge(Loan loan, Charge chargeDefinition, ChargeCalculationType calculationType,
            BigDecimal percentage) {
        LoanCharge tempLoanCharge = new LoanCharge();
        tempLoanCharge.setLoan(loan);
        tempLoanCharge.setCharge(chargeDefinition);
        tempLoanCharge.setChargeCalculation(calculationType.getValue());
        tempLoanCharge.setPercentage(percentage);
        BigDecimal baseAmount = loanChargeService.calculateAmountPercentageAppliedTo(loan, tempLoanCharge);
        return LoanCharge.percentageOf(baseAmount, percentage);
    }

    public List<LoanCharge> createAndAddForeclosureChargesToLoan(Loan loan, Map<Long, BigDecimal> mergedChargePercentages,
            LocalDate foreclosureDate) {
        List<LoanCharge> foreclosureCharges = new ArrayList<>();
        if (mergedChargePercentages == null || mergedChargePercentages.isEmpty()) {
            return foreclosureCharges;
        }

        MonetaryCurrency currency = loan.getCurrency();

        for (Map.Entry<Long, BigDecimal> entry : mergedChargePercentages.entrySet()) {
            Long chargeId = entry.getKey();
            BigDecimal amountOrPercentage = entry.getValue();

            if (amountOrPercentage == null || amountOrPercentage.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            try {
                Charge chargeDefinition = chargeRepositoryWrapper.findOneWithNotFoundDetection(chargeId);
                ChargeCalculationType calculationType = ChargeCalculationType.fromInt(chargeDefinition.getChargeCalculation());
                BigDecimal uncappedChargeAmount = calculationType.isPercentageBased()
                        ? calculatePercentageBasedCharge(loan, chargeDefinition, calculationType, amountOrPercentage)
                        : amountOrPercentage;
                final int effectiveDigits = LoanChargeRoundingUtils.resolveDigitsAfterDecimal(chargeDefinition,
                        currency.getDigitsAfterDecimal(), uncappedChargeAmount);
                final RoundingMode effectiveRoundingMode = LoanChargeRoundingUtils.resolveRoundingMode(chargeDefinition,
                        configurationDomainService);
                uncappedChargeAmount = uncappedChargeAmount.setScale(effectiveDigits, effectiveRoundingMode);
                LoanCharge loanCharge = loanChargeService.create(loan, chargeDefinition, loan.getPrincipal().getAmount(),
                        amountOrPercentage, ChargeTimeType.FORECLOSURE, calculationType, foreclosureDate, null, null, uncappedChargeAmount,
                        null);
                loanChargeService.addLoanCharge(loan, loanCharge);
                resetChargeToUnpaidState(loanCharge, uncappedChargeAmount);
                foreclosureCharges.add(loanCharge);
            } catch (Exception e) {
                // Ignore invalid charge definitions
            }
        }
        return foreclosureCharges;
    }

    public void linkForeclosureChargesToPaymentTransactionAndMarkAsPaid(Loan loan, LoanTransaction paymentTransaction) {
        if (paymentTransaction == null) {
            return;
        }

        MonetaryCurrency currency = loan.getCurrency();
        Money totalForeclosureFeeCharges = Money.zero(currency);
        Set<LoanCharge> loanCharges = loan.getLoanCharges();

        if (loanCharges == null) {
            return;
        }

        paymentTransaction.getLoanChargesPaid().removeIf(
                paidBy -> paidBy.getLoanCharge() != null && paidBy.getLoanCharge().getChargeTimeType().equals(ChargeTimeType.FORECLOSURE));

        for (LoanCharge loanCharge : loanCharges) {
            if (loanCharge.getChargeTimeType().equals(ChargeTimeType.FORECLOSURE) && loanCharge.isActive()) {
                Money chargeAmount = loanCharge.getAmount(currency);
                if (chargeAmount.isGreaterThanZero()) {
                    paymentTransaction.getLoanChargesPaid().add(new LoanChargePaidBy(paymentTransaction, loanCharge,
                            chargeAmount.getAmount(), null, configurationDomainService.getTaxRoundingMode()));
                    loanCharge.updatePaidAmountBy(chargeAmount, null, chargeAmount);
                    totalForeclosureFeeCharges = totalForeclosureFeeCharges.plus(chargeAmount);
                }
            }
        }

        if (totalForeclosureFeeCharges.isGreaterThanZero()) {
            Money currentFeeChargesPortion = paymentTransaction.getFeeChargesPortion(currency);
            Money sumOfAllFeeChargePaidBy = Money.zero(currency);
            for (LoanChargePaidBy paidBy : paymentTransaction.getLoanChargesPaid()) {
                if (paidBy.getLoanCharge() != null && paidBy.getLoanCharge().isFeeCharge()) {
                    sumOfAllFeeChargePaidBy = sumOfAllFeeChargePaidBy.plus(Money.of(currency, paidBy.getAmount()));
                }
            }
            Money adjustment = sumOfAllFeeChargePaidBy.minus(currentFeeChargesPortion);
            if (!adjustment.isZero()) {
                paymentTransaction.updateChargesComponents(adjustment, Money.zero(currency));
            }
        }
    }

    public ChargeRepositoryWrapper getChargeRepositoryWrapper() {
        return chargeRepositoryWrapper;
    }

    public LoanTransaction createForeclosurePaymentTransaction(Loan loan, LoanRepaymentScheduleInstallment foreCloseDetail,
            LocalDate foreClosureDate, ExternalId externalId) {
        MonetaryCurrency currency = loan.getCurrency();
        Money principalPayable = foreCloseDetail.getPrincipal(currency);
        Money interestPayable = foreCloseDetail.getInterestCharged(currency);
        Money feePayable = foreCloseDetail.getFeeChargesCharged(currency);
        Money penaltyPayable = foreCloseDetail.getPenaltyChargesCharged(currency);
        Money totalPaymentAmount = principalPayable.plus(interestPayable).plus(feePayable).plus(penaltyPayable);

        if (!totalPaymentAmount.isGreaterThanZero()) {
            return null;
        }

        LoanTransaction payment = LoanTransaction.repayment(loan.getOffice(), totalPaymentAmount, null, foreClosureDate, externalId);
        TransactionMetaData transactionMetaData = new TransactionMetaData("FORECLOSURE");
        payment.updateTransactionMetaData(transactionMetaData.serialize());
        return payment;
    }

    public void updateForeclosureCharges(Loan loan, Map<Long, BigDecimal> mergedChargePercentages, LocalDate closureDate) {
        MonetaryCurrency currency = loan.getCurrency();
        Money totalPrincipalOutstanding = Money.of(loan.getCurrency(), loan.getSummary().getTotalPrincipalOutstanding());
        List<LoanCharge> foreclosureCharges = createAndAddForeclosureChargesToLoan(loan, mergedChargePercentages, closureDate);
        if (foreclosureCharges.isEmpty()) {
            return;
        }
        Money totalForeclosureChargeAmount = Money.zero(currency);
        for (LoanCharge charge : foreclosureCharges) {
            Money chargeAmount = charge.getAmount(currency);
            totalForeclosureChargeAmount = totalForeclosureChargeAmount.plus(chargeAmount);
            applyForeclosureChargeUpdate(charge, chargeAmount, totalPrincipalOutstanding, closureDate,
                    mergedChargePercentages.get(charge.getCharge().getId()));
        }
        applyForeclosureChargeOnRepaymentSchedule(loan, currency, totalForeclosureChargeAmount);
    }

    private void resetChargeToUnpaidState(final LoanCharge charge, final BigDecimal chargeAmount) {
        charge.setAmount(chargeAmount);
        charge.setAmountPaid(BigDecimal.ZERO);
        charge.setAmountWaived(BigDecimal.ZERO);
        charge.setAmountWrittenOff(BigDecimal.ZERO);
        charge.setPaid(false);
        charge.setWaived(false);
        charge.setAmountOutstanding(charge.calculateOutstanding());
    }

    private void applyForeclosureChargeUpdate(final LoanCharge charge, final Money amount, final Money principalOutstanding,
            final LocalDate closureDate, final BigDecimal foreclosureChargePercentage) {
        charge.setDueDate(closureDate);
        resetChargeToUnpaidState(charge, amount.getAmount());
        charge.setAmountPercentageAppliedTo(principalOutstanding.getAmount());
        if (foreclosureChargePercentage != null) {
            charge.setPercentage(foreclosureChargePercentage);
            charge.setAmountOrPercentage(foreclosureChargePercentage);
        }
    }

    private void applyForeclosureChargeOnRepaymentSchedule(final Loan loan, final MonetaryCurrency currency,
            final Money foreclosureAmount) {
        updateRepaymentScheduleWithForeclosureFee(loan, currency, foreclosureAmount, false);
    }

    public void syncForeclosureFeeOnRepaymentSchedule(final Loan loan, final Money foreclosureFee) {
        updateRepaymentScheduleWithForeclosureFee(loan, loan.getCurrency(), foreclosureFee, true);
    }

    private void updateRepaymentScheduleWithForeclosureFee(final Loan loan, final MonetaryCurrency currency, final Money foreclosureAmount,
            final boolean markAsPaid) {
        if (!foreclosureAmount.isGreaterThanZero()) {
            return;
        }
        List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        if (installments == null || installments.isEmpty()) {
            return;
        }
        LoanRepaymentScheduleInstallment lastInstallment = LoanRepaymentScheduleInstallment.getLastNonDownPaymentInstallment(installments);
        if (!markAsPaid) {
            Money existingFeeCharges = lastInstallment.getFeeChargesCharged(currency);
            if (existingFeeCharges.isEqualTo(foreclosureAmount)) {
                return;
            }
        }
        lastInstallment.setFeeChargesCharged(foreclosureAmount.getAmount());
        lastInstallment.setFeeChargesPaid(markAsPaid ? foreclosureAmount.getAmount() : Money.zero(currency).getAmount());
        lastInstallment.setFeeChargesWaived(Money.zero(currency).getAmount());
        lastInstallment.setFeeChargesWrittenOff(Money.zero(currency).getAmount());
    }

}
