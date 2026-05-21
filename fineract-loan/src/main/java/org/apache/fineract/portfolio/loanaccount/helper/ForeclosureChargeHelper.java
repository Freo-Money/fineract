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
import java.math.MathContext;
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
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleProcessingWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeRoundingUtils;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeService;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeTaxUtils;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductRoundingModeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Helper class for handling foreclosure charge-related operations.
 */
@Component
public class ForeclosureChargeHelper {

    private static final Logger LOG = LoggerFactory.getLogger(ForeclosureChargeHelper.class);
    private static final Gson GSON = new Gson();

    private final ChargeReadPlatformService chargeReadPlatformService;
    private final ChargeRepositoryWrapper chargeRepositoryWrapper;
    private final LoanChargeService loanChargeService;
    private final ConfigurationDomainService configurationDomainService;
    private final LoanProductRoundingModeService loanProductRoundingModeService;

    public ForeclosureChargeHelper(ChargeReadPlatformService chargeReadPlatformService, ChargeRepositoryWrapper chargeRepositoryWrapper,
            @Lazy LoanChargeService loanChargeService, ConfigurationDomainService configurationDomainService,
            LoanProductRoundingModeService loanProductRoundingModeService) {
        this.chargeReadPlatformService = chargeReadPlatformService;
        this.chargeRepositoryWrapper = chargeRepositoryWrapper;
        this.loanChargeService = loanChargeService;
        this.configurationDomainService = configurationDomainService;
        this.loanProductRoundingModeService = loanProductRoundingModeService;
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
        final MathContext mc = loanProductRoundingModeService.resolveMathContext(loan.getLoanProduct().getId());
        return LoanCharge.percentageOf(baseAmount, percentage, mc);
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

                final List<LoanCharge> existingForCharge = findActiveForeclosureChargesByChargeId(loan, chargeId);
                if (!existingForCharge.isEmpty()) {
                    // Foreclosure retried for the same loan: reuse the first existing row in place rather than
                    // creating a duplicate. Skipping addLoanCharge() here avoids the Single-wrapper ADD that would
                    // otherwise accumulate fee_charges_amount on the closing installment. The LOAN_CHARGE_ADDED
                    // state-machine event is intentionally not re-fired here: the only transition gated by it
                    // (CLOSED_OBLIGATIONS_MET -> ACTIVE) is not relevant during foreclosure.
                    final LoanCharge existing = existingForCharge.get(0);
                    final Charge existingChargeDefinition = existing.getCharge();
                    LOG.warn(
                            "Foreclosure charge already present on loan {} for charge_id {} ({} active row(s)); "
                                    + "updating first row in place and deactivating duplicates.",
                            loan.getId(), chargeId, existingForCharge.size());
                    final Money principalOutstanding = Money.of(currency, loan.getSummary().getTotalPrincipalOutstanding());
                    applyForeclosureChargeUpdate(existing, Money.of(currency, uncappedChargeAmount), principalOutstanding, foreclosureDate,
                            amountOrPercentage);
                    // Keep tax breakdown (amount_sans_tax, tax_amount) in sync with the new amount.
                    // applyForeclosureChargeUpdate -> resetChargeToUnpaidState only updates the principal amount; the
                    // tax columns must be recomputed explicitly, otherwise a retry with a different percentage leaves
                    // stale tax values that the shadow-validator flags as CHARGE_amount_sans_tax mismatches.
                    LoanChargeTaxUtils.calculateAndSetTaxDetails(existing, existingChargeDefinition, foreclosureDate,
                            configurationDomainService.getTaxRoundingMode());
                    // Deactivate any secondary duplicates so reprocess() doesn't sum legacy duplicates into the
                    // closing installment's fee_charges_amount.
                    for (int i = 1; i < existingForCharge.size(); i++) {
                        final LoanCharge duplicate = existingForCharge.get(i);
                        LOG.warn("Deactivating duplicate foreclosure LoanCharge id={} on loan {} for charge_id {}.", duplicate.getId(),
                                loan.getId(), chargeId);
                        duplicate.setActive(false);
                    }
                    foreclosureCharges.add(existing);
                    continue;
                }

                final LoanCharge loanCharge = loanChargeService.create(loan, chargeDefinition, loan.getPrincipal().getAmount(),
                        amountOrPercentage, ChargeTimeType.FORECLOSURE, calculationType, foreclosureDate, null, null, uncappedChargeAmount,
                        null);
                loanChargeService.addLoanCharge(loan, loanCharge);
                resetChargeToUnpaidState(loanCharge, uncappedChargeAmount);
                foreclosureCharges.add(loanCharge);
            } catch (Exception e) {
                // Skip the charge so a single misconfigured row does not abort the whole foreclosure; log with
                // root cause so the operational team can investigate (silent swallowing here previously masked
                // real misconfigurations -- see shadow-validator NEEDS_MANUAL bucket).
                LOG.warn("Skipping foreclosure charge for loan {} charge_id {}", loan.getId(), chargeId, e);
            }
        }
        return foreclosureCharges;
    }

    private List<LoanCharge> findActiveForeclosureChargesByChargeId(final Loan loan, final Long chargeId) {
        List<LoanCharge> matches = new ArrayList<>();
        if (loan.getLoanCharges() == null) {
            return matches;
        }
        for (LoanCharge candidate : loan.getLoanCharges()) {
            if (!candidate.isActive()) {
                continue;
            }
            if (candidate.getCharge() == null || !chargeId.equals(candidate.getCharge().getId())) {
                continue;
            }
            if (!ChargeTimeType.FORECLOSURE.equals(candidate.getChargeTimeType())) {
                continue;
            }
            matches.add(candidate);
        }
        return matches;
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

    /**
     * Creates or refreshes foreclosure charges on the loan and re-derives every repayment installment's fee/penalty
     * totals from the canonical set of active {@link LoanCharge} rows (SET-from-truth via
     * {@link LoanRepaymentScheduleProcessingWrapper#reprocess}).
     *
     * <p>
     * Idempotent: a retried foreclosure reuses an existing active row for the same {@code charge_id} (see
     * {@link #findActiveForeclosureChargesByChargeId}) instead of creating a duplicate, recomputes its tax breakdown,
     * and deactivates any legacy duplicates. After reprocess, {@link #clampClosingInstallmentFeePaid} guards against
     * {@code feeChargesPaid > feeChargesCharged} on the closing installment.
     */
    public void updateForeclosureCharges(Loan loan, Map<Long, BigDecimal> mergedChargePercentages, LocalDate closureDate) {
        MonetaryCurrency currency = loan.getCurrency();
        Money totalPrincipalOutstanding = Money.of(loan.getCurrency(), loan.getSummary().getTotalPrincipalOutstanding());
        List<LoanCharge> foreclosureCharges = createAndAddForeclosureChargesToLoan(loan, mergedChargePercentages, closureDate);
        if (foreclosureCharges.isEmpty()) {
            return;
        }
        for (LoanCharge charge : foreclosureCharges) {
            applyForeclosureChargeUpdate(charge, charge.getAmount(currency), totalPrincipalOutstanding, closureDate,
                    mergedChargePercentages.get(charge.getCharge().getId()));
        }
        // SET-from-truth: re-derive fee_charges_amount on every installment as the sum of LoanCharge.amount due in
        // that installment's period. This idempotently corrects any inflation caused by Single-wrapper ADD calls
        // (addLoanCharge) earlier in the foreclosure flow, and lands the foreclosure fee on the installment whose
        // period contains closureDate -- not the original last installment.
        final LoanRepaymentScheduleProcessingWrapper scheduleWrapper = new LoanRepaymentScheduleProcessingWrapper();
        final List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        scheduleWrapper.reprocess(currency, loan.getDisbursementDate(), installments, loan.getLoanCharges());

        // Item 3 (defensive): after reprocess, feeChargesCharged on the closing installment may be smaller than its
        // pre-existing feeChargesPaid (e.g. a prior foreclosure attempt marked it paid before this retry shrank the
        // total fee). getFeeChargesOutstanding() would then return a negative value, which retrieveIncomeOutstanding-
        // TillDate sums as-is into the foreclosure payment total -- inflating or shrinking it incorrectly. Clamp
        // paid to charged on the closing installment to keep outstanding non-negative.
        clampClosingInstallmentFeePaid(installments, closureDate, currency);
    }

    private void clampClosingInstallmentFeePaid(final List<LoanRepaymentScheduleInstallment> installments, final LocalDate closureDate,
            final MonetaryCurrency currency) {
        if (installments == null || installments.isEmpty()) {
            return;
        }
        LoanRepaymentScheduleInstallment closing = LoanRepaymentScheduleProcessingWrapper.findInPeriod(closureDate, installments)
                .orElseGet(() -> LoanRepaymentScheduleInstallment.getLastNonDownPaymentInstallment(installments));
        Money charged = closing.getFeeChargesCharged(currency);
        Money paid = closing.getFeeChargesPaid(currency);
        if (paid.isGreaterThan(charged)) {
            LOG.info("Clamping feeChargesPaid {} -> {} on closing installment {} of loan {} to keep outstanding non-negative.",
                    paid.getAmount(), charged.getAmount(), closing.getInstallmentNumber(),
                    closing.getLoan() == null ? null : closing.getLoan().getId());
            closing.setFeeChargesPaid(charged.getAmount());
        }
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

    /**
     * Post-payment sync: re-derives installment fee totals from the canonical {@link LoanCharge} set and marks the
     * closing installment's fee portion as paid. Resolves the closing installment by {@code foreClosureDate} (the
     * installment whose period contains the date) so mid-loan foreclosures land on the actual closing row rather than
     * the original last installment.
     */
    public void syncForeclosureFeeOnRepaymentSchedule(final Loan loan, final Money foreclosureFee, final LocalDate foreClosureDate) {
        if (!foreclosureFee.isGreaterThanZero()) {
            return;
        }
        final MonetaryCurrency currency = loan.getCurrency();
        final List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        if (installments == null || installments.isEmpty()) {
            return;
        }

        // SET-from-truth so the schedule reflects the canonical sum of active LoanCharge rows due in each period.
        // Idempotently undoes any prior ADD inflation on the closing installment.
        final LoanRepaymentScheduleProcessingWrapper scheduleWrapper = new LoanRepaymentScheduleProcessingWrapper();
        scheduleWrapper.reprocess(currency, loan.getDisbursementDate(), installments, loan.getLoanCharges());

        // Mark the closing installment's fee portion as paid. Resolve by date so mid-loan foreclosures land on the
        // installment whose period contains foreClosureDate, not the original last installment.
        final LoanRepaymentScheduleInstallment closingInstallment = LoanRepaymentScheduleProcessingWrapper
                .findInPeriod(foreClosureDate, installments)
                .orElseGet(() -> LoanRepaymentScheduleInstallment.getLastNonDownPaymentInstallment(installments));
        closingInstallment.setFeeChargesPaid(closingInstallment.getFeeChargesCharged(currency).getAmount());
    }

}
