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

import com.google.gson.Gson;
import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.MathUtil;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanBalanceChangedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanChargePaymentPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanChargePaymentPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanCreditBalanceRefundPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanCreditBalanceRefundPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanForeClosurePostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanForeClosurePreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanRefundPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanRefundPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionDownPaymentPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionDownPaymentPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionGoodwillCreditPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionGoodwillCreditPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionInterestPaymentWaiverPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionInterestPaymentWaiverPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionInterestRefundPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionInterestRefundPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionMakeRepaymentPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionMakeRepaymentPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionMerchantIssuedRefundPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionMerchantIssuedRefundPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionPayoutRefundPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionPayoutRefundPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionRecoveryPaymentPostBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.transaction.LoanTransactionRecoveryPaymentPreBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.holiday.domain.Holiday;
import org.apache.fineract.organisation.holiday.domain.HolidayRepository;
import org.apache.fineract.organisation.holiday.domain.HolidayStatusType;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.workingdays.domain.WorkingDays;
import org.apache.fineract.organisation.workingdays.domain.WorkingDaysRepositoryWrapper;
import org.apache.fineract.portfolio.account.domain.AccountTransferStandingInstruction;
import org.apache.fineract.portfolio.account.domain.StandingInstructionRepository;
import org.apache.fineract.portfolio.account.domain.StandingInstructionStatus;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeCalculationType;
import org.apache.fineract.portfolio.client.domain.Client;
import org.apache.fineract.portfolio.client.exception.ClientNotActiveException;
import org.apache.fineract.portfolio.collateralmanagement.domain.ClientCollateralManagement;
import org.apache.fineract.portfolio.delinquency.domain.LoanDelinquencyAction;
import org.apache.fineract.portfolio.delinquency.helper.DelinquencyEffectivePauseHelper;
import org.apache.fineract.portfolio.delinquency.service.DelinquencyReadPlatformService;
import org.apache.fineract.portfolio.delinquency.service.DelinquencyWritePlatformService;
import org.apache.fineract.portfolio.delinquency.validator.LoanDelinquencyActionData;
import org.apache.fineract.portfolio.group.domain.Group;
import org.apache.fineract.portfolio.group.exception.GroupNotActiveException;
import org.apache.fineract.portfolio.loanaccount.data.HolidayDetailDTO;
import org.apache.fineract.portfolio.loanaccount.data.LoanRefundRequestData;
import org.apache.fineract.portfolio.loanaccount.data.LoanScheduleDelinquencyData;
import org.apache.fineract.portfolio.loanaccount.data.ScheduleGeneratorDTO;
import org.apache.fineract.portfolio.loanaccount.data.TransactionMetaData;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.MoneyHolder;
import org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.TransactionCtx;
import org.apache.fineract.portfolio.loanaccount.helper.ForeclosureChargeHelper;
import org.apache.fineract.portfolio.loanaccount.loanschedule.service.LoanScheduleHistoryWritePlatformService;
import org.apache.fineract.portfolio.loanaccount.rescheduleloan.domain.LoanRescheduleRequest;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanDownPaymentTransactionValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanForeclosureValidator;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanTransactionValidator;
import org.apache.fineract.portfolio.loanaccount.service.InterestRefundService;
import org.apache.fineract.portfolio.loanaccount.service.InterestRefundServiceDelegate;
import org.apache.fineract.portfolio.loanaccount.service.LoanAccrualsProcessingService;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanBalanceService;
import org.apache.fineract.portfolio.loanaccount.service.LoanChargeService;
import org.apache.fineract.portfolio.loanaccount.service.LoanDownPaymentHandlerService;
import org.apache.fineract.portfolio.loanaccount.service.LoanJournalEntryPoster;
import org.apache.fineract.portfolio.loanaccount.service.LoanRefundService;
import org.apache.fineract.portfolio.loanaccount.service.LoanScheduleService;
import org.apache.fineract.portfolio.loanaccount.service.LoanTransactionProcessingService;
import org.apache.fineract.portfolio.loanaccount.service.LoanTransactionService;
import org.apache.fineract.portfolio.loanaccount.service.LoanUtilService;
import org.apache.fineract.portfolio.loanaccount.service.ReprocessLoanTransactionsService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanSupportedInterestRefundTypes;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.apache.fineract.portfolio.paymentdetail.domain.PaymentDetail;
import org.apache.fineract.portfolio.repaymentwithpostdatedchecks.data.PostDatedChecksStatus;
import org.apache.fineract.portfolio.repaymentwithpostdatedchecks.domain.PostDatedChecks;
import org.apache.fineract.portfolio.repaymentwithpostdatedchecks.domain.PostDatedChecksRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanAccountDomainServiceJpa implements LoanAccountDomainService {

    private final LoanAssembler loanAccountAssembler;
    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final LoanTransactionRepository loanTransactionRepository;
    private final ConfigurationDomainService configurationDomainService;
    private final HolidayRepository holidayRepository;
    private final WorkingDaysRepositoryWrapper workingDaysRepository;
    private final NoteRepository noteRepository;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final LoanUtilService loanUtilService;
    private final StandingInstructionRepository standingInstructionRepository;
    private final PostDatedChecksRepository postDatedChecksRepository;
    private final LoanCollateralManagementRepository loanCollateralManagementRepository;
    private final DelinquencyWritePlatformService delinquencyWritePlatformService;
    private final LoanLifecycleStateMachine loanLifecycleStateMachine;
    private final ExternalIdFactory externalIdFactory;
    private final DelinquencyEffectivePauseHelper delinquencyEffectivePauseHelper;
    private final DelinquencyReadPlatformService delinquencyReadPlatformService;
    private final LoanAccrualsProcessingService loanAccrualsProcessingService;
    private final InterestRefundServiceDelegate interestRefundServiceDelegate;
    private final LoanTransactionValidator loanTransactionValidator;
    private final LoanForeclosureValidator loanForeclosureValidator;
    private final LoanDownPaymentTransactionValidator loanDownPaymentTransactionValidator;
    private final LoanChargeService loanChargeService;
    private final LoanScheduleService loanScheduleService;
    private final LoanDownPaymentHandlerService loanDownPaymentHandlerService;
    private final LoanChargeValidator loanChargeValidator;
    private final LoanRefundService loanRefundService;
    private final LoanAccountService loanAccountService;
    private final ReprocessLoanTransactionsService reprocessLoanTransactionsService;
    private final LoanTransactionProcessingService loanTransactionProcessingService;
    private final LoanBalanceService loanBalanceService;
    private final LoanTransactionService loanTransactionService;
    private final LoanAccountDomainServiceJpaHelper loanAccountDomainServiceJpaHelper;
    private final LoanJournalEntryPoster journalEntryPoster;
    private final LoanScheduleHistoryWritePlatformService loanScheduleHistoryWritePlatformService;
    private final ForeclosureChargeHelper foreclosureChargeHelper;
    private final LoanChargeRepository loanChargeRepository;
    private final LoanChargePaidByRepository loanChargePaidByRepository;

    @Transactional
    @Override
    public LoanTransaction makeRepayment(final LoanTransactionType repaymentTransactionType, final Loan loan,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail, final String noteText,
            final ExternalId txnExternalId, final boolean isRecoveryRepayment, final String chargeRefundChargeType,
            boolean isAccountTransfer, HolidayDetailDTO holidayDetailDto, Boolean isHolidayValidationDone) {
        return makeRepayment(repaymentTransactionType, loan, transactionDate, transactionAmount, paymentDetail, noteText, txnExternalId,
                isRecoveryRepayment, chargeRefundChargeType, isAccountTransfer, holidayDetailDto, isHolidayValidationDone, false);
    }

    @Transactional
    @Override
    public void updateLoanCollateralStatus(Set<LoanCollateralManagement> loanCollateralManagementSet, boolean isReleased) {
        for (LoanCollateralManagement loanCollateralManagement : loanCollateralManagementSet) {
            loanCollateralManagement.setIsReleased(isReleased);
        }
        this.loanCollateralManagementRepository.saveAll(loanCollateralManagementSet);
    }

    @Nullable
    private LoanTransaction createInterestRefundLoanTransaction(Loan loan, LoanTransaction refundTransaction) {

        InterestRefundService interestRefundService = interestRefundServiceDelegate.lookupInterestRefundService(loan);
        if (interestRefundService == null) {
            return null;
        }

        Money totalInterest = interestRefundService.totalInterestByTransactions(null, loan.getId(), refundTransaction.getTransactionDate(),
                List.of(), loan.getLoanTransactions().stream().map(AbstractPersistableCustom::getId).toList());
        Money newTotalInterest = interestRefundService.totalInterestByTransactions(null, loan.getId(),
                refundTransaction.getTransactionDate(), List.of(refundTransaction),
                loan.getLoanTransactions().stream().map(AbstractPersistableCustom::getId).toList());
        BigDecimal interestRefundAmount = totalInterest.minus(newTotalInterest).getAmount();

        if (MathUtil.isZero(interestRefundAmount)) {
            return null;
        }
        final ExternalId txnExternalId = externalIdFactory.create();
        businessEventNotifierService.notifyPreBusinessEvent(new LoanTransactionInterestRefundPreBusinessEvent(loan));
        return LoanTransaction.interestRefund(loan, interestRefundAmount, refundTransaction.getDateOf(), txnExternalId);
    }

    @Override
    public LoanTransaction createManualInterestRefundWithAmount(final Loan loan, final LoanTransaction targetTransaction,
            final BigDecimal interestRefundAmount, final PaymentDetail paymentDetail, final ExternalId txnExternalId) {
        businessEventNotifierService.notifyPreBusinessEvent(new LoanTransactionInterestRefundPreBusinessEvent(loan));
        return LoanTransaction.interestRefund(loan, interestRefundAmount, targetTransaction.getDateOf(), paymentDetail, txnExternalId);
    }

    @Transactional
    @Override
    public LoanTransaction makeRepayment(final LoanTransactionType repaymentTransactionType, Loan loan, final LocalDate transactionDate,
            final BigDecimal transactionAmount, final PaymentDetail paymentDetail, final String noteText, final ExternalId txnExternalId,
            final boolean isRecoveryRepayment, final String chargeRefundChargeType, boolean isAccountTransfer,
            HolidayDetailDTO holidayDetailDto, Boolean isHolidayValidationDone, final boolean isLoanToLoanTransfer) {
        checkClientOrGroupActive(loan);

        LoanBusinessEvent repaymentEvent = getLoanRepaymentTypeBusinessEvent(repaymentTransactionType, isRecoveryRepayment, loan);
        businessEventNotifierService.notifyPreBusinessEvent(repaymentEvent);

        // TODO: Is it required to validate transaction date with meeting dates
        // if repayments is synced with meeting?
        /*
         * if(loan.isSyncDisbursementWithMeeting()){ // validate actual disbursement date against meeting date
         * CalendarInstance calendarInstance = this.calendarInstanceRepository.findCalendarInstaneByLoanId
         * (loan.getId(), CalendarEntityType.LOANS.getValue()); this.loanEventApiJsonValidator
         * .validateRepaymentDateWithMeetingDate(transactionDate, calendarInstance); }
         */

        final Money repaymentAmount = Money.of(loan.getCurrency(), transactionAmount);
        LoanTransaction newRepaymentTransaction;
        if (isRecoveryRepayment) {
            newRepaymentTransaction = LoanTransaction.recoveryRepayment(loan.getOffice(), repaymentAmount, paymentDetail, transactionDate,
                    txnExternalId);
        } else {
            newRepaymentTransaction = LoanTransaction.repaymentType(repaymentTransactionType, loan.getOffice(), repaymentAmount,
                    paymentDetail, transactionDate, txnExternalId, chargeRefundChargeType);
        }

        LocalDate recalculateFrom = null;
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            recalculateFrom = transactionDate;
        }
        final ScheduleGeneratorDTO scheduleGeneratorDTOForPrepay = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom,
                transactionDate, holidayDetailDto);

        LocalDate recalculateTill = loanAccountDomainServiceJpaHelper.calculateRecalculateTillDate(loan, transactionDate,
                scheduleGeneratorDTOForPrepay, repaymentAmount);

        final ScheduleGeneratorDTO scheduleGeneratorDTO = this.loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom,
                recalculateTill, holidayDetailDto);

        if (!isHolidayValidationDone) {
            final HolidayDetailDTO holidayDetailDTO = scheduleGeneratorDTO.getHolidayDetailDTO();
            loanTransactionValidator.validateRepaymentDateIsOnHoliday(newRepaymentTransaction.getTransactionDate(),
                    holidayDetailDTO.isAllowTransactionsOnHoliday(), holidayDetailDTO.getHolidays());
            loanTransactionValidator.validateRepaymentDateIsOnNonWorkingDay(newRepaymentTransaction.getTransactionDate(),
                    holidayDetailDTO.getWorkingDays(), holidayDetailDTO.isAllowTransactionsOnNonWorkingDay());
        }
        final LoanEvent event = isRecoveryRepayment ? LoanEvent.LOAN_RECOVERY_PAYMENT : LoanEvent.LOAN_REPAYMENT_OR_WAIVER;
        loanTransactionValidator.validateActivityNotBeforeLastTransactionDate(loan, newRepaymentTransaction.getTransactionDate(), event);
        loanDownPaymentTransactionValidator.validateRepaymentTypeAccountStatus(loan, newRepaymentTransaction, event);
        loanTransactionValidator.validateActivityNotBeforeClientOrGroupTransferDate(loan, event,
                newRepaymentTransaction.getTransactionDate());
        makeRepayment(loan, newRepaymentTransaction, scheduleGeneratorDTO);

        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            loanAccrualsProcessingService.reprocessExistingAccruals(loan, true);
            loanAccrualsProcessingService.processIncomePostingAndAccruals(loan, true);
        }

        loanAccountService.saveLoanTransactionWithDataIntegrityViolationChecks(newRepaymentTransaction);
        loan = loanAccountService.saveAndFlushLoanWithDataIntegrityViolationChecks(loan);

        if (StringUtils.isNotBlank(noteText)) {
            final Note note = Note.loanTransactionNote(loan, newRepaymentTransaction, noteText);
            this.noteRepository.save(note);
        }

        loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan, loan.isInterestBearingAndInterestRecalculationEnabled(),
                true);

        setLoanDelinquencyTag(loan, transactionDate);

        journalEntryPoster.postJournalEntriesForLoanTransaction(newRepaymentTransaction, isAccountTransfer, isLoanToLoanTransfer);
        if (!repaymentTransactionType.isChargeRefund()) {
            final LoanTransactionBusinessEvent transactionRepaymentEvent = getTransactionRepaymentTypeBusinessEvent(
                    repaymentTransactionType, isRecoveryRepayment, newRepaymentTransaction);
            businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
            businessEventNotifierService.notifyPostBusinessEvent(transactionRepaymentEvent);
        }

        // disable all active standing orders linked to this loan if status
        // changes to closed
        disableStandingInstructionsLinkedToClosedLoan(loan);

        if (loan.getLoanType().isIndividualAccount()) {
            // Mark Post Dated Check as paid.
            final Set<LoanTransactionToRepaymentScheduleMapping> loanTransactionToRepaymentScheduleMappings = newRepaymentTransaction
                    .getLoanTransactionToRepaymentScheduleMappings();

            if (loanTransactionToRepaymentScheduleMappings != null) {
                for (LoanTransactionToRepaymentScheduleMapping loanTransactionToRepaymentScheduleMapping : loanTransactionToRepaymentScheduleMappings) {
                    LoanRepaymentScheduleInstallment loanRepaymentScheduleInstallment = loanTransactionToRepaymentScheduleMapping
                            .getLoanRepaymentScheduleInstallment();
                    if (loanRepaymentScheduleInstallment != null) {
                        final boolean isPaid = loanRepaymentScheduleInstallment.isNotFullyPaidOff();
                        PostDatedChecks postDatedChecks = this.postDatedChecksRepository
                                .getPendingPostDatedCheck(loanRepaymentScheduleInstallment);

                        if (postDatedChecks != null) {
                            if (!isPaid) {
                                postDatedChecks.setStatus(PostDatedChecksStatus.POST_DATED_CHECKS_PAID);
                            } else {
                                postDatedChecks.setStatus(PostDatedChecksStatus.POST_DATED_CHECKS_PENDING);
                            }
                            this.postDatedChecksRepository.saveAndFlush(postDatedChecks);
                        } else {
                            break;
                        }
                    }
                }
            }
        }

        return newRepaymentTransaction;
    }

    private LoanBusinessEvent getLoanRepaymentTypeBusinessEvent(LoanTransactionType repaymentTransactionType, boolean isRecoveryRepayment,
            Loan loan) {
        LoanBusinessEvent repaymentEvent = null;
        if (repaymentTransactionType.isRepayment()) {
            repaymentEvent = new LoanTransactionMakeRepaymentPreBusinessEvent(loan);
        } else if (repaymentTransactionType.isMerchantIssuedRefund()) {
            repaymentEvent = new LoanTransactionMerchantIssuedRefundPreBusinessEvent(loan);
        } else if (repaymentTransactionType.isPayoutRefund()) {
            repaymentEvent = new LoanTransactionPayoutRefundPreBusinessEvent(loan);
        } else if (repaymentTransactionType.isGoodwillCredit()) {
            repaymentEvent = new LoanTransactionGoodwillCreditPreBusinessEvent(loan);
        } else if (repaymentTransactionType.isInterestPaymentWaiver()) {
            repaymentEvent = new LoanTransactionInterestPaymentWaiverPreBusinessEvent(loan);
        } else if (repaymentTransactionType.isChargeRefund()) {
            repaymentEvent = new LoanChargePaymentPreBusinessEvent(loan);
        } else if (isRecoveryRepayment) {
            repaymentEvent = new LoanTransactionRecoveryPaymentPreBusinessEvent(loan);
        } else if (repaymentTransactionType.isDownPayment()) {
            repaymentEvent = new LoanTransactionDownPaymentPreBusinessEvent(loan);
        } else if (repaymentTransactionType.isInterestRefund()) {
            repaymentEvent = new LoanTransactionInterestRefundPreBusinessEvent(loan);
        }
        return repaymentEvent;
    }

    private LoanTransactionBusinessEvent getTransactionRepaymentTypeBusinessEvent(LoanTransactionType repaymentTransactionType,
            boolean isRecoveryRepayment, LoanTransaction transaction) {
        LoanTransactionBusinessEvent repaymentEvent = null;
        if (repaymentTransactionType.isRepayment()) {
            repaymentEvent = new LoanTransactionMakeRepaymentPostBusinessEvent(transaction);
        } else if (repaymentTransactionType.isMerchantIssuedRefund()) {
            repaymentEvent = new LoanTransactionMerchantIssuedRefundPostBusinessEvent(transaction);
        } else if (repaymentTransactionType.isPayoutRefund()) {
            repaymentEvent = new LoanTransactionPayoutRefundPostBusinessEvent(transaction);
        } else if (repaymentTransactionType.isGoodwillCredit()) {
            repaymentEvent = new LoanTransactionGoodwillCreditPostBusinessEvent(transaction);
        } else if (repaymentTransactionType.isInterestPaymentWaiver()) {
            repaymentEvent = new LoanTransactionInterestPaymentWaiverPostBusinessEvent(transaction);
        } else if (repaymentTransactionType.isChargeRefund()) {
            repaymentEvent = new LoanChargePaymentPostBusinessEvent(transaction);
        } else if (isRecoveryRepayment) {
            repaymentEvent = new LoanTransactionRecoveryPaymentPostBusinessEvent(transaction);
        } else if (repaymentTransactionType.isDownPayment()) {
            repaymentEvent = new LoanTransactionDownPaymentPostBusinessEvent(transaction);
        } else if (repaymentTransactionType.isInterestRefund()) {
            repaymentEvent = new LoanTransactionInterestRefundPostBusinessEvent(transaction);
        }
        return repaymentEvent;
    }

    @Override
    @Transactional
    public LoanTransaction makeChargePayment(final Loan loan, final Long chargeId, final LocalDate transactionDate,
            final BigDecimal transactionAmount, final PaymentDetail paymentDetail, final String noteText, final ExternalId txnExternalId,
            final Integer transactionType, Integer installmentNumber, boolean isAccountTransfer) {
        checkClientOrGroupActive(loan);
        if (loan.isChargedOff() && DateUtils.isBefore(transactionDate, loan.getChargedOffOnDate())) {
            throw new GeneralPlatformDomainRuleException("error.msg.transaction.date.cannot.be.earlier.than.charge.off.date", "Loan: "
                    + loan.getId()
                    + " backdated transaction is not allowed. Transaction date cannot be earlier than the charge-off date of the loan",
                    loan.getId());
        }
        businessEventNotifierService.notifyPreBusinessEvent(new LoanChargePaymentPreBusinessEvent(loan));

        final Money paymentAmout = Money.of(loan.getCurrency(), transactionAmount);
        final LoanTransactionType loanTransactionType = LoanTransactionType.fromInt(transactionType);

        final LoanTransaction newPaymentTransaction = LoanTransaction.loanPayment(null, loan.getOffice(), paymentAmout, paymentDetail,
                transactionDate, txnExternalId, loanTransactionType);

        if (loanTransactionType.isRepaymentAtDisbursement()) {
            handlePayDisbursementTransaction(loan, chargeId, newPaymentTransaction);
        } else {
            final boolean allowTransactionsOnHoliday = this.configurationDomainService.allowTransactionsOnHolidayEnabled();
            final List<Holiday> holidays = this.holidayRepository.findByOfficeIdAndGreaterThanDate(loan.getOfficeId(), transactionDate,
                    HolidayStatusType.ACTIVE.getValue());
            final WorkingDays workingDays = this.workingDaysRepository.findOne();
            final boolean allowTransactionsOnNonWorkingDay = this.configurationDomainService.allowTransactionsOnNonWorkingDayEnabled();
            final boolean isHolidayEnabled = this.configurationDomainService.isRescheduleRepaymentsOnHolidaysEnabled();
            HolidayDetailDTO holidayDetailDTO = new HolidayDetailDTO(isHolidayEnabled, holidays, workingDays, allowTransactionsOnHoliday,
                    allowTransactionsOnNonWorkingDay);

            loanDownPaymentTransactionValidator.validateAccountStatus(loan, LoanEvent.LOAN_CHARGE_PAYMENT);
            loanTransactionValidator.validateRepaymentDateIsOnHoliday(newPaymentTransaction.getTransactionDate(),
                    holidayDetailDTO.isAllowTransactionsOnHoliday(), holidayDetailDTO.getHolidays());
            loanTransactionValidator.validateRepaymentDateIsOnNonWorkingDay(newPaymentTransaction.getTransactionDate(),
                    holidayDetailDTO.getWorkingDays(), holidayDetailDTO.isAllowTransactionsOnNonWorkingDay());
            loanTransactionValidator.validateActivityNotBeforeLastTransactionDate(loan, newPaymentTransaction.getTransactionDate(),
                    LoanEvent.LOAN_CHARGE_PAYMENT);
            loanTransactionValidator.validateActivityNotBeforeClientOrGroupTransferDate(loan, LoanEvent.LOAN_CHARGE_PAYMENT,
                    newPaymentTransaction.getTransactionDate());
            loanChargeService.makeChargePayment(loan, chargeId, newPaymentTransaction, installmentNumber);
        }
        loanAccountService.saveLoanTransactionWithDataIntegrityViolationChecks(newPaymentTransaction);
        loanAccountService.saveAndFlushLoanWithDataIntegrityViolationChecks(loan);

        if (StringUtils.isNotBlank(noteText)) {
            final Note note = Note.loanTransactionNote(loan, newPaymentTransaction, noteText);
            this.noteRepository.save(note);
        }

        loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan, loan.isInterestBearingAndInterestRecalculationEnabled(),
                true);

        journalEntryPoster.postJournalEntriesForLoanTransaction(newPaymentTransaction, isAccountTransfer, false);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanChargePaymentPostBusinessEvent(newPaymentTransaction));
        return newPaymentTransaction;
    }

    private void handlePayDisbursementTransaction(final Loan loan, final Long chargeId, final LoanTransaction chargesPayment) {
        LoanCharge charge = null;
        for (final LoanCharge loanCharge : loan.getCharges()) {
            if (loanCharge.isActive() && chargeId.equals(loanCharge.getId())) {
                charge = loanCharge;
            }
        }
        final LoanChargePaidBy loanChargePaidBy = new LoanChargePaidBy(chargesPayment, charge, charge.amount(), null);
        chargesPayment.getLoanChargesPaid().add(loanChargePaidBy);
        final Money zero = Money.zero(loan.getCurrency());
        chargesPayment.updateComponents(zero, zero, charge.getAmount(loan.getCurrency()), zero);
        chargesPayment.updateLoan(loan);
        loan.addLoanTransaction(chargesPayment);
        loanBalanceService.updateLoanOutstandingBalances(loan);
        charge.markAsFullyPaid();
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

    @Override
    public LoanTransaction makeRefund(final Long accountId, final CommandProcessingResultBuilder builderResult,
            final LocalDate transactionDate, final BigDecimal transactionAmount, final PaymentDetail paymentDetail, final String noteText,
            final ExternalId txnExternalId) {
        final Loan loan = this.loanAccountAssembler.assembleFrom(accountId);
        checkClientOrGroupActive(loan);
        if (loan.isChargedOff() && DateUtils.isBefore(transactionDate, loan.getChargedOffOnDate())) {
            throw new GeneralPlatformDomainRuleException("error.msg.transaction.date.cannot.be.earlier.than.charge.off.date", "Loan: "
                    + loan.getId()
                    + " backdated transaction is not allowed. Transaction date cannot be earlier than the charge-off date of the loan",
                    loan.getId());
        }
        businessEventNotifierService.notifyPreBusinessEvent(new LoanRefundPreBusinessEvent(loan));

        final Money refundAmount = Money.of(loan.getCurrency(), transactionAmount);
        final LoanTransaction newRefundTransaction = LoanTransaction.refund(loan.getOffice(), refundAmount, paymentDetail, transactionDate,
                txnExternalId);
        final boolean allowTransactionsOnHoliday = this.configurationDomainService.allowTransactionsOnHolidayEnabled();
        final List<Holiday> holidays = this.holidayRepository.findByOfficeIdAndGreaterThanDate(loan.getOfficeId(), transactionDate,
                HolidayStatusType.ACTIVE.getValue());
        final WorkingDays workingDays = this.workingDaysRepository.findOne();
        final boolean allowTransactionsOnNonWorkingDay = this.configurationDomainService.allowTransactionsOnNonWorkingDayEnabled();

        loanTransactionValidator.validateRepaymentDateIsOnHoliday(newRefundTransaction.getTransactionDate(), allowTransactionsOnHoliday,
                holidays);
        loanTransactionValidator.validateRepaymentDateIsOnNonWorkingDay(newRefundTransaction.getTransactionDate(), workingDays,
                allowTransactionsOnNonWorkingDay);

        loanRefundService.makeRefund(loan, newRefundTransaction);

        loanAccountService.saveLoanTransactionWithDataIntegrityViolationChecks(newRefundTransaction);
        this.loanRepositoryWrapper.saveAndFlush(loan);

        if (StringUtils.isNotBlank(noteText)) {
            final Note note = Note.loanTransactionNote(loan, newRefundTransaction, noteText);
            this.noteRepository.save(note);
        }

        journalEntryPoster.postJournalEntriesForLoanTransaction(newRefundTransaction, true, false);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanRefundPostBusinessEvent(newRefundTransaction));
        builderResult.withEntityId(newRefundTransaction.getId()).withOfficeId(loan.getOfficeId()).withClientId(loan.getClientId())
                .withGroupId(loan.getGroupId());

        return newRefundTransaction;
    }

    @Transactional
    @Override
    public LoanTransaction makeDisburseTransaction(final Long loanId, final LocalDate transactionDate, final BigDecimal transactionAmount,
            final PaymentDetail paymentDetail, final String noteText, final ExternalId txnExternalId) {
        return makeDisburseTransaction(loanId, transactionDate, transactionAmount, paymentDetail, noteText, txnExternalId, false);
    }

    @Transactional
    @Override
    public LoanTransaction makeDisburseTransaction(final Long loanId, final LocalDate transactionDate, final BigDecimal transactionAmount,
            final PaymentDetail paymentDetail, final String noteText, final ExternalId txnExternalId, final boolean isLoanToLoanTransfer) {
        final Loan loan = this.loanAccountAssembler.assembleFrom(loanId);
        checkClientOrGroupActive(loan);
        if (loan.isChargedOff() && DateUtils.isBefore(transactionDate, loan.getChargedOffOnDate())) {
            throw new GeneralPlatformDomainRuleException("error.msg.transaction.date.cannot.be.earlier.than.charge.off.date", "Loan: "
                    + loan.getId()
                    + " backdated transaction is not allowed. Transaction date cannot be earlier than the charge-off date of the loan",
                    loan.getId());
        }
        final Money amount = Money.of(loan.getCurrency(), transactionAmount);
        LoanTransaction disbursementTransaction = LoanTransaction.disbursement(loan, amount, paymentDetail, transactionDate, txnExternalId,
                loan.getTotalOverpaidAsMoney());

        // Subtract Previous loan outstanding balance from netDisbursalAmount
        loan.deductFromNetDisbursalAmount(transactionAmount);

        disbursementTransaction.updateLoan(loan);
        loan.addLoanTransaction(disbursementTransaction);
        loanAccountService.saveLoanTransactionWithDataIntegrityViolationChecks(disbursementTransaction);
        loanAccountService.saveAndFlushLoanWithDataIntegrityViolationChecks(loan);

        if (StringUtils.isNotBlank(noteText)) {
            final Note note = Note.loanTransactionNote(loan, disbursementTransaction, noteText);
            this.noteRepository.save(note);
        }

        journalEntryPoster.postJournalEntriesForLoanTransaction(disbursementTransaction, true, isLoanToLoanTransfer);
        return disbursementTransaction;
    }

    @Override
    public void reverseTransfer(final LoanTransaction loanTransaction) {
        if (loanTransaction.getLoan().isChargedOff()
                && DateUtils.isBefore(loanTransaction.getTransactionDate(), loanTransaction.getLoan().getChargedOffOnDate())) {
            throw new GeneralPlatformDomainRuleException("error.msg.transaction.date.cannot.be.earlier.than.charge.off.date",
                    "Loan transaction: " + loanTransaction.getId()
                            + " reversal is not allowed before or on the date when the loan got charged-off",
                    loanTransaction.getId());
        }
        loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(loanTransaction.getLoan(), loanTransaction, "reversed");
        loanTransaction.reverse();
        loanAccountService.saveLoanTransactionWithDataIntegrityViolationChecks(loanTransaction);
    }

    @Override
    public void setLoanDelinquencyTag(final Loan loan, final LocalDate transactionDate) {
        LoanScheduleDelinquencyData loanDelinquencyData = new LoanScheduleDelinquencyData(loan.getId(), transactionDate, null, loan);
        final List<LoanDelinquencyAction> savedDelinquencyList = delinquencyReadPlatformService
                .retrieveLoanDelinquencyActions(loan.getId());
        List<LoanDelinquencyActionData> effectiveDelinquencyList = delinquencyEffectivePauseHelper
                .calculateEffectiveDelinquencyList(savedDelinquencyList);
        loanDelinquencyData = this.delinquencyWritePlatformService.calculateDelinquencyData(loanDelinquencyData, effectiveDelinquencyList);
        log.debug("Processing Loan {} with {} overdue days since date {}", loanDelinquencyData.getLoanId(),
                loanDelinquencyData.getOverdueDays(), loanDelinquencyData.getOverdueSinceDate());
        // Set or Unset the Delinquency Classification Tag
        if (loanDelinquencyData.getOverdueDays() > 0) {
            this.delinquencyWritePlatformService.applyDelinquencyTagToLoan(loanDelinquencyData, effectiveDelinquencyList);
        } else {
            this.delinquencyWritePlatformService.removeDelinquencyTagToLoan(loanDelinquencyData.getLoan());
        }
    }

    @Override
    public void setLoanDelinquencyTag(Loan loan, LocalDate transactionDate, List<LoanDelinquencyActionData> effectiveDelinquencyList) {
        LoanScheduleDelinquencyData loanDelinquencyData = new LoanScheduleDelinquencyData(loan.getId(), transactionDate, null, loan);
        loanDelinquencyData = this.delinquencyWritePlatformService.calculateDelinquencyData(loanDelinquencyData, effectiveDelinquencyList);
        log.debug("Processing Loan {} with {} overdue days since date {}", loanDelinquencyData.getLoanId(),
                loanDelinquencyData.getOverdueDays(), loanDelinquencyData.getOverdueSinceDate());
        // Set or Unset the Delinquency Classification Tag
        if (loanDelinquencyData.getOverdueDays() > 0) {
            this.delinquencyWritePlatformService.applyDelinquencyTagToLoan(loanDelinquencyData, effectiveDelinquencyList);
        } else {
            this.delinquencyWritePlatformService.removeDelinquencyTagToLoan(loanDelinquencyData.getLoan());
        }
    }

    @Override
    public LoanTransaction creditBalanceRefund(final Loan loan, final LocalDate transactionDate, final BigDecimal transactionAmount,
            final String noteText, final ExternalId externalId, PaymentDetail paymentDetail) {
        if (transactionDate.isAfter(DateUtils.getBusinessLocalDate())) {
            throw new GeneralPlatformDomainRuleException("error.msg.transaction.date.cannot.be.in.the.future",
                    "Loan: " + loan.getId() + ", Credit Balance Refund transaction cannot be created for the future.", loan.getId());
        }
        if (loan.isChargedOff() && DateUtils.isBefore(transactionDate, loan.getChargedOffOnDate())) {
            throw new GeneralPlatformDomainRuleException("error.msg.transaction.date.cannot.be.earlier.than.charge.off.date", "Loan: "
                    + loan.getId()
                    + " backdated transaction is not allowed. Transaction date cannot be earlier than the charge-off date of the loan",
                    loan.getId());
        }

        businessEventNotifierService.notifyPreBusinessEvent(new LoanCreditBalanceRefundPreBusinessEvent(loan));

        final Money refundAmount = Money.of(loan.getCurrency(), transactionAmount);
        LoanTransaction newCreditBalanceRefundTransaction = LoanTransaction.creditBalanceRefund(loan, loan.getOffice(), refundAmount,
                transactionDate, externalId, paymentDetail);

        loanDownPaymentTransactionValidator.validateAccountStatus(loan, LoanEvent.LOAN_CREDIT_BALANCE_REFUND);
        loanTransactionValidator.validateRefundDateIsAfterLastRepayment(loan, newCreditBalanceRefundTransaction.getTransactionDate());

        loanRefundService.creditBalanceRefund(loan, newCreditBalanceRefundTransaction);

        newCreditBalanceRefundTransaction = this.loanTransactionRepository.saveAndFlush(newCreditBalanceRefundTransaction);

        if (StringUtils.isNotBlank(noteText)) {
            final Note note = Note.loanTransactionNote(loan, newCreditBalanceRefundTransaction, noteText);
            this.noteRepository.save(note);
        }

        loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan, loan.isInterestBearingAndInterestRecalculationEnabled(),
                true);

        journalEntryPoster.postJournalEntriesForLoanTransaction(newCreditBalanceRefundTransaction, false, false);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
        businessEventNotifierService
                .notifyPostBusinessEvent(new LoanCreditBalanceRefundPostBusinessEvent(newCreditBalanceRefundTransaction));

        return newCreditBalanceRefundTransaction;
    }

    @Override
    public LoanTransaction makeRefundForActiveLoan(Long accountId, CommandProcessingResultBuilder builderResult, LocalDate transactionDate,
            BigDecimal transactionAmount, PaymentDetail paymentDetail, String noteText, ExternalId txnExternalId) {
        final Loan loan = this.loanAccountAssembler.assembleFrom(accountId);
        checkClientOrGroupActive(loan);
        businessEventNotifierService.notifyPreBusinessEvent(new LoanRefundPreBusinessEvent(loan));

        final Money refundAmount = Money.of(loan.getCurrency(), transactionAmount);
        if (loan.isChargedOff() && DateUtils.isBefore(transactionDate, loan.getChargedOffOnDate())) {
            throw new GeneralPlatformDomainRuleException("error.msg.transaction.date.cannot.be.earlier.than.charge.off.date", "Loan: "
                    + loan.getId()
                    + " backdated transaction is not allowed. Transaction date cannot be earlier than the charge-off date of the loan",
                    loan.getId());
        }
        final LoanTransaction newRefundTransaction = LoanTransaction.refundForActiveLoan(loan.getOffice(), refundAmount, paymentDetail,
                transactionDate, txnExternalId);
        loanTransactionValidator.validateRefundDateIsAfterLastRepayment(loan, newRefundTransaction.getTransactionDate());
        final boolean allowTransactionsOnHoliday = this.configurationDomainService.allowTransactionsOnHolidayEnabled();
        final List<Holiday> holidays = this.holidayRepository.findByOfficeIdAndGreaterThanDate(loan.getOfficeId(), transactionDate,
                HolidayStatusType.ACTIVE.getValue());
        final WorkingDays workingDays = this.workingDaysRepository.findOne();
        final boolean allowTransactionsOnNonWorkingDay = this.configurationDomainService.allowTransactionsOnNonWorkingDayEnabled();

        loanDownPaymentTransactionValidator.validateAccountStatus(loan, LoanEvent.LOAN_REFUND);
        loanTransactionValidator.validateRepaymentDateIsOnHoliday(newRefundTransaction.getTransactionDate(), allowTransactionsOnHoliday,
                holidays);
        loanTransactionValidator.validateRepaymentDateIsOnNonWorkingDay(newRefundTransaction.getTransactionDate(), workingDays,
                allowTransactionsOnNonWorkingDay);
        loanTransactionValidator.validateActivityNotBeforeClientOrGroupTransferDate(loan, LoanEvent.LOAN_REFUND,
                newRefundTransaction.getTransactionDate());
        loanRefundService.makeRefundForActiveLoan(loan, newRefundTransaction);

        this.loanTransactionRepository.saveAndFlush(newRefundTransaction);

        if (StringUtils.isNotBlank(noteText)) {
            final Note note = Note.loanTransactionNote(loan, newRefundTransaction, noteText);
            this.noteRepository.save(note);
        }

        loanAccrualsProcessingService.processAccrualsOnInterestRecalculation(loan, loan.isInterestBearingAndInterestRecalculationEnabled(),
                true);

        journalEntryPoster.postJournalEntriesForLoanTransaction(newRefundTransaction, false, false);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanRefundPostBusinessEvent(newRefundTransaction));

        builderResult.withEntityId(newRefundTransaction.getId()).withOfficeId(loan.getOfficeId()).withClientId(loan.getClientId())
                .withGroupId(loan.getGroupId());

        return newRefundTransaction;
    }

    @Override
    public LoanTransaction foreCloseLoan(Loan loan, final LocalDate foreClosureDate, final String noteText, final ExternalId externalId,
            Map<Long, BigDecimal> chargePercentages, Map<String, Object> changes) {

        if (loan.isChargedOff() && DateUtils.isBefore(foreClosureDate, loan.getChargedOffOnDate())) {
            throw new GeneralPlatformDomainRuleException("error.msg.transaction.date.cannot.be.earlier.than.charge.off.date", "Loan: "
                    + loan.getId()
                    + " backdated transaction is not allowed. Transaction date cannot be earlier than the charge-off date of the loan",
                    loan.getId());
        }
        businessEventNotifierService.notifyPreBusinessEvent(new LoanForeClosurePreBusinessEvent(loan));
        MonetaryCurrency currency = loan.getCurrency();
        List<LoanTransaction> newTransactions = new ArrayList<>();

        final ScheduleGeneratorDTO scheduleGeneratorDTO = null;
        final LoanRepaymentScheduleInstallment foreCloseDetail = loanBalanceService.fetchLoanForeclosureDetail(loan, foreClosureDate);

        // Merge with foreclosure charges from loan product and calculate fees FIRST so it can be included in accrual
        // transaction
        Map<Long, BigDecimal> mergedChargePercentages = foreclosureChargeHelper.mergeForeclosureChargesFromLoanProduct(loan,
                chargePercentages);
        Money foreclosureFeePayable = foreclosureChargeHelper.calculateForeclosureFees(loan, mergedChargePercentages, currency);

        // Process accruals including foreclosure fee
        loanAccrualsProcessingService.processAccrualsOnLoanForeClosure(loan, foreClosureDate, newTransactions, foreclosureFeePayable);

        Money interestPayable = foreCloseDetail.getInterestCharged(currency);
        Money feePayable = foreCloseDetail.getFeeChargesCharged(currency);
        Money penaltyPayable = foreCloseDetail.getPenaltyChargesCharged(currency);
        Money payPrincipal = foreCloseDetail.getPrincipal(currency);

        // Add foreclosure fee to fee payable for the repayment transaction
        feePayable = feePayable.plus(foreclosureFeePayable);

        updateInstallmentsPostDate(loan, foreClosureDate);

        LoanTransaction payment = null;
        List<LoanCharge> foreclosureChargesToSave = new ArrayList<>();

        if (payPrincipal.plus(interestPayable).plus(feePayable).plus(penaltyPayable).isGreaterThanZero()) {
            final PaymentDetail paymentDetail = null;
            payment = LoanTransaction.repayment(loan.getOffice(), payPrincipal.plus(interestPayable).plus(feePayable).plus(penaltyPayable),
                    paymentDetail, foreClosureDate, externalId);
            payment.updateLoan(loan);

            // Set transaction metadata with subtype "foreclosure"
            TransactionMetaData transactionMetaData = new TransactionMetaData("foreclosure");
            Gson gson = new Gson();
            payment.updateTransactionMetaData(gson.toJson(transactionMetaData));

            // Create actual LoanCharge entities for ALL foreclosure charges from product (even if not in request)
            // These charges will be paid by the repayment transaction, which will create LoanChargePaidBy objects
            if (mergedChargePercentages != null && !mergedChargePercentages.isEmpty()) {
                BigDecimal principalOutstanding = loan.getSummary().getTotalPrincipalOutstanding();
                for (Map.Entry<Long, BigDecimal> entry : mergedChargePercentages.entrySet()) {
                    Long chargeId = entry.getKey();
                    BigDecimal amountOrPercentage = entry.getValue();
                    try {
                        // Get charge definition
                        Charge chargeDefinition = foreclosureChargeHelper
                                .getChargeRepositoryWrapper().findOneWithNotFoundDetection(chargeId);

                        // Validate it's a foreclosure charge (chargeTimeType must be FORECLOSURE)
                        org.apache.fineract.portfolio.charge.domain.ChargeTimeType chargeTimeType = 
                                org.apache.fineract.portfolio.charge.domain.ChargeTimeType.fromInt(chargeDefinition.getChargeTimeType());
                        if (!org.apache.fineract.portfolio.charge.domain.ChargeTimeType.FORECLOSURE.equals(chargeTimeType)) {
                            log.warn("Charge {} is not a foreclosure charge (chargeTimeType: {}), skipping", chargeId, chargeTimeType);
                            continue;
                        }

                        // Validate charge calculation type - currently only support PERCENT_OF_PRINCIPAL_OUTSTANDING
                        ChargeCalculationType chargeCalculationType = ChargeCalculationType
                                .fromInt(chargeDefinition.getChargeCalculation());
                        if (!chargeCalculationType.isPercentageOfPrincipalOutstanding()) {
                            // TODO: Handle other calculation types (flat, other percentage types) in future
                            log.warn("Foreclosure charge {} has unsupported calculation type {}, skipping. Only PERCENT_OF_PRINCIPAL_OUTSTANDING is supported currently.", 
                                    chargeId, chargeCalculationType);
                            continue;
                        }

                        // Calculate charge amount for PERCENT_OF_PRINCIPAL_OUTSTANDING
                        BigDecimal chargeAmount = LoanCharge.percentageOf(principalOutstanding, amountOrPercentage);
                        chargeAmount = chargeAmount.setScale(currency.getDigitsAfterDecimal(),
                                java.math.RoundingMode.HALF_UP);

                        // Create actual LoanCharge entity for foreclosure
                        LoanCharge foreclosureLoanCharge = loanChargeService.create(loan, chargeDefinition,
                                principalOutstanding, amountOrPercentage, null, null, foreClosureDate, null, null,
                                chargeAmount, null);

                        // Verify the charge was created with a valid amount
                        Money chargeAmountMoney = foreclosureLoanCharge.getAmount(currency);
                        if (chargeAmountMoney == null || !chargeAmountMoney.isGreaterThanZero()) {
                            log.warn("Foreclosure charge {} was created with zero or invalid amount, skipping", chargeId);
                            continue;
                        }

                        // Align due date with the foreclosure date so repayment processing sees these charges as due now
                        foreclosureLoanCharge.setDueDate(foreClosureDate);

                        // Add the charge to the loan - this will make it available for payment processing
                        loanChargeService.addLoanCharge(loan, foreclosureLoanCharge);

                        // Collect charges to save all at once after the loop
                        foreclosureChargesToSave.add(foreclosureLoanCharge);
                        
                        log.debug("Created LoanCharge for foreclosure charge {} with amount {} (calculation type: {})", 
                                chargeId, chargeAmountMoney, chargeCalculationType);
                    } catch (Exception e) {
                        // Log and skip invalid charges - validation should have caught these already
                        log.warn("Failed to create LoanCharge for foreclosure charge {}: {}", chargeId, e.getMessage());
                    }
                }

                // Save all charges at once to minimize database calls
                // This ensures the charges are persisted and available when transaction processing runs
                if (!foreclosureChargesToSave.isEmpty()) {
                    loanChargeRepository.saveAllAndFlush(foreclosureChargesToSave);
                    // Verify charges are in the loan's active charges set for transaction processing
                    // They should already be there from addLoanCharge, but we ensure they're visible
                    Set<LoanCharge> activeCharges = loan.getActiveCharges();
                    Money totalForeclosureChargeOutstanding = Money.zero(currency);
                    for (LoanCharge charge : foreclosureChargesToSave) {
                        if (!activeCharges.contains(charge)) {
                            log.error("Foreclosure charge {} not found in loan's active charges - this will cause accounting errors", 
                                    charge.getId());
                        } else {
                            Money outstanding = charge.getAmountOutstanding(currency);
                            totalForeclosureChargeOutstanding = totalForeclosureChargeOutstanding.plus(outstanding);
                            log.debug("Foreclosure charge {} is in loan's active charges with outstanding amount {}", 
                                    charge.getId(), outstanding);
                        }
                    }
                    log.info("Total foreclosure charges outstanding: {} for loan {}", totalForeclosureChargeOutstanding, loan.getId());
                }
            }

            newTransactions.add(payment);
        }

        List<Long> transactionIds = new ArrayList<>();
        if (payment != null) {
            loanForeclosureValidator.validateForForeclosure(loan, payment.getTransactionDate());
        }
        loanDownPaymentTransactionValidator.validateAccountStatus(loan, LoanEvent.LOAN_FORECLOSURE);
        
        // Ensure foreclosure charges are properly associated with the loan before transaction processing
        // This ensures the transaction processor can find them via loan.getActiveCharges()
        if (!foreclosureChargesToSave.isEmpty() && payment != null) {
            // Verify charges are in getActiveCharges() which the transaction processor uses
            Set<LoanCharge> activeCharges = loan.getActiveCharges();
            Set<LoanCharge> feeCharges = activeCharges.stream()
                    .filter(LoanCharge::isFeeCharge)
                    .collect(java.util.stream.Collectors.toSet());
            
            for (LoanCharge charge : foreclosureChargesToSave) {
                boolean inActiveCharges = activeCharges.contains(charge);
                boolean inFeeCharges = feeCharges.contains(charge);
                boolean hasOutstanding = charge.getAmountOutstanding(currency).isGreaterThanZero();
                boolean isDueAtDisbursement = charge.isDueAtDisbursement();
                
                log.debug("Foreclosure charge {} - inActiveCharges: {}, inFeeCharges: {}, hasOutstanding: {}, isDueAtDisbursement: {}, outstanding: {}", 
                        charge.getId(), inActiveCharges, inFeeCharges, hasOutstanding, isDueAtDisbursement, 
                        charge.getAmountOutstanding(currency));
                
                if (!inActiveCharges) {
                    log.warn("Foreclosure charge {} not in loan's active charges - transaction processor will not find it", 
                            charge.getId());
                } else if (!inFeeCharges) {
                    log.warn("Foreclosure charge {} is not a fee charge - transaction processor will not process it for fee portion", 
                            charge.getId());
                } else if (!hasOutstanding) {
                    log.warn("Foreclosure charge {} has no outstanding amount - transaction processor will skip it", 
                            charge.getId());
                } else if (isDueAtDisbursement) {
                    log.warn("Foreclosure charge {} is marked as due at disbursement - transaction processor will skip it", 
                            charge.getId());
                }
            }
            
            Money totalFeeChargesOutstanding = feeCharges.stream()
                    .map(c -> c.getAmountOutstanding(currency))
                    .reduce(Money.zero(currency), Money::plus);
            Money totalForeclosureFeesOutstanding = foreclosureChargesToSave.stream()
                    .map(c -> c.getAmountOutstanding(currency))
                    .reduce(Money.zero(currency), Money::plus);
            Money transactionFeePortion = payment.getFeeChargesPortion(currency);
            
            log.info("Before transaction processing - Total fee charges outstanding: {}, Foreclosure fees outstanding: {}, Transaction fee portion: {}", 
                    totalFeeChargesOutstanding, totalForeclosureFeesOutstanding, transactionFeePortion);
        }
        
        // Ensure foreclosure charges are properly initialized and visible to transaction processor
        // The transaction processor will create LoanChargePaidBy entries during handleRepaymentOrRecoveryOrWaiverTransaction
        if (!foreclosureChargesToSave.isEmpty() && payment != null) {
            // Verify charges meet all conditions for transaction processor to find them:
            // 1. Must be active (should be true by default)
            // 2. Must be in loan.getActiveCharges() (should be there from addLoanCharge)
            // 3. Must be fee charges (should be true for foreclosure charges)
            // 4. Must have outstanding > 0 (should be set when created)
            // 5. Must not be due at disbursement (should be false for foreclosure charges)
            // 6. Must have a due date (we set it to foreclosure date)
            Set<LoanCharge> activeCharges = loan.getActiveCharges();
            for (LoanCharge charge : foreclosureChargesToSave) {
                if (!charge.isActive()) {
                    log.error("Foreclosure charge {} is not active - transaction processor will not find it", charge.getId());
                    charge.setActive(true);
                }
                if (!activeCharges.contains(charge)) {
                    log.error("Foreclosure charge {} not in loan's active charges - adding it", charge.getId());
                    if (loan.getLoanCharges() == null) {
                        loan.setCharges(new HashSet<>());
                    }
                    loan.getLoanCharges().add(charge);
                }
                if (charge.getDueLocalDate() == null) {
                    log.error("Foreclosure charge {} has no due date - setting it to foreclosure date", charge.getId());
                    charge.setDueDate(foreClosureDate);
                }
                if (charge.isDueAtDisbursement()) {
                    log.error("Foreclosure charge {} is marked as due at disbursement - this is incorrect", charge.getId());
                }
            }
        }
        
        handleForeClosureTransactions(loan, payment, scheduleGeneratorDTO);
        
        // Collect charge allocation info (charge, amount) for foreclosure charges
        // We'll create LoanChargePaidBy entries AFTER saving the transaction to ensure
        // they reference the saved transaction with a proper ID
        List<ChargeAllocationInfo> foreclosureChargeAllocations = new ArrayList<>();

        loanAccrualsProcessingService.reprocessExistingAccruals(loan, true);
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            loanAccrualsProcessingService.processIncomePostingAndAccruals(loan, true);
        }

        for (LoanTransaction newTransaction : newTransactions) {
            // CRITICAL: Collect LoanChargePaidBy entries BEFORE saving, as they reference the unsaved transaction
            // We'll need to create new entries with the saved transaction reference after saving
            Set<LoanChargePaidBy> existingChargesPaidBy = newTransaction.getLoanChargesPaid();
            List<LoanChargePaidBy> chargesToMigrate = new ArrayList<>();
            if (existingChargesPaidBy != null && !existingChargesPaidBy.isEmpty()) {
                log.info("Found {} existing LoanChargePaidBy entries in transaction before saving, will migrate them after saving", 
                        existingChargesPaidBy.size());
                // Create a copy of the entries to migrate (we can't use the original as they reference unsaved transaction)
                for (LoanChargePaidBy chargePaidBy : existingChargesPaidBy) {
                    chargesToMigrate.add(chargePaidBy);
                }
            }
            
            // Save the transaction first
            LoanTransaction savedNewTransaction = loanAccountService.saveLoanTransactionWithDataIntegrityViolationChecks(newTransaction);
            loan.addLoanTransaction(savedNewTransaction);
            
            // CRITICAL: For foreclosure payment transaction, calculate allocations based on ACTUAL fee portion AFTER saving
            // The transaction processor may have updated the fee portion, so we need to use the saved transaction's fee portion
            if (newTransaction == payment && savedNewTransaction.getFeeChargesPortion(currency).isGreaterThanZero()) {
                Money actualFeePortion = savedNewTransaction.getFeeChargesPortion(currency);
                Set<LoanChargePaidBy> currentChargesPaidBy = savedNewTransaction.getLoanChargesPaid();
                Money currentTotal = currentChargesPaidBy.stream()
                        .filter(cpb -> cpb.getLoanCharge() != null && cpb.getLoanCharge().isFeeCharge())
                        .map(cpb -> Money.of(currency, cpb.getAmount()))
                        .reduce(Money.zero(currency), Money::plus);
                
                // If fee portion is greater than current total, we need to create entries for foreclosure charges
                if (actualFeePortion.isGreaterThan(currentTotal) && !foreclosureChargesToSave.isEmpty()) {
                    Money missingAmount = actualFeePortion.minus(currentTotal);
                    log.info("ROOT CAUSE FIX: Fee portion ({}) is greater than existing LoanChargePaidBy total ({}), missing amount: {}. Creating entries for foreclosure charges.", 
                            actualFeePortion, currentTotal, missingAmount);
                    
                    // Get foreclosure charge IDs that already have entries
                    Set<Long> existingForeclosureChargeIds = currentChargesPaidBy.stream()
                            .filter(cpb -> cpb.getLoanCharge() != null)
                            .map(cpb -> cpb.getLoanCharge().getId())
                            .collect(java.util.stream.Collectors.toSet());
                    
                    // Create allocations for missing amount from foreclosure charges
                    Money amountToAllocate = missingAmount;
                    List<LoanCharge> sortedForeclosureCharges = foreclosureChargesToSave.stream()
                            .filter(LoanCharge::isFeeCharge)
                            .filter(charge -> !existingForeclosureChargeIds.contains(charge.getId()))
                            .sorted((c1, c2) -> {
                                LocalDate d1 = c1.getDueLocalDate();
                                LocalDate d2 = c2.getDueLocalDate();
                                if (d1 == null && d2 == null) return 0;
                                if (d1 == null) return 1;
                                if (d2 == null) return -1;
                                return d1.compareTo(d2);
                            })
                            .collect(java.util.stream.Collectors.toList());
                    
                    for (LoanCharge foreclosureCharge : sortedForeclosureCharges) {
                        if (!amountToAllocate.isGreaterThanZero()) {
                            break;
                        }
                        Money chargeOutstanding = foreclosureCharge.getAmountOutstanding(currency);
                        if (!chargeOutstanding.isGreaterThanZero()) {
                            continue;
                        }
                        
                        Money amountForThisCharge = amountToAllocate.isGreaterThanOrEqualTo(chargeOutstanding) 
                                ? chargeOutstanding 
                                : amountToAllocate;
                        
                        // Update the charge's paid amount
                        foreclosureCharge.updatePaidAmountBy(amountForThisCharge, null, amountForThisCharge);
                        
                        foreclosureChargeAllocations.add(new ChargeAllocationInfo(foreclosureCharge, amountForThisCharge.getAmount()));
                        amountToAllocate = amountToAllocate.minus(amountForThisCharge);
                        log.info("Added foreclosure charge {} to allocations with amount {} (outstanding was {})", 
                                foreclosureCharge.getId(), amountForThisCharge, chargeOutstanding);
                    }
                    
                    if (amountToAllocate.isGreaterThanZero()) {
                        log.warn("ROOT CAUSE FIX: Remaining amount {} could not be allocated to foreclosure charges", amountToAllocate);
                    }
                }
            }
            
            // CRITICAL: Collect existing entries before clearing (they reference the saved transaction now)
            Set<LoanChargePaidBy> existingChargesPaidByAfterSave = savedNewTransaction.getLoanChargesPaid();
            List<LoanChargePaidBy> chargesToMigrateAfterSave = new ArrayList<>();
            if (existingChargesPaidByAfterSave != null && !existingChargesPaidByAfterSave.isEmpty()) {
                for (LoanChargePaidBy chargePaidBy : existingChargesPaidByAfterSave) {
                    chargesToMigrateAfterSave.add(chargePaidBy);
                }
            }
            
            // CRITICAL: Clear the collection to recreate all entries with proper managed entities
            savedNewTransaction.getLoanChargesPaid().clear();
            
            // CRITICAL: Create ALL LoanChargePaidBy entries and add them to the collection
            // Then save the transaction again to trigger cascade and persist the entries
            boolean hasEntriesToAdd = false;
            
            // Migrate existing entries from BEFORE save (if any)
            if (!chargesToMigrate.isEmpty()) {
                log.info("Migrating {} existing LoanChargePaidBy entries from before save to saved transaction {}", 
                        chargesToMigrate.size(), savedNewTransaction.getId());
                for (LoanChargePaidBy oldChargePaidBy : chargesToMigrate) {
                    LoanCharge oldLoanCharge = oldChargePaidBy.getLoanCharge();
                    if (oldLoanCharge == null) {
                        log.warn("Skipping migration of LoanChargePaidBy with null LoanCharge");
                        continue;
                    }
                    LoanCharge managedCharge = loanChargeRepository.findById(oldLoanCharge.getId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "LoanCharge not found for migration: " + oldLoanCharge.getId()));
                    
                    LoanChargePaidBy newChargePaidBy = new LoanChargePaidBy(savedNewTransaction, 
                            managedCharge, oldChargePaidBy.getAmount(), 
                            oldChargePaidBy.getInstallmentNumber());
                    savedNewTransaction.getLoanChargesPaid().add(newChargePaidBy);
                    hasEntriesToAdd = true;
                    log.info("Added LoanChargePaidBy to collection for migration - charge {} with amount {}", 
                            managedCharge.getId(), oldChargePaidBy.getAmount());
                }
            }
            
            // Migrate existing entries from AFTER save (these already reference the saved transaction)
            if (!chargesToMigrateAfterSave.isEmpty()) {
                log.info("Migrating {} existing LoanChargePaidBy entries from after save to saved transaction {}", 
                        chargesToMigrateAfterSave.size(), savedNewTransaction.getId());
                for (LoanChargePaidBy existingChargePaidBy : chargesToMigrateAfterSave) {
                    LoanCharge existingLoanCharge = existingChargePaidBy.getLoanCharge();
                    if (existingLoanCharge == null) {
                        log.warn("Skipping migration of LoanChargePaidBy with null LoanCharge");
                        continue;
                    }
                    // Reload to ensure it's a managed entity
                    LoanCharge managedCharge = loanChargeRepository.findById(existingLoanCharge.getId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "LoanCharge not found for migration: " + existingLoanCharge.getId()));
                    
                    LoanChargePaidBy newChargePaidBy = new LoanChargePaidBy(savedNewTransaction, 
                            managedCharge, existingChargePaidBy.getAmount(), 
                            existingChargePaidBy.getInstallmentNumber());
                    savedNewTransaction.getLoanChargesPaid().add(newChargePaidBy);
                    hasEntriesToAdd = true;
                    log.info("Added LoanChargePaidBy to collection for migration (after save) - charge {} with amount {}", 
                            managedCharge.getId(), existingChargePaidBy.getAmount());
                }
            }
            
            // Create new entries for foreclosure charges (if not already created by transaction processor)
            if (newTransaction == payment && !foreclosureChargeAllocations.isEmpty()) {
                log.info("Creating {} LoanChargePaidBy entries for foreclosure charges on transaction {}", 
                        foreclosureChargeAllocations.size(), savedNewTransaction.getId());
                for (ChargeAllocationInfo allocation : foreclosureChargeAllocations) {
                    // CRITICAL: Reload the LoanCharge from repository to ensure it's a managed entity
                    // A detached entity can cause issues when JPA tries to persist the relationship
                    LoanCharge managedCharge = loanChargeRepository.findById(allocation.charge.getId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "LoanCharge not found after save: " + allocation.charge.getId()));
                    
                    // Create LoanChargePaidBy entry with the SAVED transaction reference and MANAGED charge
                    LoanChargePaidBy loanChargePaidBy = new LoanChargePaidBy(savedNewTransaction, managedCharge,
                            allocation.amount, null);
                    // CRITICAL: Add to collection - cascade will save it when we save the transaction
                    savedNewTransaction.getLoanChargesPaid().add(loanChargePaidBy);
                    hasEntriesToAdd = true;
                    log.info("Added LoanChargePaidBy to collection for foreclosure charge {} with amount {} (managed charge loaded)", 
                            managedCharge.getId(), allocation.amount);
                }
            }
            
            // CRITICAL: ALWAYS save the transaction again if we have any entries to ensure they're persisted
            // This is needed even if entries were migrated (they need to be re-saved with the saved transaction reference)
            if (hasEntriesToAdd || !chargesToMigrate.isEmpty() || !chargesToMigrateAfterSave.isEmpty()) {
                log.info("Saving transaction {} again to trigger cascade for {} LoanChargePaidBy entries", 
                        savedNewTransaction.getId(), savedNewTransaction.getLoanChargesPaid().size());
                LoanTransaction savedTransaction = loanAccountService.saveLoanTransactionWithDataIntegrityViolationChecks(savedNewTransaction);
                loanTransactionRepository.flush();
                log.info("Transaction saved with LoanChargePaidBy entries via cascade");
                
                // CRITICAL: Reload the transaction from repository to ensure all relationships are properly loaded
                // After save, the entity might have detached relationships, so we reload it
                Long transactionId = savedTransaction.getId();
                LoanTransaction reloadedTransaction = loanTransactionRepository.findById(transactionId)
                        .orElseThrow(() -> new IllegalStateException("Transaction not found after save: " + transactionId));
                savedNewTransaction = reloadedTransaction;
                
                // CRITICAL: Explicitly initialize all LoanCharge relationships in LoanChargePaidBy entries
                // This ensures the relationships are loaded before passing to accounting system
                Set<LoanChargePaidBy> chargesPaidBy = savedNewTransaction.getLoanChargesPaid();
                if (chargesPaidBy != null) {
                    for (LoanChargePaidBy chargePaidBy : chargesPaidBy) {
                        // Force initialization of LoanCharge relationship by accessing it and its nested Charge
                        LoanCharge loanCharge = chargePaidBy.getLoanCharge();
                        if (loanCharge != null) {
                            // Access the Charge relationship to force it to load (accounting system needs this)
                            loanCharge.getCharge();
                            // Access isFeeCharge/isPenaltyCharge to ensure the relationship is fully initialized
                            loanCharge.isFeeCharge();
                            loanCharge.isPenaltyCharge();
                        }
                    }
                }
            } else {
                // Even if we didn't add new entries, we still need to reload to ensure relationships are loaded
                // This handles the case where entries were already persisted by the transaction processor
                Long transactionId = savedNewTransaction.getId();
                LoanTransaction reloadedTransaction = loanTransactionRepository.findById(transactionId)
                        .orElseThrow(() -> new IllegalStateException("Transaction not found: " + transactionId));
                savedNewTransaction = reloadedTransaction;
                
                // Force initialization of relationships
                Set<LoanChargePaidBy> chargesPaidBy = savedNewTransaction.getLoanChargesPaid();
                if (chargesPaidBy != null) {
                    for (LoanChargePaidBy chargePaidBy : chargesPaidBy) {
                        LoanCharge loanCharge = chargePaidBy.getLoanCharge();
                        if (loanCharge != null) {
                            loanCharge.getCharge();
                            loanCharge.isFeeCharge();
                            loanCharge.isPenaltyCharge();
                        }
                    }
                }
            }
            
            // CRITICAL: Verify the collection has the entries (this forces lazy loading if needed)
            // The collection should now contain all the entries we just added
            Set<LoanChargePaidBy> chargesPaidBy = savedNewTransaction.getLoanChargesPaid();
            if (chargesPaidBy != null) {
                // Force initialization by iterating - the collection should have our entries
                int count = 0;
                int feeChargeCount = 0;
                Money totalAmount = Money.zero(currency);
                for (LoanChargePaidBy chargePaidBy : chargesPaidBy) {
                    count++;
                    LoanCharge loanCharge = chargePaidBy.getLoanCharge();
                    if (loanCharge == null) {
                        log.warn("LoanChargePaidBy entry {} has null LoanCharge relationship", chargePaidBy.getId());
                        continue;
                    }
                    if (loanCharge.isFeeCharge()) {
                        feeChargeCount++;
                        totalAmount = totalAmount.plus(Money.of(currency, chargePaidBy.getAmount()));
                    }
                }
                log.info("After saving - Transaction {}: loanChargesPaid collection has {} entries ({} fee charges), total fee amount: {}", 
                        savedNewTransaction.getId(), count, feeChargeCount, totalAmount);
                
                if (count == 0 && savedNewTransaction.getFeeChargesPortion(currency).isGreaterThanZero()) {
                    log.error("CRITICAL: Transaction {} has fee portion {} but collection has 0 entries after saving! " +
                            "This will cause accounting error!", savedNewTransaction.getId(), 
                            savedNewTransaction.getFeeChargesPortion(currency));
                } else if (feeChargeCount == 0 && savedNewTransaction.getFeeChargesPortion(currency).isGreaterThanZero()) {
                    log.error("CRITICAL: Transaction {} has fee portion {} but no fee charges found in collection ({} entries)! " +
                            "This will cause accounting error!", savedNewTransaction.getId(), 
                            savedNewTransaction.getFeeChargesPortion(currency), count);
                }
            }
            
            // Use savedNewTransaction - the collection should now have all entries
            journalEntryPoster.postJournalEntriesForLoanTransaction(savedNewTransaction, false, false);
            transactionIds.add(savedNewTransaction.getId());
        }
        changes.put("transactions", transactionIds);
        changes.put("eventAmount", payPrincipal.getAmount().negate());

        // Transition loan status to CLOSED after foreclosure
        loanLifecycleStateMachine.determineAndTransition(loan, foreClosureDate);

        loan = loanAccountService.saveAndFlushLoanWithDataIntegrityViolationChecks(loan);

        // Disable standing instructions and release collateral for closed loan
        disableStandingInstructionsLinkedToClosedLoan(loan);
        updateAndSaveLoanCollateralTransactionsForIndividualAccounts(loan, payment);

        if (StringUtils.isNotBlank(noteText)) {
            changes.put("note", noteText);
            final Note note = Note.loanNote(loan, noteText);
            this.noteRepository.save(note);
        }

        businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(loan));
        businessEventNotifierService.notifyPostBusinessEvent(new LoanForeClosurePostBusinessEvent(payment));
        return payment;
    }

    @Override
    @Transactional
    public void disableStandingInstructionsLinkedToClosedLoan(Loan loan) {
        if ((loan != null) && (loan.getStatus() != null) && loan.getStatus().isClosed()) {
            final Integer standingInstructionStatus = StandingInstructionStatus.ACTIVE.getValue();
            final Collection<AccountTransferStandingInstruction> accountTransferStandingInstructions = this.standingInstructionRepository
                    .findByLoanAccountAndStatus(loan, standingInstructionStatus);

            if (!accountTransferStandingInstructions.isEmpty()) {
                for (AccountTransferStandingInstruction accountTransferStandingInstruction : accountTransferStandingInstructions) {
                    accountTransferStandingInstruction.updateStatus(StandingInstructionStatus.DISABLED.getValue());
                    this.standingInstructionRepository.save(accountTransferStandingInstruction);
                }
            }
        }
    }

    @Override
    public void updateAndSaveLoanCollateralTransactionsForIndividualAccounts(Loan loan, LoanTransaction loanTransaction) {
        if (loan.getLoanType().isIndividualAccount()) {
            Set<LoanCollateralManagement> loanCollateralManagements = loan.getLoanCollateralManagements();
            for (LoanCollateralManagement loanCollateralManagement : loanCollateralManagements) {
                if (loanTransaction != null) {
                    loanCollateralManagement.setLoanTransactionData(loanTransaction);
                }
                ClientCollateralManagement clientCollateralManagement = loanCollateralManagement.getClientCollateralManagement();

                if (loan.getStatus().isClosed()) {
                    loanCollateralManagement.setIsReleased(true);
                    BigDecimal quantity = loanCollateralManagement.getQuantity();
                    clientCollateralManagement.updateQuantity(clientCollateralManagement.getQuantity().add(quantity));
                    loanCollateralManagement.setClientCollateralManagement(clientCollateralManagement);
                }
            }
            this.loanCollateralManagementRepository.saveAll(loanCollateralManagements);
        }
    }

    @Override
    public Pair<LoanTransaction, LoanTransaction> makeRefund(final Loan loan, final ScheduleGeneratorDTO scheduleGeneratorDTO,
            final LoanTransactionType loanTransactionType, final LocalDate transactionDate, final BigDecimal transactionAmount,
            final PaymentDetail paymentDetail, final ExternalId txnExternalId, final Boolean interestRefundCalculationOverride) {
        // Pre-processing business event
        switch (loanTransactionType) {
            case MERCHANT_ISSUED_REFUND ->
                businessEventNotifierService.notifyPreBusinessEvent(new LoanTransactionMerchantIssuedRefundPreBusinessEvent(loan));
            case PAYOUT_REFUND ->
                businessEventNotifierService.notifyPreBusinessEvent(new LoanTransactionPayoutRefundPreBusinessEvent(loan));
            default ->
                throw new UnsupportedOperationException(String.format("Not configured loan transaction type: %s!", loanTransactionType));
        }
        LoanTransaction refundTransaction = LoanTransaction.refund(loan, loanTransactionType, transactionAmount, paymentDetail,
                transactionDate, txnExternalId);

        final boolean isTransactionChronologicallyLatest = loanTransactionService.isChronologicallyLatestRepaymentOrWaiver(loan,
                refundTransaction);

        final boolean shouldCreateInterestRefundTransaction = Objects.requireNonNullElseGet(interestRefundCalculationOverride,
                () -> loan.getLoanProductRelatedDetail().getSupportedInterestRefundTypes().stream()
                        .map(LoanSupportedInterestRefundTypes::getTransactionType)
                        .anyMatch(transactionType -> transactionType.equals(loanTransactionType)));
        LoanTransaction interestRefundTransaction = null;

        if (shouldCreateInterestRefundTransaction) {
            interestRefundTransaction = createInterestRefundLoanTransaction(loan, refundTransaction);
            if (interestRefundTransaction != null) {
                interestRefundTransaction.getLoanTransactionRelations().add(LoanTransactionRelation
                        .linkToTransaction(interestRefundTransaction, refundTransaction, LoanTransactionRelationTypeEnum.RELATED));
            }
        }

        final LoanRepaymentScheduleInstallment currentInstallment = loan.fetchLoanRepaymentScheduleInstallmentByDueDate(transactionDate);

        boolean processLatest = isTransactionChronologicallyLatest //
                && !loan.isForeclosure() //
                && !loan.hasChargesAffectedByBackdatedRepaymentLikeTransaction(refundTransaction) //
                && loanTransactionProcessingService.canProcessLatestTransactionOnly(loan, refundTransaction, currentInstallment); //
        if (processLatest) {
            loanTransactionProcessingService.processLatestTransaction(loan.getTransactionProcessingStrategyCode(), refundTransaction,
                    new TransactionCtx(loan.getCurrency(), loan.getRepaymentScheduleInstallments(), loan.getActiveCharges(),
                            new MoneyHolder(loan.getTotalOverpaidAsMoney()), null));
            loan.getLoanTransactions().add(refundTransaction);
            if (interestRefundTransaction != null) {
                loanTransactionProcessingService.processLatestTransaction(loan.getTransactionProcessingStrategyCode(),
                        interestRefundTransaction, new TransactionCtx(loan.getCurrency(), loan.getRepaymentScheduleInstallments(),
                                loan.getActiveCharges(), new MoneyHolder(loan.getTotalOverpaidAsMoney()), null));
                loan.addLoanTransaction(interestRefundTransaction);
            }
        } else {
            if (loan.isCumulativeSchedule() && loan.isInterestBearingAndInterestRecalculationEnabled()) {
                loanScheduleService.regenerateRepaymentScheduleWithInterestRecalculation(loan, scheduleGeneratorDTO);
            } else if (loan.isProgressiveSchedule() && ((loan.hasChargeOffTransaction() && loan.hasAccelerateChargeOffStrategy())
                    || loan.hasContractTerminationTransaction())) {
                loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
            }
            loan.getLoanTransactions().add(refundTransaction);
            if (interestRefundTransaction != null) {
                loan.addLoanTransaction(interestRefundTransaction);
            }

            reprocessLoanTransactionsService.reprocessTransactions(loan);
        }

        // Store and flush newly created transaction to generate PK
        loanAccountService.saveLoanTransactionWithDataIntegrityViolationChecks(refundTransaction);
        if (interestRefundTransaction != null) {
            loanAccountService.saveLoanTransactionWithDataIntegrityViolationChecks(interestRefundTransaction);
        }

        loanLifecycleStateMachine.determineAndTransition(loan, transactionDate);

        switch (loanTransactionType) {
            case MERCHANT_ISSUED_REFUND -> businessEventNotifierService
                    .notifyPostBusinessEvent(new LoanTransactionMerchantIssuedRefundPostBusinessEvent(refundTransaction));
            case PAYOUT_REFUND ->
                businessEventNotifierService.notifyPostBusinessEvent(new LoanTransactionPayoutRefundPostBusinessEvent(refundTransaction));
            default ->
                throw new UnsupportedOperationException(String.format("Not configured loan transaction type: %s!", loanTransactionType));
        }

        if (interestRefundTransaction != null) {
            businessEventNotifierService
                    .notifyPostBusinessEvent(new LoanTransactionInterestRefundPostBusinessEvent(interestRefundTransaction));
        }
        return Pair.of(refundTransaction, interestRefundTransaction);
    }

    @Override
    public void updateAndSavePostDatedChecksForIndividualAccount(final Loan loan, final LoanTransaction transaction) {
        if (loan.getLoanType().isIndividualAccount()) {
            // Mark Post Dated Check as paid.
            final Set<LoanTransactionToRepaymentScheduleMapping> loanTransactionToRepaymentScheduleMappings = transaction
                    .getLoanTransactionToRepaymentScheduleMappings();

            if (loanTransactionToRepaymentScheduleMappings != null) {
                for (LoanTransactionToRepaymentScheduleMapping loanTransactionToRepaymentScheduleMapping : loanTransactionToRepaymentScheduleMappings) {
                    LoanRepaymentScheduleInstallment loanRepaymentScheduleInstallment = loanTransactionToRepaymentScheduleMapping
                            .getLoanRepaymentScheduleInstallment();
                    if (loanRepaymentScheduleInstallment != null) {
                        final boolean isPaid = loanRepaymentScheduleInstallment.isNotFullyPaidOff();
                        PostDatedChecks postDatedChecks = this.postDatedChecksRepository
                                .getPendingPostDatedCheck(loanRepaymentScheduleInstallment);

                        if (postDatedChecks != null) {
                            if (!isPaid) {
                                postDatedChecks.setStatus(PostDatedChecksStatus.POST_DATED_CHECKS_PAID);
                            } else {
                                postDatedChecks.setStatus(PostDatedChecksStatus.POST_DATED_CHECKS_PENDING);
                            }
                            this.postDatedChecksRepository.saveAndFlush(postDatedChecks);
                        } else {
                            break;
                        }
                    }
                }
            }
        }
    }

    @Override
    public LoanTransaction applyInterestRefund(final Loan loan, final LoanRefundRequestData loanRefundRequest) {
        final PaymentDetail paymentDetail = null;
        final LocalDate transactionDate = DateUtils.getBusinessLocalDate();
        final BigDecimal refundAmount = loanRefundRequest.getTotalAmount();

        final LoanTransaction interestRefundTransaction = LoanTransaction.interestRefund(loan, loan.getOffice(), refundAmount,
                loanRefundRequest.getPrincipal(), loanRefundRequest.getInterest(), loanRefundRequest.getFeeCharges(),
                loanRefundRequest.getPenaltyCharges(), paymentDetail, transactionDate, externalIdFactory.create());
        interestRefundTransaction.updateLoan(loan);
        loanAccountService.saveLoanTransactionWithDataIntegrityViolationChecks(interestRefundTransaction);
        loan.addLoanTransaction(interestRefundTransaction);
        journalEntryPoster.postJournalEntriesForLoanTransaction(interestRefundTransaction, false, false);

        return interestRefundTransaction;
    }

    private void updateInstallmentsPostDate(final Loan loan, final LocalDate transactionDate) {
        final List<LoanRepaymentScheduleInstallment> newInstallments = new ArrayList<>(loan.getRepaymentScheduleInstallments());
        final MonetaryCurrency currency = loan.getCurrency();
        Money totalPrincipal = Money.zero(currency);
        final Money[] balances = loanBalanceService.retrieveIncomeForOverlappingPeriod(loan, transactionDate);
        boolean isInterestComponent = true;
        for (final LoanRepaymentScheduleInstallment installment : loan.getRepaymentScheduleInstallments()) {
            if (!DateUtils.isAfter(transactionDate, installment.getDueDate())) {
                totalPrincipal = totalPrincipal.plus(installment.getPrincipal(currency));
                newInstallments.remove(installment);
                if (DateUtils.isEqual(transactionDate, installment.getDueDate())) {
                    isInterestComponent = false;
                }
            }

        }

        for (LoanDisbursementDetails loanDisbursementDetails : loan.getDisbursementDetails()) {
            if (loanDisbursementDetails.actualDisbursementDate() == null) {
                totalPrincipal = Money.of(currency, totalPrincipal.getAmount().subtract(loanDisbursementDetails.principal()));
            }
        }

        LocalDate installmentStartDate = loan.getDisbursementDate();

        if (!newInstallments.isEmpty()) {
            installmentStartDate = newInstallments.get(newInstallments.size() - 1).getDueDate();
        }

        int installmentNumber = newInstallments.size();

        if (!isInterestComponent) {
            installmentNumber++;
        }

        final LoanRepaymentScheduleInstallment newInstallment = new LoanRepaymentScheduleInstallment(null, newInstallments.size() + 1,
                installmentStartDate, transactionDate, totalPrincipal.getAmount(), balances[0].getAmount(), balances[1].getAmount(),
                balances[2].getAmount(), isInterestComponent, null);
        newInstallment.updateInstallmentNumber(newInstallments.size() + 1);
        newInstallments.add(newInstallment);
        loan.updateLoanScheduleOnForeclosure(newInstallments);

        final Set<LoanCharge> charges = loan.getActiveCharges();
        final int penaltyWaitPeriod = 0;
        for (LoanCharge loanCharge : charges) {
            if (DateUtils.isAfter(loanCharge.getDueLocalDate(), transactionDate)) {
                loanCharge.setActive(false);
            } else if (loanCharge.getDueLocalDate() == null) {
                loanChargeService.recalculateLoanCharge(loan, loanCharge, penaltyWaitPeriod);
                loanCharge.updateWaivedAmount(currency);
            }
        }

        for (LoanTransaction loanTransaction : loan.getLoanTransactions()) {
            if (loanTransaction.isChargesWaiver()) {
                for (LoanChargePaidBy chargePaidBy : loanTransaction.getLoanChargesPaid()) {
                    if ((chargePaidBy.getLoanCharge().isDueDateCharge()
                            && DateUtils.isBefore(transactionDate, chargePaidBy.getLoanCharge().getDueLocalDate()))
                            || (chargePaidBy.getLoanCharge().isInstalmentFee() && chargePaidBy.getInstallmentNumber() != null
                                    && chargePaidBy.getInstallmentNumber() > installmentNumber)) {
                        loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(loanTransaction.getLoan(),
                                loanTransaction, "reversed");
                        loanTransaction.reverse();
                    }
                }

            }
        }
    }

    @SuppressWarnings("null")
    private void makeRepayment(final Loan loan, final LoanTransaction repaymentTransaction,
            final ScheduleGeneratorDTO scheduleGeneratorDTO) {
        loanChargeValidator.validateRepaymentTypeTransactionNotBeforeAChargeRefund(loan, repaymentTransaction, "created");
        loanDownPaymentHandlerService.handleRepaymentOrRecoveryOrWaiverTransaction(loan, repaymentTransaction, null, scheduleGeneratorDTO);
    }

    private void handleForeClosureTransactions(final Loan loan, final LoanTransaction repaymentTransaction,
            final ScheduleGeneratorDTO scheduleGeneratorDTO) {
        // Process the transaction first before marking as foreclosed
        // This allows the transaction processor to use processLatest which is more efficient
        loanDownPaymentHandlerService.handleRepaymentOrRecoveryOrWaiverTransaction(loan, repaymentTransaction, null, scheduleGeneratorDTO);
        // Set foreclosure status after transaction processing
        loan.setLoanSubStatus(LoanSubStatus.FORECLOSED);
    }

    @Override
    public void createAndSaveLoanScheduleArchive(Loan loan) {
        final LoanRescheduleRequest loanRescheduleRequest = null;
        final List<LoanRepaymentScheduleInstallment> installments = loan.getRepaymentScheduleInstallments();
        this.loanScheduleHistoryWritePlatformService.createAndSaveLoanScheduleArchive(installments, loan, loanRescheduleRequest);
    }

    /**
     * Helper class to hold charge allocation information before creating LoanChargePaidBy entries.
     * This allows us to create the entries AFTER the transaction is saved, ensuring proper persistence.
     */
    private static class ChargeAllocationInfo {
        final LoanCharge charge;
        final BigDecimal amount;

        ChargeAllocationInfo(LoanCharge charge, BigDecimal amount) {
            this.charge = charge;
            this.amount = amount;
        }
    }

}
