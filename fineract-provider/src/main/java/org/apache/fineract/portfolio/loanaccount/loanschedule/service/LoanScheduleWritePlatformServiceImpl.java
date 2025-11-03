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
package org.apache.fineract.portfolio.loanaccount.loanschedule.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanScheduleVariationsAddedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanScheduleVariationsDeletedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.loanaccount.data.LoanTermVariationsData;
import org.apache.fineract.portfolio.loanaccount.data.ScheduleGeneratorDTO;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountDomainService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanAccountService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTermVariations;
import org.apache.fineract.portfolio.loanaccount.service.LoanAccrualsProcessingService;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanBalanceService;
import org.apache.fineract.portfolio.loanaccount.service.LoanScheduleService;
import org.apache.fineract.portfolio.loanaccount.service.LoanUtilService;
import org.apache.fineract.portfolio.loanaccount.service.ReprocessLoanTransactionsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class LoanScheduleWritePlatformServiceImpl implements LoanScheduleWritePlatformService {

    private final LoanAssembler loanAssembler;
    private final LoanScheduleAssembler loanScheduleAssembler;
    private final LoanUtilService loanUtilService;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final LoanAccrualsProcessingService loanAccrualsProcessingService;
    private final LoanScheduleService loanScheduleService;
    private final LoanAccountService loanAccountService;
    private final LoanRepository loanRepository;
    private final FromJsonHelper fromApiJsonHelper;
    private final LoanAccountDomainService loanAccountDomainService;
    private final ReprocessLoanTransactionsService reprocessLoanTransactionsService;
    private final LoanBalanceService loanBalanceService;

    @Override
    public CommandProcessingResult addLoanScheduleVariations(final Long loanId, final JsonCommand command) {
        final Loan loan = loanAssembler.assembleFrom(loanId);
        Map<Long, LoanTermVariations> loanTermVariations = new HashMap<>();
        for (LoanTermVariations termVariations : loan.getLoanTermVariations()) {
            loanTermVariations.put(termVariations.getId(), termVariations);
        }
        loanScheduleAssembler.assempleVariableScheduleFrom(loan, command.json());

        loanAccountService.saveLoanWithDataIntegrityViolationChecks(loan);
        final Map<String, Object> changes = new HashMap<>();
        List<LoanTermVariationsData> newVariationsData = new ArrayList<>();
        List<LoanTermVariations> modifiedVariations = loan.getLoanTermVariations();
        for (LoanTermVariations termVariations : modifiedVariations) {
            if (loanTermVariations.containsKey(termVariations.getId())) {
                loanTermVariations.remove(termVariations.getId());
            } else {
                newVariationsData.add(termVariations.toData());
            }
        }
        if (!loanTermVariations.isEmpty()) {
            changes.put("removedVariations", loanTermVariations.keySet());
        }
        changes.put("loanTermVariations", newVariationsData);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanScheduleVariationsAddedBusinessEvent(loan));
        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withLoanId(loanId) //
                .with(changes) //
                .build();
    }

    @Override
    public CommandProcessingResult deleteLoanScheduleVariations(final Long loanId) {
        final Loan loan = loanAssembler.assembleFrom(loanId);
        List<LoanTermVariations> variations = loan.getLoanTermVariations();
        List<Long> deletedVariations = new ArrayList<>(variations.size());
        for (LoanTermVariations loanTermVariations : variations) {
            deletedVariations.add(loanTermVariations.getId());
        }
        final Map<String, Object> changes = new HashMap<>();
        changes.put("removedEntityIds", deletedVariations);
        loan.getLoanTermVariations().clear();
        final LocalDate recalculateFrom = null;
        ScheduleGeneratorDTO scheduleGeneratorDTO = loanUtilService.buildScheduleGeneratorDTO(loan, recalculateFrom);
        loanScheduleService.regenerateRepaymentSchedule(loan, scheduleGeneratorDTO);
        loanAccrualsProcessingService.reprocessExistingAccruals(loan, false);
        loanAccountService.saveLoanWithDataIntegrityViolationChecks(loan);
        businessEventNotifierService.notifyPostBusinessEvent(new LoanScheduleVariationsDeletedBusinessEvent(loan));
        return new CommandProcessingResultBuilder() //
                .withLoanId(loanId) //
                .with(changes) //
                .build();
    }

    @Override
    @Transactional
    public CommandProcessingResult createCustomSchedule(final Long loanId, final JsonCommand command) {
        return processCustomSchedule(loanId, command, false);
    }

    @Override
    @Transactional
    public CommandProcessingResult updateCustomSchedule(final Long loanId, final JsonCommand command) {
        return processCustomSchedule(loanId, command, true);
    }

    private CommandProcessingResult processCustomSchedule(final Long loanId, final JsonCommand command, final boolean isUpdate) {
        final Loan loan = this.loanAssembler.assembleFrom(loanId);

        // Validate loan state
        validateLoanStateForCustomSchedule(loan, isUpdate);

        // Validate and assemble installments from JSON
        final List<LoanRepaymentScheduleInstallment> installments = validateAndAssembleCustomScheduleFrom(command, loan);

        // Update loan with custom schedule
        updateWithCustomSchedule(loan, installments);

        return new CommandProcessingResultBuilder().withCommandId(command.commandId()).withLoanId(loanId).build();
    }

    private void validateLoanStateForCustomSchedule(final Loan loan, final boolean isUpdate) {
        // Can create/update custom schedule in submitted, approved, or active (disbursed) state
        if (!loan.isSubmittedAndPendingApproval() && !loan.isApproved() && !loan.getStatus().isActive()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.invalid.state", "Custom schedule can only be "
                    + (isUpdate ? "updated" : "created") + " for loans in submitted, approved, or active state");
        }

        // Cannot modify if loan is closed, overpaid, or written off
        if (loan.isClosed() || loan.isChargedOff()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.loan.closed",
                    "Custom schedule cannot be " + (isUpdate ? "updated" : "created") + " for closed or charged-off loans");
        }

        // Check for overdue installment charges that would block schedule modification
        // Note: We check this because updateLoanScheduleSummary() clears installments which
        // would violate FK constraint from m_loan_overdue_installment_charge table
        if (loan.getStatus().isActive()) {
            boolean hasOverdueCharges = loan.getCharges().stream().anyMatch(charge -> charge.getOverdueInstallmentCharge() != null);

            if (hasOverdueCharges) {
                throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.has.overdue.charges",
                        "Cannot modify custom schedule - loan has overdue installment charges. "
                                + "Please waive or pay these charges before modifying the schedule.");
            }
        }

        // For update, custom schedule must already exist
        if (isUpdate && !loan.isCustomScheduleDefined()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.not.defined",
                    "Cannot update custom schedule - custom schedule was not created for this loan");
        }

        // For create, custom schedule must not exist yet
        if (!isUpdate && loan.isCustomScheduleDefined()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.already.exists",
                    "Custom schedule already exists for this loan - use update instead");
        }
    }

    List<LoanRepaymentScheduleInstallment> validateAndAssembleCustomScheduleFrom(JsonCommand command, Loan loan) {
        final JsonArray installmentsArray = command.arrayOfParameterNamed("installments");
        final String dateFormat = command.dateFormat();
        final Locale locale = command.extractLocale();
        LoanRepaymentScheduleInstallment installment = null;
        List<LoanRepaymentScheduleInstallment> installments = new ArrayList<>();
        Integer periodNumber = 1;
        // validate and assemble installments
        for (JsonElement installmentJson : installmentsArray) {
            final LocalDate fromDate = this.fromApiJsonHelper.extractLocalDateNamed("fromDate", installmentJson, dateFormat, locale);
            final LocalDate dueDate = this.fromApiJsonHelper.extractLocalDateNamed("dueDate", installmentJson, dateFormat, locale);
            final BigDecimal principalDue = this.fromApiJsonHelper.extractBigDecimalNamed("principalDue", installmentJson, locale);
            final BigDecimal interestDue = this.fromApiJsonHelper.extractBigDecimalNamed("interestDue", installmentJson, locale);
            final BigDecimal feeChargesDue = this.fromApiJsonHelper.extractBigDecimalNamed("feeChargesDue", installmentJson, locale);
            final BigDecimal penaltyChargesDue = this.fromApiJsonHelper.extractBigDecimalNamed("penaltyChargesDue", installmentJson,
                    locale);

            // Validate no negative amounts
            validateNoNegativeAmounts(periodNumber, principalDue, interestDue, feeChargesDue, penaltyChargesDue);

            installment = LoanRepaymentScheduleInstallment.createInstallmentDetail(loan, fromDate, dueDate, periodNumber, principalDue,
                    interestDue, feeChargesDue, penaltyChargesDue);
            installments.add(installment);
            periodNumber++;
        }

        // Validate at least one installment has non-zero principal
        // (Last installment CAN have zero principal for interest-only or fee-only periods)
        boolean hasNonZeroPrincipal = installments.stream()
                .anyMatch(inst -> inst.getPrincipal(loan.getCurrency()).getAmount().compareTo(BigDecimal.ZERO) > 0);

        if (!hasNonZeroPrincipal) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.all.zero.principal",
                    "At least one installment must have non-zero principal amount");
        }

        validateCustomSchedule(loan, installments);
        return installments;
    }

    private void validateCustomSchedule(Loan loan, List<LoanRepaymentScheduleInstallment> installments) {
        if (installments == null || installments.isEmpty()) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.empty", "Custom schedule cannot be empty");
        }

        // Validate first installment dates
        if (!installments.isEmpty()) {
            LoanRepaymentScheduleInstallment firstInstallment = installments.get(0);

            // Check disbursement date
            LocalDate disbursementDate = loan.getDisbursementDate() != null ? loan.getDisbursementDate()
                    : loan.getExpectedDisbursementDate();

            if (disbursementDate != null) {
                // First installment fromDate must match disbursement date
                if (!firstInstallment.getFromDate().equals(disbursementDate)) {
                    throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.first.installment.fromdate.mismatch",
                            "First installment fromDate (" + firstInstallment.getFromDate() + ") must match disbursement date ("
                                    + disbursementDate + ")");
                }
            }

            // Check expected first repayment date
            LocalDate expectedFirstRepaymentDate = loan.getExpectedFirstRepaymentOnDate();
            if (expectedFirstRepaymentDate != null) {
                // First installment dueDate should match expected first repayment date
                if (!firstInstallment.getDueDate().equals(expectedFirstRepaymentDate)) {
                    throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.first.installment.duedate.mismatch",
                            "First installment dueDate (" + firstInstallment.getDueDate() + ") must match expected first repayment date ("
                                    + expectedFirstRepaymentDate + ")");
                }
            }
        }

        // Validate number of installments against loan product min/max constraints
        final Integer numberOfInstallments = installments.size();
        if (loan.getLoanProduct().getLoanProductMinMaxConstraints() != null) {
            final Integer minNumberOfRepayments = loan.getLoanProduct().getLoanProductMinMaxConstraints().getMinNumberOfRepayments();
            final Integer maxNumberOfRepayments = loan.getLoanProduct().getLoanProductMinMaxConstraints().getMaxNumberOfRepayments();

            if (minNumberOfRepayments != null && numberOfInstallments < minNumberOfRepayments) {
                throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.installments.below.minimum",
                        "Number of installments (" + numberOfInstallments + ") is less than the minimum required (" + minNumberOfRepayments
                                + ") for this loan product");
            }

            if (maxNumberOfRepayments != null && numberOfInstallments > maxNumberOfRepayments) {
                throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.installments.exceeds.maximum",
                        "Number of installments (" + numberOfInstallments + ") exceeds the maximum allowed (" + maxNumberOfRepayments
                                + ") for this loan product");
            }
        }

        // Validate total principal matches loan amount
        BigDecimal totalPrincipal = installments.stream().map(i -> i.getPrincipal(loan.getCurrency()).getAmount()).reduce(BigDecimal.ZERO,
                BigDecimal::add);

        if (totalPrincipal.compareTo(loan.getPrincipal().getAmount()) != 0) {
            throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.principal.mismatch",
                    "Total principal in custom schedule (" + totalPrincipal + ") must equal loan principal ("
                            + loan.getPrincipal().getAmount() + ")");
        }

        // Validate chronological order and no gaps
        LocalDate previousDueDate = null;
        for (int i = 0; i < installments.size(); i++) {
            LoanRepaymentScheduleInstallment inst = installments.get(i);

            // fromDate must be before dueDate
            if (!inst.getFromDate().isBefore(inst.getDueDate())) {
                throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.invalid.dates",
                        "Installment " + (i + 1) + ": fromDate must be before dueDate");
            }

            // Check chronological order
            if (previousDueDate != null && !inst.getDueDate().isAfter(previousDueDate)) {
                throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.not.chronological",
                        "Installment " + (i + 1) + " must have due date after previous installment");
            }

            // Check for gaps (fromDate should equal previous dueDate)
            if (i > 0 && !inst.getFromDate().equals(previousDueDate)) {
                throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.date.gap",
                        "Installment " + (i + 1) + " has a gap: fromDate should be " + previousDueDate);
            }

            previousDueDate = inst.getDueDate();
        }
    }

    private void validateNoNegativeAmounts(Integer periodNumber, BigDecimal principalDue, BigDecimal interestDue, BigDecimal feeChargesDue,
            BigDecimal penaltyChargesDue) {
        List<String> negativeFields = new ArrayList<>();

        if (principalDue.compareTo(BigDecimal.ZERO) < 0) {
            negativeFields.add("principal");
        }
        if (interestDue.compareTo(BigDecimal.ZERO) < 0) {
            negativeFields.add("interest");
        }
        if (feeChargesDue.compareTo(BigDecimal.ZERO) < 0) {
            negativeFields.add("fee charges");
        }
        if (penaltyChargesDue.compareTo(BigDecimal.ZERO) < 0) {
            negativeFields.add("penalty charges");
        }

        if (!negativeFields.isEmpty()) {
            String fields = String.join(", ", negativeFields);
            throw new GeneralPlatformDomainRuleException("error.msg.loan.custom.schedule.negative.amounts",
                    "Installment " + periodNumber + " has negative amounts for: " + fields);
        }
    }

    void updateWithCustomSchedule(Loan loan, List<LoanRepaymentScheduleInstallment> installments) {
        // 1. Update schedule - clears existing and adds new installments
        loan.updateLoanScheduleSummary(installments);

        // 2. Update repayment period derived fields (if disbursed)
        final LocalDate disbursementDate = loan.getDisbursementDate() != null ? loan.getDisbursementDate()
                : loan.getExpectedDisbursementDate();
        if (disbursementDate != null) {
            loan.updateLoanRepaymentPeriodsDerivedFields(disbursementDate);
        }

        // 3. Update fee charges due at disbursement
        loan.updateSummaryWithTotalFeeChargesDueAtDisbursement(loan.deriveSumTotalOfChargesDueAtDisbursement());

        // 4. Reprocess existing transactions with new schedule (if any exist)
        if (loan.getLoanTransactions() != null && !loan.getLoanTransactions().isEmpty()) {
            this.reprocessLoanTransactionsService.reprocessTransactions(loan);
        }

        // 5. Update loan summary derived fields (outstanding balances, etc.)
        this.loanBalanceService.updateLoanSummaryDerivedFields(loan);

        // 6. Recalculate accruals if periodic accrual accounting is enabled
        if (loan.isPeriodicAccrualAccountingEnabledOnLoanProduct()) {
            this.loanAccrualsProcessingService.reprocessExistingAccruals(loan, false);
        }

        // 7. Mark as custom schedule defined
        loan.setCustomScheduleDefined(true);

        // 8. Create schedule archive for audit trail
        this.loanAccountDomainService.createAndSaveLoanScheduleArchive(loan);

        // 9. Save loan with all changes
        this.loanRepository.saveAndFlush(loan);
    }

}
