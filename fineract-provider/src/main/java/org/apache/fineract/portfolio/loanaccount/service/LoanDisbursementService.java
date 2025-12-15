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

import static org.apache.fineract.portfolio.loanaccount.domain.Loan.RECALCULATE_LOAN_SCHEDULE;

import com.google.common.base.Splitter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.configuration.service.TemporaryConfigurationServiceContainer;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.serialization.JsonParserHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanDisbursalTransactionBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.loanaccount.api.LoanApiConstants;
import org.apache.fineract.portfolio.loanaccount.data.ScheduleGeneratorDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanDisbursementDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTrancheCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTrancheDisbursementCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanDisbursementValidator;
import org.apache.fineract.portfolio.loanproduct.domain.BrokenPeriodInterestStrategy;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.springframework.lang.NonNull;

@Slf4j
@RequiredArgsConstructor
public class LoanDisbursementService {

    private final LoanChargeValidator loanChargeValidator;
    private final LoanDisbursementValidator loanDisbursementValidator;
    private final ReprocessLoanTransactionsService reprocessLoanTransactionsService;
    private final LoanChargeService loanChargeService;
    private final LoanBalanceService loanBalanceService;
    private final LoanJournalEntryPoster loanJournalEntryPoster;
    private final LoanTransactionRepository loanTransactionRepository;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final LoanDownPaymentHandlerService loanDownPaymentHandlerService;

    public void updateDisbursementDetails(final Loan loan, final JsonCommand jsonCommand, final Map<String, Object> actualChanges) {
        final List<Long> disbursementList = loan.fetchDisbursementIds();
        final List<Long> loanChargeIds = loan.fetchLoanTrancheChargeIds();
        final int chargeIdLength = loanChargeIds.size();
        String chargeIds;
        // From modify application page, if user removes all charges, we should
        // get empty array.
        // So we need to remove all charges applied for this loan
        final boolean removeAllCharges = jsonCommand.parameterExists(LoanApiConstants.chargesParameterName)
                && jsonCommand.arrayOfParameterNamed(LoanApiConstants.chargesParameterName).isEmpty();

        if (jsonCommand.parameterExists(LoanApiConstants.disbursementDataParameterName)) {
            final JsonArray disbursementDataArray = jsonCommand.arrayOfParameterNamed(LoanApiConstants.disbursementDataParameterName);
            if (disbursementDataArray != null && !disbursementDataArray.isEmpty()) {
                String dateFormat;
                Locale locale = null;
                final Map<String, String> dateAndLocale = getDateFormatAndLocale(jsonCommand);
                dateFormat = dateAndLocale.get(LoanApiConstants.dateFormatParameterName);
                if (dateAndLocale.containsKey(LoanApiConstants.localeParameterName)) {
                    locale = JsonParserHelper.localeFromString(dateAndLocale.get(LoanApiConstants.localeParameterName));
                }
                for (JsonElement jsonElement : disbursementDataArray) {
                    final JsonObject jsonObject = jsonElement.getAsJsonObject();
                    final Map<String, Object> parsedDisbursementData = parseDisbursementDetails(jsonObject, dateFormat, locale);
                    final LocalDate expectedDisbursementDate = (LocalDate) parsedDisbursementData
                            .get(LoanApiConstants.expectedDisbursementDateParameterName);
                    final BigDecimal principal = (BigDecimal) parsedDisbursementData
                            .get(LoanApiConstants.disbursementPrincipalParameterName);
                    final Long disbursementID = (Long) parsedDisbursementData.get(LoanApiConstants.disbursementIdParameterName);
                    chargeIds = (String) parsedDisbursementData.get(LoanApiConstants.loanChargeIdParameterName);
                    if (chargeIds != null) {
                        if (chargeIds.contains(",")) {
                            final Iterable<String> chargeId = Splitter.on(',').split(chargeIds);
                            for (String loanChargeId : chargeId) {
                                loanChargeIds.remove(Long.parseLong(loanChargeId));
                            }
                        } else {
                            loanChargeIds.remove(Long.parseLong(chargeIds));
                        }
                    }
                    createOrUpdateDisbursementDetails(loan, disbursementID, actualChanges, expectedDisbursementDate, principal,
                            disbursementList);
                }
                removeDisbursementAndAssociatedCharges(loan, actualChanges, disbursementList, loanChargeIds, chargeIdLength,
                        removeAllCharges);
            }
        }
    }

    public Money adjustDisburseAmount(final Loan loan, @NonNull final JsonCommand command,
            @NonNull final LocalDate actualDisbursementDate) {
        Money disburseAmount = loan.getLoanRepaymentScheduleDetail().getPrincipal().zero();
        final BigDecimal principalDisbursed = command.bigDecimalValueOfParameterNamed(LoanApiConstants.principalDisbursedParameterName);
        if (loan.getActualDisbursementDate() == null || DateUtils.isBefore(actualDisbursementDate, loan.getActualDisbursementDate())) {
            loan.setActualDisbursementDate(actualDisbursementDate);
        }
        BigDecimal diff = BigDecimal.ZERO;
        final Collection<LoanDisbursementDetails> rawDetails = loan.fetchUndisbursedDetail();
        final Collection<LoanDisbursementDetails> details = hasMultipleTranchesOnSameDateWithSameExpectedDate(rawDetails,
                actualDisbursementDate) ? sortDisbursementDetailsByBusinessRules(rawDetails) : rawDetails;
        if (principalDisbursed == null) {
            disburseAmount = loan.getLoanRepaymentScheduleDetail().getPrincipal();
            if (!details.isEmpty()) {
                // When no specific amount provided, disburse ALL undisbursed tranches for the date
                disburseAmount = disburseAmount.zero();
                for (LoanDisbursementDetails disbursementDetails : details) {
                    disbursementDetails.updateActualDisbursementDate(actualDisbursementDate);
                    disburseAmount = disburseAmount.plus(disbursementDetails.principal());
                }
            }
        } else {
            if (loan.getLoanProduct().isMultiDisburseLoan()) {
                disburseAmount = Money.of(loan.getCurrency(), principalDisbursed);
            } else {
                disburseAmount = disburseAmount.plus(principalDisbursed);
            }

            if (details.isEmpty()) {
                diff = loan.getLoanRepaymentScheduleDetail().getPrincipal().minus(principalDisbursed).getAmount();
            } else {
                // Check if this is a tranche-based loan (has multiple predefined disbursement details)
                // versus a non-tranche multi-disbursal loan (creates disbursement details on-the-fly)
                boolean isTrancheBasedLoan = hasMultipleOrPreDefinedDisbursementDetails(loan, details);

                if (isTrancheBasedLoan && details.size() >= 1) {
                    // For tranche-based loans, find the matching tranche by amount first, then by order
                    LoanDisbursementDetails selectedTranche = null;

                    // First try to find a tranche that exactly matches the requested disbursement amount
                    for (LoanDisbursementDetails disbursementDetails : details) {
                        if (disbursementDetails.actualDisbursementDate() == null
                                && disbursementDetails.principal().compareTo(principalDisbursed) == 0) {
                            selectedTranche = disbursementDetails;
                            break;
                        }
                    }

                    // If no exact match found, take the first available tranche (next in line)
                    if (selectedTranche == null) {
                        for (LoanDisbursementDetails disbursementDetails : details) {
                            if (disbursementDetails.actualDisbursementDate() == null) {
                                selectedTranche = disbursementDetails;
                                break;
                            }
                        }
                    }

                    if (selectedTranche != null) {
                        // Update the selected tranche with the actual disbursement
                        selectedTranche.updateActualDisbursementDate(actualDisbursementDate);
                        selectedTranche.updatePrincipal(principalDisbursed);
                    }
                } else {
                    // For non-tranche multi-disbursal loans: preserve original behavior
                    // Update all available disbursement details with the actual disbursement date and amount
                    for (LoanDisbursementDetails disbursementDetails : details) {
                        disbursementDetails.updateActualDisbursementDate(actualDisbursementDate);
                        disbursementDetails.updatePrincipal(principalDisbursed);
                    }
                }
            }
            BigDecimal totalAmount = BigDecimal.ZERO;
            if (loan.loanProduct().isMultiDisburseLoan()) {
                Collection<LoanDisbursementDetails> loanDisburseDetails = loan.getDisbursementDetails();
                BigDecimal setPrincipalAmount = BigDecimal.ZERO;
                for (LoanDisbursementDetails disbursementDetails : loanDisburseDetails) {
                    if (disbursementDetails.actualDisbursementDate() != null) {
                        setPrincipalAmount = setPrincipalAmount.add(disbursementDetails.principal());
                    }
                    totalAmount = totalAmount.add(disbursementDetails.principal());
                }
                loan.getLoanRepaymentScheduleDetail().setPrincipal(setPrincipalAmount);
            } else {
                loan.getLoanRepaymentScheduleDetail()
                        .setPrincipal(loan.getLoanRepaymentScheduleDetail().getPrincipal().minus(diff).getAmount());
                totalAmount = loan.getLoanRepaymentScheduleDetail().getPrincipal().getAmount();
            }
            loanDisbursementValidator.compareDisbursedToApprovedOrProposedPrincipal(loan, disburseAmount.getAmount(), totalAmount);
        }
        return disburseAmount;
    }

    public void handleDisbursementTransaction(final Loan loan, final LocalDate disbursedOn, final PaymentDetail paymentDetail,
            final ScheduleGeneratorDTO scheduleGeneratorDTO) {
        final List<LoanTransaction> transactionsToSave = new ArrayList<>();

        // Recalculate tax for all charges with actual disbursement date
        for (LoanCharge loanCharge : loan.getActiveCharges()) {
            loanCharge.updateLoanChargeTaxDetails(disbursedOn, loanCharge.amount());
        }

        // Calculate disbursement charges and BPI amount
        final Money disbursementCharges = Money.of(loan.getCurrency(), loan.deriveSumTotalOfChargesDueAtDisbursement());
        final boolean collectBpiAtDisbursement = shouldCollectBpiAtDisbursement(loan);
        final Money bpiAmount = collectBpiAtDisbursement && loan.getBrokenPeriodInterest() != null
                ? Money.of(loan.getCurrency(), loan.getBrokenPeriodInterest())
                : Money.zero(loan.getCurrency());

        // Create disbursement transaction for charges + BPI (if any)
        final Money chargesAndBpiTotal = disbursementCharges.plus(bpiAmount);
        if (chargesAndBpiTotal.isGreaterThanZero()) {
            createChargesAndBpiDisbursementTransaction(loan, disbursedOn, paymentDetail, chargesAndBpiTotal, transactionsToSave);
        }

        // Create BPI repayment transaction (offsets BPI portion, keeps balance at principal)
        final LoanTransaction bpiRepaymentTransaction = bpiAmount.isGreaterThanZero()
                ? createBpiRepaymentTransaction(loan, disbursedOn, paymentDetail, bpiAmount, transactionsToSave)
                : null;

        // Create charges repayment transaction (offsets charges portion, keeps balance at principal)
        if (disbursementCharges.isGreaterThanZero()) {
            createChargesRepaymentTransaction(loan, disbursedOn, paymentDetail, disbursementCharges, transactionsToSave);
        }

        // Batch save all transactions and post journal entries
        saveAndPostAllTransactions(loan, transactionsToSave);

        // Process BPI repayment to allocate it to installments
        if (bpiRepaymentTransaction != null) {
            loanDownPaymentHandlerService.handleRepaymentOrRecoveryOrWaiverTransaction(loan, bpiRepaymentTransaction, null,
                    scheduleGeneratorDTO);
        }

        // Validate disbursement date
        loanDisbursementValidator.validateDisburseDate(loan, disbursedOn, loan.getExpectedFirstRepaymentOnDate());
    }

    private void createChargesRepaymentTransaction(final Loan loan, final LocalDate disbursedOn, final PaymentDetail paymentDetail,
            final Money disbursementCharges, final List<LoanTransaction> transactionsToSave) {
        final LoanTransaction chargesPayment = LoanTransaction.repaymentAtDisbursement(loan.getOffice(), disbursementCharges, paymentDetail,
                disbursedOn, null);
        final Money totalFeeChargesDueAtDisbursement = loan.getSummary().getTotalFeeChargesDueAtDisbursement(loan.getCurrency());
        final boolean hasFeesAtDisbursement = totalFeeChargesDueAtDisbursement.isGreaterThanZero();

        // Mark charges as paid and associate with repayment transaction
        for (final LoanCharge charge : loan.getActiveCharges()) {
            final LocalDate actualDisbursementDate = loan.getActualDisbursementDate(charge);
            final Integer chargeTimeType = charge.getCharge().getChargeTimeType();
            final boolean isDisbursementCharge = (chargeTimeType.equals(ChargeTimeType.DISBURSEMENT.getValue())
                    || chargeTimeType.equals(ChargeTimeType.TRANCHE_DISBURSEMENT.getValue())) && disbursedOn.equals(actualDisbursementDate)
                    && !charge.isWaived() && !charge.isFullyPaid();

            if (isDisbursementCharge && hasFeesAtDisbursement && !charge.getChargePaymentMode().isPaymentModeAccountTransfer()) {
                charge.markAsFullyPaid();
                final LoanChargePaidBy loanChargePaidBy = new LoanChargePaidBy(chargesPayment, charge, charge.amount(), null);
                chargesPayment.getLoanChargesPaid().add(loanChargePaidBy);
            } else if (disbursedOn.equals(loan.getActualDisbursementDate()) && loan.isUpfrontAccrualAccountingEnabledOnLoanProduct()) {
                final LoanTransaction applyLoanChargeTransaction = loanChargeService.handleChargeAppliedTransaction(loan, charge,
                        disbursedOn);
                if (applyLoanChargeTransaction != null) {
                    loanTransactionRepository.saveAndFlush(applyLoanChargeTransaction);
                    loanJournalEntryPoster.postJournalEntriesForLoanTransaction(applyLoanChargeTransaction, false, false);
                }
            }
        }

        // Update transaction components and add to loan
        final Money zero = Money.zero(loan.getCurrency());
        chargesPayment.updateComponentsAndTotal(zero, zero, disbursementCharges, zero);
        chargesPayment.updateLoan(loan);
        loan.addLoanTransaction(chargesPayment);
        transactionsToSave.add(chargesPayment);
    }

    private LoanTransaction createBpiRepaymentTransaction(final Loan loan, final LocalDate disbursedOn, final PaymentDetail paymentDetail,
            final Money bpiAmount, final List<LoanTransaction> transactionsToSave) {
        // Create BPI as a paid repayment transaction
        final LoanTransaction bpiRepayment = LoanTransaction.repayment(loan.getOffice(), bpiAmount, paymentDetail, disbursedOn,
                generateExternalIdIfEnabled());

        // Update components: BPI is interest, so set interest portion as paid
        final Money zero = Money.zero(loan.getCurrency());
        bpiRepayment.updateComponentsAndTotal(zero, bpiAmount, zero, zero); // principal, interest, fees, penalties

        bpiRepayment.updateLoan(loan);
        loan.addLoanTransaction(bpiRepayment);
        transactionsToSave.add(bpiRepayment);

        return bpiRepayment;
    }

    private boolean shouldCollectBpiAtDisbursement(final Loan loan) {
        // Check loan-level flag only (set during loan creation/modification)
        if (!loan.isBpiCollectedAtDisbursement()) {
            return false;
        }

        // Validate BPI amount exists
        if (loan.getBrokenPeriodInterest() == null || loan.getBrokenPeriodInterest().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        // Validate BPI configuration and strategy
        return loan.getBpiConfig() != null && loan.getBpiConfig().getBrokenPeriodConfig() != null && loan.getBpiConfig()
                .getBrokenPeriodConfig().getStrategy() == BrokenPeriodInterestStrategy.ADD_TO_FIRST_INSTALLMENT_WITH_PRINCIPAL_GRACE;
    }

    private void createChargesAndBpiDisbursementTransaction(final Loan loan, final LocalDate disbursedOn, final PaymentDetail paymentDetail,
            final Money chargesAndBpiTotal, final List<LoanTransaction> transactionsToSave) {
        final Money totalOverpaid = loan.getTotalOverpaidAsMoney();
        final LoanTransaction chargesDisbursementTransaction = LoanTransaction.disbursement(loan, chargesAndBpiTotal, paymentDetail,
                disbursedOn, generateExternalIdIfEnabled(), totalOverpaid);
        chargesDisbursementTransaction.updateLoan(loan);
        loan.addLoanTransaction(chargesDisbursementTransaction);
        transactionsToSave.add(chargesDisbursementTransaction);
    }

    private void saveAndPostAllTransactions(final Loan loan, final List<LoanTransaction> transactions) {
        // Batch save all transactions
        if (!transactions.isEmpty()) {
            loanTransactionRepository.saveAllAndFlush(transactions);

            // Post journal entries and fire business events for each transaction
            for (LoanTransaction transaction : transactions) {
                loanJournalEntryPoster.postJournalEntriesForLoanTransaction(transaction, false, false);

                // Fire business event for disbursement transactions
                if (transaction.isDisbursement()) {
                    businessEventNotifierService.notifyPostBusinessEvent(new LoanDisbursalTransactionBusinessEvent(transaction));
                }
            }

            // Update loan balance once after all transactions
            loanBalanceService.updateLoanOutstandingBalances(loan);
        }
    }

    private ExternalId generateExternalIdIfEnabled() {
        if (TemporaryConfigurationServiceContainer.isExternalIdAutoGenerationEnabled()) {
            return ExternalId.generate();
        }
        return ExternalId.empty();
    }

    private void createOrUpdateDisbursementDetails(final Loan loan, final Long disbursementID, final Map<String, Object> actualChanges,
            final LocalDate expectedDisbursementDate, final BigDecimal principal, final List<Long> existingDisbursementList) {
        if (disbursementID != null) {
            LoanDisbursementDetails loanDisbursementDetail = loan.fetchLoanDisbursementsById(disbursementID);
            existingDisbursementList.remove(disbursementID);
            if (loanDisbursementDetail.actualDisbursementDate() == null) {
                LocalDate actualDisbursementDate = null;
                LoanDisbursementDetails disbursementDetails = new LoanDisbursementDetails(expectedDisbursementDate, actualDisbursementDate,
                        principal, loan.getNetDisbursalAmount(), false);
                disbursementDetails.updateLoan(loan);
                if (!loanDisbursementDetail.equals(disbursementDetails)) {
                    loanDisbursementDetail.copy(disbursementDetails);
                    actualChanges.put("disbursementDetailId", disbursementID);
                    actualChanges.put(RECALCULATE_LOAN_SCHEDULE, true);
                }
            }
        } else {
            final var disbursementDetails = loan.addLoanDisbursementDetails(expectedDisbursementDate, principal);
            for (LoanTrancheCharge trancheCharge : loan.getTrancheCharges()) {
                Charge chargeDefinition = trancheCharge.getCharge();
                ExternalId externalId = ExternalId.empty();
                if (TemporaryConfigurationServiceContainer.isExternalIdAutoGenerationEnabled()) {
                    externalId = ExternalId.generate();
                }
                final LoanCharge loanCharge = loanChargeService.create(loan, chargeDefinition, principal, null, null, null,
                        expectedDisbursementDate, null, null, BigDecimal.ZERO, externalId);
                LoanTrancheDisbursementCharge loanTrancheDisbursementCharge = new LoanTrancheDisbursementCharge(loanCharge,
                        disbursementDetails);
                loanCharge.updateLoanTrancheDisbursementCharge(loanTrancheDisbursementCharge);

                loanChargeValidator.validateChargeAdditionForDisbursedLoan(loan, loanCharge);
                loanChargeValidator.validateChargeHasValidSpecifiedDateIfApplicable(loan, loanCharge, loan.getDisbursementDate());
                loanChargeService.addLoanCharge(loan, loanCharge);
            }
            actualChanges.put(LoanApiConstants.disbursementDataParameterName, expectedDisbursementDate + "-" + principal);
            actualChanges.put(RECALCULATE_LOAN_SCHEDULE, true);
        }
    }

    private void removeDisbursementAndAssociatedCharges(final Loan loan, final Map<String, Object> actualChanges,
            final List<Long> disbursementList, final List<Long> loanChargeIds, final int chargeIdLength, final boolean removeAllCharges) {
        if (removeAllCharges) {
            final LoanCharge[] tempCharges = new LoanCharge[loan.getCharges().size()];
            loan.getCharges().toArray(tempCharges);
            for (LoanCharge loanCharge : tempCharges) {
                loanChargeValidator.validateLoanIsNotClosed(loan, loanCharge);
                loanChargeValidator.validateLoanChargeIsNotWaived(loan, loanCharge);
                reprocessLoanTransactionsService.removeLoanCharge(loan, loanCharge);
            }
            loan.getTrancheCharges().clear();
        } else {
            if (!loanChargeIds.isEmpty() && loanChargeIds.size() != chargeIdLength) {
                for (Long chargeId : loanChargeIds) {
                    final LoanCharge deleteCharge = loanChargeService.fetchLoanChargesById(loan, chargeId);
                    if (loan.getCharges().contains(deleteCharge)) {
                        loanChargeValidator.validateLoanIsNotClosed(loan, deleteCharge);
                        loanChargeValidator.validateLoanChargeIsNotWaived(loan, deleteCharge);
                        reprocessLoanTransactionsService.removeLoanCharge(loan, deleteCharge);
                    }
                }
            }
        }
        for (Long id : disbursementList) {
            removeChargesByDisbursementID(loan, id);
            loan.removeDisbursementDetails(id);
            actualChanges.put(RECALCULATE_LOAN_SCHEDULE, true);
        }
    }

    private void removeChargesByDisbursementID(final Loan loan, final Long id) {
        loan.getCharges().stream() //
                .filter(charge -> { //
                    final LoanTrancheDisbursementCharge transCharge = charge.getTrancheDisbursementCharge(); //
                    if (transCharge == null || !Objects.equals(id, transCharge.getloanDisbursementDetails().getId())) {
                        return false;
                    }
                    loanChargeValidator.validateLoanIsNotClosed(loan, charge); //
                    loanChargeValidator.validateLoanChargeIsNotWaived(loan, charge); //
                    return true; //
                }) //
                .forEach(loanCharge -> reprocessLoanTransactionsService.removeLoanCharge(loan, loanCharge));
    }

    // This method returns date format and locale if present in the JsonCommand
    private Map<String, String> getDateFormatAndLocale(final JsonCommand jsonCommand) {
        Map<String, String> returnObject = new HashMap<>();
        JsonElement jsonElement = jsonCommand.parsedJson();
        if (jsonElement.isJsonObject()) {
            JsonObject topLevel = jsonElement.getAsJsonObject();
            if (topLevel.has(LoanApiConstants.dateFormatParameterName)
                    && topLevel.get(LoanApiConstants.dateFormatParameterName).isJsonPrimitive()) {
                final JsonPrimitive primitive = topLevel.get(LoanApiConstants.dateFormatParameterName).getAsJsonPrimitive();
                returnObject.put(LoanApiConstants.dateFormatParameterName, primitive.getAsString());
            }
            if (topLevel.has(LoanApiConstants.localeParameterName)
                    && topLevel.get(LoanApiConstants.localeParameterName).isJsonPrimitive()) {
                final JsonPrimitive primitive = topLevel.get(LoanApiConstants.localeParameterName).getAsJsonPrimitive();
                String localeString = primitive.getAsString();
                returnObject.put(LoanApiConstants.localeParameterName, localeString);
            }
        }
        return returnObject;
    }

    private Map<String, Object> parseDisbursementDetails(final JsonObject jsonObject, String dateFormat, Locale locale) {
        Map<String, Object> returnObject = new HashMap<>();
        if (jsonObject.get(LoanApiConstants.expectedDisbursementDateParameterName) != null
                && jsonObject.get(LoanApiConstants.expectedDisbursementDateParameterName).isJsonPrimitive()) {
            final JsonPrimitive primitive = jsonObject.get(LoanApiConstants.expectedDisbursementDateParameterName).getAsJsonPrimitive();
            final String valueAsString = primitive.getAsString();
            if (StringUtils.isNotBlank(valueAsString)) {
                LocalDate date = JsonParserHelper.convertFrom(valueAsString, LoanApiConstants.expectedDisbursementDateParameterName,
                        dateFormat, locale);
                if (date != null) {
                    returnObject.put(LoanApiConstants.expectedDisbursementDateParameterName, date);
                }
            }
        }

        if (jsonObject.get(LoanApiConstants.disbursementPrincipalParameterName).isJsonPrimitive()
                && StringUtils.isNotBlank(jsonObject.get(LoanApiConstants.disbursementPrincipalParameterName).getAsString())) {
            BigDecimal principal = jsonObject.getAsJsonPrimitive(LoanApiConstants.disbursementPrincipalParameterName).getAsBigDecimal();
            returnObject.put(LoanApiConstants.disbursementPrincipalParameterName, principal);
        }

        if (jsonObject.has(LoanApiConstants.disbursementIdParameterName)
                && jsonObject.get(LoanApiConstants.disbursementIdParameterName).isJsonPrimitive()
                && StringUtils.isNotBlank(jsonObject.get(LoanApiConstants.disbursementIdParameterName).getAsString())) {
            Long id = jsonObject.getAsJsonPrimitive(LoanApiConstants.disbursementIdParameterName).getAsLong();
            returnObject.put(LoanApiConstants.disbursementIdParameterName, id);
        }

        if (jsonObject.has(LoanApiConstants.loanChargeIdParameterName)
                && jsonObject.get(LoanApiConstants.loanChargeIdParameterName).isJsonPrimitive()
                && StringUtils.isNotBlank(jsonObject.get(LoanApiConstants.loanChargeIdParameterName).getAsString())) {
            returnObject.put(LoanApiConstants.loanChargeIdParameterName,
                    jsonObject.getAsJsonPrimitive(LoanApiConstants.loanChargeIdParameterName).getAsString());
        }
        return returnObject;
    }

    private boolean hasMultipleOrPreDefinedDisbursementDetails(final Loan loan,
            final Collection<LoanDisbursementDetails> undisbursedDetails) {
        Collection<LoanDisbursementDetails> allDisbursementDetails = loan.getDisbursementDetails();

        if (undisbursedDetails.size() > 1) {
            return true;
        }

        if (allDisbursementDetails.size() > 1 && !undisbursedDetails.isEmpty()) {
            return true;
        }

        if (undisbursedDetails.size() == 1) {
            LoanDisbursementDetails singleDetail = undisbursedDetails.iterator().next();
            BigDecimal loanPrincipal = loan.getLoanRepaymentScheduleDetail().getPrincipal().getAmount();

            if (singleDetail.principal().compareTo(loanPrincipal) == 0) {
                return false;
            }
        }

        // Default to tranche behavior for safety in ambiguous cases
        return true;
    }

    public static List<LoanDisbursementDetails> sortDisbursementDetailsByBusinessRules(
            Collection<LoanDisbursementDetails> disbursementDetails) {
        if (disbursementDetails == null || disbursementDetails.isEmpty()) {
            return List.of();
        }

        return disbursementDetails.stream()
                .sorted(Comparator.comparing(LoanDisbursementDetails::expectedDisbursementDate)
                        .thenComparing((LoanDisbursementDetails d1, LoanDisbursementDetails d2) -> d2.principal().compareTo(d1.principal()))
                        .thenComparing(LoanDisbursementDetails::getId))
                .collect(Collectors.toList());
    }

    public static boolean hasMultipleTranchesOnSameDate(Collection<LoanDisbursementDetails> disbursementDetails) {
        if (disbursementDetails == null || disbursementDetails.size() <= 1) {
            return false;
        }

        return disbursementDetails.stream()
                .collect(Collectors.groupingBy(LoanDisbursementDetails::expectedDisbursementDate, Collectors.counting())).values().stream()
                .anyMatch(count -> count > 1);
    }

    public static boolean hasMultipleTranchesOnSameDateWithSameExpectedDate(Collection<LoanDisbursementDetails> disbursementDetails,
            LocalDate actualDisbursementDate) {
        if (disbursementDetails == null || disbursementDetails.size() <= 1 || actualDisbursementDate == null) {
            return false;
        }

        long tranchesForActualDate = disbursementDetails.stream()
                .filter(detail -> actualDisbursementDate.equals(detail.expectedDisbursementDate())).count();

        return tranchesForActualDate > 1;
    }

}
