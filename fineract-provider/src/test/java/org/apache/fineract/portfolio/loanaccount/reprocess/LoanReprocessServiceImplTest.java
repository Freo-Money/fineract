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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.event.business.domain.loan.LoanReprocessedBusinessEvent;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanCharge;
import org.apache.fineract.portfolio.loanaccount.domain.LoanChargePaidBy;
import org.apache.fineract.portfolio.loanaccount.domain.LoanDisbursementDetails;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepositoryWrapper;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionRepository;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransactionType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.LoanScheduleType;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanReprocessValidator;
import org.apache.fineract.portfolio.loanaccount.service.LoanAssembler;
import org.apache.fineract.portfolio.loanaccount.service.LoanJournalEntryPoster;
import org.apache.fineract.portfolio.loanaccount.service.LoanScheduleRegenerationService;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRelatedDetail;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductRoundingModeService;
import org.apache.fineract.portfolio.note.domain.Note;
import org.apache.fineract.portfolio.note.domain.NoteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Guards the parameter mutation and the disbursement-event move - everything the shared regeneration service does not
 * own.
 * <p>
 * The moves are the part with no inverse: {@link LoanTransaction#reverse()} cannot be undone, and a replacement created
 * with the wrong date, amount or external id is a correction that has to be corrected by hand. The date pinning matters
 * for a different reason: without it a loan that never carried an explicit first repayment date has every instalment
 * slide with the disbursement, which is not what a correction is supposed to mean.
 */
class LoanReprocessServiceImplTest {

    private static final Long LOAN_ID = 77L;
    private static final LocalDate OLD_DATE = LocalDate.of(2026, 6, 5);
    private static final LocalDate NEW_DATE = LocalDate.of(2026, 6, 3);
    private static final LocalDate FIRST_DUE_DATE = LocalDate.of(2026, 7, 5);
    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("INR", 2, null);

    private final LoanAssembler loanAssembler = mock(LoanAssembler.class);
    private final LoanRepositoryWrapper loanRepositoryWrapper = mock(LoanRepositoryWrapper.class);
    private final LoanTransactionRepository loanTransactionRepository = mock(LoanTransactionRepository.class);
    private final LoanReprocessValidator validator = mock(LoanReprocessValidator.class);
    private final LoanScheduleRegenerationService regenerationService = mock(LoanScheduleRegenerationService.class);
    private final LoanJournalEntryPoster journalEntryPoster = mock(LoanJournalEntryPoster.class);
    private final BusinessEventNotifierService businessEventNotifierService = mock(BusinessEventNotifierService.class);
    private final ConfigurationDomainService configurationDomainService = mock(ConfigurationDomainService.class);
    private final NoteRepository noteRepository = mock(NoteRepository.class);
    private final LoanProductRoundingModeService loanProductRoundingModeService = mock(LoanProductRoundingModeService.class);

    private final LoanReprocessServiceImpl service = new LoanReprocessServiceImpl(loanAssembler, loanRepositoryWrapper,
            loanTransactionRepository, validator, regenerationService, journalEntryPoster, businessEventNotifierService,
            configurationDomainService, noteRepository, loanProductRoundingModeService);

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil
                .setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.now(ZoneId.systemDefault()))));
        // Money construction resolves the tenant's rounding mode; without this every Money.of here fails.
        MoneyHelper.initializeTenantRoundingMode("default", RoundingMode.HALF_EVEN.ordinal());
        lenient().when(configurationDomainService.getTaxRoundingMode()).thenReturn(RoundingMode.HALF_DOWN);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    // ----- the disbursement move -----

    @Test
    void reversesTheDisbursementAndRecreatesItAtTheNewDate() {
        final LoanTransaction original = disbursementTransaction(1L, "EXT-DISB");
        final Loan loan = activeLoan(List.of(original));

        service.reprocessLoan(LOAN_ID, command());

        verify(original).reverse();
        verify(original).manuallyAdjustedOrReversed();
        final LoanTransaction replacement = onlyReplacement();
        assertThat(replacement.getTypeOf()).isEqualTo(LoanTransactionType.DISBURSEMENT);
        assertThat(replacement.getTransactionDate()).isEqualTo(NEW_DATE);
        assertThat(replacement.getAmount()).isEqualByComparingTo(new BigDecimal("12000"));
        verify(loan).addLoanTransaction(replacement);
    }

    /**
     * {@code reverse()} renames the original to {@code R_<value>_<id>}, which frees the value for the replacement -
     * integrators tracking the disbursement by external id keep following the live transaction.
     */
    @Test
    void theReplacementInheritsTheOriginalExternalId() {
        final LoanTransaction original = disbursementTransaction(1L, "EXT-DISB");
        activeLoan(List.of(original));

        service.reprocessLoan(LOAN_ID, command());

        assertThat(onlyReplacement().getExternalId().getValue()).isEqualTo("EXT-DISB");
    }

    /**
     * A loan with a charge deducted at disbursement carries two DISBURSEMENT rows - the net paid to the client and the
     * charge portion. Moving only the first would strand the other on the old date.
     */
    @Test
    void everyDisbursementRowIsMovedNotJustTheFirst() {
        final LoanTransaction first = disbursementTransaction(1L, "EXT-A");
        final LoanTransaction second = disbursementTransaction(2L, "EXT-B");
        activeLoan(List.of(first, second));

        service.reprocessLoan(LOAN_ID, command());

        verify(first).reverse();
        verify(second).reverse();
        assertThat(replacements()).hasSize(2).allSatisfy(txn -> assertThat(txn.getTransactionDate()).isEqualTo(NEW_DATE));
    }

    @Test
    void alreadyReversedTransactionsAreLeftAlone() {
        final LoanTransaction reversed = disbursementTransaction(1L, "EXT-OLD");
        when(reversed.isNotReversed()).thenReturn(false);
        activeLoan(List.of(reversed));

        service.reprocessLoan(LOAN_ID, command());

        verify(reversed, never()).reverse();
        assertThat(replacements()).isEmpty();
    }

    /**
     * The associations are snapshotted before the reversal so the replacement pays exactly the charges the original
     * paid, in the same amounts. Re-deriving them from the loan's active charges would be a guess.
     */
    @Test
    void theDisbursementChargePaymentKeepsItsChargeAssociations() {
        final LoanCharge charge = mock(LoanCharge.class);
        lenient().when(charge.getTaxGroup()).thenReturn(null);
        final LoanChargePaidBy association = mock(LoanChargePaidBy.class);
        lenient().when(association.getLoanCharge()).thenReturn(charge);
        lenient().when(association.getAmount()).thenReturn(new BigDecimal("250.00"));
        lenient().when(association.getInstallmentNumber()).thenReturn(null);

        final LoanTransaction original = transaction(LoanTransactionType.REPAYMENT_AT_DISBURSEMENT, 3L, "EXT-FEE",
                new BigDecimal("250.00"));
        when(original.getLoanChargesPaid()).thenReturn(Set.of(association));
        activeLoan(List.of(original));

        service.reprocessLoan(LOAN_ID, command());

        final LoanTransaction replacement = onlyReplacement();
        assertThat(replacement.getTypeOf()).isEqualTo(LoanTransactionType.REPAYMENT_AT_DISBURSEMENT);
        assertThat(replacement.getLoanChargesPaid()).hasSize(1);
        assertThat(replacement.getLoanChargesPaid().iterator().next().getLoanCharge()).isSameAs(charge);
        // The whole amount sits in the fee portion, exactly as LoanDisbursementService creates it.
        assertThat(replacement.getFeeChargesPortion()).isEqualByComparingTo(new BigDecimal("250.00"));
    }

    /**
     * The components come from what the transaction actually pays, not a blanket fee assignment - a penalty charge paid
     * at disbursement must land in the penalty bucket, or the contra and the replacement stop netting per income
     * account.
     */
    @Test
    void thePenaltyPortionOfADisbursementChargePaymentStaysPenalty() {
        final LoanCharge fee = mock(LoanCharge.class);
        lenient().when(fee.isPenaltyCharge()).thenReturn(false);
        final LoanCharge penalty = mock(LoanCharge.class);
        lenient().when(penalty.isPenaltyCharge()).thenReturn(true);

        final LoanChargePaidBy feePaid = mock(LoanChargePaidBy.class);
        lenient().when(feePaid.getLoanCharge()).thenReturn(fee);
        lenient().when(feePaid.getAmount()).thenReturn(new BigDecimal("200.00"));
        final LoanChargePaidBy penaltyPaid = mock(LoanChargePaidBy.class);
        lenient().when(penaltyPaid.getLoanCharge()).thenReturn(penalty);
        lenient().when(penaltyPaid.getAmount()).thenReturn(new BigDecimal("50.00"));

        final LoanTransaction original = transaction(LoanTransactionType.REPAYMENT_AT_DISBURSEMENT, 3L, "EXT-MIX",
                new BigDecimal("250.00"));
        when(original.getLoanChargesPaid()).thenReturn(Set.of(feePaid, penaltyPaid));
        activeLoan(List.of(original));

        service.reprocessLoan(LOAN_ID, command());

        final LoanTransaction replacement = onlyReplacement();
        assertThat(replacement.getFeeChargesPortion()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(replacement.getPenaltyChargesPortion()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(replacement.getAmount()).isEqualByComparingTo(new BigDecimal("250.00"));
    }

    /**
     * The charge-level tax split must follow the disbursement to the new date, the way a real disbursal derives it -
     * otherwise m_loan_charge_tax_details keeps the old-date split while the paid-by rows (which drive the GL) carry
     * the new one, and the two records disagree.
     */
    @Test
    void refreshesTheTaxSplitOfDisbursementChargesAtTheNewDate() {
        final LoanCharge disbursementCharge = mock(LoanCharge.class);
        lenient().when(disbursementCharge.isDueAtDisbursement()).thenReturn(true);
        lenient().when(disbursementCharge.amount()).thenReturn(new BigDecimal("250.00"));
        final LoanCharge laterCharge = mock(LoanCharge.class);
        lenient().when(laterCharge.isDueAtDisbursement()).thenReturn(false);

        final Loan loan = activeLoan(List.of());
        when(loan.getActiveCharges()).thenReturn(Set.of(disbursementCharge, laterCharge));
        when(loanProductRoundingModeService.resolveTaxRoundingMode(10L)).thenReturn(RoundingMode.HALF_DOWN);

        service.reprocessLoan(LOAN_ID, command());

        verify(disbursementCharge).updateLoanChargeTaxDetails(NEW_DATE, new BigDecimal("250.00"), RoundingMode.HALF_DOWN);
        // "Other charges keep it as is": only disbursement-time charges are re-derived.
        verify(laterCharge, never()).updateLoanChargeTaxDetails(any(), any(), any());
    }

    // ----- the dates carried on the loan -----

    @Test
    void movesBothDisbursementDatesOnTheLoanAndOnTheDetailRow() {
        final LoanDisbursementDetails details = mock(LoanDisbursementDetails.class);
        lenient().when(details.principal()).thenReturn(new BigDecimal("12000"));
        final Loan loan = activeLoan(List.of());
        when(loan.getDisbursementDetails()).thenReturn(new ArrayList<>(List.of(details)));

        service.reprocessLoan(LOAN_ID, command());

        verify(loan).setActualDisbursementDate(NEW_DATE);
        verify(loan).setExpectedDisbursementDate(NEW_DATE);
        verify(details).updateActualDisbursementDate(NEW_DATE);
        // Leaving the expected date behind would have the row describing a disbursement expected on one date and made
        // on another, which never happened.
        verify(details).updateExpectedDisbursementDateAndAmount(NEW_DATE, new BigDecimal("12000"));
    }

    /**
     * Interest is computed from {@code interestChargedFromDate} when set. A value that equalled the old disbursement
     * date was derived from it and must follow; one set apart from it on purpose - an interest holiday - must not.
     */
    @Test
    void theInterestAnchorFollowsTheDisbursementOnlyWhenItWasDerivedFromIt() {
        final Loan derived = activeLoan(List.of());
        when(derived.getInterestChargedFromDate()).thenReturn(OLD_DATE);

        final CommandProcessingResult result = service.reprocessLoan(LOAN_ID, command());

        verify(derived).setInterestChargedFromDate(NEW_DATE);
        assertThat(result.getChanges()).containsEntry("interestChargedFromDate", NEW_DATE.toString());
    }

    @Test
    void aDeliberateInterestAnchorIsLeftWhereItIs() {
        final LocalDate holiday = OLD_DATE.plusDays(10);
        final Loan loan = activeLoan(List.of());
        when(loan.getInterestChargedFromDate()).thenReturn(holiday);

        service.reprocessLoan(LOAN_ID, command());

        verify(loan, never()).setInterestChargedFromDate(any());
    }

    // ----- holding the instalment dates still -----

    /**
     * The generator falls back to the disbursement date when no first repayment date is set, so without pinning, every
     * due date would slide by the same offset as the disbursement.
     */
    @Test
    void pinsTheFirstRepaymentDateWhenTheLoanNeverCarriedOne() {
        final Loan loan = activeLoan(List.of());
        when(loan.getExpectedFirstRepaymentOnDate()).thenReturn(null);

        final CommandProcessingResult result = service.reprocessLoan(LOAN_ID, command());

        // In order, not merely both: the regeneration reads the pinned date. Pinned after, the schedule would already
        // have been rebuilt with every due date slid along with the disbursement.
        final InOrder inOrder = inOrder(loan, regenerationService);
        inOrder.verify(loan).setExpectedFirstRepaymentOnDate(FIRST_DUE_DATE);
        inOrder.verify(regenerationService).regenerateAndReplay(loan);
        // Reported under its own key: the expectedFirstRepaymentOnDate *parameter* is still rejected as unimplemented,
        // and the audit record must not read as though it had been accepted.
        assertThat(result.getChanges()).containsEntry(LoanReprocessApiConstants.expectedFirstRepaymentOnDatePinnedResponseParam,
                FIRST_DUE_DATE.toString());
        assertThat(result.getChanges()).doesNotContainKey(LoanReprocessApiConstants.expectedFirstRepaymentOnDateParamName);
    }

    @Test
    void leavesAnExplicitFirstRepaymentDateAlone() {
        final Loan loan = activeLoan(List.of());
        when(loan.getExpectedFirstRepaymentOnDate()).thenReturn(FIRST_DUE_DATE);

        service.reprocessLoan(LOAN_ID, command());

        verify(loan, never()).setExpectedFirstRepaymentOnDate(any());
    }

    // ----- ordering, events and the audit trail -----

    @Test
    void regeneratesTheScheduleAndAnnouncesTheReprocess() {
        final Loan loan = activeLoan(List.of());

        service.reprocessLoan(LOAN_ID, command());

        verify(validator).validate(eq(loan), any());
        verify(regenerationService).regenerateAndReplay(loan);
        verify(businessEventNotifierService).notifyPostBusinessEvent(any(LoanReprocessedBusinessEvent.class));
    }

    @Test
    void attachesTheNoteToTheLoanRatherThanToAReversedTransaction() {
        activeLoan(List.of());

        service.reprocessLoan(LOAN_ID, commandWithNote("corrected per ops ticket 4821"));

        final ArgumentCaptor<Note> captor = ArgumentCaptor.forClass(Note.class);
        verify(noteRepository).save(captor.capture());
        assertThat(captor.getValue().getNote()).isEqualTo("corrected per ops ticket 4821");
    }

    @Test
    void savesNoNoteWhenNoneWasSupplied() {
        activeLoan(List.of());

        service.reprocessLoan(LOAN_ID, command());

        verify(noteRepository, never()).save(any(Note.class));
    }

    /**
     * The audit convention: the date change is recorded as the string the caller supplied, plus the locale and format
     * needed to parse it back. A raw LocalDate would land in m_portfolio_command_source as a [year,month,day] array no
     * audit reader expects.
     */
    @Test
    void reportsTheCorrectedDateBackToTheCallerAsTheSuppliedString() {
        activeLoan(List.of());

        final CommandProcessingResult result = service.reprocessLoan(LOAN_ID, command());

        assertThat(result.getLoanId()).isEqualTo(LOAN_ID);
        assertThat(result.getChanges()).containsEntry(LoanReprocessApiConstants.actualDisbursementDateParamName, "03 June 2026");
        assertThat(result.getChanges()).containsEntry(LoanReprocessApiConstants.dateFormatParamName, "dd MMMM yyyy");
        assertThat(result.getChanges()).containsEntry(LoanReprocessApiConstants.localeParamName, "en");
    }

    // ----- fixtures -----

    private List<LoanTransaction> replacements() {
        final ArgumentCaptor<LoanTransaction> captor = ArgumentCaptor.forClass(LoanTransaction.class);
        verify(loanTransactionRepository, org.mockito.Mockito.atLeast(0)).saveAndFlush(captor.capture());
        // Originals are saved at their own, unchanged, date; only the replacements carry the new one.
        return captor.getAllValues().stream().filter(txn -> NEW_DATE.equals(txn.getTransactionDate())).toList();
    }

    private LoanTransaction onlyReplacement() {
        final List<LoanTransaction> replacements = replacements();
        assertThat(replacements).hasSize(1);
        return replacements.get(0);
    }

    private JsonCommand command() {
        final JsonCommand command = mock(JsonCommand.class);
        lenient().when(command.localDateValueOfParameterNamed(LoanReprocessApiConstants.actualDisbursementDateParamName))
                .thenReturn(NEW_DATE);
        lenient().when(command.stringValueOfParameterNamed(LoanReprocessApiConstants.actualDisbursementDateParamName))
                .thenReturn("03 June 2026");
        lenient().when(command.dateFormat()).thenReturn("dd MMMM yyyy");
        lenient().when(command.locale()).thenReturn("en");
        return command;
    }

    private JsonCommand commandWithNote(final String note) {
        final JsonCommand command = command();
        lenient().when(command.stringValueOfParameterNamed(LoanReprocessApiConstants.noteParamName)).thenReturn(note);
        return command;
    }

    private Loan activeLoan(final List<LoanTransaction> transactions) {
        final LoanProductRelatedDetail detail = mock(LoanProductRelatedDetail.class);
        lenient().when(detail.getLoanScheduleType()).thenReturn(LoanScheduleType.CUMULATIVE);
        final LoanProduct product = mock(LoanProduct.class);
        lenient().when(product.getId()).thenReturn(10L);

        final LoanRepaymentScheduleInstallment installment = mock(LoanRepaymentScheduleInstallment.class);
        lenient().when(installment.getDueDate()).thenReturn(FIRST_DUE_DATE);
        lenient().when(installment.isDownPayment()).thenReturn(false);

        final Loan loan = mock(Loan.class);
        lenient().when(loan.getId()).thenReturn(LOAN_ID);
        lenient().when(loan.getOffice()).thenReturn(mock(Office.class));
        lenient().when(loan.getOfficeId()).thenReturn(1L);
        lenient().when(loan.getClientId()).thenReturn(2L);
        lenient().when(loan.getGroupId()).thenReturn(null);
        lenient().when(loan.getCurrency()).thenReturn(CURRENCY);
        lenient().when(loan.getDisbursementDate()).thenReturn(OLD_DATE);
        lenient().when(loan.getInterestChargedFromDate()).thenReturn(null);
        lenient().when(loan.getExpectedFirstRepaymentOnDate()).thenReturn(FIRST_DUE_DATE);
        lenient().when(loan.getLoanProductRelatedDetail()).thenReturn(detail);
        lenient().when(loan.getLoanProduct()).thenReturn(product);
        lenient().when(loan.getActiveCharges()).thenReturn(Set.of());
        lenient().when(loan.getTotalOverpaidAsMoney()).thenReturn(Money.zero(CURRENCY));
        lenient().when(loan.getLoanTransactions()).thenReturn(new ArrayList<>(transactions));
        lenient().when(loan.getDisbursementDetails()).thenReturn(new ArrayList<>());
        lenient().when(loan.getRepaymentScheduleInstallments()).thenReturn(List.of(installment));

        lenient().when(loanAssembler.assembleFrom(LOAN_ID)).thenReturn(loan);
        lenient().when(loanRepositoryWrapper.saveAndFlush(loan)).thenReturn(loan);
        lenient().when(loanTransactionRepository.saveAndFlush(any(LoanTransaction.class))).thenAnswer(call -> call.getArgument(0));
        lenient().doNothing().when(journalEntryPoster).postJournalEntriesForLoanTransaction(any(), anyBoolean(), anyBoolean());
        return loan;
    }

    private LoanTransaction disbursementTransaction(final Long id, final String externalId) {
        return transaction(LoanTransactionType.DISBURSEMENT, id, externalId, new BigDecimal("12000"));
    }

    private LoanTransaction transaction(final LoanTransactionType type, final Long id, final String externalId, final BigDecimal amount) {
        final LoanTransaction transaction = mock(LoanTransaction.class);
        lenient().when(transaction.getId()).thenReturn(id);
        lenient().when(transaction.isNotReversed()).thenReturn(true);
        lenient().when(transaction.getTypeOf()).thenReturn(type);
        lenient().when(transaction.getTransactionDate()).thenReturn(OLD_DATE);
        lenient().when(transaction.getExternalId()).thenReturn(new ExternalId(externalId));
        lenient().when(transaction.getAmount(CURRENCY)).thenReturn(Money.of(CURRENCY, amount));
        lenient().when(transaction.getPaymentDetail()).thenReturn(null);
        lenient().when(transaction.getLoanChargesPaid()).thenReturn(Set.of());
        return transaction;
    }
}
