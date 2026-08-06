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
package org.apache.fineract.integrationtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.PostLoanProductsResponse;
import org.apache.fineract.client.models.PostLoansLoanIdRequest;
import org.apache.fineract.client.models.PostLoansLoanIdResponse;
import org.apache.fineract.client.util.CallFailedRuntimeException;
import org.apache.fineract.client.util.Calls;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.FineractClientHelper;
import org.junit.jupiter.api.Test;

/**
 * End-to-end cover for {@code POST /loans/{loanId}?command=reprocessLoan} moving the disbursement date.
 * <p>
 * The unit tests pin the parameter mutation and the replay wiring in isolation. What only a running system shows is
 * what spans them: that the instalment due dates hold still while the disbursement moves, and that the general ledger
 * is left with a contra on the old date and a fresh pair on the new one rather than two live disbursements.
 */
public class LoanReprocessDisbursementDateTest extends BaseLoanIntegrationTest {

    private static final String ORIGINAL_DISBURSEMENT_DATE = "05 June 2026";
    private static final String CORRECTED_DISBURSEMENT_DATE = "03 June 2026";
    private static final LocalDate CORRECTED_DISBURSEMENT = LocalDate.of(2026, 6, 3);
    private static final LocalDate EXPECTED_FIRST_DUE_DATE = LocalDate.of(2026, 7, 5);
    private static final Double PRINCIPAL = 1000.0;

    /**
     * The whole point of the correction: inception moves, the agreed instalment dates do not. Only the first period's
     * length - and with it its interest - is genuinely in question.
     */
    @Test
    public void movingTheDisbursementEarlierHoldsTheInstalmentDueDates() {
        runAt(ORIGINAL_DISBURSEMENT_DATE, () -> {
            final Long loanId = disbursedLoan();

            final GetLoansLoanIdResponse before = loanTransactionHelper.getLoanDetails(loanId);
            assertNotNull(before.getRepaymentSchedule());
            final LocalDate dueDateBefore = before.getRepaymentSchedule().getPeriods().get(1).getDueDate();
            assertEquals(EXPECTED_FIRST_DUE_DATE, dueDateBefore);

            reprocessDisbursementDate(loanId, CORRECTED_DISBURSEMENT_DATE, "moved per ops ticket 4821");

            final GetLoansLoanIdResponse after = loanTransactionHelper.getLoanDetails(loanId);
            assertEquals(CORRECTED_DISBURSEMENT, after.getTimeline().getActualDisbursementDate());
            assertNotNull(after.getRepaymentSchedule());
            assertEquals(dueDateBefore, after.getRepaymentSchedule().getPeriods().get(1).getDueDate(),
                    "the instalment the customer was told about must not move with the disbursement");
        });
    }

    /**
     * Editing the date in place would strand the journal entries already posted at the old date, and re-posting a
     * non-reversed transaction adds a second set rather than replacing the first. Reversing emits the contra at the old
     * date and the replacement posts a fresh pair at the new one.
     */
    @Test
    public void theOldDisbursementIsReversedAndReplacedRatherThanEdited() {
        runAt(ORIGINAL_DISBURSEMENT_DATE, () -> {
            final Long loanId = disbursedLoan();

            reprocessDisbursementDate(loanId, CORRECTED_DISBURSEMENT_DATE, null);

            final GetLoansLoanIdResponse after = loanTransactionHelper.getLoanDetails(loanId);
            assertNotNull(after.getTransactions());

            final long liveDisbursements = after.getTransactions().stream() //
                    .filter(txn -> Boolean.TRUE.equals(txn.getType().getDisbursement())) //
                    .filter(txn -> !Boolean.TRUE.equals(txn.getManuallyReversed())) //
                    .count();
            assertEquals(1L, liveDisbursements, "exactly one disbursement should be live after the correction");

            final boolean liveOneCarriesTheNewDate = after.getTransactions().stream() //
                    .filter(txn -> Boolean.TRUE.equals(txn.getType().getDisbursement())) //
                    .filter(txn -> !Boolean.TRUE.equals(txn.getManuallyReversed())) //
                    .anyMatch(txn -> CORRECTED_DISBURSEMENT.equals(txn.getDate()));
            assertTrue(liveOneCarriesTheNewDate, "the live disbursement should carry the corrected date");

            // The original pair, its contra, and the replacement pair - six entries, net effect of one disbursement.
            verifyJournalEntries(loanId, //
                    debit(loansReceivableAccount, PRINCIPAL), credit(fundSource, PRINCIPAL), //
                    credit(loansReceivableAccount, PRINCIPAL), debit(fundSource, PRINCIPAL), //
                    debit(loansReceivableAccount, PRINCIPAL), credit(fundSource, PRINCIPAL));
        });
    }

    /**
     * Refused rather than silently succeeding: re-posting the ledger to arrive where the loan already is would be a
     * correction that corrects nothing, and the caller would have no way to tell.
     */
    @Test
    public void refusesACorrectionToTheDateTheLoanAlreadyCarries() {
        runAt(ORIGINAL_DISBURSEMENT_DATE, () -> {
            final Long loanId = disbursedLoan();

            final CallFailedRuntimeException failure = assertThrows(CallFailedRuntimeException.class,
                    () -> reprocessDisbursementDate(loanId, ORIGINAL_DISBURSEMENT_DATE, null));

            assertTrue(failure.getMessage().contains("disbursement.date.unchanged"), "unexpected failure: " + failure.getMessage());
        });
    }

    /**
     * A disbursement on or after the first instalment's due date would ask the generator for a first period of zero or
     * negative length, since that due date is held still.
     * <p>
     * Run at a business date past the first due date on purpose: with the business date still at disbursement, the
     * offending correction date would be in the future and the future-date rule would fire first, leaving this rule
     * unexercised.
     */
    @Test
    public void refusesACorrectionPastTheFirstInstalmentDueDate() {
        final AtomicReference<Long> loanId = new AtomicReference<>();
        runAt(ORIGINAL_DISBURSEMENT_DATE, () -> loanId.set(disbursedLoan()));

        runAt("10 July 2026", () -> {
            final CallFailedRuntimeException failure = assertThrows(CallFailedRuntimeException.class,
                    () -> reprocessDisbursementDate(loanId.get(), "06 July 2026", null));

            assertTrue(failure.getMessage().contains("not.before.first.installment"), "unexpected failure: " + failure.getMessage());
        });
    }

    // ----- fixtures -----

    private Long disbursedLoan() {
        final Long clientId = ClientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
        final PostLoanProductsResponse product = loanProductHelper.createLoanProduct(//
                createOnePeriod30DaysLongNoInterestPeriodicAccrualProduct()//
                        // Single disbursal: the correction rewrites the disbursement event, which has one meaning only
                        // when there is one of them.
                        .multiDisburseLoan(false)//
                        .disallowExpectedDisbursements(false)//
                        .maxTrancheCount(null));
        final Long loanId = applyAndApproveLoan(clientId, product.getResourceId(), ORIGINAL_DISBURSEMENT_DATE, PRINCIPAL, 1);
        disburseLoan(loanId, BigDecimal.valueOf(PRINCIPAL), ORIGINAL_DISBURSEMENT_DATE);
        return loanId;
    }

    private PostLoansLoanIdResponse reprocessDisbursementDate(final Long loanId, final String correctedDate, final String note) {
        final PostLoansLoanIdRequest request = new PostLoansLoanIdRequest()//
                .actualDisbursementDate(correctedDate)//
                .dateFormat(DATETIME_PATTERN)//
                .locale("en");
        if (note != null) {
            request.note(note);
        }
        return Calls.ok(FineractClientHelper.getFineractClient().loans.stateTransitions(loanId, request, "reprocessLoan"));
    }
}
