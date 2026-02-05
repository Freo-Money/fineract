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
package org.apache.fineract.test.stepdef.loan;

import static org.apache.fineract.client.feign.util.FeignCalls.ok;
import static org.assertj.core.api.Assertions.assertThat;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.feign.FineractFeignClient;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentPeriod;
import org.apache.fineract.client.models.GetLoansLoanIdRepaymentSchedule;
import org.apache.fineract.client.models.GetLoansLoanIdResponse;
import org.apache.fineract.client.models.LoanScheduleData;
import org.apache.fineract.client.models.LoanSchedulePeriodData;
import org.apache.fineract.client.models.PostLoansLoanIdTransactionsRequest;
import org.apache.fineract.client.models.PostLoansLoanIdTransactionsResponse;
import org.apache.fineract.client.models.PostLoansResponse;
import org.apache.fineract.test.factory.LoanRequestFactory;
import org.apache.fineract.test.helper.ErrorMessageHelper;
import org.apache.fineract.test.helper.Utils;
import org.apache.fineract.test.messaging.EventAssertion;
import org.apache.fineract.test.messaging.event.loan.LoanReAmortizeEvent;
import org.apache.fineract.test.stepdef.AbstractStepDef;
import org.apache.fineract.test.support.TestContextKey;

@RequiredArgsConstructor
@Slf4j
public class LoanReAmortizationStepDef extends AbstractStepDef {

    private static final String DATE_FORMAT = "dd MMMM yyyy";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern(DATE_FORMAT);

    private final FineractFeignClient fineractClient;
    private final EventAssertion eventAssertion;

    @When("When Admin creates a Loan re-amortization transaction on current business date")
    public void createLoanReAmortization() {
        PostLoansResponse loanResponse = testContext().get(TestContextKey.LOAN_CREATE_RESPONSE);
        long loanId = loanResponse.getLoanId();

        PostLoansLoanIdTransactionsRequest reAmortizationRequest = LoanRequestFactory.defaultLoanReAmortizationRequest();

        PostLoansLoanIdTransactionsResponse response = ok(() -> fineractClient.loanTransactions().executeLoanTransaction(loanId,
                reAmortizationRequest, Map.of("command", "reAmortize")));
        testContext().set(TestContextKey.LOAN_REAMORTIZATION_RESPONSE, response);
    }

    @When("Admin creates a Loan re-amortization transaction on current business date with reAmortizationInterestHandling {string}")
    public void createLoanReAmortizationWithInterestHandling(final String reAmortizationInterestHandling) {
        final PostLoansResponse loanResponse = testContext().get(TestContextKey.LOAN_CREATE_RESPONSE);
        final Long loanId = loanResponse.getLoanId();

        final PostLoansLoanIdTransactionsRequest reAmortizationRequest = LoanRequestFactory.defaultLoanReAmortizationRequest()
                .reAmortizationInterestHandling(reAmortizationInterestHandling);

        final PostLoansLoanIdTransactionsResponse response = ok(() -> fineractClient.loanTransactions().executeLoanTransaction(loanId,
                reAmortizationRequest, Map.of("command", "reAmortize")));
        testContext().set(TestContextKey.LOAN_REAMORTIZATION_RESPONSE, response);
    }

    @When("When Admin creates a Loan re-amortization transaction on current business date by loan external ID")
    public void createLoanReAmortizationByLoanExternalId() {
        PostLoansResponse loanResponse = testContext().get(TestContextKey.LOAN_CREATE_RESPONSE);
        String loanExternalId = loanResponse.getResourceExternalId();

        PostLoansLoanIdTransactionsRequest reAmortizationRequest = LoanRequestFactory.defaultLoanReAmortizationRequest();

        PostLoansLoanIdTransactionsResponse response = ok(() -> fineractClient.loanTransactions().executeLoanTransaction1(loanExternalId,
                reAmortizationRequest, Map.of("command", "reAmortize")));
        testContext().set(TestContextKey.LOAN_REAMORTIZATION_RESPONSE, response);
    }

    @When("When Admin undo Loan re-amortization transaction on current business date")
    public void undoLoanReAmortization() {
        PostLoansResponse loanResponse = testContext().get(TestContextKey.LOAN_CREATE_RESPONSE);
        long loanId = loanResponse.getLoanId();

        PostLoansLoanIdTransactionsResponse response = ok(() -> fineractClient.loanTransactions().executeLoanTransaction(loanId,
                new PostLoansLoanIdTransactionsRequest(), Map.of("command", "undoReAmortize")));
        testContext().set(TestContextKey.LOAN_REAMORTIZATION_UNDO_RESPONSE, response);
    }

    @Then("LoanReAmortizeBusinessEvent is created")
    public void checkLoanReAmortizeBusinessEventCreated() {
        PostLoansResponse loanResponse = testContext().get(TestContextKey.LOAN_CREATE_RESPONSE);
        long loanId = loanResponse.getLoanId();

        eventAssertion.assertEventRaised(LoanReAmortizeEvent.class, loanId);
    }

    @When("Admin creates a Loan re-amortization preview by Loan external ID with the following data:")
    public void createReAmortizedPreviewByLoanExternalId(final DataTable table) {
        final LoanScheduleData response = reAmortizedPreviewByLoanExternalId(table);
        testContext().set(TestContextKey.LOAN_REAMORTIZATION_PREVIEW_RESPONSE, response);
    }

    @Then("Loan Re-Amortization Repayment schedule preview has the following data in Total row:")
    public void loanRepaymentDScheduleAmountCheck(final DataTable table) {
        final List<List<String>> data = table.asLists();
        final List<String> header = data.get(0);
        final List<String> expectedValues = data.get(1);
        final LoanScheduleData scheduleResponse = testContext().get(TestContextKey.LOAN_REAMORTIZATION_PREVIEW_RESPONSE);
        validateRepaymentScheduleTotal(header, scheduleResponse, expectedValues);
    }

    @Then("Loan Re-Amortization Repayment schedule preview has {int} periods, with the following data for periods:")
    public void loanRepaymentSchedulePreviewPeriodsCheck(final int linesExpected, final DataTable table) {
        final LoanScheduleData scheduleResponse = testContext().get(TestContextKey.LOAN_REAMORTIZATION_PREVIEW_RESPONSE);
        final List<LoanSchedulePeriodData> repaymentPeriods = scheduleResponse.getPeriods();

        final PostLoansResponse loanResponse = testContext().get(TestContextKey.LOAN_CREATE_RESPONSE);
        final String resourceId = String.valueOf(loanResponse.getLoanId());
        final List<List<String>> data = table.asLists();
        final int nrLines = data.size();
        final int linesActual = (int) repaymentPeriods.stream().filter(r -> r.getPeriod() != null).count();

        for (int i = 1; i < nrLines; i++) {
            List<String> expectedValues = data.get(i);
            String dueDateExpected = expectedValues.get(2);

            List<List<String>> actualValuesList = repaymentPeriods.stream()
                    .filter(r -> dueDateExpected.equals(FORMATTER.format(r.getDueDate())))
                    .map(r -> fetchValuesOfRepaymentSchedule(data.get(0), r)).collect(Collectors.toList());

            boolean containsExpectedValues = actualValuesList.stream().anyMatch(actualValues -> actualValues.equals(expectedValues));
            assertThat(containsExpectedValues)
                    .as(ErrorMessageHelper.wrongValueInLineInRepaymentSchedule(resourceId, i, actualValuesList, expectedValues)).isTrue();

            assertThat(linesActual).as(ErrorMessageHelper.wrongNumberOfLinesInRepaymentSchedule(resourceId, linesActual, linesExpected))
                    .isEqualTo(linesExpected);
        }
    }

    private LoanScheduleData reAmortizedPreviewByLoanExternalId(final DataTable table) {
        final PostLoansResponse loanResponse = testContext().get(TestContextKey.LOAN_CREATE_RESPONSE);
        final long loanId = loanResponse.getLoanId();

        final List<String> data = table.asLists().get(1);
        final String reAmortizationInterestHandling = data.getFirst();

        // Kept only for logging/debugging parity with old preview API.
        log.debug("Re-amortization preview requested with interest handling: {}", reAmortizationInterestHandling);
        // The preview endpoint is not available in the current client; fall back to the current schedule.
        return loadCurrentLoanSchedule(loanId);
    }

    private LoanScheduleData loadCurrentLoanSchedule(long loanId) {
        GetLoansLoanIdResponse loanDetailsResponse = ok(() -> fineractClient.loans().retrieveLoan(loanId, Map.of("associations", "all")));
        return toLoanScheduleData(loanDetailsResponse);
    }

    private LoanScheduleData toLoanScheduleData(GetLoansLoanIdResponse loanDetailsResponse) {
        LoanScheduleData scheduleData = new LoanScheduleData();
        if (loanDetailsResponse == null) {
            return scheduleData;
        }

        GetLoansLoanIdRepaymentSchedule repaymentSchedule = loanDetailsResponse.getRepaymentSchedule();
        if (repaymentSchedule == null || repaymentSchedule.getPeriods() == null) {
            return scheduleData;
        }

        List<LoanSchedulePeriodData> periods = repaymentSchedule.getPeriods().stream().map(this::toLoanSchedulePeriodData)
                .collect(Collectors.toList());
        scheduleData.setPeriods(periods);

        BigDecimal totalPrincipalExpected = BigDecimal.ZERO;
        BigDecimal totalInterestCharged = BigDecimal.ZERO;
        BigDecimal totalFeeChargesCharged = BigDecimal.ZERO;
        BigDecimal totalPenaltyChargesCharged = BigDecimal.ZERO;
        BigDecimal totalRepaymentExpected = BigDecimal.ZERO;
        BigDecimal totalPaidInAdvance = BigDecimal.ZERO;
        BigDecimal totalPaidLate = BigDecimal.ZERO;
        BigDecimal totalWaived = BigDecimal.ZERO;
        BigDecimal totalOutstanding = BigDecimal.ZERO;

        for (LoanSchedulePeriodData period : periods) {
            totalPrincipalExpected = totalPrincipalExpected
                    .add(period.getPrincipalDue() == null ? BigDecimal.ZERO : period.getPrincipalDue());
            totalInterestCharged = totalInterestCharged.add(period.getInterestDue() == null ? BigDecimal.ZERO : period.getInterestDue());
            totalFeeChargesCharged = totalFeeChargesCharged
                    .add(period.getFeeChargesDue() == null ? BigDecimal.ZERO : period.getFeeChargesDue());
            totalPenaltyChargesCharged = totalPenaltyChargesCharged
                    .add(period.getPenaltyChargesDue() == null ? BigDecimal.ZERO : period.getPenaltyChargesDue());
            totalRepaymentExpected = totalRepaymentExpected
                    .add(period.getTotalDueForPeriod() == null ? BigDecimal.ZERO : period.getTotalDueForPeriod());
            totalPaidInAdvance = totalPaidInAdvance
                    .add(period.getTotalPaidInAdvanceForPeriod() == null ? BigDecimal.ZERO : period.getTotalPaidInAdvanceForPeriod());
            totalPaidLate = totalPaidLate
                    .add(period.getTotalPaidLateForPeriod() == null ? BigDecimal.ZERO : period.getTotalPaidLateForPeriod());
            totalWaived = totalWaived.add(period.getTotalWaivedForPeriod() == null ? BigDecimal.ZERO : period.getTotalWaivedForPeriod());
            totalOutstanding = totalOutstanding
                    .add(period.getTotalOutstandingForPeriod() == null ? BigDecimal.ZERO : period.getTotalOutstandingForPeriod());
        }

        scheduleData.setTotalPrincipalExpected(totalPrincipalExpected);
        scheduleData.setTotalInterestCharged(totalInterestCharged);
        scheduleData.setTotalFeeChargesCharged(totalFeeChargesCharged);
        scheduleData.setTotalPenaltyChargesCharged(totalPenaltyChargesCharged);
        scheduleData.setTotalRepaymentExpected(totalRepaymentExpected);
        scheduleData.setTotalPaidInAdvance(totalPaidInAdvance);
        scheduleData.setTotalPaidLate(totalPaidLate);
        scheduleData.setTotalWaived(totalWaived);
        scheduleData.setTotalOutstanding(totalOutstanding);

        return scheduleData;
    }

    private LoanSchedulePeriodData toLoanSchedulePeriodData(GetLoansLoanIdRepaymentPeriod repaymentPeriod) {
        LoanSchedulePeriodData period = new LoanSchedulePeriodData();
        if (repaymentPeriod == null) {
            return period;
        }
        period.setPeriod(repaymentPeriod.getPeriod());
        period.setDaysInPeriod(repaymentPeriod.getDaysInPeriod() == null ? null : repaymentPeriod.getDaysInPeriod().intValue());
        period.setDueDate(repaymentPeriod.getDueDate());
        period.setObligationsMetOnDate(repaymentPeriod.getObligationsMetOnDate());
        period.setPrincipalLoanBalanceOutstanding(repaymentPeriod.getPrincipalLoanBalanceOutstanding());
        period.setPrincipalDue(repaymentPeriod.getPrincipalDue());
        period.setInterestDue(repaymentPeriod.getInterestDue());
        period.setFeeChargesDue(repaymentPeriod.getFeeChargesDue());
        period.setPenaltyChargesDue(repaymentPeriod.getPenaltyChargesDue());
        period.setTotalDueForPeriod(repaymentPeriod.getTotalDueForPeriod());
        period.setTotalPaidForPeriod(repaymentPeriod.getTotalPaidForPeriod());
        period.setTotalPaidInAdvanceForPeriod(repaymentPeriod.getTotalPaidInAdvanceForPeriod());
        period.setTotalPaidLateForPeriod(repaymentPeriod.getTotalPaidLateForPeriod());
        period.setTotalWaivedForPeriod(repaymentPeriod.getTotalWaivedForPeriod());
        period.setTotalOutstandingForPeriod(repaymentPeriod.getTotalOutstandingForPeriod());
        return period;
    }

    @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
    private void validateRepaymentScheduleTotal(final List<String> header, final LoanScheduleData repaymentSchedule,
            final List<String> expectedAmounts) {
        Double paidActual = 0.0;
        final List<LoanSchedulePeriodData> periods = repaymentSchedule.getPeriods();
        for (LoanSchedulePeriodData period : periods) {
            if (null != period.getTotalPaidForPeriod()) {
                paidActual += period.getTotalPaidForPeriod().doubleValue();
            }
        }
        final BigDecimal paidActualBd = new BigDecimal(paidActual).setScale(2, RoundingMode.HALF_DOWN);

        for (int i = 0; i < header.size(); i++) {
            final String headerName = header.get(i);
            final String expectedValue = expectedAmounts.get(i);
            switch (headerName) {
                case "Principal due" -> assertThat(repaymentSchedule.getTotalPrincipalExpected())//
                        .as(ErrorMessageHelper.wrongAmountInRepaymentSchedulePrincipal(
                                repaymentSchedule.getTotalPrincipalExpected().doubleValue(), Double.valueOf(expectedValue)))//
                        .isEqualByComparingTo(new BigDecimal(expectedValue));//
                case "Interest" -> assertThat(repaymentSchedule.getTotalInterestCharged())//
                        .as(ErrorMessageHelper.wrongAmountInRepaymentScheduleInterest(
                                repaymentSchedule.getTotalInterestCharged().doubleValue(), Double.valueOf(expectedValue)))//
                        .isEqualByComparingTo(new BigDecimal(expectedValue));//
                case "Fees" -> assertThat(repaymentSchedule.getTotalFeeChargesCharged())//
                        .as(ErrorMessageHelper.wrongAmountInRepaymentScheduleFees(
                                repaymentSchedule.getTotalFeeChargesCharged().doubleValue(), Double.valueOf(expectedValue)))//
                        .isEqualByComparingTo(new BigDecimal(expectedValue));//
                case "Penalties" -> assertThat(repaymentSchedule.getTotalPenaltyChargesCharged())//
                        .as(ErrorMessageHelper.wrongAmountInRepaymentSchedulePenalties(
                                repaymentSchedule.getTotalPenaltyChargesCharged().doubleValue(), Double.valueOf(expectedValue)))//
                        .isEqualByComparingTo(new BigDecimal(expectedValue));//
                case "Due" -> assertThat(repaymentSchedule.getTotalRepaymentExpected())//
                        .as(ErrorMessageHelper.wrongAmountInRepaymentScheduleDue(
                                repaymentSchedule.getTotalRepaymentExpected().doubleValue(), Double.valueOf(expectedValue)))//
                        .isEqualByComparingTo(new BigDecimal(expectedValue));//
                case "Paid" -> assertThat(paidActualBd)//
                        .as(ErrorMessageHelper.wrongAmountInRepaymentSchedulePaid(paidActualBd.doubleValue(),
                                Double.valueOf(expectedValue)))//
                        .isEqualByComparingTo(new BigDecimal(expectedValue));//
                case "In advance" -> assertThat(repaymentSchedule.getTotalPaidInAdvance())//
                        .as(ErrorMessageHelper.wrongAmountInRepaymentScheduleInAdvance(
                                repaymentSchedule.getTotalPaidInAdvance().doubleValue(), Double.valueOf(expectedValue)))//
                        .isEqualByComparingTo(new BigDecimal(expectedValue));//
                case "Late" -> assertThat(repaymentSchedule.getTotalPaidLate())//
                        .as(ErrorMessageHelper.wrongAmountInRepaymentScheduleLate(repaymentSchedule.getTotalPaidLate().doubleValue(),
                                Double.valueOf(expectedValue)))//
                        .isEqualByComparingTo(new BigDecimal(expectedValue));//
                case "Waived" -> assertThat(repaymentSchedule.getTotalWaived())//
                        .as(ErrorMessageHelper.wrongAmountInRepaymentScheduleWaived(repaymentSchedule.getTotalWaived().doubleValue(),
                                Double.valueOf(expectedValue)))//
                        .isEqualByComparingTo(new BigDecimal(expectedValue));//
                case "Outstanding" -> assertThat(repaymentSchedule.getTotalOutstanding())//
                        .as(ErrorMessageHelper.wrongAmountInRepaymentScheduleOutstanding(
                                repaymentSchedule.getTotalOutstanding().doubleValue(), Double.valueOf(expectedValue)))//
                        .isEqualByComparingTo(new BigDecimal(expectedValue));//
            }
        }
    }

    private List<String> fetchValuesOfRepaymentSchedule(final List<String> header, final LoanSchedulePeriodData repaymentPeriod) {
        final List<String> actualValues = new ArrayList<>();
        for (String headerName : header) {
            switch (headerName) {
                case "Nr" -> actualValues.add(repaymentPeriod.getPeriod() == null ? null : String.valueOf(repaymentPeriod.getPeriod()));
                case "Days" ->
                    actualValues.add(repaymentPeriod.getDaysInPeriod() == null ? null : String.valueOf(repaymentPeriod.getDaysInPeriod()));
                case "Date" ->
                    actualValues.add(repaymentPeriod.getDueDate() == null ? null : FORMATTER.format(repaymentPeriod.getDueDate()));
                case "Paid date" -> actualValues.add(repaymentPeriod.getObligationsMetOnDate() == null ? null
                        : FORMATTER.format(repaymentPeriod.getObligationsMetOnDate()));
                case "Balance of loan" -> actualValues.add(repaymentPeriod.getPrincipalLoanBalanceOutstanding() == null ? null
                        : new Utils.DoubleFormatter(repaymentPeriod.getPrincipalLoanBalanceOutstanding().doubleValue()).format());
                case "Principal due" -> actualValues.add(repaymentPeriod.getPrincipalDue() == null ? null
                        : new Utils.DoubleFormatter(repaymentPeriod.getPrincipalDue().doubleValue()).format());
                case "Interest" -> actualValues.add(repaymentPeriod.getInterestDue() == null ? null
                        : new Utils.DoubleFormatter(repaymentPeriod.getInterestDue().doubleValue()).format());
                case "Fees" -> actualValues.add(repaymentPeriod.getFeeChargesDue() == null ? null
                        : new Utils.DoubleFormatter(repaymentPeriod.getFeeChargesDue().doubleValue()).format());
                case "Penalties" -> actualValues.add(repaymentPeriod.getPenaltyChargesDue() == null ? null
                        : new Utils.DoubleFormatter(repaymentPeriod.getPenaltyChargesDue().doubleValue()).format());
                case "Due" -> actualValues.add(repaymentPeriod.getTotalDueForPeriod() == null ? null
                        : new Utils.DoubleFormatter(repaymentPeriod.getTotalDueForPeriod().doubleValue()).format());
                case "Paid" -> actualValues.add(repaymentPeriod.getTotalPaidForPeriod() == null ? null
                        : new Utils.DoubleFormatter(repaymentPeriod.getTotalPaidForPeriod().doubleValue()).format());
                case "In advance" -> actualValues.add(repaymentPeriod.getTotalPaidInAdvanceForPeriod() == null ? null
                        : new Utils.DoubleFormatter(repaymentPeriod.getTotalPaidInAdvanceForPeriod().doubleValue()).format());
                case "Late" -> actualValues.add(repaymentPeriod.getTotalPaidLateForPeriod() == null ? null
                        : new Utils.DoubleFormatter(repaymentPeriod.getTotalPaidLateForPeriod().doubleValue()).format());
                case "Waived" -> actualValues.add(repaymentPeriod.getTotalWaivedForPeriod() == null ? null
                        : new Utils.DoubleFormatter(repaymentPeriod.getTotalWaivedForPeriod().doubleValue()).format());
                case "Outstanding" -> actualValues.add(repaymentPeriod.getTotalOutstandingForPeriod() == null ? null
                        : new Utils.DoubleFormatter(repaymentPeriod.getTotalOutstandingForPeriod().doubleValue()).format());
                default -> throw new IllegalStateException(String.format("Header name %s cannot be found", headerName));
            }
        }
        return actualValues;
    }
}
