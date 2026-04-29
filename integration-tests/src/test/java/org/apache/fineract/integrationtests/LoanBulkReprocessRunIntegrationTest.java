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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.loans.LoanApplicationTestBuilder;
import org.apache.fineract.integrationtests.common.loans.LoanProductTestBuilder;
import org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test for the async loan bulk-reprocess feature. Covers two regression-prone behaviors:
 * <ul>
 * <li>{@code submitAsyncRun} must return promptly — the dedicated {@code JobLauncher} is wired with its own
 * {@code ThreadPoolTaskExecutor}, so the API thread does not block on the entire run.</li>
 * <li>{@code JobExecutionListener.beforeJob}/{@code afterJob} call {@code @Modifying} repository methods, so those
 * methods must carry {@code @Transactional} (or the listener must own the transaction). Without it the listener throws
 * {@code TransactionRequiredException}; the run never reaches a terminal status. The poll-for-FINISHED assertion below
 * catches that regression.</li>
 * </ul>
 */
public class LoanBulkReprocessRunIntegrationTest {

    private static final String BULK_REPROCESS_RUNS_URL = "/fineract-provider/api/v1/loans/reprocess/runs?" + Utils.TENANT_IDENTIFIER;
    private static final long POLL_TIMEOUT_MS = 60_000L;
    private static final long POLL_INTERVAL_MS = 1_000L;
    /** The async submit must return well within this; the run itself may take longer. */
    private static final long SUBMIT_MAX_DURATION_MS = 15_000L;

    private RequestSpecification requestSpec;
    private ResponseSpecification responseSpec;
    private ResponseSpecification submitResponseSpec;
    private LoanTransactionHelper loanTransactionHelper;
    private ClientHelper clientHelper;

    @BeforeEach
    public void setUp() {
        Utils.initializeRESTAssured();
        this.requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        this.requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        this.requestSpec.header("Fineract-Platform-TenantId", "default");
        this.responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
        // submit is async and must return 202 Accepted
        this.submitResponseSpec = new ResponseSpecBuilder().expectStatusCode(202).build();
        this.loanTransactionHelper = new LoanTransactionHelper(this.requestSpec, this.responseSpec);
        this.clientHelper = new ClientHelper(this.requestSpec, this.responseSpec);
    }

    @Test
    public void submittingBulkReprocessReturnsQuicklyAndRunReachesFinished() {
        // 1) Set up a single approved+disbursed loan so the reprocess has something real to do.
        final Integer clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId().intValue();

        final String loanProductJson = new LoanProductTestBuilder().build(null);
        final Integer loanProductId = loanTransactionHelper.getLoanProductId(loanProductJson);

        final String operationDate = "01 January 2024";
        final String loanApplicationJson = new LoanApplicationTestBuilder().withPrincipal("1000").withLoanTermFrequency("1")
                .withLoanTermFrequencyAsMonths().withNumberOfRepayments("1").withRepaymentEveryAfter("1")
                .withRepaymentFrequencyTypeAsMonths().withInterestRatePerPeriod("0").withInterestTypeAsFlatBalance()
                .withAmortizationTypeAsEqualPrincipalPayments().withInterestCalculationPeriodTypeSameAsRepaymentPeriod()
                .withExpectedDisbursementDate(operationDate).withSubmittedOnDate(operationDate).withLoanType("individual")
                .build(clientId.toString(), loanProductId.toString(), null);

        final Integer loanId = loanTransactionHelper.getLoanId(loanApplicationJson);
        loanTransactionHelper.approveLoan(operationDate, "1000", loanId, null);
        loanTransactionHelper.disburseLoanWithNetDisbursalAmount(operationDate, loanId, "1000");

        // 2) Submit the bulk reprocess and time it.
        final Map<String, Object> body = new HashMap<>();
        body.put("loanIds", List.of(loanId));
        body.put("batchSize", 10);
        final String submitJson = new Gson().toJson(body);

        final long submitStart = System.currentTimeMillis();
        final String submitResponse = Utils.performServerPost(requestSpec, submitResponseSpec, BULK_REPROCESS_RUNS_URL, submitJson, null);
        final long submitDurationMs = System.currentTimeMillis() - submitStart;

        assertNotNull(submitResponse, "Bulk reprocess submit must return a body");
        assertTrue(submitDurationMs < SUBMIT_MAX_DURATION_MS,
                "Submit took " + submitDurationMs + " ms — endpoint blocked instead of being async (regression of dedicated JobLauncher)");

        final JsonObject submitObj = JsonParser.parseString(submitResponse).getAsJsonObject();
        assertTrue(submitObj.has("runId"), "Response must contain runId");
        final long runId = submitObj.get("runId").getAsLong();

        // 3) Poll until terminal. The listener-without-transaction bug surfaces here as FAILED with a
        // TransactionRequiredException message — and the @Transactional fix makes this assertion pass.
        final JsonObject finalRun = pollUntilTerminal(runId);
        final String finalStatus = finalRun.get("status").getAsString();
        if (!"FINISHED".equals(finalStatus)) {
            final String errorMessage = finalRun.has("errorMessage") && !finalRun.get("errorMessage").isJsonNull()
                    ? finalRun.get("errorMessage").getAsString()
                    : "<none>";
            fail("Expected run " + runId + " to reach FINISHED, but reached " + finalStatus + " (errorMessage=" + errorMessage + ")");
        }

        assertEquals(1, finalRun.get("totalLoanIds").getAsInt(), "totalLoanIds");
        assertEquals(1, finalRun.get("processedCount").getAsInt(), "processedCount");
        assertEquals(0, finalRun.get("failedCount").getAsInt(), "failedCount");

        // 4) Failures page should be empty.
        final String failuresJson = Utils.performServerGet(requestSpec, responseSpec,
                "/fineract-provider/api/v1/loans/reprocess/runs/" + runId + "/failures?" + Utils.TENANT_IDENTIFIER, null);
        final JsonObject failuresObj = JsonParser.parseString(failuresJson).getAsJsonObject();
        assertEquals(0, failuresObj.get("totalFailed").getAsInt(), "FINISHED runs must report 0 failures");
    }

    private JsonObject pollUntilTerminal(final long runId) {
        final long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        final String runUrl = "/fineract-provider/api/v1/loans/reprocess/runs/" + runId + "?" + Utils.TENANT_IDENTIFIER;
        JsonObject lastSeen = null;
        while (System.currentTimeMillis() < deadline) {
            final String json = Utils.performServerGet(requestSpec, responseSpec, runUrl, null);
            lastSeen = JsonParser.parseString(json).getAsJsonObject();
            final String status = lastSeen.get("status").getAsString();
            if ("FINISHED".equals(status) || "FINISHED_WITH_FAILURES".equals(status) || "FAILED".equals(status)) {
                return lastSeen;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while polling run " + runId);
            }
        }
        fail("Run " + runId + " did not reach a terminal status within " + POLL_TIMEOUT_MS + " ms. Last seen: "
                + (lastSeen == null ? "<null>" : lastSeen.toString()));
        return lastSeen; // unreachable
    }
}
