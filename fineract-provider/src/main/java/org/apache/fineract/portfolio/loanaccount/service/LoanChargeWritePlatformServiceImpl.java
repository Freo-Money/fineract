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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanApplyOverdueChargeBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanBalanceChangedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.charge.LoanAddChargeBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.charge.LoanDeleteChargeBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.charge.LoanUpdateChargeBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.charge.LoanWaiveChargeBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.charge.LoanWaiveChargeUndoBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanAccrualTransactionCreatedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanChargeAdjustmentPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanChargeAdjustmentPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanChargeRefundBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.exception.InvalidCurrencyException;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.PortfolioAccountData;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetailRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetails;
import org.apache.fineract.portfolio.account.domain.AccountTransferType;
import org.apache.fineract.portfolio.account.exception.AccountTransferNotFoundException;
import org.apache.fineract.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.charge.exception.ChargeCannotBeAppliedToException;
import org.apache.fineract.portfolio.charge.exception.ChargeCannotBeUpdatedException;
import org.apache.fineract.portfolio.charge.exception.LoanChargeCannotBeAddedException;
import org.apache.fineract.portfolio.charge.exception.LoanChargeCannotBeDeletedException;
import org.apache.fineract.portfolio.charge.exception.LoanChargeCannotBePayedException;
import org.apache.fineract.portfolio.charge.exception.LoanChargeCannotBeUpdatedException;
import org.apache.fineract.portfolio.charge.exception.LoanChargeCannotBeWaivedException;
import org.apache.fineract.portfolio.charge.exception.LoanChargeNotFoundException;
import org.apache.fineract.portfolio.charge.exception.LoanChargeWaiveCannotBeReversedException;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.exception.ClientNotActiveException;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.group.domain.Group;
import org.apache.fineract.portfolio.group.exception.GroupNotActiveException;
import org.apache.fineract.portfolio.loanaccount.api.LoanApiConstants;
import org.apache.fineract.portfolio.loanaccount.data.LoanChargePaidByData;
import org.apache.fineract.portfolio.loanaccount.data.ScheduleGeneratorDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeEffectiveDueDateComparator;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargeRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanDisbursementDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanEvent;
import org.apache.fineract.portfolio.loanaccount.domain.LoanInstallmentCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanInterestRecalcualtionAdditionalDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanLifecycleStateMachine;
import org.apache.fineract.portfolio.loanaccount.domain.LoanOverdueInstallmentCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleProcessingWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleTransactionProcessorFactory;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTrancheDisbursementCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRelation;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRelationTypeEnum;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.LoanRepaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.MoneyHolder;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.TransactionCtx;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.impl.AdvancedPaymentScheduleTransactionProcessor;
import org.apache.fineract.portfolio.loanaccount.exception.InstallmentNotFoundException;
import org.apache.fineract.portfolio.loanaccount.exception.InvalidLoanTransactionTypeException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanChargeAdjustmentException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanChargeDeactivationException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanChargeRefundException;
import org.apache.fineract.portfolio.loanaccount.exception.LoanTransactionNotFoundException;
import org.apache.fineract.portfolio.loanaccount.loanschedule.data.OverdueLoanScheduleData;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.DefaultScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeApiJsonValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanDownPaymentTransactionValidator;
import org.apache.fineract.portfolio.loanaccount.service.adjustment.LoanAdjustmentParameter;
import org.apache.fineract.portfolio.loanaccount.service.adjustment.LoanAdjustmentService;
import org.apache.fineract.portfolio.loanaccount.service.strategy.OverdueChargeCutoffDateResolver;
import org.apache.fineract.portfolio.loanproduct.data.LoanOverdueDTO;
import org.apache.fineract.portfolio.loanproduct.exception.LinkedAccountRequiredException;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.paymentdetail.service.PaymentDetailWritePlatformService;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
public class LoanChargeWritePlatformServiceImpl implements LoanChargeWritePlatformService {

    private static final String AMOUNT = "amount";
    private final LoanChargeApiJsonValidator loanChargeApiJsonValidator;
    private final LoanAssembler loanAssembler;
    private final ChargeRepositoryWrapper chargeRepository;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final LoanTransactionRepository loanTransactionRepository;
    private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;
    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final LoanAccountDomainService loanAccountDomainService;
    private final LoanChargeRepository loanChargeRepository;
    private final LoanWritePlatformService loanWritePlatformService;
    private final LoanUtilService loanUtilService;
    private final LoanChargeReadPlatformService loanChargeReadPlatformService;
    private final LoanLifecycleStateMachine loanLifecycleStateMachine;
    private final AccountAssociationsReadPlatformService accountAssociationsReadPlatformService;
    private final FromJsonHelper fromApiJsonHelper;
    private final ConfigurationDomainService configurationDomainService;
    private final LoanRepaymentScheduleTransactionProcessorFactory loanRepaymentScheduleTransactionProcessorFactory;
    private final ExternalIdFactory externalIdFactory;
    private final AccountTransferDetailRepository accountTransferDetailRepository;
    private final LoanChargeAssembler loanChargeAssembler;
    private final PaymentDetailWritePlatformService paymentDetailWritePlatformService;
    private final NoteRepository noteRepository;
    private final LoanAccrualsProcessingService loanAccrualsProcessingService;
    private final LoanDownPaymentTransactionValidator loanDownPaymentTransactionValidator;
    private final LoanChargeValidator loanChargeValidator;
    private final LoanScheduleService loanScheduleService;
    private final ReprocessLoanTransactionsService reprocessLoanTransactionsService;
    private final LoanAccountService loanAccountService;
    private final LoanAdjustmentService loanAdjustmentService;
    private final LoanChargeService loanChargeService;
    private final LoanJournalEntryPoster loanJournalEntryPoster;
    private final OverdueChargeCutoffDateResolver overdueChargeCutoffDateResolver;
    private final LoanReadPlatformService loanReadPlatformService;

    private static boolean isPartOfThisInstallment(LoanCharge loanCharge, LoanRepaymentScheduleInstallment e) {
        return DateUtils.isAfter(loanCharge.getDueDate(), e.getFromDate()) && !DateUtils.isAfter(loanCharge.getDueDate(), e.getDueDate());
    }

    @Transactional
    @Override
    public CommandProcessingResult addLoanCharge(final Long loanId, final JsonCommand command) {
        this.loanChargeApiJsonValidator.validateAddLoanCharge(command.json());

        Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);
        if (loan.isChargedOff()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.is.charged.off",
                    "Adding charge to Loan: " + loanId + " is not allowed. Loan Account is Charged-off", loanId);
        }
        List<LoanDisbursementDetails> loanDisburseDetails = loan.getDisbursementDetails();
        final Long chargeDefinitionId = command.longValueOfParameterNamed("chargeId");
        final Charge chargeDefinition = this.chargeRepository.findOneWithNotFoundDetection(chargeDefinitionId);

        if (loan.isDisbursed() && chargeDefinition.isDisbursementCharge()) {
            // validates whether any pending disbursements are available to
            // apply this charge
            validateAddingNewChargeAllowed(loanDisburseDetails);
        }

        boolean isAppliedOnBackDate = false;
        LoanCharge loanCharge = null;
        LocalDate recalculateFrom = loan.fetchInterestRecalculateFromDate();
        LocalDate transactionDate = null;
        if (chargeDefinition.isPercentageOfDisbursementAmount()) {
            LoanTrancheDisbursementCharge loanTrancheDisbursementCharge;
            ExternalId externalId = externalIdFactory.createFromCommand(command, "externalId");
            boolean isFirst = true;
            for (LoanDisbursementDetails disbursementDetail : loanDisburseDetails) {
                if (disbursementDetail.actualDisbursementDate() == null) {
                    // If multiple charges to be applied, only the first one will get the provided externalId, for the
                    // rest we generate new ones (if needed)
                    if (!isFirst) {
                        externalId = externalIdFactory.create();
                    }
                    LocalDate dueDate = disbursementDetail.expectedDisbursementDateAsLocalDate();
                    loanCharge = loanChargeAssembler.createNewWithoutLoan(chargeDefinition, disbursementDetail.principal(), null, null,
                            null, dueDate, null, null, externalId);
                    loanTrancheDisbursementCharge = new LoanTrancheDisbursementCharge(loanCharge, disbursementDetail);
                    loanCharge.updateLoanTrancheDisbursementCharge(loanTrancheDisbursementCharge);
                    businessEventNotifierService.notifyPreBusinessEvent(new LoanAddChargeBusinessEvent(loanCharge));
                    validateAddLoanCharge(loan, chargeDefinition, loanCharge);
                    addCharge(loan, chargeDefinition, loanCharge);
                    isAppliedOnBackDate = true;
                    if (DateUtils.isAfter(recalculateFrom, dueDate)) {
                        recalculateFrom = dueDate;
                    }
                    if (isFirst) {
                        transactionDate = loanCharge.getEffectiveDueDate();
                    }
                    isFirst = false;
                }
            }
            if (loanCharge == null) {
                final String errorMessage = "Charge with identifier " + chargeDefinition.getId()
                        + " cannot be applied: No valid loan disbursement available";
                throw new ChargeCannotBeAppliedToException("loan", errorMessage, chargeDefinition.getId());
            }
            loan.addTrancheLoanCharge(chargeDefinition);
        } else {
            loanCharge = loanChargeAssembler.createNewFromJson(loan, chargeDefinition, command);
            businessEventNotifierService.notifyPreBusinessEvent(new LoanAddChargeBusinessEvent(loanCharge));

            validateAddLoanCharge(loan, chargeDefinition, loanCharge);
            isAppliedOnBackDate = addCharge(loan, chargeDefinition, loanCharge);
            if (DateUtils.isAfter(recalculateFrom, loanCharge.getDueLocalDate())) {
                isAppliedOnBackDate = true;
                recalculateFrom = loanCharge.getDueLocalDate();
            }
            transactionDate = loanCharge.getEffectiveDueDate();
        }

        boolean reprocessRequired = true;
        // overpaid transactions will be reprocessed and pay this charge
        boolean overpaidReprocess = !loanCharge.isDueAtDisbursement() && !loanCharge.isPaid() && loan.getStatus().isOverpaid();
        if (!overpaidReprocess && loan.isInterestBearingAndInterestRecalculationEnabled()) {
            if (isAppliedOnBackDate && loan.isFeeCompoundingEnabledForInterestRecalculation()) {
                loan = runScheduleRecalculation(loan, recalculateFrom);
                reprocessRequired = false;
            }
            this.loanWritePlatformService.updateOriginalSchedule(loan);
        }
        if (!overpaidReprocess && AdvancedPaymentScheduleTransactionProcessor.ADVANCED_PAYMENT_ALLOCATION_STRATEGY
                .equals(loan.transactionProcessingStrategy())) {
            // [For Adv payment allocation strategy] check if charge due date is earlier than last transaction
            // date, if yes trigger reprocess else no reprocessing
            final Optional<LocalDate> lastPaymentTransactionDate = loanTransactionRepository.findLastTransactionDateForReprocessing(loan);
            if (lastPaymentTransactionDate.isPresent()
                    && DateUtils.isAfter(loanCharge.getEffectiveDueDate(), lastPaymentTransactionDate.get())) {
                reprocessRequired = false;
            }
        }

        if (reprocessRequired) {
            if (loan.isProgressiveSchedule() && ((loan.hasChargeOffTransaction() && loan.hasAccelerateChargeOffStrategy())
                    || loan.hasContractTerminationTransaction())) {
                final ScheduleGeneratorDTO scheduleGeneratorDTO = loanUtilService.buildScheduleGeneratorDTO(loan, null);
                loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
            }

            if (overpaidReprocess) {
                reprocessLoanTransactionsService.reprocessTransactionsWithPostTransactionChecks(loan, transactionDate);
            } else {
                reprocessLoanTransactionsService.reprocessTransactions(loan);
            }
            loanLifecycleStateMachine.determineAndTransition(loan, transactionDate);
            loan = loanAccountService.saveAndFlushLoanWithDataIntegrityViolationChecks(loan);
        }

        if (loan.isInterestBearingAndInterestRecalculationEnabled() && isAppliedOnBackDate
                && loan.isFeeCompoundingEnabledForInterestRecalculation()) {
            loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan, true, true);
        }
        this.loanAccountDomainService.setLoanDelinquencyTag(loan, DateUtils.getBusinessLocalDate());

        businessEventNotifierService.notifyPostBusinessEvent(new LoanAddChargeBusinessEvent(loanCharge));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));

        return new CommandProcessingResultBuilder().withCommandId(command.commandId()) //
                .withEntityId(loanCharge.getId()) //
                .withEntityExternalId(loanCharge.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .build();
    }

    @Transactional
    @Override
    public CommandProcessingResult loanChargeRefund(final Long loanId, final JsonCommand command) {

        this.loanChargeApiJsonValidator.validateLoanChargeRefundTransaction(command.json());

        final Long loanChargeId = command.longValueOfParameterNamed("loanChargeId");
        final LoanCharge loanCharge = retrieveLoanChargeBy(loanId, loanChargeId);

        final Integer installmentNumber = command.integerValueOfParameterNamed("installmentNumber");
        final LocalDate dueDate = command.localDateValueOfParameterNamed("dueDate");
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed("transactionAmount");

        final LoanInstallmentCharge installmentChargeEntry = loanChargeRefundEntranceValidation(loanCharge, installmentNumber, dueDate);
        Integer installmentNumberIdentified = null;
        if (installmentChargeEntry != null) {
            installmentNumberIdentified = installmentChargeEntry.getRepaymentInstallment().getInstallmentNumber();
        }
        final BigDecimal fullRefundAbleAmount = loanChargeValidateRefundAmount(loanCharge, installmentChargeEntry, transactionAmount);

        JsonCommand repaymentJsonCommand = adaptLoanChargeRefundCommandForFurtherRepaymentProcessing(command, fullRefundAbleAmount);

        boolean isRecoveryRepayment = false;
        String chargeRefundChargeType = "F";
        if (loanCharge.isPenaltyCharge()) {
            chargeRefundChargeType = "P";
        }
        // chargeRefundChargeType only included as a parameter for accounting reason - in order to identify whether fee
        // or penalty GL account is relevant
        CommandProcessingResult result = loanWritePlatformService.makeLoanRepaymentWithChargeRefundChargeType(
                LoanTransactionType.CHARGE_REFUND, repaymentJsonCommand.getLoanId(), repaymentJsonCommand, isRecoveryRepayment,
                chargeRefundChargeType);

        Long loanChargeRefundTransactionId = result.getResourceId();
        LoanTransaction newChargeRefundTxn = null;
        for (LoanTransaction chargeRefundTxn : loanCharge.getLoan().getLoanTransactions()) {
            if (loanChargeRefundTransactionId.equals(chargeRefundTxn.getId())) {
                newChargeRefundTxn = chargeRefundTxn;
                final BigDecimal appliedRefundAmount = newChargeRefundTxn.getAmount(loanCharge.getLoan().getCurrency()).getAmount()
                        .multiply(BigDecimal.valueOf(-1));
                final LoanChargePaidBy loanChargePaidByForChargeRefund = new LoanChargePaidBy(newChargeRefundTxn, loanCharge,
                        appliedRefundAmount, installmentNumberIdentified);
                newChargeRefundTxn.getLoanChargesPaid().add(loanChargePaidByForChargeRefund);
                loanCharge.getLoanChargePaidBySet().add(loanChargePaidByForChargeRefund);
                break;
            }
        }
        if (newChargeRefundTxn != null) {
            businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(newChargeRefundTxn.getLoan()));
        }
        businessEventNotifierService.notifyPostBusinessEvent(new LoanChargeRefundBusinessEvent(newChargeRefundTxn));
        return result;
    }

    @Transactional
    @Override
    public CommandProcessingResult undoWaiveLoanCharge(final JsonCommand command) {

        LoanTransaction loanTransaction = this.loanTransactionRepository.findByIdAndLoanId(command.entityId(), command.getLoanId())
                .orElseThrow(() -> new LoanTransactionNotFoundException(command.entityId(), command.getLoanId()));
        if (!loanTransaction.getTypeOf().getCode().equals(LoanTransactionType.WAIVE_CHARGES.getCode())) {
            throw new InvalidLoanTransactionTypeException("transaction", "undo.waive.charge", "Transaction is not a waive charge type.");
        }
        if (!loanTransaction.isNotReversed()) {
            throw new LoanChargeWaiveCannotBeReversedException(
                    LoanChargeWaiveCannotBeReversedException.LoanChargeWaiveCannotUndoReason.ALREADY_REVERSED, loanTransaction.getId());
        }

        Set<LoanChargePaidBy> loanChargePaidBySet = loanTransaction.getLoanChargesPaid();
        LoanChargePaidBy loanChargePaidBy = loanChargePaidBySet.stream().findFirst().orElseThrow(LoanChargeNotFoundException::new);
        final LoanCharge loanCharge = loanChargePaidBy.getLoanCharge();
        // Validate loan charge is not already paid
        if (loanCharge.isPaid()) {
            throw new LoanChargeWaiveCannotBeReversedException(
                    LoanChargeWaiveCannotBeReversedException.LoanChargeWaiveCannotUndoReason.ALREADY_PAID, loanCharge.getId());
        }

        final Long loanId = loanTransaction.getLoan().getId();
        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);
        // Charges may be waived only when the loan associated with them are
        // active
        if (!loan.getStatus().isActive()) {
            throw new LoanChargeWaiveCannotBeReversedException(
                    LoanChargeWaiveCannotBeReversedException.LoanChargeWaiveCannotUndoReason.LOAN_INACTIVE, loanCharge.getId());
        }
        if (loan.isChargedOff() && !DateUtils.isAfter(loanTransaction.getTransactionDate(), loan.getChargedOffOnDate())) {
            throw new GeneralPlatformDomainRuleException("error.msg.transaction.date.cannot.be.earlier.than.charge.off.date",
                    "Undo Loan transaction: " + loanTransaction.getId()
                            + " is not allowed before or on the date when the loan got charged-off",
                    loanTransaction.getId());
        }
        final Map<String, Object> changes = new LinkedHashMap<>();

        businessEventNotifierService.notifyPreBusinessEvent(new LoanWaiveChargeUndoBusinessEvent(loanCharge));

        undoWaivedCharge(changes, loan, loanTransaction, loanChargePaidBy);

        businessEventNotifierService.notifyPostBusinessEvent(new LoanWaiveChargeUndoBusinessEvent(loanCharge));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
        changes.put("principalPortion", loanTransaction.getPrincipalPortion());
        changes.put("interestPortion", loanTransaction.getInterestPortion());
        changes.put("feeChargesPortion", loanTransaction.getFeeChargesPortion());
        changes.put("penaltyChargesPortion", loanTransaction.getPenaltyChargesPortion());
        changes.put("outstandingLoanBalance", loanTransaction.getOutstandingLoanBalance());
        changes.put("id", loanTransaction.getId());
        changes.put("externalId", loanTransaction.getExternalId());
        changes.put("date", loanTransaction.getTransactionDate());

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(loanCharge.getId()) //
                .withEntityExternalId(loanCharge.getExternalId()) //
                .withSubEntityId(loanTransaction.getId()) //
                .withSubEntityExternalId(loanTransaction.getExternalId()) //
                .withLoanId(loanId) //
                .with(changes).build();
    }

    @Transactional
    @Override
    public CommandProcessingResult updateLoanCharge(final Long loanId, final Long loanChargeId, final JsonCommand command) {

        this.loanChargeApiJsonValidator.validateUpdateOfLoanCharge(command.json());

        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);
        final LoanCharge loanCharge = retrieveLoanChargeBy(loanId, loanChargeId);

        // Charges may be edited only when the loan associated with them are
        // yet to be approved (are in submitted and pending status)
        if (!loan.getStatus().isSubmittedAndPendingApproval()) {
            throw new LoanChargeCannotBeUpdatedException(
                    LoanChargeCannotBeUpdatedException.LoanChargeCannotBeUpdatedReason.LOAN_NOT_IN_SUBMITTED_AND_PENDING_APPROVAL_STAGE,
                    loanCharge.getId());
        }
        businessEventNotifierService.notifyPreBusinessEvent(new LoanUpdateChargeBusinessEvent(loanCharge));

        loanChargeValidator.validateLoanIsNotClosed(loan, loanCharge);

        final Map<String, Object> changes = new LinkedHashMap<>(3);
        if (loan.getActiveCharges().contains(loanCharge)) {
            final BigDecimal amount = loanChargeService.calculateAmountPercentageAppliedTo(loan, loanCharge);
            final Map<String, Object> loanChargeChanges = loanChargeService.update(command, amount, loanCharge);
            changes.putAll(loanChargeChanges);
            loan.updateSummaryWithTotalFeeChargesDueAtDisbursement(loan.deriveSumTotalOfChargesDueAtDisbursement());
        }

        if (!loanCharge.isDueAtDisbursement()) {
            reprocessLoanTransactionsService.reprocessTransactions(loan);
        } else {
            // reprocess loan schedule based on charge been waived.
            final LoanRepaymentScheduleProcessingWrapper wrapper = new LoanRepaymentScheduleProcessingWrapper();
            wrapper.reprocess(loan.getCurrency(), loan.getDisbursementDate(), loan.getRepaymentScheduleInstallments(),
                    loan.getActiveCharges());
        }

        this.loanRepositoryWrapper.save(loan);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanUpdateChargeBusinessEvent(loanCharge));
        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(loanChargeId) //
                .withEntityExternalId(loanCharge.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .with(changes) //
                .build();
    }

    @Transactional
    @Override
    public CommandProcessingResult waiveLoanCharge(final Long loanId, final Long loanChargeId, final JsonCommand command) {

        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);
        this.loanChargeApiJsonValidator.validateInstallmentChargeTransaction(command.json());
        final ExternalId externalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);
        final LoanCharge loanCharge = retrieveLoanChargeBy(loanId, loanChargeId);

        // Charges may be waived only when the loan associated with them are
        // active
        if (!loan.getStatus().isActive()) {
            throw new LoanChargeCannotBeWaivedException(LoanChargeCannotBeWaivedException.LoanChargeCannotBeWaivedReason.LOAN_INACTIVE,
                    loanCharge.getId());
        }

        // validate loan charge is not already paid or waived
        if (loanCharge.isWaived()) {
            throw new LoanChargeCannotBeWaivedException(LoanChargeCannotBeWaivedException.LoanChargeCannotBeWaivedReason.ALREADY_WAIVED,
                    loanCharge.getId());
        } else if (loanCharge.isPaid()) {
            throw new LoanChargeCannotBeWaivedException(LoanChargeCannotBeWaivedException.LoanChargeCannotBeWaivedReason.ALREADY_PAID,
                    loanCharge.getId());
        }
        businessEventNotifierService.notifyPreBusinessEvent(new LoanWaiveChargeBusinessEvent(loanCharge));
        Integer loanInstallmentNumber = null;
        if (loanCharge.isInstalmentFee()) {
            LoanInstallmentCharge chargePerInstallment = null;
            if (!StringUtils.isBlank(command.json())) {
                final LocalDate dueDate = command.localDateValueOfParameterNamed("dueDate");
                final Integer installmentNumber = command.integerValueOfParameterNamed("installmentNumber");
                if (dueDate != null) {
                    chargePerInstallment = loanCharge.getInstallmentLoanCharge(dueDate);
                } else if (installmentNumber != null) {
                    chargePerInstallment = loanCharge.getInstallmentLoanCharge(installmentNumber);
                }
            }
            if (chargePerInstallment == null) {
                chargePerInstallment = loanCharge.getUnpaidInstallmentLoanCharge();
            }
            if (chargePerInstallment.isWaived()) {
                throw new LoanChargeCannotBePayedException(LoanChargeCannotBePayedException.LoanChargeCannotBePayedReason.ALREADY_WAIVED,
                        loanCharge.getId());
            } else if (chargePerInstallment.isPaid()) {
                throw new LoanChargeCannotBePayedException(LoanChargeCannotBePayedException.LoanChargeCannotBePayedReason.ALREADY_PAID,
                        loanCharge.getId());
            }
            loanInstallmentNumber = chargePerInstallment.getRepaymentInstallment().getInstallmentNumber();
        }

        final Map<String, Object> changes = new LinkedHashMap<>();
        changes.put(LoanApiConstants.externalIdParameterName, externalId);

        LocalDate recalculateFrom = null;
        ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);

        Money accruedCharge = Money.zero(loan.getCurrency());
        if (loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
            Collection<LoanChargePaidByData> chargePaidByCollection = this.loanChargeReadPlatformService
                    .retrieveLoanChargesPaidBy(loanCharge.getId(), LoanTransactionType.ACCRUAL, loanInstallmentNumber);
            for (LoanChargePaidByData chargePaidByData : chargePaidByCollection) {
                accruedCharge = accruedCharge.plus(chargePaidByData.getAmount());
            }
        }

        loanChargeValidator.validateLoanIsNotClosed(loan, loanCharge);
        final LoanTransaction waiveTransaction = waiveLoanCharge(loan, loanCharge, changes, loanInstallmentNumber, scheduleGeneratorDTO,
                accruedCharge, externalId);

        if (loan.isInterestBearingAndInterestRecalculationEnabled()
                && DateUtils.isBefore(loanCharge.getDueLocalDate(), DateUtils.getBusinessLocalDate())) {
            loanAccrualsProcessingService.reprocessExistingAccruals(loan, true);
            loanAccrualsProcessingService.processIncomePostingAndAccruals(loan, true);
        }

        this.loanTransactionRepository.saveAndFlush(waiveTransaction);
        this.loanRepositoryWrapper.save(loan);

        loanJournalEntryPoster.postJournalEntriesForLoanTransaction(waiveTransaction, false, false);

        // For NPA loans with periodic accrual accounting, create ACCRUAL_WRITEOFF transaction for waived charges
        createAccrualWriteoffForWaivedCharges(loan, waiveTransaction);

        this.loanAccountDomainService.setLoanDelinquencyTag(loan, DateUtils.getBusinessLocalDate());
        businessEventNotifierService.notifyPostBusinessEvent(new LoanWaiveChargeBusinessEvent(loanCharge));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(loanChargeId) //
                .withEntityExternalId(loanCharge.getExternalId()) //
                .withSubEntityId(waiveTransaction.getId()) //
                .withSubEntityExternalId(waiveTransaction.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .with(changes) //
                .build();
    }

    @Transactional
    @Override
    public CommandProcessingResult deleteLoanCharge(final Long loanId, final Long loanChargeId, final JsonCommand command) {

        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);
        final LoanCharge loanCharge = retrieveLoanChargeBy(loanId, loanChargeId);

        // Charges may be deleted only when the loan associated with them are
        // yet to be approved (are in submitted and pending status)
        if (!loan.getStatus().isSubmittedAndPendingApproval()) {
            throw new LoanChargeCannotBeDeletedException(
                    LoanChargeCannotBeDeletedException.LoanChargeCannotBeDeletedReason.LOAN_NOT_IN_SUBMITTED_AND_PENDING_APPROVAL_STAGE,
                    loanCharge.getId());
        }
        businessEventNotifierService.notifyPreBusinessEvent(new LoanDeleteChargeBusinessEvent(loanCharge));

        loanChargeValidator.validateLoanIsNotClosed(loan, loanCharge);
        loanChargeValidator.validateLoanChargeIsNotWaived(loan, loanCharge);
        reprocessLoanTransactionsService.removeLoanCharge(loan, loanCharge);
        this.loanRepositoryWrapper.save(loan);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanDeleteChargeBusinessEvent(loanCharge));
        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(loanChargeId) //
                .withEntityExternalId(loanCharge.getExternalId()) //
                .withOfficeId(loan.getOfficeId()) //
                .withClientId(loan.getClientId()) //
                .withGroupId(loan.getGroupId()) //
                .withLoanId(loanId) //
                .build();
    }

    @Transactional
    @Override
    public CommandProcessingResult payLoanCharge(final Long loanId, Long loanChargeId, final JsonCommand command,
            final boolean isChargeIdIncludedInJson) {

        boolean isAccountTransfer = !command.parameterExists("isAccountTransfer")
                || command.booleanPrimitiveValueOfParameterNamed("isAccountTransfer");
        this.loanChargeApiJsonValidator.validateChargePaymentTransaction(command.json(), isChargeIdIncludedInJson);
        if (isChargeIdIncludedInJson) {
            loanChargeId = command.longValueOfParameterNamed("chargeId");
        }
        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);
        final LoanCharge loanCharge = retrieveLoanChargeBy(loanId, loanChargeId);
        final ExternalId externalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);

        // Charges may be waived only when the loan associated with them are
        // active
        if (!loan.getStatus().isActive()) {
            throw new LoanChargeCannotBePayedException(LoanChargeCannotBePayedException.LoanChargeCannotBePayedReason.LOAN_INACTIVE,
                    loanCharge.getId());
        }

        // validate loan charge is not already paid or waived
        if (loanCharge.isWaived()) {
            throw new LoanChargeCannotBePayedException(LoanChargeCannotBePayedException.LoanChargeCannotBePayedReason.ALREADY_WAIVED,
                    loanCharge.getId());
        } else if (loanCharge.isPaid()) {
            throw new LoanChargeCannotBePayedException(LoanChargeCannotBePayedException.LoanChargeCannotBePayedReason.ALREADY_PAID,
                    loanCharge.getId());
        }

        if (isAccountTransfer && !loanCharge.getChargePaymentMode().isPaymentModeAccountTransfer()) {
            throw new LoanChargeCannotBePayedException(
                    LoanChargeCannotBePayedException.LoanChargeCannotBePayedReason.CHARGE_NOT_ACCOUNT_TRANSFER, loanCharge.getId());
        }

        final LocalDate transactionDate = command.localDateValueOfParameterNamed("transactionDate");

        final Locale locale = command.extractLocale();
        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(command.dateFormat()).withLocale(locale);
        Integer loanInstallmentNumber = null;
        BigDecimal amount = loanCharge.amountOutstanding();
        if (loanCharge.isInstalmentFee()) {
            LoanInstallmentCharge chargePerInstallment = null;
            final LocalDate dueDate = command.localDateValueOfParameterNamed("dueDate");
            final Integer installmentNumber = command.integerValueOfParameterNamed("installmentNumber");
            if (dueDate != null) {
                chargePerInstallment = loanCharge.getInstallmentLoanCharge(dueDate);
            } else if (installmentNumber != null) {
                chargePerInstallment = loanCharge.getInstallmentLoanCharge(installmentNumber);
            }
            if (chargePerInstallment == null) {
                chargePerInstallment = loanCharge.getUnpaidInstallmentLoanCharge();
            }
            if (chargePerInstallment.isWaived()) {
                throw new LoanChargeCannotBePayedException(LoanChargeCannotBePayedException.LoanChargeCannotBePayedReason.ALREADY_WAIVED,
                        loanCharge.getId());
            } else if (chargePerInstallment.isPaid()) {
                throw new LoanChargeCannotBePayedException(LoanChargeCannotBePayedException.LoanChargeCannotBePayedReason.ALREADY_PAID,
                        loanCharge.getId());
            }
            loanInstallmentNumber = chargePerInstallment.getRepaymentInstallment().getInstallmentNumber();
            amount = chargePerInstallment.getAmountOutstanding();
        }
        if (isAccountTransfer) {
            final PortfolioAccountData portfolioAccountData = this.accountAssociationsReadPlatformService
                    .retriveLoanLinkedAssociation(loanId);
            if (portfolioAccountData == null) {
                final String errorMessage = "Charge with id:" + loanChargeId + " requires linked savings account for payment";
                throw new LinkedAccountRequiredException("loanCharge.pay", errorMessage, loanChargeId);
            }
            final SavingsAccount fromSavingsAccount = null;
            final boolean isRegularTransaction = true;
            final boolean isExceptionForBalanceCheck = false;
            final AccountTransferDTO accountTransferDTO = new AccountTransferDTO(transactionDate, amount, PortfolioAccountType.SAVINGS,
                    PortfolioAccountType.LOAN, portfolioAccountData.getId(), loanId, "Loan Charge Payment", locale, fmt, null, null,
                    LoanTransactionType.CHARGE_PAYMENT.getValue(), loanChargeId, loanInstallmentNumber,
                    AccountTransferType.CHARGE_PAYMENT.getValue(), null, null, externalId, null, null, fromSavingsAccount,
                    isRegularTransaction, isExceptionForBalanceCheck);
            Long transferTransactionId = this.accountTransfersWritePlatformService.transferFunds(accountTransferDTO);
            AccountTransferDetails transferDetails = this.accountTransferDetailRepository.findById(transferTransactionId)
                    .orElseThrow(() -> new AccountTransferNotFoundException(transferTransactionId));
            LoanTransaction loanTransaction = transferDetails.getAccountTransferTransactions().get(0).getToLoanTransaction();
            businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
            return new CommandProcessingResultBuilder() //
                    .withCommandId(command.commandId()) //
                    .withEntityId(loanChargeId) //
                    .withEntityExternalId(loanCharge.getExternalId()) //
                    .withSubEntityId(loanTransaction.getId()) //
                    .withSubEntityExternalId(loanTransaction.getExternalId()) //
                    .withOfficeId(loan.getOfficeId()) //
                    .withClientId(loan.getClientId()) //
                    .withGroupId(loan.getGroupId()) //
                    .withLoanId(loanId) //
                    .withSavingsId(portfolioAccountData.getId()).build();
        } else {
            PaymentDetail paymentDetail = paymentDetailWritePlatformService.createPaymentDetail(command, null);
            String noteText = command.stringValueOfParameterNamedAllowingNull("note");
            LoanTransaction loanTransaction = this.loanAccountDomainService.makeChargePayment(loan, loanChargeId, transactionDate, amount,
                    paymentDetail, noteText, externalId, LoanTransactionType.CHARGE_PAYMENT.getValue(), null, isAccountTransfer);
            businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
            return new CommandProcessingResultBuilder() //
                    .withCommandId(command.commandId()) //
                    .withEntityId(loanChargeId) //
                    .withEntityExternalId(loanCharge.getExternalId()) //
                    .withSubEntityId(loanTransaction.getId()) //
                    .withSubEntityExternalId(loanTransaction.getExternalId()) //
                    .withOfficeId(loan.getOfficeId()) //
                    .withClientId(loan.getClientId()) //
                    .withGroupId(loan.getGroupId()) //
                    .withLoanId(loanId) //
                    .build();
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult adjustmentForLoanCharge(Long loanId, Long loanChargeId, JsonCommand command) {
        this.loanChargeApiJsonValidator.validateLoanChargeAdjustmentRequest(loanId, loanChargeId, command.json());

        final LoanCharge loanCharge = retrieveLoanChargeBy(loanId, loanChargeId);
        final LocalDate transactionDate = DateUtils.getBusinessLocalDate();
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed("amount");
        final ExternalId externalId = externalIdFactory.createFromCommand(command, "externalId");
        final String locale = command.locale();

        Map<String, Object> changes = new HashMap<>();
        changes.put("externalId", externalId);
        changes.put("amount", transactionAmount);
        changes.put("transactionDate", transactionDate);
        changes.put("locale", locale);

        loanChargeAdjustmentEntranceValidation(loanCharge, transactionAmount);
        final Loan loan = loanAssembler.assembleFrom(loanId);

        final CommandProcessingResultBuilder commandProcessingResultBuilder = new CommandProcessingResultBuilder();
        PaymentDetail paymentDetail = this.paymentDetailWritePlatformService.createPaymentDetail(command, changes);
        if (paymentDetail != null) {
            paymentDetail = this.paymentDetailWritePlatformService.persistPaymentDetail(paymentDetail);
        }
        LoanTransaction loanTransaction = applyChargeAdjustment(loan, loanCharge, transactionAmount, transactionDate, externalId,
                paymentDetail);

        // Update loan transaction on repayment.
        loanAccountDomainService.updateAndSaveLoanCollateralTransactionsForIndividualAccounts(loan, loanTransaction);

        final String noteText = command.stringValueOfParameterNamed("note");
        if (StringUtils.isNotBlank(noteText)) {
            final Note note = Note.loanNote(loan, noteText);
            changes.put("note", noteText);
            this.noteRepository.save(note);
        }

        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanChargeAdjustmentPostBusinessEvent(loanTransaction));

        return commandProcessingResultBuilder.withCommandId(command.commandId()) //
                .withLoanId(loanId) //
                .withEntityId(loanChargeId) //
                .withEntityExternalId(loanCharge.getExternalId()) //
                .withSubEntityId(loanTransaction.getId()) //
                .withSubEntityExternalId(loanTransaction.getExternalId()) //
                .with(changes) //
                .build();
    }

    @Transactional
    @Override
    public CommandProcessingResult deactivateOverdueLoanCharge(Long loanId, JsonCommand command) {
        LocalDate fromDueDate = command.dateValueOfParameterNamed("dueDate");

        List<LoanCharge> loanCharges = loanChargeRepository.findByLoanIdAndFromDueDate(loanId, fromDueDate);
        loanCharges.forEach(this::inactivateOverdueLoanCharge);

        Loan loan = loanAssembler.assembleFrom(loanId);
        List<LoanRepaymentScheduleInstallment> repaymentScheduleInstallments = loan
                .getRepaymentScheduleInstallments(si -> DateUtils.isDateInRangeInclusive(fromDueDate, si.getFromDate(), si.getDueDate())
                        || DateUtils.isAfter(si.getFromDate(), fromDueDate));
        repaymentScheduleInstallments.forEach(si -> si.setPenaltyAccrued(null));
        List<LoanTransaction> accrualsToReverse = loan.getLoanTransactions(
                tx -> tx.isNotReversed() && DateUtils.isAfterInclusive(tx.getTransactionDate(), fromDueDate) && tx.isAccrualRelated());
        accrualsToReverse.forEach(tx -> loanAdjustmentService.adjustLoanTransaction(loan, tx,
                LoanAdjustmentParameter.builder().transactionDate(tx.getTransactionDate()).build(), null, new HashMap<>()));

        loanRepositoryWrapper.saveAndFlush(loan);

        final CommandProcessingResultBuilder commandProcessingResultBuilder = new CommandProcessingResultBuilder();
        return commandProcessingResultBuilder.withLoanId(loanId) //
                .withEntityId(loanId) //
                .withEntityExternalId(loan.getExternalId()) //
                .build();
    }

    private void inactivateOverdueLoanCharge(LoanCharge loanCharge) {
        if (!loanCharge.getChargeTimeType().isOverdueInstallment()) {
            throw new LoanChargeDeactivationException("loan.charge.deactivate.invalid.charge.type",
                    "Loan charge is not an overdue installment charge");
        }

        if (!loanCharge.isActive()) {
            throw new LoanChargeDeactivationException("loan.charge.deactivate.invalid.status", "Loan charge is not active");
        }

        loanCharge.setActive(false);
        loanChargeRepository.saveAndFlush(loanCharge);

        businessEventNotifierService.notifyPostBusinessEvent(new LoanUpdateChargeBusinessEvent(loanCharge));
    }

    @Transactional
    @Override
    public void applyOverdueChargesForLoan(final Long loanId, Collection<OverdueLoanScheduleData> overdueLoanScheduleDataList) {
        if (overdueLoanScheduleDataList.isEmpty()) {
            log.info("Apply overdue charges for loan {}: skipping, no overdue installments to process", loanId);
            return;
        }
        log.info("Apply overdue charges for loan {}: starting, {} overdue installment(s) to process", loanId,
                overdueLoanScheduleDataList.size());

        Loan loan = this.loanAssembler.assembleFrom(loanId);
        if (loan.isChargedOff()) {
            log.warn("Apply overdue charges for loan {}: skipping, loan is charged-off", loanId);
            return;
        }
        Optional<Charge> optPenaltyCharge = loan.getLoanProduct().getCharges().stream()
                .filter((e) -> ChargeTimeType.OVERDUE_INSTALLMENT.getValue().equals(e.getChargeTimeType()) && e.isLoanCharge()).findFirst();
        if (optPenaltyCharge.isEmpty()) {
            log.info("Apply overdue charges for loan {}: skipping, loan product has no overdue-installment charge defined", loanId);
            return;
        }
        final Charge penaltyCharge = optPenaltyCharge.get();
        final BigDecimal maxCumulativePenaltyCap = penaltyCharge.getMaxCumulativePenaltyCap();
        BigDecimal remainingCumulativePenaltyCap = null;
        if (maxCumulativePenaltyCap != null) {
            BigDecimal alreadyApplied = calculateExistingPenaltyAmountForCharge(loan, penaltyCharge);
            remainingCumulativePenaltyCap = maxCumulativePenaltyCap.subtract(alreadyApplied);
            if (remainingCumulativePenaltyCap.compareTo(BigDecimal.ZERO) <= 0) {
                log.info("Apply overdue charges for loan {}: skipping, cumulative penalty cap already reached (cap={}, alreadyApplied={})",
                        loanId, maxCumulativePenaltyCap, alreadyApplied);
                return;
            }
        }
        boolean runInterestRecalculation = false;
        LocalDate recalculateFrom = DateUtils.getBusinessLocalDate();
        LocalDate lastChargeDate = null;
        int installmentsProcessed = 0;
        for (final OverdueLoanScheduleData overdueInstallment : overdueLoanScheduleDataList) {

            final JsonElement parsedCommand = this.fromApiJsonHelper.parse(overdueInstallment.toString());
            final JsonCommand command = JsonCommand.from(overdueInstallment.toString(), parsedCommand, this.fromApiJsonHelper, null, null,
                    null, null, null, loanId, null, null, null, null, null, null, null, null);

            LoanOverdueDTO overdueDTO = applyChargeToOverdueLoanInstallment(loan, overdueInstallment.getChargeId(),
                    overdueInstallment.getPeriodNumber(), command, remainingCumulativePenaltyCap);

            loan = overdueDTO.getLoan();
            remainingCumulativePenaltyCap = overdueDTO.getRemainingCumulativePenaltyCap();
            runInterestRecalculation = runInterestRecalculation || overdueDTO.isRunInterestRecalculation();
            if (DateUtils.isAfter(recalculateFrom, overdueDTO.getRecalculateFrom())) {
                recalculateFrom = overdueDTO.getRecalculateFrom();
            }
            if (lastChargeDate == null || DateUtils.isAfter(overdueDTO.getLastChargeAppliedDate(), lastChargeDate)) {
                lastChargeDate = overdueDTO.getLastChargeAppliedDate();
            }
            installmentsProcessed++;
            if (remainingCumulativePenaltyCap != null && remainingCumulativePenaltyCap.compareTo(BigDecimal.ZERO) <= 0) {
                log.info("Apply overdue charges for loan {}: cumulative penalty cap reached after processing {} installment(s)", loanId,
                        installmentsProcessed);
                break;
            }
        }
        if (loan != null) {
            boolean reprocessRequired = true;
            LocalDate recalculatedTill = loan.fetchInterestRecalculateFromDate();
            if (DateUtils.isAfter(recalculateFrom, recalculatedTill)) {
                recalculateFrom = recalculatedTill;
            }

            if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
                if (runInterestRecalculation && loan.isFeeCompoundingEnabledForInterestRecalculation()) {
                    loan = runScheduleRecalculation(loan, recalculateFrom);
                    reprocessRequired = false;
                }
                this.loanWritePlatformService.updateOriginalSchedule(loan);
            }

            if (reprocessRequired) {
                addInstallmentIfPenaltyAppliedAfterLastDueDate(loan, lastChargeDate);
                if (loan.isProgressiveSchedule() && ((loan.hasChargeOffTransaction() && loan.hasAccelerateChargeOffStrategy())
                        || loan.hasContractTerminationTransaction())) {
                    final ScheduleGeneratorDTO scheduleGeneratorDTO = loanUtilService.buildScheduleGeneratorDTO(loan, null);
                    loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
                }
                reprocessLoanTransactionsService.reprocessTransactions(loan);
                loan = loanAccountService.saveAndFlushLoanWithDataIntegrityViolationChecks(loan);
            }

            if (loan.isInterestBearingAndInterestRecalculationEnabled() && runInterestRecalculation
                    && loan.isFeeCompoundingEnabledForInterestRecalculation()) {
                loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan, true, true);
            }
            this.loanAccountDomainService.setLoanDelinquencyTag(loan, DateUtils.getBusinessLocalDate());
            log.info(
                    "Apply overdue charges for loan {}: completed, installmentsProcessed={}, runInterestRecalculation={}, reprocessRequired={}",
                    loanId, installmentsProcessed, runInterestRecalculation, reprocessRequired);
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult applyOverdueChargesForLoanByLoanId(final Long loanId) {
        Loan loan = this.loanAssembler.assembleFrom(loanId);
        final Collection<OverdueLoanScheduleData> overdueLoanScheduleDataList = loanReadPlatformService
                .retrieveAllOverdueInstallmentsForLoan(loan);
        if (overdueLoanScheduleDataList.isEmpty()) {
            log.debug("No overdue installments to apply penalty charges for loan {}", loanId);
        } else {
            log.info("Applying overdue penalty charges for loan {}, {} overdue installment(s)", loanId, overdueLoanScheduleDataList.size());
            applyOverdueChargesForLoan(loanId, overdueLoanScheduleDataList);
        }
        return new CommandProcessingResultBuilder().withLoanId(loanId).build();
    }

    private LoanTransaction applyChargeAdjustment(final Loan loan, final LoanCharge loanCharge, final BigDecimal transactionAmount,
            final LocalDate transactionDate, final ExternalId txnExternalId, PaymentDetail paymentDetail) {
        businessEventNotifierService.notifyPreBusinessEvent(new LoanChargeAdjustmentPreBusinessEvent(loan));

        LoanTransaction loanChargeAdjustmentTransaction = LoanTransaction.chargeAdjustment(loan, transactionAmount, transactionDate,
                txnExternalId, paymentDetail);
        LoanTransactionRelation loanTransactionRelation = LoanTransactionRelation.linkToCharge(loanChargeAdjustmentTransaction, loanCharge,
                LoanTransactionRelationTypeEnum.CHARGE_ADJUSTMENT);
        loanChargeAdjustmentTransaction.getLoanTransactionRelations().add(loanTransactionRelation);

        final LoanRepaymentScheduleTransactionProcessor loanRepaymentScheduleTransactionProcessor = loanRepaymentScheduleTransactionProcessorFactory
                .determineProcessor(loan.transactionProcessingStrategy());
        loan.addLoanTransaction(loanChargeAdjustmentTransaction);
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            if (loan.isProgressiveSchedule() && ((loan.hasChargeOffTransaction() && loan.hasAccelerateChargeOffStrategy())
                    || loan.hasContractTerminationTransaction())) {
                final ScheduleGeneratorDTO scheduleGeneratorDTO = loanUtilService.buildScheduleGeneratorDTO(loan, null);
                loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
            }
            reprocessLoanTransactionsService.reprocessTransactions(loan);
        } else {
            loanRepaymentScheduleTransactionProcessor.processLatestTransaction(loanChargeAdjustmentTransaction,
                    new TransactionCtx(loan.getCurrency(), loan.getRepaymentScheduleInstallments(), loan.getActiveCharges(),
                            new MoneyHolder(loan.getTotalOverpaidAsMoney()), null));
        }
        loanAccountService.saveLoanTransactionWithDataIntegrityViolationChecks(loanChargeAdjustmentTransaction);
        loanLifecycleStateMachine.determineAndTransition(loan, loan.getLastUserTransactionDate());

        loanAccountService.saveAndFlushLoanWithDataIntegrityViolationChecks(loan);
        loanJournalEntryPoster.postJournalEntriesForLoanTransaction(loanChargeAdjustmentTransaction, false, false);
        loanAccountDomainService.setLoanDelinquencyTag(loan, transactionDate);

        return loanChargeAdjustmentTransaction;
    }

    private void undoWaivedCharge(final Map<String, Object> changes, final Loan loan, final LoanTransaction loanTransaction,
            final LoanChargePaidBy loanChargePaidBy) {
        switch (loanChargePaidBy.getLoanCharge().getChargeTimeType()) {
            case SPECIFIED_DUE_DATE -> undoSpecifiedDueDateCharge(changes, loan, loanTransaction, loanChargePaidBy);
            case INSTALMENT_FEE -> undoInstalmentFee(changes, loan, loanTransaction, loanChargePaidBy);
            default -> throw new UnsupportedOperationException(
                    "Undo waive charge is not support for this charge: " + loanChargePaidBy.getLoanCharge().getChargeTimeType());
        }
    }

    private void undoInstalmentFee(Map<String, Object> changes, Loan loan, LoanTransaction loanTransaction,
            LoanChargePaidBy loanChargePaidBy) {
        LoanCharge loanCharge = loanChargePaidBy.getLoanCharge();
        final Integer installmentNumber = loanChargePaidBy.getInstallmentNumber();
        LoanInstallmentCharge chargePerInstallment;
        // final Integer installmentNumber = command.integerValueOfParameterNamed("installmentNumber");
        if (installmentNumber != null) {
            // Get installment charge.
            chargePerInstallment = loanCharge.getInstallmentLoanCharge(installmentNumber);
            // Get installment amount waived.
            BigDecimal amountWaived = chargePerInstallment.getAmountWaived(loan.getCurrency()).getAmount();
            // Check whether the installment charge is not waived. If so throw new error
            if (!chargePerInstallment.isWaived() || amountWaived == null) {
                throw new LoanChargeWaiveCannotBeReversedException(
                        LoanChargeWaiveCannotBeReversedException.LoanChargeWaiveCannotUndoReason.NOT_WAIVED, loanCharge.getId());
            }
            loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(loanTransaction.getLoan(), loanTransaction,
                    "reversed");
            // Reverse waived transaction
            loanTransaction.reverse();
            // Set manually adjusted value to `1`
            loanTransaction.setManuallyAdjustedOrReversed();
            // Get loan charge outstanding amount
            BigDecimal amountOutstanding = loanCharge.getAmountOutstanding(loan.getCurrency()).getAmount();
            // Add the amount waived to outstanding amount
            loanCharge.setOutstandingAmount(amountOutstanding.add(amountWaived));
            // Get loan charge total amount waived
            BigDecimal totalAmountWaved = loanCharge.getAmountWaived(loan.getCurrency()).getAmount();
            // Subtract the amount waived from the existing amount waived.
            loanCharge.setAmountWaived(totalAmountWaved.subtract(amountWaived));
            // Get installment outstanding amount
            BigDecimal amountOutstandingPerInstallment = chargePerInstallment.getAmountOutstanding();
            // Add the amount waived to the outstanding amount of the installment
            chargePerInstallment.setOutstandingAmount(amountOutstandingPerInstallment.add(amountWaived));
            // Set the amount waived value to ZERO
            chargePerInstallment.setAmountWaived(null);
            // Reset waived flag
            chargePerInstallment.undoWaiveFlag();
            // Update installment balances
            updateRepaymentInstalmentWithWaivedAmount(loanCharge, chargePerInstallment.getInstallment(), amountWaived);
            // Update loan charge.
            loanCharge.setInstallmentLoanCharge(chargePerInstallment, chargePerInstallment.getInstallment().getInstallmentNumber());
            if (loanCharge.amount().compareTo(loanCharge.amountOutstanding()) == 0 && loanCharge.isWaived()) {
                loanCharge.undoWaived();
            }
            loan.updateLoanSummaryForUndoWaiveCharge(amountWaived, loanCharge.isPenaltyCharge());
            loanJournalEntryPoster.postJournalEntriesForLoanTransaction(loanTransaction, false, false);
            changes.put(AMOUNT, amountWaived);
        } else {
            throw new InstallmentNotFoundException(loanTransaction.getId());
        }
    }

    private void undoSpecifiedDueDateCharge(final Map<String, Object> changes, final Loan loan, final LoanTransaction loanTransaction,
            final LoanChargePaidBy loanChargePaidBy) {
        LoanCharge loanCharge = loanChargePaidBy.getLoanCharge();
        BigDecimal amountWaived = loanCharge.getAmountWaived(loan.getCurrency()).getAmount();
        if (!loanCharge.isWaived() || amountWaived == null) {
            throw new LoanChargeWaiveCannotBeReversedException(
                    LoanChargeWaiveCannotBeReversedException.LoanChargeWaiveCannotUndoReason.NOT_WAIVED, loanCharge.getId());
        }
        loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(loanTransaction.getLoan(), loanTransaction, "reversed");
        loanTransaction.reverse();
        loanTransaction.setManuallyAdjustedOrReversed();
        loanCharge.setOutstandingAmount(loanCharge.amountOutstanding().add(amountWaived));
        loanCharge.setAmountWaived(null);
        loanCharge.undoWaived();
        LoanRepaymentScheduleInstallment installment = loan.getRepaymentScheduleInstallments().stream()
                .filter(e -> isPartOfThisInstallment(loanCharge, e)).findFirst().orElseThrow();
        updateRepaymentInstalmentWithWaivedAmount(loanCharge, installment, amountWaived);
        loan.updateLoanSummaryForUndoWaiveCharge(amountWaived, loanCharge.isPenaltyCharge());
        loanJournalEntryPoster.postJournalEntriesForLoanTransaction(loanTransaction, false, false);
        changes.put(AMOUNT, amountWaived);
    }

    private void updateRepaymentInstalmentWithWaivedAmount(final LoanCharge loanCharge, final LoanRepaymentScheduleInstallment installment,
            final BigDecimal amountWaived) {
        if (loanCharge.isPenaltyCharge()) {
            // Get the penalty charges waived amount per installment
            BigDecimal penaltyChargesWaivedAmount = installment.getPenaltyChargesWaived(loanCharge.getLoan().getCurrency()).getAmount();
            // Subtract the amount waived from the existing fee charges waived amount.
            installment.setPenaltyChargesWaived(penaltyChargesWaivedAmount.subtract(amountWaived));
        } else {
            // Get the fee charges waived amount per installment
            BigDecimal feeChargesWaivedAmount = installment.getFeeChargesWaived(loanCharge.getLoan().getCurrency()).getAmount();
            // Subtract the amount waived from the existing fee charges waived amount.
            installment.setFeeChargesWaived(feeChargesWaivedAmount.subtract(amountWaived));
        }
    }

    private void validateAddingNewChargeAllowed(List<LoanDisbursementDetails> loanDisburseDetails) {
        boolean pendingDisbursementAvailable = false;
        for (LoanDisbursementDetails disbursementDetail : loanDisburseDetails) {
            if (disbursementDetail.actualDisbursementDate() == null) {
                pendingDisbursementAvailable = true;
                break;
            }
        }
        if (!pendingDisbursementAvailable) {
            throw new ChargeCannotBeUpdatedException("error.msg.charge.cannot.be.updated.no.pending.disbursements.in.loan",
                    "This charge cannot be added, No disbursement is pending");
        }
    }

    private void validateAddLoanCharge(final Loan loan, final Charge chargeDefinition, final LoanCharge loanCharge) {
        if (chargeDefinition.isOverdueInstallment()) {
            final String defaultUserMessage = "Installment charge cannot be added to the loan.";
            throw new LoanChargeCannotBeAddedException("loanCharge", "overdue.charge", defaultUserMessage, null,
                    chargeDefinition.getName());
        } else if (loanCharge.getDueLocalDate() != null) {
            // TODO: Review, error message seems not valid if interest recalculation is not enabled.
            boolean isCumulative = loan.getLoanRepaymentScheduleDetail().getLoanScheduleType().equals(LoanScheduleType.CUMULATIVE);
            LocalDate validationDate = loan.isInterestBearingAndInterestRecalculationEnabled() && isCumulative
                    ? loan.getLastUserTransactionDate()
                    : loan.getDisbursementDate();
            if (DateUtils.isBefore(loanCharge.getDueLocalDate(), validationDate)) {
                final String defaultUserMessage = "charge with date before last transaction date can not be added to loan.";
                throw new LoanChargeCannotBeAddedException("loanCharge", "date.is.before.last.transaction.date", defaultUserMessage, null,
                        chargeDefinition.getName());
            }
        } else if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
            final Set<LoanCharge> loanCharges = new HashSet<>(1);
            loanCharges.add(loanCharge);
            this.loanChargeApiJsonValidator.validateLoanCharges(loanCharges, dataValidationErrors);
            if (!dataValidationErrors.isEmpty()) {
                throw new PlatformApiDataValidationException(dataValidationErrors);
            }
        }
    }

    private boolean addCharge(final Loan loan, final Charge chargeDefinition, LoanCharge loanCharge) {
        if (!loan.hasCurrencyCodeOf(chargeDefinition.getCurrencyCode())) {
            final String errorMessage = "Charge and Loan must have the same currency.";
            throw new InvalidCurrencyException("loanCharge", "attach.to.loan", errorMessage);
        }

        if (loanCharge.getChargePaymentMode().isPaymentModeAccountTransfer()) {
            final PortfolioAccountData portfolioAccountData = this.accountAssociationsReadPlatformService
                    .retriveLoanLinkedAssociation(loan.getId());
            if (portfolioAccountData == null) {
                final String errorMessage = loanCharge.name() + "Charge  requires linked savings account for payment";
                throw new LinkedAccountRequiredException("loanCharge.add", errorMessage, loanCharge.name());
            }
        }

        loanChargeValidator.validateChargeAdditionForDisbursedLoan(loan, loanCharge);
        loanChargeValidator.validateChargeHasValidSpecifiedDateIfApplicable(loan, loanCharge, loan.getDisbursementDate());
        loanChargeService.addLoanCharge(loan, loanCharge);
        loanCharge = this.loanChargeRepository.saveAndFlush(loanCharge);

        // we want to apply charge transactions only for those loans charges that are applied when a loan is active and
        // the loan product uses Upfront Accruals, or only when the loan are closed too,
        if ((loan.getStatus().isActive() && loan.isUpfrontAccrualAccountingEnabledOnLoanProduct()) || loan.getStatus().isOverpaid()
                || loan.getStatus().isClosedObligationsMet()) {
            final LoanTransaction applyLoanChargeTransaction = loanChargeService.handleChargeAppliedTransaction(loan, loanCharge, null);
            if (applyLoanChargeTransaction != null) {
                this.loanTransactionRepository.saveAndFlush(applyLoanChargeTransaction);
                loanJournalEntryPoster.postJournalEntriesForLoanTransaction(applyLoanChargeTransaction, false, false);
                businessEventNotifierService
                        .notifyPostBusinessEvent(new LoanAccrualTransactionCreatedBusinessEvent(applyLoanChargeTransaction));
            }
        } else if (configurationDomainService.isImmediateChargeAccrualPostMaturityEnabled()
                && DateUtils.getBusinessLocalDate().isAfter(loan.getMaturityDate())) {
            final LoanTransaction loanTransaction = loanChargeService.createChargeAppliedTransaction(loan, loanCharge, null);
            this.loanTransactionRepository.saveAndFlush(loanTransaction);
            loanJournalEntryPoster.postJournalEntriesForLoanTransaction(loanTransaction, false, false);
            businessEventNotifierService.notifyPostBusinessEvent(new LoanAccrualTransactionCreatedBusinessEvent(loanTransaction));
        }

        return DateUtils.isBeforeBusinessDate(loanCharge.getDueLocalDate());
    }

    private LoanOverdueDTO applyChargeToOverdueLoanInstallment(final Loan loan, final Long loanChargeId, final Integer periodNumber,
            final JsonCommand command, BigDecimal remainingCumulativePenaltyCap) {
        log.info("applyChargeToOverdueLoanInstallment: loanId={}, chargeId={}, periodNumber={}", loan.getId(), loanChargeId, periodNumber);
        boolean runInterestRecalculation = false;
        final Charge chargeDefinition = this.chargeRepository.findOneWithNotFoundDetection(loanChargeId);

        Collection<Integer> frequencyNumbers = loanChargeReadPlatformService.retrieveOverdueInstallmentChargeFrequencyNumber(loan,
                chargeDefinition, periodNumber);

        Integer feeFrequency = chargeDefinition.feeFrequency();
        final ScheduledDateGenerator scheduledDateGenerator = new DefaultScheduledDateGenerator();
        Map<Integer, LocalDate> scheduleDates = new HashMap<>();
        final Long penaltyPostingWaitPeriodValue = this.configurationDomainService.retrieveGraceOnPenaltyPostingPeriod();
        final LocalDate dueDate = command.localDateValueOfParameterNamed("dueDate");
        if (remainingCumulativePenaltyCap != null && remainingCumulativePenaltyCap.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("applyChargeToOverdueLoanInstallment: loanId={}, periodNumber={}, skipping (cumulative penalty cap reached)",
                    loan.getId(), periodNumber);
            return new LoanOverdueDTO(loan, runInterestRecalculation, DateUtils.getBusinessLocalDate(), dueDate,
                    remainingCumulativePenaltyCap);
        }
        // penalty-wait-period controls WHEN we pick up the loan (eligibility). grace-on-penalty-posting delays
        // the first penalty date: first penalty = dueDate + 1 + grace (grace=0 -> 6th, grace=1 -> 7th, grace=2 -> 8th).
        LocalDate startDate = dueDate.plusDays(1L + penaltyPostingWaitPeriodValue);
        int frequencyNumber = 1;
        if (feeFrequency == null) {
            scheduleDates.put(frequencyNumber++, startDate);
        } else {
            // feeInterval may be null for legacy charges; use 1 (every period) as default
            final int feeInterval = chargeDefinition.feeInterval() != null ? chargeDefinition.feeInterval() : 1;
            // Apply penalties only up to yesterday: continue while schedule date < business date
            while (!DateUtils.isAfterBusinessDate(startDate)) {
                scheduleDates.put(frequencyNumber++, startDate);

                startDate = scheduledDateGenerator.getRepaymentPeriodDate(PeriodFrequencyType.fromInt(feeFrequency), feeInterval,
                        startDate);
            }
        }

        for (Integer frequency : frequencyNumbers) {
            scheduleDates.remove(frequency);
        }

        // Filter out charge dates that are before the cutoff date
        // This ensures charges are only applied from the cutoff date forward
        final LocalDate cutoffDate = overdueChargeCutoffDateResolver.getCutoffDate(loan);
        scheduleDates.entrySet().removeIf(entry -> DateUtils.isBefore(entry.getValue(), cutoffDate));

        LoanRepaymentScheduleInstallment installment = null;
        LocalDate lastChargeAppliedDate = dueDate;
        LocalDate recalculateFrom = DateUtils.getBusinessLocalDate();
        if (scheduleDates.isEmpty()) {
            log.info("applyChargeToOverdueLoanInstallment: loanId={}, periodNumber={}, no schedule dates to apply (cutoffDate={})",
                    loan.getId(), periodNumber, cutoffDate);
        }
        if (!scheduleDates.isEmpty()) {
            log.info("applyChargeToOverdueLoanInstallment: loanId={}, periodNumber={}, applying penalty for {} schedule date(s)",
                    loan.getId(), periodNumber, scheduleDates.size());
            installment = loan.fetchRepaymentScheduleInstallment(periodNumber);
            lastChargeAppliedDate = installment.getDueDate();
            businessEventNotifierService.notifyPreBusinessEvent(new LoanApplyOverdueChargeBusinessEvent(loan));

            List<Map.Entry<Integer, LocalDate>> sortedScheduleDates = scheduleDates.entrySet().stream().sorted(Map.Entry.comparingByValue())
                    .collect(Collectors.toList());

            for (Map.Entry<Integer, LocalDate> entry : sortedScheduleDates) {
                final LoanCharge loanCharge = loanChargeAssembler.createNewFromJson(loan, chargeDefinition, command, entry.getValue());

                if (BigDecimal.ZERO.compareTo(loanCharge.amount()) == 0) {
                    continue;
                }

                if (remainingCumulativePenaltyCap != null) {
                    if (remainingCumulativePenaltyCap.compareTo(BigDecimal.ZERO) <= 0) {
                        break;
                    }
                    BigDecimal cappedAmount = loanCharge.amount().min(remainingCumulativePenaltyCap);
                    if (cappedAmount.compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }
                    if (cappedAmount.compareTo(loanCharge.amount()) < 0) {
                        loanCharge.setAmount(cappedAmount);
                        loanCharge.setAmountOutstanding(loanCharge.calculateOutstanding());
                    }
                    remainingCumulativePenaltyCap = remainingCumulativePenaltyCap.subtract(loanCharge.amount());
                }

                LoanOverdueInstallmentCharge overdueInstallmentCharge = new LoanOverdueInstallmentCharge(loanCharge, installment,
                        entry.getKey());
                loanCharge.updateOverdueInstallmentCharge(overdueInstallmentCharge);

                boolean isAppliedOnBackDate = addCharge(loan, chargeDefinition, loanCharge);
                runInterestRecalculation = runInterestRecalculation || isAppliedOnBackDate;
                if (DateUtils.isBefore(entry.getValue(), recalculateFrom)) {
                    recalculateFrom = entry.getValue();
                }
                if (DateUtils.isAfter(entry.getValue(), lastChargeAppliedDate)) {
                    lastChargeAppliedDate = entry.getValue();
                }
            }
            businessEventNotifierService.notifyPostBusinessEvent(new LoanApplyOverdueChargeBusinessEvent(loan));
            businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
            log.info("applyChargeToOverdueLoanInstallment: loanId={}, periodNumber={}, completed, runInterestRecalculation={}",
                    loan.getId(), periodNumber, runInterestRecalculation);
        }

        return new LoanOverdueDTO(loan, runInterestRecalculation, recalculateFrom, lastChargeAppliedDate, remainingCumulativePenaltyCap);
    }

    private BigDecimal calculateExistingPenaltyAmountForCharge(Loan loan, Charge chargeDefinition) {
        BigDecimal amount = loan.getCharges().stream().filter(LoanCharge::isPenaltyCharge).filter(LoanCharge::isActive)
                .filter(c -> c.getCharge().getId().equals(chargeDefinition.getId())).map(LoanCharge::amount)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        log.info("calculateExistingPenaltyAmountForCharge: loanId={}, chargeId={}, existingPenaltyAmount={}", loan.getId(),
                chargeDefinition.getId(), amount);
        return amount;
    }

    private void addInstallmentIfPenaltyAppliedAfterLastDueDate(Loan loan, LocalDate lastChargeDate) {
        if (lastChargeDate == null) {
            return;
        }
        List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        if (installments.isEmpty()) {
            return;
        }
        // Do NOT use fetchRepaymentScheduleInstallment(installments.size()) - installment numbers often don't
        // match list size (e.g. down payment #0 + periods #1-11 = 12 items, so there is no installment #12)
        LoanRepaymentScheduleInstallment lastInstallment = null;
        for (int i = installments.size() - 1; i >= 0; i--) {
            LoanRepaymentScheduleInstallment inst = installments.get(i);
            if (!inst.isRecalculatedInterestComponent()) {
                lastInstallment = inst;
                break;
            }
        }
        if (lastInstallment == null || lastInstallment.getDueDate() == null) {
            return;
        }
        if (DateUtils.isAfter(lastChargeDate, lastInstallment.getDueDate())) {
            log.info("addInstallmentIfPenaltyAppliedAfterLastDueDate: loanId={}, adding installment (lastChargeDate={}, lastDueDate={})",
                    loan.getId(), lastChargeDate, lastInstallment.getDueDate());
            // Remove trailing recalculated installments before adding the new one
            while (!installments.isEmpty() && installments.get(installments.size() - 1).isRecalculatedInterestComponent()) {
                installments.remove(installments.size() - 1);
            }
            if (installments.isEmpty()) {
                return;
            }
            lastInstallment = installments.get(installments.size() - 1);
            if (lastInstallment.getDueDate() == null) {
                return;
            }
            boolean recalculatedInterestComponent = true;
            BigDecimal principal = BigDecimal.ZERO;
            BigDecimal interest = BigDecimal.ZERO;
            BigDecimal feeCharges = BigDecimal.ZERO;
            BigDecimal penaltyCharges = BigDecimal.ONE;
            final Set<LoanInterestRecalcualtionAdditionalDetails> compoundingDetails = null;
            LoanRepaymentScheduleInstallment newEntry = new LoanRepaymentScheduleInstallment(loan, installments.size() + 1,
                    lastInstallment.getDueDate(), lastChargeDate, principal, interest, feeCharges, penaltyCharges,
                    recalculatedInterestComponent, compoundingDetails);
            loan.addLoanRepaymentScheduleInstallment(newEntry);
        }
    }

    public Loan runScheduleRecalculation(Loan loan, final LocalDate recalculateFrom) {
        log.info("runScheduleRecalculation: loanId={}, recalculateFrom={}, interestRecalcEnabled={}, chargedOff={}", loan.getId(),
                recalculateFrom, loan.isInterestBearingAndInterestRecalculationEnabled(), loan.isChargedOff());
        if (loan.isInterestBearingAndInterestRecalculationEnabled() && !loan.isChargedOff()) {
            ScheduleGeneratorDTO generatorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);
            loanScheduleService.handleRegenerateRepaymentScheduleWithInterestRecalculation(loan, generatorDTO);
            loanAccrualsProcessingService.reprocessExistingAccruals(loan, true);
            loanAccrualsProcessingService.processIncomePostingAndAccruals(loan, true);
            loan = loanAccountService.saveAndFlushLoanWithDataIntegrityViolationChecks(loan);
            log.info("runScheduleRecalculation: loanId={}, completed successfully", loan.getId());
        } else {
            log.info("runScheduleRecalculation: loanId={}, skipped (conditions not met)", loan.getId());
        }
        return loan;
    }

    private JsonCommand adaptLoanChargeRefundCommandForFurtherRepaymentProcessing(JsonCommand command, BigDecimal fullRefundAbleAmount) {
        // creates JsonCommand for onward repayment processing
        JsonObject jsonObject = (JsonObject) this.fromApiJsonHelper.parse(command.json());

        String dateFormat;
        if (this.fromApiJsonHelper.parameterExists("dateFormat", jsonObject)) {
            dateFormat = this.fromApiJsonHelper.extractStringNamed("dateFormat", jsonObject);
        } else {
            dateFormat = "dd MMMM yyyy";
            jsonObject.addProperty("dateFormat", dateFormat);
        }
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(dateFormat);
        LocalDate transactionDate = DateUtils.getBusinessLocalDate();
        String transactionDateString = transactionDate.format(dateTimeFormatter);
        jsonObject.addProperty("transactionDate", transactionDateString);
        if (!this.fromApiJsonHelper.parameterExists("transactionAmount", jsonObject)) {
            jsonObject.addProperty("transactionAmount", fullRefundAbleAmount.toString());
        }
        jsonObject.remove("loanChargeId");
        jsonObject.remove("installmentNumber");
        jsonObject.remove("dueDate");

        return JsonCommand.fromExistingCommand(command, jsonObject);
    }

    private BigDecimal loanChargeValidateRefundAmount(LoanCharge loanCharge, LoanInstallmentCharge installmentChargeEntry,
            BigDecimal transactionAmount) {
        // if transactionAmount not provided return max refundable amount (amount paid minus previous refunds)
        BigDecimal chargeAmountPaid;
        BigDecimal chargeAmountRefunded = BigDecimal.ZERO;
        MonetaryCurrency loanCurrency = loanCharge.getLoan().getCurrency();
        if (loanCharge.isInstalmentFee() && installmentChargeEntry != null) {
            final Integer installmentNumber = installmentChargeEntry.getRepaymentInstallment().getInstallmentNumber();
            chargeAmountPaid = installmentChargeEntry.getAmountPaid(loanCurrency).getAmount();
            for (LoanChargePaidBy loanChargePaidBy : loanCharge.getLoanChargePaidBySet()) {
                if (installmentNumber.equals(loanChargePaidBy.getInstallmentNumber()) && isRefundElementOfChargeRefund(loanChargePaidBy)) {
                    chargeAmountRefunded = chargeAmountRefunded.add(loanChargePaidBy.getAmount());
                }
            }
        } else {
            chargeAmountPaid = loanCharge.getAmountPaid(loanCurrency).getAmount();
            for (LoanChargePaidBy loanChargePaidBy : loanCharge.getLoanChargePaidBySet()) {
                if (isRefundElementOfChargeRefund(loanChargePaidBy)) {
                    chargeAmountRefunded = chargeAmountRefunded.add(loanChargePaidBy.getAmount());
                }
            }
        }
        chargeAmountRefunded = chargeAmountRefunded.multiply(BigDecimal.valueOf(-1));

        if (chargeAmountRefunded.compareTo(chargeAmountPaid) > 0) {
            final String errorMessage = "loan.charge.more.refunded.than.paid.unexpected.system.error";
            final String details = "Paid: " + chargeAmountPaid.toString() + "  Refunded: " + chargeAmountPaid;
            throw new LoanChargeRefundException(errorMessage, details);
        }

        BigDecimal refundableAmount = chargeAmountPaid.subtract(chargeAmountRefunded);
        // refund amount was provided.
        if (transactionAmount != null && transactionAmount.compareTo(refundableAmount) > 0) {
            final String errorMessage = "loan.charge.transaction.amount.is.more.than.is.refundable";
            final String details = "transactionAmount: " + transactionAmount + "  Refundable: " + refundableAmount;
            throw new LoanChargeRefundException(errorMessage, details);
        }

        return refundableAmount;
    }

    private LoanCharge retrieveLoanChargeBy(final Long loanId, final Long loanChargeId) {
        final LoanCharge loanCharge = this.loanChargeRepository.findById(loanChargeId)
                .orElseThrow(() -> new LoanChargeNotFoundException(loanChargeId));

        if (loanCharge.hasNotLoanIdentifiedBy(loanId)) {
            throw new LoanChargeNotFoundException(loanChargeId, loanId);
        }
        return loanCharge;
    }

    private boolean isRefundElementOfChargeRefund(LoanChargePaidBy loanChargePaidBy) {
        // The Refund Element is always negative
        return (loanChargePaidBy.getLoanTransaction().isChargeRefund() && loanChargePaidBy.getAmount().compareTo(BigDecimal.ZERO) < 0);
    }

    private LoanInstallmentCharge loanChargeRefundEntranceValidation(LoanCharge loanCharge, Integer installmentNumber, LocalDate dueDate) {

        LoanInstallmentCharge installmentChargeEntry = null;

        Loan loan = loanCharge.getLoan();
        if (!(loan.isOpen() || loan.getStatus().isClosedObligationsMet() || loan.getStatus().isOverpaid())) {
            final String errorMessage = "loan.charge.refund.invalid.status";
            throw new LoanChargeRefundException(errorMessage, loan.getStatus().toString());
        }

        if (dueDate != null && installmentNumber != null) {
            throwLoanChargeRefundException("loan.charge.refund.dueDate.and.installmentNumber.provided.use.only.one", installmentNumber,
                    dueDate);
        }

        if (loanCharge.isInstalmentFee()) { // identify specific installment
            if (dueDate == null && installmentNumber == null) {
                throwLoanChargeRefundException(
                        "loan.charge.refund.neither.dueDate.nor.installmentNumber.provided.for.this.installment.charge", installmentNumber,
                        dueDate);
            }

            if (dueDate != null) {
                installmentChargeEntry = loanCharge.getInstallmentLoanCharge(dueDate);
            } else if (installmentNumber != null) {
                installmentChargeEntry = loanCharge.getInstallmentLoanCharge(installmentNumber);
            }

            if (installmentChargeEntry == null) {
                throwLoanChargeRefundException("loan.charge.refund.installment.not.found", installmentNumber, dueDate);
            }
        } else {

            if (dueDate != null || installmentNumber != null) {
                throwLoanChargeRefundException(
                        "loan.charge.refund.dueDate.or.installmentNumber.provided.but.this.is.not.an.installment.charge", installmentNumber,
                        dueDate);
            }
        }

        return installmentChargeEntry;
    }

    private void loanChargeAdjustmentEntranceValidation(final LoanCharge loanCharge, final BigDecimal transactionAmount) {
        final Loan loan = loanCharge.getLoan();
        if (!(loan.isOpen() || loan.getStatus().isClosedObligationsMet() || loan.getStatus().isOverpaid())) {
            final String errorCode = "loan.charge.adjustment.invalid.status";
            throw new LoanChargeAdjustmentException(errorCode,
                    "Adjustment is not supported for the status of " + loan.getStatus().toString());
        }

        if (transactionAmount.compareTo(loanCharge.amount()) > 0) {
            final String errorCode = "loan.charge.adjustment.invalid.amount";
            throw new LoanChargeAdjustmentException(errorCode,
                    "Transaction amount cannot be higher than the charge amount: " + loanCharge.amount());
        }

        BigDecimal availableAmountForAdjustment = calculateAvailableAmountForChargeAdjustment(loanCharge);
        if (transactionAmount.compareTo(availableAmountForAdjustment) > 0) {
            final String errorCode = "loan.charge.adjustment.invalid.amount";
            throw new LoanChargeAdjustmentException(errorCode,
                    "Transaction amount cannot be higher than the available charge amount for adjustment: " + availableAmountForAdjustment);
        }
        checkClientOrGroupActive(loan);
        loanDownPaymentTransactionValidator.validateAccountStatus(loan, LoanEvent.LOAN_CHARGE_ADJUSTMENT);
    }

    private BigDecimal calculateAvailableAmountForChargeAdjustment(final LoanCharge loanCharge) {
        BigDecimal availableAmountForAdjustment = loanCharge.amount();
        for (LoanTransaction loanTransaction : loanCharge.getLoan().getLoanTransactions()) {
            if (loanTransaction.isNotReversed() && loanTransaction.getTypeOf().isChargeAdjustment()) {
                LoanTransactionRelation loanTransactionRelation = loanTransaction.getLoanTransactionRelations().stream()
                        .filter(e -> e.getToCharge() != null).findFirst().orElseThrow();
                if (loanCharge.equals(loanTransactionRelation.getToCharge())) {
                    availableAmountForAdjustment = availableAmountForAdjustment.subtract(loanTransaction.getAmount());
                }
            }
        }
        return availableAmountForAdjustment;
    }

    private void throwLoanChargeRefundException(String errorMessage, Integer installmentNumber, LocalDate dueDate) {
        String dueDateValue = "";
        String installmentNumberValue = "";
        if (dueDate != null) {
            dueDateValue = dueDate.toString();
        }
        if (installmentNumber != null) {
            installmentNumberValue = installmentNumber.toString();
        }
        throw new LoanChargeRefundException(errorMessage, "dueDate: " + dueDateValue + "  installmentNumber: " + installmentNumberValue);
    }

    private void checkClientOrGroupActive(final Loan loan) {
        final Client client = loan.client();
        if (client != null) {
            if (client.isNotActive()) {
                throw new ClientNotActiveException(client.getId());
            }
        }
        final Group group = loan.group();
        if (group != null) {
            if (group.isNotActive()) {
                throw new GroupNotActiveException(group.getId());
            }
        }
    }

    public LoanTransaction waiveLoanCharge(final Loan loan, final LoanCharge loanCharge, final Map<String, Object> changes,
            final Integer loanInstallmentNumber, final ScheduleGeneratorDTO scheduleGeneratorDTO, final Money accruedCharge,
            final ExternalId externalId) {
        final Money amountWaived = loanCharge.waive(loan.getCurrency(), loanInstallmentNumber);
        changes.put("amount", amountWaived.getAmount());

        Money unrecognizedIncome = amountWaived.zero();
        Money chargeComponent = amountWaived;
        if (loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
            Money receivableCharge;
            if (loanInstallmentNumber != null) {
                receivableCharge = accruedCharge
                        .minus(loanCharge.getInstallmentLoanCharge(loanInstallmentNumber).getAmountPaid(loan.getCurrency()));
            } else {
                receivableCharge = accruedCharge.minus(loanCharge.getAmountPaid(loan.getCurrency()));
            }
            if (receivableCharge.isLessThanZero()) {
                receivableCharge = amountWaived.zero();
            }
            if (amountWaived.isGreaterThan(receivableCharge)) {
                chargeComponent = receivableCharge;
                unrecognizedIncome = amountWaived.minus(receivableCharge);
            }
        }
        Money feeChargesWaived = chargeComponent;
        Money penaltyChargesWaived = Money.zero(loan.getCurrency());
        if (loanCharge.isPenaltyCharge()) {
            penaltyChargesWaived = chargeComponent;
            feeChargesWaived = Money.zero(loan.getCurrency());
        }

        LocalDate transactionDate = loan.getDisbursementDate();
        final LocalDate businessDate = DateUtils.getBusinessLocalDate();
        if (loanCharge.isDueDateCharge()) {
            if (DateUtils.isAfter(loanCharge.getDueLocalDate(), businessDate)) {
                transactionDate = businessDate;
            } else {
                transactionDate = loanCharge.getDueLocalDate();
            }
        } else if (loanCharge.isInstalmentFee()) {
            LocalDate repaymentDueDate = loanCharge.getInstallmentLoanCharge(loanInstallmentNumber).getRepaymentInstallment().getDueDate();
            if (DateUtils.isAfter(repaymentDueDate, businessDate)) {
                transactionDate = businessDate;
            } else {
                transactionDate = repaymentDueDate;
            }
        }

        scheduleGeneratorDTO.setRecalculateFrom(transactionDate);

        loan.updateSummaryWithTotalFeeChargesDueAtDisbursement(loan.deriveSumTotalOfChargesDueAtDisbursement());

        final LoanTransaction waiveLoanChargeTransaction = LoanTransaction.waiveLoanCharge(loan, loan.getOffice(), amountWaived,
                transactionDate, feeChargesWaived, penaltyChargesWaived, unrecognizedIncome, externalId);
        final LoanChargePaidBy loanChargePaidBy = new LoanChargePaidBy(waiveLoanChargeTransaction, loanCharge,
                waiveLoanChargeTransaction.getAmount(loan.getCurrency()).getAmount(), loanInstallmentNumber);
        waiveLoanChargeTransaction.getLoanChargesPaid().add(loanChargePaidBy);
        loan.addLoanTransaction(waiveLoanChargeTransaction);
        if (loan.isCumulativeSchedule() && loan.isInterestBearingAndInterestRecalculationEnabled()
                && DateUtils.isBefore(loanCharge.getDueLocalDate(), businessDate)) {
            loanScheduleService.regenerateRepaymentScheduleWithInterestRecalculation(loan, scheduleGeneratorDTO);
        } else if (loan.isProgressiveSchedule() && ((loan.hasChargeOffTransaction() && loan.hasAccelerateChargeOffStrategy())
                || loan.hasContractTerminationTransaction())) {
            loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
        }
        // Waive of charges whose due date falls after latest 'repayment' transaction don't require entire loan schedule
        // to be reprocessed.
        if (!loanCharge.isDueAtDisbursement() && loanCharge.isPaidOrPartiallyPaid(loan.getCurrency())) {
            reprocessLoanTransactionsService.reprocessTransactions(loan);
        } else {
            // reprocess loan schedule based on charge been waived.
            final LoanRepaymentScheduleProcessingWrapper wrapper = new LoanRepaymentScheduleProcessingWrapper();
            wrapper.reprocess(loan.getCurrency(), loan.getDisbursementDate(), loan.getRepaymentScheduleInstallments(),
                    loan.getActiveCharges());
        }

        loanLifecycleStateMachine.determineAndTransition(loan, waiveLoanChargeTransaction.getTransactionDate());

        return waiveLoanChargeTransaction;
    }

    @Override
    @Transactional
    public CommandProcessingResult payByChargeId(Long loanId, Long chargeId, JsonCommand command) {
        // Validate and fetch loan
        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);

        // Validate loan is active
        if (!loan.getStatus().isActive()) {
            throw new LoanChargeCannotBePayedException(LoanChargeCannotBePayedException.LoanChargeCannotBePayedReason.LOAN_INACTIVE,
                    loanId);
        }

        List<LoanCharge> matchingCharges = loan.getActiveCharges().stream()
                .filter(lc -> lc.getCharge() != null && lc.getCharge().getId().equals(chargeId) && !lc.isPaid() && !lc.isWaived())
                .sorted(LoanChargeEffectiveDueDateComparator.INSTANCE).collect(Collectors.toList());
        if (matchingCharges.isEmpty()) {
            throw new LoanChargeNotFoundException(chargeId, loanId);
        }

        // Get transaction details (extract once, reuse for all charges)
        final LocalDate transactionDate = command.localDateValueOfParameterNamed("transactionDate");
        BigDecimal remainingAmount = command.bigDecimalValueOfParameterNamed("transactionAmount");

        // Validate transaction amount is provided and positive
        if (remainingAmount == null || remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.amount.invalid",
                    "Transaction amount must be provided and greater than zero", remainingAmount);
        }
        final ExternalId requestExternalId = externalIdFactory.createFromCommand(command, LoanApiConstants.externalIdParameterName);
        final PaymentDetail paymentDetail = paymentDetailWritePlatformService.createPaymentDetail(command, null);
        final String noteText = command.stringValueOfParameterNamedAllowingNull("note");
        // Note: isAccountTransfer is always false in payByChargeId as per requirements
        final boolean isAccountTransfer = false;

        // Calculate total outstanding across all matching charges (handles both regular and installment charges)
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        for (LoanCharge loanCharge : matchingCharges) {
            if (loanCharge.isInstalmentFee()) {
                // For installment fees, get unpaid installment charge
                LoanInstallmentCharge unpaidInstallment = loanCharge.getUnpaidInstallmentLoanCharge();
                if (unpaidInstallment != null) {
                    totalOutstanding = totalOutstanding.add(unpaidInstallment.getAmountOutstanding());
                }
            } else {
                totalOutstanding = totalOutstanding.add(loanCharge.getAmountOutstanding(loan.getCurrency()).getAmount());
            }
        }

        // Validate transaction amount doesn't exceed total outstanding
        if (remainingAmount.compareTo(totalOutstanding) > 0) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.amount.greater.than.total.outstanding",
                    "Transaction amount " + remainingAmount + " cannot be greater than total outstanding " + totalOutstanding,
                    remainingAmount, totalOutstanding);
        }

        // Note: We don't need existingTransactionIds here as we're using per-transaction journal entry posting

        // Track payment details
        final Map<String, Object> changes = new LinkedHashMap<>();
        final List<Long> paidChargeIds = new ArrayList<>();
        final List<LoanTransaction> transactions = new ArrayList<>();
        BigDecimal totalPaidAmount = BigDecimal.ZERO;

        // When request has externalId and we create multiple transactions, each must have a unique external_id
        // (DB unique constraint). First transaction keeps the request externalId; subsequent get suffix "-2", "-3", ...
        // so recon can find all via prefix match (external_id LIKE requestExternalId%).
        final int maxExternalIdLength = 100;
        int transactionIndex = 0;

        // Process each charge using the same logic as payLoanCharge (reuse business logic)
        // Create transactions in memory first, then batch process - optimized for DB calls
        for (LoanCharge loanCharge : matchingCharges) {
            if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                break; // No more amount to pay
            }

            BigDecimal chargeOutstanding;
            Integer installmentNumber = null;
            Money paymentAmount;

            // Handle installment fees - reuse logic from payLoanCharge
            if (loanCharge.isInstalmentFee()) {
                LoanInstallmentCharge chargePerInstallment = loanCharge.getUnpaidInstallmentLoanCharge();
                if (chargePerInstallment == null) {
                    continue; // Skip fully paid installment charges
                }
                // Validate repayment installment exists (defensive check)
                if (chargePerInstallment.getRepaymentInstallment() == null) {
                    throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.installment.missing",
                            "Repayment installment is missing for installment charge " + loanCharge.getId(), loanCharge.getId());
                }
                installmentNumber = chargePerInstallment.getRepaymentInstallment().getInstallmentNumber();
                chargeOutstanding = chargePerInstallment.getAmountOutstanding();
            } else {
                chargeOutstanding = loanCharge.getAmountOutstanding(loan.getCurrency()).getAmount();
            }

            BigDecimal amountToPay = remainingAmount.min(chargeOutstanding);
            paymentAmount = Money.of(loan.getCurrency(), amountToPay);

            // Assign unique externalId per transaction when request provided one (avoids unique constraint violation)
            final ExternalId transactionExternalId = resolveTransactionExternalId(requestExternalId, transactionIndex, maxExternalIdLength);

            // Create transaction using same logic as makeChargePayment (CHARGE_PAYMENT type)
            final LoanTransactionType loanTransactionType = LoanTransactionType.CHARGE_PAYMENT;
            LoanTransaction chargePaymentTransaction = LoanTransaction.loanPayment(null, loan.getOffice(), paymentAmount, paymentDetail,
                    transactionDate, transactionExternalId, loanTransactionType);

            // Use loanChargeService.makeChargePayment to handle proper processing logic
            // This ensures same business logic as payLoanCharge is applied (transaction processing, allocation, etc.)
            this.loanChargeService.makeChargePayment(loan, loanCharge.getId(), chargePaymentTransaction, installmentNumber);

            transactions.add(chargePaymentTransaction);
            remainingAmount = remainingAmount.subtract(amountToPay);
            totalPaidAmount = totalPaidAmount.add(amountToPay);
            paidChargeIds.add(loanCharge.getId());
            transactionIndex++;
        }
        if (remainingAmount.compareTo(BigDecimal.ZERO) > 0) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.payment.amount.greater.than.total.outstanding",
                    "Transaction amount " + remainingAmount + " cannot be greater than total outstanding " + totalOutstanding,
                    remainingAmount, totalOutstanding);
        }

        // Batch process all transactions - optimized DB operations
        if (!transactions.isEmpty()) {
            // Reprocess loan transactions ONCE (not in loop)
            if (loan.isProgressiveSchedule() && ((loan.hasChargeOffTransaction() && loan.hasAccelerateChargeOffStrategy())
                    || loan.hasContractTerminationTransaction())) {
                final ScheduleGeneratorDTO scheduleGeneratorDTO = loanUtilService.buildScheduleGeneratorDTO(loan, null);
                loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
            }
            reprocessLoanTransactionsService.reprocessTransactions(loan);
            loanLifecycleStateMachine.determineAndTransition(loan, transactionDate);

            // Batch save all transactions at ONCE (single DB call)
            this.loanTransactionRepository.saveAll(transactions);
            this.loanTransactionRepository.flush();

            // Save loan ONCE (single DB call)
            this.loanRepositoryWrapper.saveAndFlush(loan);

            // Post journal entries per transaction (reuse same logic as payLoanCharge)
            // This ensures proper accounting entries for each transaction
            for (LoanTransaction transaction : transactions) {
                this.loanJournalEntryPoster.postJournalEntriesForLoanTransaction(transaction, isAccountTransfer, false);
            }

            // Process accruals ONCE after all transactions
            loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan,
                    loan.isInterestBearingAndInterestRecalculationEnabled(), true);

            // Update delinquency ONCE (single DB call)
            this.loanAccountDomainService.setLoanDelinquencyTag(loan, DateUtils.getBusinessLocalDate());

            // Fire events ONCE
            businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
        }

        // Add note if provided (only if we have at least one transaction)
        if (StringUtils.isNotBlank(noteText) && !transactions.isEmpty()) {
            final Note note = Note.loanTransactionNote(loan, transactions.get(transactions.size() - 1), noteText);
            this.noteRepository.save(note);
        }

        // Build response with payment details
        changes.put("totalPaidAmount", totalPaidAmount);
        changes.put("chargeDefinitionId", chargeId);
        changes.put("paidChargeIds", paidChargeIds);

        return new CommandProcessingResultBuilder().withCommandId(command.commandId()).withEntityId(chargeId)
                .withOfficeId(loan.getOfficeId()).withClientId(loan.getClientId()).withGroupId(loan.getGroupId()).withLoanId(loanId)
                .with(changes).build();
    }

    /**
     * When a single request creates multiple charge-payment transactions and the request provides an externalId, each
     * transaction must have a unique external_id (DB unique constraint). The first transaction keeps the request
     * externalId; subsequent ones get a suffix "-2", "-3", etc. Recon can find all via prefix match (external_id LIKE
     * requestExternalId%).
     */
    private ExternalId resolveTransactionExternalId(final ExternalId requestExternalId, final int transactionIndex,
            final int maxExternalIdLength) {
        if (requestExternalId == null || requestExternalId.isEmpty()) {
            return requestExternalId != null ? requestExternalId : ExternalId.empty();
        }
        if (transactionIndex == 0) {
            return requestExternalId;
        }
        final String base = requestExternalId.getValue();
        final String suffix = "-" + (transactionIndex + 1);
        if (base.length() + suffix.length() > maxExternalIdLength) {
            return ExternalId.generate();
        }
        return new ExternalId(base + suffix);
    }

    @Override
    @Transactional
    public CommandProcessingResult waiveBulkLoanCharges(Long loanId, JsonCommand command) {
        // Validate loan status - single DB call with charges loaded
        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        if (!loan.getStatus().isActive() || loan.isClosed()) {
            throw new LoanChargeCannotBeWaivedException(LoanChargeCannotBeWaivedException.LoanChargeCannotBeWaivedReason.LOAN_INACTIVE,
                    loan.getId());
        }
        checkClientOrGroupActive(loan);

        // Extract charge IDs
        final Long[] chargeIdsForWaiver = command.longArrayValueOfParameterNamed("chargeIds");
        if (chargeIdsForWaiver == null || chargeIdsForWaiver.length == 0) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charges.empty", "No charges provided for waiver", loanId);
        }

        // Validate no null values in charge IDs array
        for (int i = 0; i < chargeIdsForWaiver.length; i++) {
            if (chargeIdsForWaiver[i] == null) {
                throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.id.null", "Charge ID at index " + i + " is null",
                        loanId);
            }
        }

        // Pre-validate all charges before processing (using already-loaded charges from loan)
        final Set<Long> chargeIdSet = Set.of(chargeIdsForWaiver);
        final List<LoanCharge> chargesToWaive = new ArrayList<>();
        for (LoanCharge loanCharge : loan.getCharges()) {
            if (chargeIdSet.contains(loanCharge.getId())) {
                if (!loanCharge.isActive()) {
                    throw new LoanChargeCannotBeWaivedException(
                            LoanChargeCannotBeWaivedException.LoanChargeCannotBeWaivedReason.LOAN_INACTIVE, loanCharge.getId());
                } else if (loanCharge.isWaived()) {
                    throw new LoanChargeCannotBeWaivedException(
                            LoanChargeCannotBeWaivedException.LoanChargeCannotBeWaivedReason.ALREADY_WAIVED, loanCharge.getId());
                } else if (loanCharge.isPaid()) {
                    throw new LoanChargeCannotBeWaivedException(
                            LoanChargeCannotBeWaivedException.LoanChargeCannotBeWaivedReason.ALREADY_PAID, loanCharge.getId());
                }
                chargesToWaive.add(loanCharge);
            }
        }

        if (chargesToWaive.isEmpty()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.charges.empty", "No valid charges found for waiver", loanId);
        }

        // Batch process all waivers
        final Map<String, Object> changes = new LinkedHashMap<>();

        // Determine earliest recalculate date (for optimized schedule regeneration)
        LocalDate recalculateFrom = null;
        for (LoanCharge charge : chargesToWaive) {
            if (charge.getDueLocalDate() != null) {
                if (recalculateFrom == null || DateUtils.isBefore(charge.getDueLocalDate(), recalculateFrom)) {
                    recalculateFrom = charge.getDueLocalDate();
                }
            }
        }

        final ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);

        // Process each waiver using the same logic as waiveLoanCharge (reuse business
        // logic)
        final List<LoanTransaction> waiveTransactions = new ArrayList<>();
        Money totalWaivedAmount = Money.zero(loan.getCurrency());

        // Validate that bulk waive is not used for installment fees
        for (LoanCharge loanCharge : chargesToWaive) {
            if (loanCharge.isInstalmentFee()) {
                throw new GeneralPlatformDomainRuleException("error.msg.loan.charge.bulk.waive.not.supported.for.installment.fee",
                        "Bulk waive is not supported for installment fee charges. Please waive installment charges individually.",
                        loanCharge.getId());
            }
        }

        // Calculate accrued charges for all charges to be waived (in a single pass to
        // optimize DB queries)
        Map<Long, Money> accruedChargeMap = new HashMap<>();
        for (LoanCharge loanCharge : chargesToWaive) {
            Money accruedCharge = Money.zero(loan.getCurrency());
            if (loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
                Integer installmentNumber = null;
                // Remove installment fee logic since we validated above it's not allowed
                Collection<LoanChargePaidByData> chargePaidByCollection = this.loanChargeReadPlatformService
                        .retrieveLoanChargesPaidBy(loanCharge.getId(), LoanTransactionType.ACCRUAL, installmentNumber);
                for (LoanChargePaidByData chargePaidByData : chargePaidByCollection) {
                    accruedCharge = accruedCharge.plus(chargePaidByData.getAmount());
                }
                accruedChargeMap.put(loanCharge.getId(), accruedCharge);
            }
        }

        // Process each waiver - create transactions in memory first
        for (LoanCharge loanCharge : chargesToWaive) {
            // Notify pre-event
            businessEventNotifierService.notifyPreBusinessEvent(new LoanWaiveChargeBusinessEvent(loanCharge));

            // Get installment number if applicable (same logic as waiveLoanCharge)
            Integer loanInstallmentNumber = null;

            // Get accrued charge from pre-calculated map
            Money accruedCharge = accruedChargeMap.getOrDefault(loanCharge.getId(), Money.zero(loan.getCurrency()));

            // Waive the charge using the same method as single charge waiver (reuse business logic)
            final ExternalId externalId = externalIdFactory.create();
            final LoanTransaction waiveTransaction = waiveLoanCharge(loan, loanCharge, changes, loanInstallmentNumber, scheduleGeneratorDTO,
                    accruedCharge, externalId);

            waiveTransactions.add(waiveTransaction);
            totalWaivedAmount = totalWaivedAmount.plus(waiveTransaction.getAmount(loan.getCurrency()));

            // Notify post-event
            businessEventNotifierService.notifyPostBusinessEvent(new LoanWaiveChargeBusinessEvent(loanCharge));
        }

        // Batch save all transactions at ONCE (single DB call)
        if (!waiveTransactions.isEmpty()) {
            this.loanTransactionRepository.saveAll(waiveTransactions);
            this.loanTransactionRepository.flush();

            // Save loan ONCE (single DB call)
            this.loanRepositoryWrapper.saveAndFlush(loan);

            // Post journal entries per transaction (reuse same logic as waiveLoanCharge)
            // This ensures proper accounting entries for each transaction
            for (LoanTransaction transaction : waiveTransactions) {
                this.loanJournalEntryPoster.postJournalEntriesForLoanTransaction(transaction, false, false);
            }

            // For NPA loans with periodic accrual accounting, create ACCRUAL_WRITEOFF transactions for waived charges
            // Note: createAccrualWriteoffForWaivedCharges already checks NPA and periodic accrual accounting
            for (LoanTransaction waiveTransaction : waiveTransactions) {
                createAccrualWriteoffForWaivedCharges(loan, waiveTransaction);
            }

            // Handle interest recalculation if needed (ONCE after all waivers)
            if (loan.isInterestBearingAndInterestRecalculationEnabled() && recalculateFrom != null
                    && DateUtils.isBefore(recalculateFrom, DateUtils.getBusinessLocalDate())) {
                loanAccrualsProcessingService.reprocessExistingAccruals(loan, true);
                loanAccrualsProcessingService.processIncomePostingAndAccruals(loan, true);
            }

            // Update delinquency tag ONCE (single DB call)
            this.loanAccountDomainService.setLoanDelinquencyTag(loan, DateUtils.getBusinessLocalDate());
        }

        // Notify balance changed event ONCE
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));

        // Build result with waived charges info
        changes.put("totalWaivedAmount", totalWaivedAmount.getAmount());
        changes.put("chargesWaived", chargesToWaive.size());
        changes.put("chargeIds", chargeIdsForWaiver);

        return new CommandProcessingResultBuilder().withCommandId(command.commandId()).withOfficeId(loan.getOfficeId())
                .withClientId(loan.getClientId()).withGroupId(loan.getGroupId()).withLoanId(loanId).with(changes).build();
    }

    /**
     * Creates ACCRUAL_WRITEOFF transaction for waived charges on NPA loans with periodic accrual accounting.
     */
    private void createAccrualWriteoffForWaivedCharges(final Loan loan, final LoanTransaction waiveTransaction) {
        if (!loan.isNpa() || !loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
            return;
        }

        BigDecimal waivedFee = MathUtil.nullToZero(waiveTransaction.getFeeChargesPortion());
        BigDecimal waivedPenalty = MathUtil.nullToZero(waiveTransaction.getPenaltyChargesPortion());
        BigDecimal totalAccrualWriteoff = MathUtil.add(waivedFee, waivedPenalty);

        if (MathUtil.isGreaterThanZero(totalAccrualWriteoff)) {
            LoanTransaction accrualWriteoffTransaction = LoanTransaction.accrualWriteoffTransaction(loan, loan.getOffice(),
                    waiveTransaction.getTransactionDate(), totalAccrualWriteoff, null, waivedFee, waivedPenalty,
                    externalIdFactory.create());
            LoanTransaction savedAccrualWriteoffTransaction = this.loanTransactionRepository.save(accrualWriteoffTransaction);
            this.loanTransactionRepository.flush(); // Flush to ensure ID is available for journal entry posting
            loan.addLoanTransaction(savedAccrualWriteoffTransaction);
            loanJournalEntryPoster.postJournalEntriesForLoanTransaction(savedAccrualWriteoffTransaction, false, false);
        }
    }
}
