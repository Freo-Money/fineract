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

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.closure.domain.GLClosure;
import org.apache.fineract.accounting.closure.domain.GLClosureRepository;
import org.apache.fineract.cob.service.LoanAccountLockService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.service.AccountTransfersReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.reprocess.LoanReprocessApiConstants;
import org.apache.fineract.portfolio.loanaccount.reprocess.LoanReprocessRequest;
import org.apache.fineract.portfolio.loanaccount.service.LoanScheduleRegenerationService;
import org.springframework.stereotype.Component;

/**
 * Pre-flight gate for {@code reprocessLoan}. Every rule runs before any mutation, so a rejection leaves the loan
 * untouched rather than relying on transaction rollback.
 * <p>
 * Two exception families, deliberately. Problems with a <i>supplied value</i> raise
 * {@link PlatformApiDataValidationException} carrying an {@link ApiParameterError}, so the client learns which field is
 * at fault and can attribute it in a form. Problems with the <i>state of the loan</i> - not active, topup, an
 * unsupported product shape - raise {@link GeneralPlatformDomainRuleException}, because no parameter would fix them.
 */
@Component
@RequiredArgsConstructor
public class LoanReprocessValidator {

    private final GLClosureRepository glClosureRepository;
    private final AccountTransfersReadPlatformService accountTransfersReadPlatformService;
    private final ConfigurationDomainService configurationDomainService;
    private final LoanAccountLockService loanAccountLockService;

    public void validate(final Loan loan, final LoanReprocessRequest request) {
        validateSomethingWasRequested(request);
        validateAllRequestedParametersAreImplemented(request);
        validateLoanIsNotLockedByCob(loan);
        validateLoanIsEligible(loan);
        validateLoanWasNotMigrated(loan);
        validateProductIsInScope(loan);
        validateAccountingPeriodIsOpen(loan, request);

        // Per-parameter rules. Only the implemented parameters have a body; the rest are declared so the shape of the
        // eventual implementation is visible and the not-yet-supported gate above is the single place to relax.
        validateActualDisbursementDate(loan, request);
        validatePrincipal(loan, request);
        validateExpectedFirstRepaymentOnDate(loan, request);
        validateInterestRatePerPeriod(loan, request);
        validateNumberOfRepayments(loan, request);
    }

    // ----- request shape -----

    private void validateSomethingWasRequested(final LoanReprocessRequest request) {
        if (!request.hasAnyChange()) {
            throw parameterError(LoanReprocessApiConstants.ERROR_NO_PARAMETER_SUPPLIED,
                    "At least one of " + LoanReprocessApiConstants.CORRECTABLE_PARAMETERS + " must be supplied.", null);
        }
    }

    /**
     * Rejects declared-but-unwired parameters up front. Silently ignoring one would be far worse than refusing it: the
     * caller would see a successful reprocess that did not apply their change.
     */
    private void validateAllRequestedParametersAreImplemented(final LoanReprocessRequest request) {
        final Set<String> unimplemented = request.unimplementedParameters();
        if (!unimplemented.isEmpty()) {
            throw parameterError(
                    LoanReprocessApiConstants.ERROR_PARAMETER_NOT_IMPLEMENTED, "Reprocessing is not yet supported for " + unimplemented
                            + ". Currently supported: " + LoanReprocessApiConstants.IMPLEMENTED_PARAMETERS + ".",
                    unimplemented.iterator().next());
        }
    }

    // ----- loan state -----

    /**
     * Refuses a loan the COB is currently processing.
     * <p>
     * A direct API call is caught by {@code LoanCOBApiFilter}, but the maker-checker approval path executes through
     * {@code POST /makercheckers/{id}}, which that filter does not match - so without this check a checker could run
     * the reprocess concurrently with the COB chunk that holds the loan's hard lock, each rewriting state the other is
     * reading. Checked here as well as at the filter so every execution path is covered.
     */
    private void validateLoanIsNotLockedByCob(final Loan loan) {
        if (loan.getId() != null && this.loanAccountLockService.isLoanHardLocked(loan.getId())) {
            throw new GeneralPlatformDomainRuleException(LoanReprocessApiConstants.ERROR_COB_LOCKED,
                    "Reprocessing is not allowed while the loan is being processed by close-of-business.", loan.getId());
        }
    }

    private void validateLoanIsEligible(final Loan loan) {
        // Delegated rather than restated: bulk repair applies the same rule, and two copies would drift.
        if (!LoanScheduleRegenerationService.isRegenerable(loan)) {
            throw new GeneralPlatformDomainRuleException(LoanReprocessApiConstants.ERROR_NOT_ACTIVE,
                    "Reprocessing is only allowed on an active loan.", loan.getId());
        }
        if (loan.isTopup()) {
            // Matches the undo-disbursal rule: a topup is funded by another loan's closure, so rewriting its inception
            // would silently invalidate the linked account transfer.
            throw new GeneralPlatformDomainRuleException(LoanReprocessApiConstants.ERROR_TOPUP,
                    "Reprocessing is not allowed on a topup loan.", loan.getId());
        }
        for (final LoanTransaction transaction : loan.getLoanTransactions()) {
            if (!transaction.isNotReversed()) {
                continue;
            }
            if (transaction.hasChargebackLoanTransactionRelations()) {
                throw new GeneralPlatformDomainRuleException(LoanReprocessApiConstants.ERROR_LINKED_TRANSACTIONS,
                        "Reprocessing is not allowed while the loan carries chargeback-linked transactions.", loan.getId());
            }
            if (LoanTransactionType.DOWN_PAYMENT.equals(transaction.getTypeOf())) {
                // A down payment is dated at disbursement and would be stranded on the old date, exactly like the
                // disbursement charge transaction - but unlike that one it is not handled here yet.
                throw new GeneralPlatformDomainRuleException(LoanReprocessApiConstants.ERROR_DOWN_PAYMENT_PRESENT,
                        "Reprocessing is not supported on loans with a down payment transaction.", loan.getId());
            }
        }
        validateDisbursementIsNotAccountTransferLinked(loan);
        validateLiveDisbursementTransactionExists(loan);
    }

    /**
     * The move reverses and re-creates the live {@code DISBURSEMENT} transactions - with none to move, the loop is a
     * silent no-op: the loan's dates and schedule would move while the general ledger keeps the disbursement on the old
     * date with no contra, and the caller would be told the correction succeeded.
     * <p>
     * Active status does not imply the transaction exists: status is a column, the transaction is a row, and nothing
     * ties them together on a loan whose disbursement was reversed by an earlier operation or arrived in that shape
     * from imported data. At least one, not exactly one - a loan with a charge deducted at disbursement legitimately
     * carries two {@code DISBURSEMENT} rows.
     */
    private void validateLiveDisbursementTransactionExists(final Loan loan) {
        final boolean hasLiveDisbursement = loan.getLoanTransactions().stream() //
                .filter(LoanTransaction::isNotReversed) //
                .anyMatch(LoanTransaction::isDisbursement);
        if (!hasLiveDisbursement) {
            throw new GeneralPlatformDomainRuleException(LoanReprocessApiConstants.ERROR_NO_LIVE_DISBURSEMENT,
                    "Reprocessing requires a live disbursement transaction, but the loan has none.", loan.getId());
        }
    }

    /**
     * Refuses loans that came across in the FinFlux migration.
     * <p>
     * Their charge tax splits were backfilled from FinFlux and are reconciled to the paisa. Moving the disbursement
     * date re-creates the disbursement-charge payment, and {@code LoanChargePaidBy} derives a fresh tax split from the
     * transaction's date and the current {@code tax_rounding_mode} rather than carrying the stored one across - so
     * reprocessing a migrated loan would silently overwrite FinFlux-sourced figures with Fineract-computed ones.
     * <p>
     * "Migrated" is decided exactly the way {@code OverdueChargeCutoffDateResolver} decides it, so the two cannot drift
     * apart. Deliberately fails open: when the migration configuration is not populated there is no migration to
     * protect against, and every loan is treated as natively disbursed.
     */
    private void validateLoanWasNotMigrated(final Loan loan) {
        final LocalDate migrationCutoffDate = this.configurationDomainService.retrieveMigrationCutoffDate();
        final Long lastImportedLoanId = this.configurationDomainService.retrieveMigrationLastImportedLoanId();
        if (migrationCutoffDate == null || lastImportedLoanId == null) {
            return;
        }
        if (loan.getId() != null && loan.getId() <= lastImportedLoanId) {
            throw new GeneralPlatformDomainRuleException(LoanReprocessApiConstants.ERROR_MIGRATED_LOAN,
                    "Reprocessing is not allowed on a loan migrated from the source system; its charge tax details would be recomputed.",
                    loan.getId());
        }
    }

    /**
     * The disbursement transaction is reversed and recreated, which would orphan the
     * {@code m_account_transfer_transaction} row pointing at it. Detected the way {@code adjustLoanTransaction} does.
     */
    private void validateDisbursementIsNotAccountTransferLinked(final Loan loan) {
        for (final LoanTransaction transaction : loan.getLoanTransactions()) {
            if (transaction.isNotReversed() && transaction.isDisbursement() && transaction.getId() != null
                    && this.accountTransfersReadPlatformService.isAccountTransfer(transaction.getId(), PortfolioAccountType.LOAN)) {
                throw new GeneralPlatformDomainRuleException(LoanReprocessApiConstants.ERROR_ACCOUNT_TRANSFER_LINKED,
                        "Reprocessing is not allowed when the disbursement is linked to an account transfer.", loan.getId());
            }
        }
    }

    private void validateProductIsInScope(final Loan loan) {
        // Each of these has a materially different replay path and is rejected rather than mis-handled: silently
        // producing a wrong schedule is far worse than refusing the request.
        if (loan.getLoanProduct().isMultiDisburseLoan()) {
            throw new GeneralPlatformDomainRuleException(LoanReprocessApiConstants.ERROR_MULTI_DISBURSE_UNSUPPORTED,
                    "Reprocessing does not support multi-disbursement loans.", loan.getId());
        }
        validateSingleDisbursementDetail(loan);
        if (loan.isProgressiveSchedule()) {
            throw new GeneralPlatformDomainRuleException(LoanReprocessApiConstants.ERROR_PROGRESSIVE_UNSUPPORTED,
                    "Reprocessing does not support progressive schedule loans.", loan.getId());
        }
        if (loan.isInterestBearingAndInterestRecalculationEnabled()) {
            throw new GeneralPlatformDomainRuleException(LoanReprocessApiConstants.ERROR_INTEREST_RECALCULATION_UNSUPPORTED,
                    "Reprocessing does not support loans with interest recalculation enabled.", loan.getId());
        }
    }

    /**
     * A second, data-level guard behind the multi-disbursement product check above.
     * <p>
     * The apply step rewrites every disbursement detail row it finds, and the disbursement transaction move assumes one
     * disbursement event. A product flagged single-disburse that nonetheless carries several rows would be rewritten
     * wholesale, which is not a correction anyone asked for.
     * <p>
     * An absent row is tolerated rather than refused: the schedule is driven by {@code loan.getDisbursementDate()}, so
     * the correction is still right, there is simply nothing to update.
     */
    private void validateSingleDisbursementDetail(final Loan loan) {
        // Live rows only: a reversed detail row is history, not a second disbursement.
        final long liveDetails = loan.getDisbursementDetails().stream().filter(details -> !details.isReversed()).count();
        if (liveDetails > 1) {
            throw new GeneralPlatformDomainRuleException(LoanReprocessApiConstants.ERROR_MULTIPLE_DISBURSEMENT_DETAILS,
                    "Reprocessing requires a single disbursement, but the loan carries " + liveDetails + " disbursement details.",
                    loan.getId());
        }
    }

    /**
     * The rule that blocks most aged loans, and the reason to check it here rather than discover it mid-flight.
     * <p>
     * Every contra and re-post is dated on its original transaction date, not today.
     * {@code AccrualBasedAccountingProcessorForLoan} re-checks the office's latest GL closure for each of those dates
     * and throws {@code ACCOUNTING_CLOSED} when the closing date is on or after them. Both the old disbursement date
     * (where the contra lands) and the new one (where the replacement lands) must clear it.
     * <p>
     * Closures are never deleted to make room: {@code GLClosureWritePlatformServiceJpaRepositoryImpl} only permits
     * deleting the latest for an office, so reopening far enough would reopen the books for every other account in it.
     * <p>
     * Skipped entirely when the product keeps no accounting. {@code createJournalEntriesForLoanTransaction} returns
     * before touching the ledger in that case, so refusing the request would be refusing it on account of entries that
     * were never going to be posted.
     */
    private void validateAccountingPeriodIsOpen(final Loan loan, final LoanReprocessRequest request) {
        if (loan.isAccountingDisabledOnLoanProduct()) {
            return;
        }
        final GLClosure latestClosure = this.glClosureRepository.getLatestGLClosureByBranch(loan.getOfficeId());
        if (latestClosure == null) {
            return;
        }
        final LocalDate current = loan.getDisbursementDate();
        final LocalDate corrected = request.getActualDisbursementDate();
        final LocalDate earliestAffected = corrected == null || DateUtils.isBefore(current, corrected) ? current : corrected;

        if (!DateUtils.isBefore(latestClosure.getClosingDate(), earliestAffected)) {
            throw new GeneralPlatformDomainRuleException(LoanReprocessApiConstants.ERROR_ACCOUNTING_CLOSED,
                    "Accounting for this office is closed as of " + latestClosure.getClosingDate()
                            + ", but reprocessing would post journal entries dated " + earliestAffected + " or later.",
                    latestClosure.getClosingDate(), earliestAffected);
        }
    }

    // ----- per-parameter rules -----

    private void validateActualDisbursementDate(final Loan loan, final LoanReprocessRequest request) {
        final LocalDate newDate = request.getActualDisbursementDate();
        if (newDate == null) {
            return;
        }
        final String param = LoanReprocessApiConstants.actualDisbursementDateParamName;
        if (newDate.equals(loan.getDisbursementDate())) {
            throw parameterError(LoanReprocessApiConstants.ERROR_DISBURSEMENT_DATE_UNCHANGED,
                    "The disbursement date is already " + newDate + ".", param);
        }
        if (DateUtils.isDateInTheFuture(newDate)) {
            throw parameterError(LoanReprocessApiConstants.ERROR_DISBURSEMENT_DATE_IN_FUTURE,
                    "The disbursement date cannot be in the future.", param);
        }
        // Bounded below by approval, mirroring the ordinary disbursal rule in LoanTransactionValidatorImpl. Moving
        // earlier than approval requires undoing and redoing the approval first.
        if (loan.getApprovedOnDate() != null && DateUtils.isBefore(newDate, loan.getApprovedOnDate())) {
            throw parameterError(LoanReprocessApiConstants.ERROR_DISBURSEMENT_BEFORE_APPROVAL,
                    "The disbursement date cannot be before the loan approval date " + loan.getApprovedOnDate() + ".", param);
        }
        validateNoRepaymentOnTheDisbursementDate(loan);
        validateDisbursementStaysBeforeTheFirstInstallment(loan, newDate, param);
        validateChronology(loan, newDate, param);
    }

    /**
     * Broken-period interest is posted as an ordinary {@code REPAYMENT} dated at disbursement
     * ({@code LoanDisbursementService#createBpiRepaymentTransaction}), carrying no marker that distinguishes it from a
     * genuine same-day repayment. Moving the disbursement date changes the broken period and therefore the BPI amount,
     * but the existing transaction would keep the old one - so the schedule and the transaction would disagree with no
     * way to tell which is right.
     * <p>
     * Rejected rather than guessed. Such loans need the BPI transaction adjusted by hand.
     * <p>
     * Narrowed to {@code REPAYMENT} on purpose. Matching every transaction dated that day would also catch the accruals
     * that upfront-accrual accounting posts at disbursement ({@code LoanDisbursementService} charge-applied branch),
     * which step 4 re-derives anyway - refusing those would block the loan while blaming broken-period interest for it.
     * Amounts that do not depend on the disbursement date, a same-day goodwill credit or refund among them, replay
     * against the rebuilt schedule without trouble.
     */
    private void validateNoRepaymentOnTheDisbursementDate(final Loan loan) {
        final LocalDate currentDisbursementDate = loan.getDisbursementDate();
        final boolean hasSameDayRepayment = loan.getLoanTransactions().stream() //
                .filter(LoanTransaction::isNotReversed) //
                .filter(transaction -> LoanTransactionType.REPAYMENT.equals(transaction.getTypeOf())) //
                .anyMatch(transaction -> currentDisbursementDate.equals(transaction.getTransactionDate()));
        if (hasSameDayRepayment) {
            throw new GeneralPlatformDomainRuleException(LoanReprocessApiConstants.ERROR_REPAYMENT_ON_DISBURSEMENT_DATE,
                    "The loan carries a repayment dated on the disbursement date " + currentDisbursementDate
                            + ". If this is broken-period interest its amount depends on the disbursement date and must be corrected manually.",
                    currentDisbursementDate);
        }
    }

    /**
     * Keeps the corrected disbursement strictly before the date the schedule generator will start repayments from.
     * <p>
     * Installment due dates are held still across a correction - the apply step pins
     * {@code expectedFirstRepaymentOnDate} so only the first period stretches or shrinks. That leaves the first period
     * running from the new disbursement date to a due date that does not move, so a disbursement on or after that due
     * date would ask the generator for a period of zero or negative length.
     * <p>
     * Bounded against the same quantity the generator reads, not merely the current schedule:
     * {@code calculateRepaymentStartingFromDate} takes {@code expectedFirstRepaymentOnDate} when set and only derives
     * from the schedule when it is not. Bounding against the installment due date alone would let through a correction
     * that clears the installments but lands after a stored first-repayment date - a negative first period the rule
     * exists to prevent.
     */
    private void validateDisbursementStaysBeforeTheFirstInstallment(final Loan loan, final LocalDate newDate, final String param) {
        final Optional<LocalDate> generatorStartDate = loan.getExpectedFirstRepaymentOnDate() != null
                ? Optional.of(loan.getExpectedFirstRepaymentOnDate())
                : firstInstallmentDueDate(loan);
        if (generatorStartDate.isPresent() && !DateUtils.isBefore(newDate, generatorStartDate.get())) {
            throw parameterError(LoanReprocessApiConstants.ERROR_DISBURSEMENT_NOT_BEFORE_FIRST_INSTALLMENT,
                    "The disbursement date " + newDate + " must be before the first repayment date " + generatorStartDate.get() + ".",
                    param);
        }
    }

    /**
     * Earliest real instalment due date - the date the apply step pins {@code expectedFirstRepaymentOnDate} to when the
     * loan never carried one. Shared with {@code LoanReprocessServiceImpl} so the bound checked here and the date
     * pinned there cannot drift apart. Down payments excluded: they are dated at disbursement and are not the first
     * instalment in the agreed sense.
     */
    public static Optional<LocalDate> firstInstallmentDueDate(final Loan loan) {
        return loan.getRepaymentScheduleInstallments().stream() //
                .filter(installment -> !installment.isDownPayment()) //
                .map(LoanRepaymentScheduleInstallment::getDueDate) //
                .min(Comparator.naturalOrder());
    }

    /**
     * Moving the disbursement later than an existing repayment would strand that repayment before its own disbursement.
     * <p>
     * {@code DISBURSEMENT} and {@code REPAYMENT_AT_DISBURSEMENT} are excluded because both <i>are</i> the disbursement
     * event and move with it. Including the latter would block every loan that carries disbursement-time charges from
     * ever moving its date forward.
     * <p>
     * Strict: landing <i>on</i> the earliest transaction's date is rejected too. Accepting it would manufacture the
     * exact shape {@link #validateNoRepaymentOnTheDisbursementDate} refuses - a repayment dated on the disbursement
     * date - and lock the loan out of every further correction, including undoing this one.
     */
    private void validateChronology(final Loan loan, final LocalDate newDate, final String param) {
        final Optional<LocalDate> earliestActivity = loan.getLoanTransactions().stream() //
                .filter(LoanTransaction::isNotReversed) //
                .filter(transaction -> !isPartOfDisbursementEvent(transaction)) //
                .map(LoanTransaction::getTransactionDate) //
                .min(Comparator.naturalOrder());
        if (earliestActivity.isPresent() && !DateUtils.isBefore(newDate, earliestActivity.get())) {
            throw parameterError(LoanReprocessApiConstants.ERROR_DISBURSEMENT_AFTER_TRANSACTION, "The disbursement date " + newDate
                    + " is on or after the earliest existing transaction dated " + earliestActivity.get() + ".", param);
        }
    }

    static boolean isPartOfDisbursementEvent(final LoanTransaction transaction) {
        return transaction.isDisbursement() || LoanTransactionType.REPAYMENT_AT_DISBURSEMENT.equals(transaction.getTypeOf());
    }

    /** Placeholder - see {@link LoanReprocessApiConstants#IMPLEMENTED_PARAMETERS}. */
    private void validatePrincipal(final Loan loan, final LoanReprocessRequest request) {
        // Not reachable while `principal` is gated as unimplemented. When wiring it up, the rules are: greater than
        // zero, not below the amount actually disbursed, not above the approved principal, and within the product
        // min/max. Changing it also re-bases percentage-of-principal disbursement charges.
    }

    /** Placeholder - see {@link LoanReprocessApiConstants#IMPLEMENTED_PARAMETERS}. */
    private void validateExpectedFirstRepaymentOnDate(final Loan loan, final LoanReprocessRequest request) {
        // When wiring it up: must be strictly after the effective disbursement date, and must not fall on a holiday or
        // non-working day.
    }

    /** Placeholder - see {@link LoanReprocessApiConstants#IMPLEMENTED_PARAMETERS}. */
    private void validateInterestRatePerPeriod(final Loan loan, final LoanReprocessRequest request) {
        // When wiring it up: non-negative and within the product min/max.
    }

    /** Placeholder - see {@link LoanReprocessApiConstants#IMPLEMENTED_PARAMETERS}. */
    private void validateNumberOfRepayments(final Loan loan, final LoanReprocessRequest request) {
        // When wiring it up: at least one and within the product min/max. Shrinking it drops installments, which the
        // replay only tolerates once the transaction mappings referencing them have been released.
    }

    private PlatformApiDataValidationException parameterError(final String code, final String message, final String parameterName) {
        return new PlatformApiDataValidationException("validation.msg.validation.errors.exist", "Validation errors exist.",
                List.of(ApiParameterError.parameterError(code, message, parameterName)));
    }
}
