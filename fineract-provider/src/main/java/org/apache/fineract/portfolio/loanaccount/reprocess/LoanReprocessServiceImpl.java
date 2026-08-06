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
package org.apache.fineract.portfolio.loanaccount.reprocess;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanBalanceChangedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanReprocessedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanUpdateDisbursementDataBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.reprocess.service.LoanReprocessService;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanReprocessValidator;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanJournalEntryPoster;
import org.apache.fineract.portfolio.loanaccount.service.LoanScheduleRegenerationService;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductRoundingModeService;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rewrites loan parameters that invalidate the repayment schedule, regenerates it from inception and replays the
 * existing transactions.
 * <p>
 * The heavy lifting is upstream's, deliberately: {@code reprocessLoanTransactions} already resets installment derived
 * components and charge paid amounts and rebuilds transaction-to-installment mappings, so none of that is repeated
 * here. What this class owns is the parameter mutation and moving the disbursement event's transactions and journal
 * entries to the new date.
 * <p>
 * Everything runs in one transaction. {@link LoanTransaction#reverse()} has no inverse, so a failure at any step must
 * take the whole operation with it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanReprocessServiceImpl implements LoanReprocessService {

    private final LoanAssembler loanAssembler;
    private final LoanRepositoryWrapper loanRepositoryWrapper;
    private final LoanTransactionRepository loanTransactionRepository;
    private final LoanReprocessValidator validator;
    private final LoanScheduleRegenerationService loanScheduleRegenerationService;
    private final LoanJournalEntryPoster journalEntryPoster;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final ConfigurationDomainService configurationDomainService;
    private final NoteRepository noteRepository;
    private final LoanProductRoundingModeService loanProductRoundingModeService;

    @Transactional
    @Override
    public CommandProcessingResult reprocessLoan(final Long loanId, final JsonCommand command) {
        final Loan loan = this.loanAssembler.assembleFrom(loanId);
        final LoanReprocessRequest request = LoanReprocessRequest.from(command);

        // 1 - reject before touching anything, so a refusal needs no rollback.
        this.validator.validate(loan, request);

        final Map<String, Object> changes = new LinkedHashMap<>();

        // 2 - apply each requested parameter. Only the implemented ones do anything; the validator has already
        // rejected the rest, so the placeholders below are unreachable rather than silently skipped.
        applyActualDisbursementDate(loan, request, changes);
        applyPrincipal(loan, request, changes);
        applyExpectedFirstRepaymentOnDate(loan, request, changes);
        applyInterestRatePerPeriod(loan, request, changes);
        applyNumberOfRepayments(loan, request, changes);

        // Audit convention: a date change is recorded as the string the caller supplied plus the locale and format
        // needed to parse it back - the shape every other command writes and the maker-checker UI renders. The raw
        // LocalDate would serialize as a [year,month,day] array in m_portfolio_command_source.
        if (changes.containsKey(LoanReprocessApiConstants.actualDisbursementDateParamName)) {
            changes.put(LoanReprocessApiConstants.actualDisbursementDateParamName,
                    command.stringValueOfParameterNamed(LoanReprocessApiConstants.actualDisbursementDateParamName));
            changes.put(LoanReprocessApiConstants.dateFormatParamName, command.dateFormat());
            changes.put(LoanReprocessApiConstants.localeParamName, command.locale());
        }

        // 3 - rebuild the schedule, replay the transactions, and settle everything derived from the schedule that the
        // replay does not touch. Shared with bulk repair so the two cannot drift apart.
        this.loanScheduleRegenerationService.regenerateAndReplay(loan);

        final Loan savedLoan = this.loanRepositoryWrapper.saveAndFlush(loan);

        if (StringUtils.isNotBlank(request.getNote())) {
            // Attached to the loan rather than a transaction: the transaction it would naturally hang off has just
            // been reversed.
            this.noteRepository.save(Note.loanNote(savedLoan, request.getNote()));
        }

        this.businessEventNotifierService.notifyPostBusinessEvent(new LoanBalanceChangedBusinessEvent(savedLoan));
        // Raised after the flush: the serializer re-reads the loan and its schedule by id, so the rows have to be
        // there. Balances alone would not tell a consumer the schedule itself was rewritten.
        this.businessEventNotifierService.notifyPostBusinessEvent(new LoanReprocessedBusinessEvent(savedLoan));
        if (changes.containsKey(LoanReprocessApiConstants.actualDisbursementDateParamName)) {
            // External consumers track the disbursement date off this event; a balance change alone would not tell
            // them the date moved.
            this.businessEventNotifierService.notifyPostBusinessEvent(new LoanUpdateDisbursementDataBusinessEvent(savedLoan));
        }

        log.info("Reprocessed loan {} after changing {}", loanId, changes.keySet());

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withEntityId(loanId) //
                .withOfficeId(savedLoan.getOfficeId()) //
                .withClientId(savedLoan.getClientId()) //
                .withGroupId(savedLoan.getGroupId()) //
                .withLoanId(loanId) //
                .with(changes) //
                .build();
    }

    // ----- parameter apply steps -----

    private void applyActualDisbursementDate(final Loan loan, final LoanReprocessRequest request, final Map<String, Object> changes) {
        final LocalDate newDate = request.getActualDisbursementDate();
        if (newDate == null) {
            return;
        }
        final LocalDate oldDate = loan.getDisbursementDate();

        // Move the disbursement event's transactions first, while their own dates still read as the old ones - that is
        // what puts the contra entries on the old date.
        moveDisbursementTransaction(loan, newDate);
        moveDisbursementChargeTransaction(loan, newDate);
        refreshDisbursementChargeTaxDetails(loan, newDate);

        loan.setActualDisbursementDate(newDate);
        loan.setExpectedDisbursementDate(newDate);
        // Both dates on the detail row, not just the actual one: leaving the expected date behind would have the row
        // describing a disbursement that was expected on one date and made on another, which never happened. Reversed
        // rows are history and keep their original dates.
        loan.getDisbursementDetails().stream().filter(details -> !details.isReversed()).forEach(details -> {
            details.updateActualDisbursementDate(newDate);
            details.updateExpectedDisbursementDateAndAmount(newDate, details.principal());
        });
        pinFirstRepaymentDateIfItWasDerived(loan, changes);
        moveInterestAnchorIfItFollowedDisbursement(loan, oldDate, newDate, changes);

        changes.put(LoanReprocessApiConstants.actualDisbursementDateParamName, newDate);
        log.debug("Loan {} disbursement date moved {} -> {}", loan.getId(), oldDate, newDate);
    }

    /**
     * Re-derives the charge-level tax split of disbursement-time charges at the corrected date, mirroring what
     * {@code LoanDisbursementService#handleDisbursementTransaction} does on a real disbursal.
     * <p>
     * Without this the two tax records diverge: the replacement charge payment's paid-by rows carry a split computed at
     * the new date - and the general ledger follows those - while {@code m_loan_charge_tax_details} would keep the
     * split computed at the old one. Across a rate-effective-date boundary that is a reconciliation break with no
     * record of why.
     * <p>
     * Disbursement-time charges only. Charges due later were never split at the disbursement date, so the correction
     * gives them nothing to re-derive; overdue penalties are governed by the snapshot-restore in the regeneration step.
     */
    private void refreshDisbursementChargeTaxDetails(final Loan loan, final LocalDate newDate) {
        for (final LoanCharge charge : loan.getActiveCharges()) {
            if (charge.isDueAtDisbursement()) {
                charge.updateLoanChargeTaxDetails(newDate, charge.amount(),
                        this.loanProductRoundingModeService.resolveTaxRoundingMode(loan.getLoanProduct().getId()));
            }
        }
    }

    /**
     * Holds the instalment due dates still while the disbursement moves.
     * <p>
     * The generator takes its first repayment date from {@code expectedFirstRepaymentOnDate} when one is set, and only
     * falls back to the calendar and then to the disbursement date when it is not
     * ({@code LoanUtilService#calculateRepaymentStartingFromDate}). So a loan that never carried one would have every
     * due date slide by the same offset as the disbursement - which is not what a correction means. The instalment
     * dates were agreed with the customer and collected against; what the correction genuinely changes is the length of
     * the first period, and with it that period's interest.
     * <p>
     * Pinning the date makes that behaviour explicit and durable instead of a side effect of which fields happen to be
     * populated. A loan that already carries one is left alone - its dates are stable already.
     */
    private void pinFirstRepaymentDateIfItWasDerived(final Loan loan, final Map<String, Object> changes) {
        if (loan.getExpectedFirstRepaymentOnDate() != null) {
            return;
        }
        // The same helper the validator bounds against, so the date checked there and the date pinned here cannot
        // drift apart.
        LoanReprocessValidator.firstInstallmentDueDate(loan).ifPresent(firstDueDate -> {
            loan.setExpectedFirstRepaymentOnDate(firstDueDate);
            // A distinct key, not expectedFirstRepaymentOnDateParamName: that parameter is still rejected as
            // unimplemented when supplied, and the audit record must not read as though it had been accepted. This
            // entry reports a side effect of the date move, not an applied input.
            changes.put(LoanReprocessApiConstants.expectedFirstRepaymentOnDatePinnedResponseParam, firstDueDate.toString());
            log.debug("Loan {}: pinned the first repayment date at {} so the instalment dates hold across the correction", loan.getId(),
                    firstDueDate);
        });
    }

    /**
     * Moves the interest anchor with the disbursement, but only when it was following it.
     * <p>
     * Interest is computed from {@code interestChargedFromDate} when set, not from the disbursement date. Leaving it
     * behind produces a schedule whose first period spans the new number of days while charging interest for the old
     * one - observed live as a 26-day period still billing 25 days of interest.
     * <p>
     * Only moved when it currently equals the <i>old</i> disbursement date, which means it was derived rather than
     * deliberately chosen. A value set apart from the disbursement on purpose - an interest holiday, say - is left
     * exactly where it is.
     */
    private void moveInterestAnchorIfItFollowedDisbursement(final Loan loan, final LocalDate oldDate, final LocalDate newDate,
            final Map<String, Object> changes) {
        final LocalDate anchor = loan.getInterestChargedFromDate();
        if (anchor == null || !anchor.equals(oldDate)) {
            return;
        }
        loan.setInterestChargedFromDate(newDate);
        // ISO string, not the LocalDate: derived dates have no caller-supplied form, and the raw object would
        // serialize as a [year,month,day] array in the audit record.
        changes.put("interestChargedFromDate", newDate.toString());
        log.debug("Loan {}: interest anchor followed the disbursement, moved {} -> {}", loan.getId(), oldDate, newDate);
    }

    /**
     * Reverses the disbursement and re-creates it at the new date.
     * <p>
     * Editing the date in place is not viable: the journal entries already posted stay at the old date, and re-posting
     * a non-reversed transaction adds a second set rather than replacing the first. Reversing emits the contra at the
     * old date through the native loan path; the replacement then posts a fresh pair at the new one.
     * <p>
     * {@code reverse()} renames the original's external id to {@code R_<value>_<id>}, which frees the original value
     * for the replacement so integrators tracking the disbursement by external id are unaffected.
     */
    private void moveDisbursementTransaction(final Loan loan, final LocalDate newDate) {
        // Every one of them, not just the first. A loan with a charge deducted at disbursement carries two
        // DISBURSEMENT rows - the net amount paid to the client and the charge portion - and moving only one would
        // strand the other on the old date.
        for (final LoanTransaction original : liveTransactionsOfType(loan, LoanTransactionType.DISBURSEMENT)) {
            final Money amount = original.getAmount(loan.getCurrency());
            final ExternalId externalId = original.getExternalId();

            reverseAndPost(original);

            final LoanTransaction replacement = LoanTransaction.disbursement(loan, amount, original.getPaymentDetail(), newDate, externalId,
                    loan.getTotalOverpaidAsMoney());
            // Attach to the loan before persisting. Loan#loanTransactions is mapped with orphanRemoval = true, so a
            // row that exists in the database but not in the collection is deleted on the next flush.
            replacement.updateLoan(loan);
            loan.addLoanTransaction(replacement);
            this.loanTransactionRepository.saveAndFlush(replacement);
            this.journalEntryPoster.postJournalEntriesForLoanTransaction(replacement, false, false);
        }
    }

    /**
     * Moves the {@code REPAYMENT_AT_DISBURSEMENT} transaction that settles disbursement-time charges.
     * <p>
     * It has to move with the disbursement. Left behind it would strand the charge payment on the old date while the
     * charges stay marked fully paid - {@code reprocessLoanTransactions} deliberately skips {@code resetPaidAmount} for
     * charges due at disbursement, so the replay will not correct it.
     */
    private void moveDisbursementChargeTransaction(final Loan loan, final LocalDate newDate) {
        for (final LoanTransaction original : liveTransactionsOfType(loan, LoanTransactionType.REPAYMENT_AT_DISBURSEMENT)) {
            final Money amount = original.getAmount(loan.getCurrency());
            final ExternalId externalId = original.getExternalId();
            // Snapshot the associations before reversing, so the replacement pays exactly the charges this transaction
            // paid, in the same amounts. Re-deriving them from the loan's active charges would guess.
            final List<LoanChargePaidBy> originalAssociations = new ArrayList<>(original.getLoanChargesPaid());

            reverseAndPost(original);

            final LoanTransaction replacement = LoanTransaction.repaymentAtDisbursement(loan.getOffice(), amount,
                    original.getPaymentDetail(), newDate, externalId);
            for (final LoanChargePaidBy association : originalAssociations) {
                replacement.getLoanChargesPaid().add(new LoanChargePaidBy(replacement, association.getLoanCharge(), association.getAmount(),
                        association.getInstallmentNumber(), this.configurationDomainService.getTaxRoundingMode()));
            }
            // Components derived from the associations rather than hard-coded: upstream's all-fee assignment is only
            // right while every disbursement-time charge is a fee. Summing what the transaction actually pays, by the
            // charge's own classification, keeps the contra and the replacement netting per income account whatever
            // the charges are.
            final Money zero = Money.zero(loan.getCurrency());
            Money feePortion = zero;
            Money penaltyPortion = zero;
            for (final LoanChargePaidBy association : originalAssociations) {
                final Money paid = Money.of(loan.getCurrency(), association.getAmount());
                if (association.getLoanCharge().isPenaltyCharge()) {
                    penaltyPortion = penaltyPortion.plus(paid);
                } else {
                    feePortion = feePortion.plus(paid);
                }
            }
            if (feePortion.plus(penaltyPortion).isZero()) {
                // A row with no surviving associations still moved real money; classify it the way upstream created
                // it rather than zeroing the replacement.
                feePortion = amount;
            }
            replacement.updateComponentsAndTotal(zero, zero, feePortion, penaltyPortion);
            replacement.updateLoan(loan);
            loan.addLoanTransaction(replacement);
            this.loanTransactionRepository.saveAndFlush(replacement);
            this.journalEntryPoster.postJournalEntriesForLoanTransaction(replacement, false, false);
        }
    }

    /** Reverse and post in that order: the contra takes the transaction's own, still-unchanged, date. */
    private void reverseAndPost(final LoanTransaction transaction) {
        transaction.reverse();
        transaction.manuallyAdjustedOrReversed();
        this.loanTransactionRepository.saveAndFlush(transaction);
        this.journalEntryPoster.postJournalEntriesForLoanTransaction(transaction, false, false);
    }

    /**
     * Copied into a list rather than streamed lazily: the callers reverse these and add replacements to the same
     * collection while iterating, which would otherwise throw {@link java.util.ConcurrentModificationException}.
     */
    private List<LoanTransaction> liveTransactionsOfType(final Loan loan, final LoanTransactionType type) {
        return loan.getLoanTransactions().stream() //
                .filter(LoanTransaction::isNotReversed) //
                .filter(transaction -> type.equals(transaction.getTypeOf())) //
                .toList();
    }

    /** Placeholder - gated as unimplemented in {@link LoanReprocessApiConstants#IMPLEMENTED_PARAMETERS}. */
    private void applyPrincipal(final Loan loan, final LoanReprocessRequest request, final Map<String, Object> changes) {
        // When wiring up: set loan.getLoanRepaymentScheduleDetail().setPrincipal(...), and move the disbursement
        // transaction for the amount change the same way applyActualDisbursementDate moves it for the date change.
    }

    /** Placeholder - gated as unimplemented in {@link LoanReprocessApiConstants#IMPLEMENTED_PARAMETERS}. */
    private void applyExpectedFirstRepaymentOnDate(final Loan loan, final LoanReprocessRequest request, final Map<String, Object> changes) {
        // When wiring up: loan.setExpectedFirstRepaymentOnDate(...). No transaction moves; the schedule regeneration
        // in step 3 picks it up on its own.
    }

    /** Placeholder - gated as unimplemented in {@link LoanReprocessApiConstants#IMPLEMENTED_PARAMETERS}. */
    private void applyInterestRatePerPeriod(final Loan loan, final LoanReprocessRequest request, final Map<String, Object> changes) {
        // When wiring up: loan.getLoanRepaymentScheduleDetail().setNominalInterestRatePerPeriod(...). No transaction
        // moves, but every posted accrual is invalidated - step 4 re-derives them.
    }

    /** Placeholder - gated as unimplemented in {@link LoanReprocessApiConstants#IMPLEMENTED_PARAMETERS}. */
    private void applyNumberOfRepayments(final Loan loan, final LoanReprocessRequest request, final Map<String, Object> changes) {
        // When wiring up: loan.getLoanRepaymentScheduleDetail().setNumberOfRepayments(...). Shrinking the term drops
        // installments, which LoanScheduleComponent#updateLoanSchedule only tolerates once the transaction mappings
        // referencing them have been released.
    }
}
