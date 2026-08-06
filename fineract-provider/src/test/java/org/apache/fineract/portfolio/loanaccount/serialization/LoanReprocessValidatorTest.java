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
package org.apache.fineract.portfolio.loanaccount.serialization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.accounting.closure.domain.GLClosure;
import org.apache.fineract.accounting.closure.domain.GLClosureRepository;
import org.apache.fineract.cob.service.LoanAccountLockService;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanDisbursementDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.reprocess.LoanReprocessApiConstants;
import org.apache.fineract.portfolio.loanaccount.reprocess.LoanReprocessRequest;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the pre-flight gate for {@code reprocessLoan}.
 * <p>
 * Three rules are worth breaking the build over: the not-yet-implemented gate, which is the only thing stopping an
 * unwired parameter from being silently ignored; the accounting-closure gate, which stops the operation discovering
 * mid-flight that it cannot post into a closed period; and the chronology rule, whose exclusion list decides whether
 * loans carrying disbursement charges can move their date forward at all.
 */
class LoanReprocessValidatorTest {

    private static final LocalDate DISBURSED_ON = LocalDate.of(2026, 6, 5);
    private static final Long OFFICE_ID = 1L;

    private final GLClosureRepository glClosureRepository = mock(GLClosureRepository.class);
    private final AccountTransfersReadPlatformService accountTransfersReadPlatformService = mock(AccountTransfersReadPlatformService.class);
    private final ConfigurationDomainService configurationDomainService = mock(ConfigurationDomainService.class);
    private final LoanAccountLockService loanAccountLockService = mock(LoanAccountLockService.class);
    private final LoanReprocessValidator validator = new LoanReprocessValidator(glClosureRepository, accountTransfersReadPlatformService,
            configurationDomainService, loanAccountLockService);

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil
                .setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.now(ZoneId.systemDefault()))));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    // ----- request shape -----

    @Test
    void rejectsWhenNothingWasRequested() {
        assertParameterError(() -> validator.validate(activeLoan(), LoanReprocessRequest.builder().build()),
                LoanReprocessApiConstants.ERROR_NO_PARAMETER_SUPPLIED, null);
    }

    @Test
    void reportsWhichParametersWereRequested() {
        final LoanReprocessRequest request = LoanReprocessRequest.builder().actualDisbursementDate(DISBURSED_ON).numberOfRepayments(6)
                .build();

        assertThat(request.requestedParameters()).containsExactly(LoanReprocessApiConstants.actualDisbursementDateParamName,
                LoanReprocessApiConstants.numberOfRepaymentsParamName);
        assertThat(request.hasAnyChange()).isTrue();
    }

    // ----- the placeholder gate -----

    @Test
    void rejectsParametersThatAreDeclaredButNotYetWiredUp() {
        assertParameterError(
                () -> validator.validate(activeLoan(), LoanReprocessRequest.builder().principal(new BigDecimal("12000")).build()),
                LoanReprocessApiConstants.ERROR_PARAMETER_NOT_IMPLEMENTED, LoanReprocessApiConstants.principalParamName);
    }

    @Test
    void rejectsTheWholeRequestWhenOnlyOneParameterIsUnimplemented() {
        // Partially applying would be worse than refusing: the caller would see success without their change.
        assertParameterError(
                () -> validator.validate(activeLoan(),
                        LoanReprocessRequest.builder().actualDisbursementDate(DISBURSED_ON.minusDays(2)).numberOfRepayments(6).build()),
                LoanReprocessApiConstants.ERROR_PARAMETER_NOT_IMPLEMENTED, LoanReprocessApiConstants.numberOfRepaymentsParamName);
    }

    @Test
    void unimplementedParametersExcludeTheSupportedOne() {
        final LoanReprocessRequest request = LoanReprocessRequest.builder().actualDisbursementDate(DISBURSED_ON)
                .interestRatePerPeriod(new BigDecimal("10")).build();

        assertThat(request.unimplementedParameters()).containsExactly(LoanReprocessApiConstants.interestRatePerPeriodParamName);
    }

    // ----- eligibility -----

    @Test
    void rejectsLoanThatIsNotActive() {
        final Loan loan = activeLoan();
        when(loan.getStatus()).thenReturn(LoanStatus.CLOSED_OBLIGATIONS_MET);

        assertThatThrownBy(() -> validator.validate(loan, dateChange())).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("only allowed on an active loan");
    }

    @Test
    void rejectsChargedOffLoanEvenThoughItReportsAsActive() {
        final Loan loan = activeLoan();
        when(loan.isChargedOff()).thenReturn(true);

        assertThatThrownBy(() -> validator.validate(loan, dateChange())).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("only allowed on an active loan");
    }

    @Test
    void rejectsTopupLoan() {
        final Loan loan = activeLoan();
        when(loan.isTopup()).thenReturn(true);

        assertThatThrownBy(() -> validator.validate(loan, dateChange())).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("topup");
    }

    @Test
    void rejectsLoanWhoseDisbursementIsLinkedToAnAccountTransfer() {
        final Loan loan = activeLoan();
        final LoanTransaction disbursement = transaction(LoanTransactionType.DISBURSEMENT, DISBURSED_ON, 1L);
        when(loan.getLoanTransactions()).thenReturn(List.of(disbursement));
        when(accountTransfersReadPlatformService.isAccountTransfer(anyLong(), org.mockito.ArgumentMatchers.any())).thenReturn(true);

        assertThatThrownBy(() -> validator.validate(loan, dateChange())).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("account transfer");
    }

    @Test
    void rejectsLoanCarryingADownPayment() {
        final Loan loan = activeLoan();
        final LoanTransaction downPayment = transaction(LoanTransactionType.DOWN_PAYMENT, DISBURSED_ON, 2L);
        when(loan.getLoanTransactions()).thenReturn(List.of(downPayment));

        assertThatThrownBy(() -> validator.validate(loan, dateChange())).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("down payment");
    }

    /**
     * Active status is a column; the disbursement transaction is a row - nothing ties them together. Without this rule
     * a loan whose disbursement sits reversed would "succeed": dates and schedule move, journal entries stay, and the
     * ledger permanently disagrees with the loan.
     */
    @Test
    void rejectsLoanWithNoLiveDisbursementTransaction() {
        final Loan loan = activeLoan();
        final LoanTransaction reversedDisbursement = transaction(LoanTransactionType.DISBURSEMENT, DISBURSED_ON, 1L);
        when(reversedDisbursement.isNotReversed()).thenReturn(false);
        when(loan.getLoanTransactions()).thenReturn(List.of(reversedDisbursement));

        assertThatThrownBy(() -> validator.validate(loan, dateChange())).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("live disbursement");
    }

    /**
     * The maker-checker approval path executes through {@code /makercheckers/{id}}, which the COB API filter does not
     * match - this rule is what stops a checker running the reprocess concurrently with the COB chunk holding the
     * loan's hard lock.
     */
    @Test
    void rejectsLoanCurrentlyHardLockedByCob() {
        final Loan loan = activeLoan();
        when(loanAccountLockService.isLoanHardLocked(1L)).thenReturn(true);

        assertThatThrownBy(() -> validator.validate(loan, dateChange())).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("close-of-business");
    }

    // ----- migrated loans -----

    /**
     * Migrated loans carry charge tax splits backfilled from the source system. Re-creating the disbursement-charge
     * payment would have {@code LoanChargePaidBy} derive a fresh split, overwriting reconciled figures - so they are
     * refused outright rather than silently rewritten.
     */
    @Test
    void rejectsLoanMigratedFromTheSourceSystem() {
        final Loan loan = activeLoan();
        when(loan.getId()).thenReturn(500L);
        stubMigration(DISBURSED_ON.minusYears(1), 900L);

        assertThatThrownBy(() -> validator.validate(loan, dateChange())).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("migrated");
    }

    @Test
    void allowsLoanOpenedAfterTheMigration() {
        final Loan loan = activeLoan();
        when(loan.getId()).thenReturn(1200L);
        stubMigration(DISBURSED_ON.minusYears(1), 900L);

        assertThatCode(() -> validator.validate(loan, dateChange())).doesNotThrowAnyException();
    }

    /**
     * Fails open by design: with no migration configured there is no migration to protect against, and refusing every
     * low-id loan would block the feature outright on a tenant that never migrated.
     */
    @Test
    void allowsAnyLoanWhenTheMigrationConfigurationIsNotPopulated() {
        final Loan loan = activeLoan();
        when(loan.getId()).thenReturn(1L);
        stubMigration(null, null);

        assertThatCode(() -> validator.validate(loan, dateChange())).doesNotThrowAnyException();
    }

    @Test
    void allowsWhenOnlyOneHalfOfTheMigrationConfigurationIsSet() {
        final Loan loan = activeLoan();
        when(loan.getId()).thenReturn(1L);
        stubMigration(DISBURSED_ON.minusYears(1), null);

        assertThatCode(() -> validator.validate(loan, dateChange())).doesNotThrowAnyException();
    }

    // ----- scope exclusions -----

    @Test
    void rejectsLoanCarryingMoreThanOneDisbursementDetail() {
        final Loan loan = activeLoan();
        when(loan.getDisbursementDetails())
                .thenReturn(new ArrayList<>(List.of(mock(LoanDisbursementDetails.class), mock(LoanDisbursementDetails.class))));

        assertThatThrownBy(() -> validator.validate(loan, dateChange())).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("single disbursement");
    }

    @Test
    void toleratesALoanWithNoDisbursementDetailRow() {
        // The schedule is driven by the loan's own disbursement date, so the correction is still right - there is
        // simply nothing on the detail row to update.
        final Loan loan = activeLoan();
        when(loan.getDisbursementDetails()).thenReturn(new ArrayList<>());

        assertThatCode(() -> validator.validate(loan, dateChange())).doesNotThrowAnyException();
    }

    @Test
    void rejectsMultiDisbursementLoan() {
        final Loan loan = activeLoan();
        when(loan.getLoanProduct().isMultiDisburseLoan()).thenReturn(true);

        assertThatThrownBy(() -> validator.validate(loan, dateChange())).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("multi-disbursement");
    }

    @Test
    void rejectsProgressiveScheduleLoan() {
        final Loan loan = activeLoan();
        when(loan.isProgressiveSchedule()).thenReturn(true);

        assertThatThrownBy(() -> validator.validate(loan, dateChange())).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("progressive");
    }

    @Test
    void rejectsInterestRecalculationLoan() {
        final Loan loan = activeLoan();
        when(loan.isInterestBearingAndInterestRecalculationEnabled()).thenReturn(true);

        assertThatThrownBy(() -> validator.validate(loan, dateChange())).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("interest recalculation");
    }

    // ----- accounting closure gate -----

    @Test
    void allowsWhenNoClosureExistsForTheOffice() {
        when(glClosureRepository.getLatestGLClosureByBranch(anyLong())).thenReturn(null);

        assertThatCode(() -> validator.validate(activeLoan(), dateChange())).doesNotThrowAnyException();
    }

    @Test
    void allowsWhenClosureIsStrictlyBeforeBothDates() {
        stubClosure(DISBURSED_ON.minusDays(10));

        assertThatCode(() -> validator.validate(activeLoan(), dateChange())).doesNotThrowAnyException();
    }

    @Test
    void skipsTheClosureGateWhenTheProductKeepsNoAccounting() {
        // No journal entries are posted for such a product, so a closure cannot be in the way of any.
        final Loan loan = activeLoan();
        when(loan.isAccountingDisabledOnLoanProduct()).thenReturn(true);
        stubClosure(DISBURSED_ON);

        assertThatCode(() -> validator.validate(loan, dateChange())).doesNotThrowAnyException();
    }

    @Test
    void rejectsWhenClosureFallsOnTheDisbursementDate() {
        // checkForBranchClosures throws when closingDate >= transactionDate, so a closure ON the date still blocks it.
        stubClosure(DISBURSED_ON);

        assertThatThrownBy(() -> validator.validate(activeLoan(), dateChange())).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("Accounting for this office is closed");
    }

    @Test
    void gateUsesTheEarlierOfTheOldAndNewDates() {
        // The new date is earlier, so it is the one that must clear the closure - the contra lands on the old date but
        // the replacement lands on the new one.
        final LocalDate newDate = DISBURSED_ON.minusDays(5);
        stubClosure(newDate);

        assertThatThrownBy(() -> validator.validate(activeLoan(), LoanReprocessRequest.builder().actualDisbursementDate(newDate).build()))
                .isInstanceOf(GeneralPlatformDomainRuleException.class).hasMessageContaining("Accounting for this office is closed");
    }

    // ----- disbursement date rules -----

    @Test
    void rejectsWhenTheDateIsUnchanged() {
        assertParameterError(
                () -> validator.validate(activeLoan(), LoanReprocessRequest.builder().actualDisbursementDate(DISBURSED_ON).build()),
                LoanReprocessApiConstants.ERROR_DISBURSEMENT_DATE_UNCHANGED, LoanReprocessApiConstants.actualDisbursementDateParamName);
    }

    @Test
    void rejectsFutureDate() {
        assertParameterError(
                () -> validator.validate(activeLoan(),
                        LoanReprocessRequest.builder().actualDisbursementDate(LocalDate.now(ZoneId.systemDefault()).plusDays(1)).build()),
                LoanReprocessApiConstants.ERROR_DISBURSEMENT_DATE_IN_FUTURE, LoanReprocessApiConstants.actualDisbursementDateParamName);
    }

    @Test
    void rejectsDateBeforeApproval() {
        final Loan loan = activeLoan();
        when(loan.getApprovedOnDate()).thenReturn(DISBURSED_ON.minusDays(2));

        assertParameterError(
                () -> validator.validate(loan, LoanReprocessRequest.builder().actualDisbursementDate(DISBURSED_ON.minusDays(5)).build()),
                LoanReprocessApiConstants.ERROR_DISBURSEMENT_BEFORE_APPROVAL, LoanReprocessApiConstants.actualDisbursementDateParamName);
    }

    // ----- chronology, and the disbursement-event exclusion -----

    @Test
    void rejectsDateMovedPastAnExistingRepayment() {
        final Loan loan = activeLoan();
        final LoanTransaction disbursement = transaction(LoanTransactionType.DISBURSEMENT, DISBURSED_ON, 1L);
        final LoanTransaction repayment = transaction(LoanTransactionType.REPAYMENT, DISBURSED_ON.plusMonths(1), 3L);
        when(loan.getLoanTransactions()).thenReturn(List.of(disbursement, repayment));

        assertParameterError(
                () -> validator.validate(loan, LoanReprocessRequest.builder().actualDisbursementDate(DISBURSED_ON.plusMonths(2)).build()),
                LoanReprocessApiConstants.ERROR_DISBURSEMENT_AFTER_TRANSACTION, LoanReprocessApiConstants.actualDisbursementDateParamName);
    }

    @Test
    void allowsDateMovedEarlierThanExistingRepayments() {
        final Loan loan = activeLoan();
        final LoanTransaction disbursement = transaction(LoanTransactionType.DISBURSEMENT, DISBURSED_ON, 1L);
        final LoanTransaction repayment = transaction(LoanTransactionType.REPAYMENT, DISBURSED_ON.plusMonths(1), 3L);
        when(loan.getLoanTransactions()).thenReturn(List.of(disbursement, repayment));

        assertThatCode(() -> validator.validate(loan, dateChange())).doesNotThrowAnyException();
    }

    /**
     * Landing ON an existing repayment's date would manufacture the exact same-day-repayment shape the validator itself
     * refuses, locking the loan out of every further correction - including undoing this one.
     */
    @Test
    void rejectsDateLandingExactlyOnTheEarliestTransaction() {
        final Loan loan = activeLoan();
        final LocalDate repaymentDate = DISBURSED_ON.plusDays(10);
        final LoanTransaction disbursement = transaction(LoanTransactionType.DISBURSEMENT, DISBURSED_ON, 1L);
        final LoanTransaction repayment = transaction(LoanTransactionType.REPAYMENT, repaymentDate, 3L);
        when(loan.getLoanTransactions()).thenReturn(List.of(disbursement, repayment));

        assertParameterError(() -> validator.validate(loan, LoanReprocessRequest.builder().actualDisbursementDate(repaymentDate).build()),
                LoanReprocessApiConstants.ERROR_DISBURSEMENT_AFTER_TRANSACTION, LoanReprocessApiConstants.actualDisbursementDateParamName);
    }

    @Test
    void disbursementChargeTransactionDoesNotBlockAForwardMove() {
        // REPAYMENT_AT_DISBURSEMENT is part of the disbursement event and moves with it. Counting it here would stop
        // every loan carrying disbursement charges from ever moving its date forward.
        final Loan loan = activeLoan();
        final LoanTransaction disbursement = transaction(LoanTransactionType.DISBURSEMENT, DISBURSED_ON, 1L);
        final LoanTransaction chargePayment = transaction(LoanTransactionType.REPAYMENT_AT_DISBURSEMENT, DISBURSED_ON, 4L);
        when(loan.getLoanTransactions()).thenReturn(List.of(disbursement, chargePayment));

        assertThatCode(
                () -> validator.validate(loan, LoanReprocessRequest.builder().actualDisbursementDate(DISBURSED_ON.plusDays(3)).build()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWhenARepaymentSitsOnTheDisbursementDate() {
        // Cannot be distinguished from broken-period interest, whose amount depends on the disbursement date.
        final Loan loan = activeLoan();
        final LoanTransaction disbursement = transaction(LoanTransactionType.DISBURSEMENT, DISBURSED_ON, 1L);
        final LoanTransaction sameDay = transaction(LoanTransactionType.REPAYMENT, DISBURSED_ON, 5L);
        when(loan.getLoanTransactions()).thenReturn(List.of(disbursement, sameDay));

        assertThatThrownBy(() -> validator.validate(loan, dateChange())).isInstanceOf(GeneralPlatformDomainRuleException.class)
                .hasMessageContaining("broken-period interest");
    }

    /**
     * The same-day rule exists for broken-period interest, which is posted as a {@code REPAYMENT}. Upfront-accrual
     * accounting also posts accruals at disbursement, and those are re-derived by the regeneration - matching every
     * transaction dated that day would block such loans while blaming broken-period interest for it.
     */
    @Test
    void anAccrualOnTheDisbursementDateDoesNotBlockTheCorrection() {
        final Loan loan = activeLoan();
        final LoanTransaction disbursement = transaction(LoanTransactionType.DISBURSEMENT, DISBURSED_ON, 1L);
        final LoanTransaction accrual = transaction(LoanTransactionType.ACCRUAL, DISBURSED_ON, 6L);
        when(loan.getLoanTransactions()).thenReturn(List.of(disbursement, accrual));

        assertThatCode(() -> validator.validate(loan, dateChange())).doesNotThrowAnyException();
    }

    // ----- the first instalment bound -----

    /**
     * Instalment due dates are held still across a correction, so the first period runs from the new disbursement date
     * to a due date that does not move. A disbursement on or after that due date would ask for a period of zero or
     * negative length.
     */
    @Test
    void rejectsDateThatWouldLandOnOrAfterTheFirstInstallmentDueDate() {
        final Loan loan = activeLoan();
        final List<LoanRepaymentScheduleInstallment> installments = List.of(installment(DISBURSED_ON.minusDays(5)));
        when(loan.getRepaymentScheduleInstallments()).thenReturn(installments);

        assertParameterError(() -> validator.validate(loan, dateChange()),
                LoanReprocessApiConstants.ERROR_DISBURSEMENT_NOT_BEFORE_FIRST_INSTALLMENT,
                LoanReprocessApiConstants.actualDisbursementDateParamName);
    }

    @Test
    void allowsDateThatStaysBeforeTheFirstInstallmentDueDate() {
        final Loan loan = activeLoan();
        final List<LoanRepaymentScheduleInstallment> installments = List.of(installment(DISBURSED_ON.plusMonths(1)));
        when(loan.getRepaymentScheduleInstallments()).thenReturn(installments);

        assertThatCode(() -> validator.validate(loan, dateChange())).doesNotThrowAnyException();
    }

    /**
     * The generator starts repayments from {@code expectedFirstRepaymentOnDate} when one is stored, not from the
     * schedule - so when the two diverge, the bound must follow the stored date. Bounding against the installments
     * alone would let through a correction landing after the stored date: a negative first period.
     */
    @Test
    void theFirstInstallmentBoundFollowsTheStoredFirstRepaymentDateWhenOneIsSet() {
        final Loan loan = activeLoan();
        when(loan.getExpectedFirstRepaymentOnDate()).thenReturn(DISBURSED_ON.minusDays(4));
        final List<LoanRepaymentScheduleInstallment> installments = List.of(installment(DISBURSED_ON.plusMonths(1)));
        when(loan.getRepaymentScheduleInstallments()).thenReturn(installments);

        // dateChange() corrects to DISBURSED_ON - 2: before the installment due date, but after the stored
        // first-repayment date the generator will actually use.
        assertParameterError(() -> validator.validate(loan, dateChange()),
                LoanReprocessApiConstants.ERROR_DISBURSEMENT_NOT_BEFORE_FIRST_INSTALLMENT,
                LoanReprocessApiConstants.actualDisbursementDateParamName);
    }

    @Test
    void theFirstInstallmentBoundIgnoresDownPayments() {
        // A down payment is dated at disbursement; treating it as the first instalment would reject every correction.
        final Loan loan = activeLoan();
        final LoanRepaymentScheduleInstallment downPayment = mock(LoanRepaymentScheduleInstallment.class);
        lenient().when(downPayment.isDownPayment()).thenReturn(true);
        lenient().when(downPayment.getDueDate()).thenReturn(DISBURSED_ON);
        final List<LoanRepaymentScheduleInstallment> installments = List.of(downPayment, installment(DISBURSED_ON.plusMonths(1)));
        when(loan.getRepaymentScheduleInstallments()).thenReturn(installments);

        assertThatCode(() -> validator.validate(loan, dateChange())).doesNotThrowAnyException();
    }

    /**
     * Parameter-level failures must carry a field-attributed {@link ApiParameterError} so a client can highlight the
     * offending input, rather than a flat domain-rule message.
     */
    private void assertParameterError(final org.assertj.core.api.ThrowableAssert.ThrowingCallable call, final String expectedCode,
            final String expectedParameter) {
        assertThatThrownBy(call).isInstanceOf(PlatformApiDataValidationException.class).satisfies(thrown -> {
            final List<ApiParameterError> errors = ((PlatformApiDataValidationException) thrown).getErrors();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getUserMessageGlobalisationCode()).isEqualTo(expectedCode);
            assertThat(errors.get(0).getParameterName()).isEqualTo(expectedParameter);
        });
    }

    // ----- fixtures -----

    private LoanReprocessRequest dateChange() {
        return LoanReprocessRequest.builder().actualDisbursementDate(DISBURSED_ON.minusDays(2)).build();
    }

    private Loan activeLoan() {
        final LoanProduct product = mock(LoanProduct.class);
        lenient().when(product.isMultiDisburseLoan()).thenReturn(false);

        final Loan loan = mock(Loan.class);
        lenient().when(loan.getId()).thenReturn(1L);
        lenient().when(loan.getOfficeId()).thenReturn(OFFICE_ID);
        lenient().when(loan.getStatus()).thenReturn(LoanStatus.ACTIVE);
        lenient().when(loan.isChargedOff()).thenReturn(false);
        lenient().when(loan.isForeclosure()).thenReturn(false);
        lenient().when(loan.isContractTermination()).thenReturn(false);
        lenient().when(loan.isTopup()).thenReturn(false);
        lenient().when(loan.isProgressiveSchedule()).thenReturn(false);
        lenient().when(loan.isInterestBearingAndInterestRecalculationEnabled()).thenReturn(false);
        lenient().when(loan.getLoanProduct()).thenReturn(product);
        lenient().when(loan.getDisbursementDate()).thenReturn(DISBURSED_ON);
        lenient().when(loan.getApprovedOnDate()).thenReturn(null);
        // Every eligible loan carries its live disbursement transaction; tests that stub getLoanTransactions
        // themselves must include one too, or they trip the no-live-disbursement rule instead of their target.
        // Built before the when(...): creating a mock inside an unfinished stubbing trips Mockito.
        final List<LoanTransaction> defaultTransactions = List.of(transaction(LoanTransactionType.DISBURSEMENT, DISBURSED_ON, 99L));
        lenient().when(loan.getLoanTransactions()).thenReturn(defaultTransactions);
        lenient().when(loan.isAccountingDisabledOnLoanProduct()).thenReturn(false);
        lenient().when(loan.getDisbursementDetails()).thenReturn(new ArrayList<>(List.of(mock(LoanDisbursementDetails.class))));
        lenient().when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of());
        return loan;
    }

    private LoanRepaymentScheduleInstallment installment(final LocalDate dueDate) {
        final LoanRepaymentScheduleInstallment installment = mock(LoanRepaymentScheduleInstallment.class);
        lenient().when(installment.getDueDate()).thenReturn(dueDate);
        lenient().when(installment.isDownPayment()).thenReturn(false);
        return installment;
    }

    private LoanTransaction transaction(final LoanTransactionType type, final LocalDate date, final Long id) {
        final LoanTransaction transaction = mock(LoanTransaction.class);
        lenient().when(transaction.getId()).thenReturn(id);
        lenient().when(transaction.isNotReversed()).thenReturn(true);
        lenient().when(transaction.getTypeOf()).thenReturn(type);
        lenient().when(transaction.isDisbursement()).thenReturn(LoanTransactionType.DISBURSEMENT.equals(type));
        lenient().when(transaction.hasChargebackLoanTransactionRelations()).thenReturn(false);
        lenient().when(transaction.getTransactionDate()).thenReturn(date);
        return transaction;
    }

    private void stubMigration(final LocalDate cutoffDate, final Long lastImportedLoanId) {
        lenient().when(configurationDomainService.retrieveMigrationCutoffDate()).thenReturn(cutoffDate);
        lenient().when(configurationDomainService.retrieveMigrationLastImportedLoanId()).thenReturn(lastImportedLoanId);
    }

    private void stubClosure(final LocalDate closingDate) {
        final GLClosure closure = mock(GLClosure.class);
        lenient().when(closure.getClosingDate()).thenReturn(closingDate);
        when(glClosureRepository.getLatestGLClosureByBranch(anyLong())).thenReturn(closure);
    }
}
