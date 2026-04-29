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
package org.apache.fineract.portfolio.loanaccount.api;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.serialization.GoogleGsonSerializerHelper;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.loanaccount.data.LoanBulkReprocessFailurePageData;
import org.apache.fineract.portfolio.loanaccount.data.LoanBulkReprocessRequestData;
import org.apache.fineract.portfolio.loanaccount.data.LoanBulkReprocessRunData;
import org.apache.fineract.portfolio.loanaccount.domain.bulkreprocess.LoanBulkReprocessRun;
import org.apache.fineract.portfolio.loanaccount.service.LoanBulkReprocessConstants;
import org.apache.fineract.portfolio.loanaccount.service.LoanBulkReprocessRequestParser;
import org.apache.fineract.portfolio.loanaccount.service.LoanBulkReprocessRunReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.service.LoanBulkReprocessRunWritePlatformService;
import org.springframework.stereotype.Component;

@Path("/v1/loans/reprocess/runs")
@Component
@Tag(name = "Loans", description = "Async bulk replay of loan transactions (reprocess) for a list of loan ids.")
@RequiredArgsConstructor
public class LoanBulkReprocessRunsApiResource {

    private static final String RESOURCE_NAME_FOR_PERMISSIONS = "LOAN";

    private final PlatformSecurityContext context;
    private final LoanBulkReprocessRequestParser requestParser;
    private final LoanBulkReprocessRunWritePlatformService runWritePlatformService;
    private final LoanBulkReprocessRunReadPlatformService runReadPlatformService;
    // Use the shared Gson with Fineract's LocalDateTime/LocalDate adapters; default Gson cannot
    // serialize java.time.LocalDateTime correctly on modern JDKs.
    private final Gson gson = GoogleGsonSerializerHelper.createSimpleGson();

    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Submit an async bulk loan reprocess run", description = "Creates a durable bulk reprocess run and starts processing in the background. "
            + "Use GET /v1/loans/reprocess/runs/{runId} to poll progress and "
            + "GET /v1/loans/reprocess/runs/{runId}/failures to page through failure reasons. " + "Requires LOAN UPDATE permission.")
    @RequestBody(required = true, content = @Content(examples = {
            @ExampleObject(name = "submit", value = "{\"loanIds\": [1, 2, 3], \"batchSize\": 100}") }))
    @ApiResponses({ @ApiResponse(responseCode = "202", description = "Accepted: returns run id and initial status"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "403", description = "Not authorized") })
    public Response submitRun(final String apiRequestBodyAsJson) {
        this.context.authenticatedUser().validateHasUpdatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
        final LoanBulkReprocessRequestData request = this.requestParser.parse(apiRequestBodyAsJson);
        final LoanBulkReprocessRun run = this.runWritePlatformService.submitAsyncRun(request);
        final LoanBulkReprocessRunData data = this.runReadPlatformService.retrieveRun(run.getId());
        return Response.status(Status.ACCEPTED).entity(this.gson.toJson(data)).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("{runId}")
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Get bulk reprocess run status", description = "Returns current status and counters for the run.")
    public String getRun(@PathParam("runId") final Long runId) {
        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        final LoanBulkReprocessRunData data = this.runReadPlatformService.retrieveRun(runId);
        return this.gson.toJson(data);
    }

    @GET
    @Path("{runId}/failures")
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "List failures for a bulk reprocess run", description = "Returns a paginated list of failed loan ids and messages for the given run.")
    public String getFailures(@PathParam("runId") final Long runId, @QueryParam("offset") @DefaultValue("0") final Integer offset,
            @QueryParam("limit") @DefaultValue("200") final Integer limit) {
        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        final LoanBulkReprocessFailurePageData data = this.runReadPlatformService.retrieveFailures(runId, offset != null ? offset : 0,
                limit != null ? limit : LoanBulkReprocessConstants.DEFAULT_FAILURES_PAGE_LIMIT);
        return this.gson.toJson(data);
    }

    @POST
    @Path("{runId}/release-pending")
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Release stuck pending items for a run", description = "Operator recovery endpoint. Marks all PENDING items of the run as FAILED and closes the run "
            + "as FINISHED/FINISHED_WITH_FAILURES. Use when a run is stuck after JVM crash or unexpected termination.")
    public String releasePending(@PathParam("runId") final Long runId) {
        this.context.authenticatedUser().validateHasUpdatePermission(RESOURCE_NAME_FOR_PERMISSIONS);
        this.runWritePlatformService.releasePendingItems(runId);
        // releasePendingItems already wrote a descriptive errorMessage on the run. Re-read and return it as-is —
        // a single source of truth avoids duplicate messaging in the response body.
        final LoanBulkReprocessRunData data = this.runReadPlatformService.retrieveRun(runId);
        return this.gson.toJson(data);
    }
}
