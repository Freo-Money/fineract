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
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanScheduleVariationsAddedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanScheduleVariationsDeletedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.portfolio.loanaccount.data.LoanInstallmentDTO;
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
import org.apache.fineract.portfolio.loanaccount.service.LoanScheduleService;
import org.apache.fineract.portfolio.loanaccount.service.LoanUtilService;
import org.apache.fineract.portfolio.loanaccount.service.LoanWritePlatformServiceJpaRepositoryImpl;
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
    private final LoanWritePlatformServiceJpaRepositoryImpl loanWritePlatformServiceJpaRepositoryImpl;

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
        loanAccrualsProcessingService.reprocessExistingAccruals(loan);
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
        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        final List<LoanRepaymentScheduleInstallment> installments = validateAndAssembleCustomScheduleFrom(command, loan);
        updateWithCustomSchedule(loan, installments);
        return new CommandProcessingResultBuilder().withCommandId(command.commandId()).withLoanId(loanId).build();
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

            installment = LoanRepaymentScheduleInstallment.createInstallmentDetail(loan, fromDate, dueDate, periodNumber, principalDue,
                    interestDue, feeChargesDue, penaltyChargesDue);
            installments.add(installment);
            periodNumber++;
        }

        return installments;
    }

    void updateWithCustomSchedule(Loan loan, List<LoanRepaymentScheduleInstallment> installments) {
        // update schedule and summary
        loan.updateLoanScheduleSummary(installments);
        setDueDateAsEndDate(loan);
        this.loanWritePlatformServiceJpaRepositoryImpl.syncTransactionsWithSchedule(loan);
        updateBackDueDate(loan);
        loan.setCustomScheduleDefined(true);
        this.loanAccountDomainService.createAndSaveLoanScheduleArchive(loan);
        this.loanRepository.save(loan);

    }

    private void setDueDateAsEndDate(Loan loan) {
        /*
         * loan.getRepaymentScheduleInstallments().forEach(installment -> {
         * scheduleDueDateMap.put(installment.getInstallmentNumber(), installment.getDueDate()); LocalDate endDate =
         * installment.getEndDate(); installment.updateDueDate(endDate); });
         */}

    private void updateBackDueDate(Loan loan) {
        /*
         * loan.getRepaymentScheduleInstallments().forEach(installment -> { LocalDate dueDate =
         * scheduleDueDateMap.get(installment.getInstallmentNumber()); installment.updateDueDate(dueDate); });
         */}

    @Override
    @Transactional
    public CommandProcessingResult updateCustomSchedule(Long loanId, List<LoanInstallmentDTO> loanInstallments) {
        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        final List<LoanRepaymentScheduleInstallment> installments = new ArrayList<>();
        for (LoanInstallmentDTO installmentDTO : loanInstallments) {
            LoanRepaymentScheduleInstallment installment = LoanRepaymentScheduleInstallment.createInstallmentDetail(loan,
                    installmentDTO.getFromDate(), installmentDTO.getDueDate(), installmentDTO.getPeriodNumber(),
                    installmentDTO.getPrincipalDue(), installmentDTO.getInterestDue(), installmentDTO.getFeeChargesDue(),
                    installmentDTO.getPenaltyChargesDue());
            installments.add(installment);
        }
        updateWithCustomSchedule(loan, installments);
        return new CommandProcessingResultBuilder().withLoanId(loanId).build();
    }
}
