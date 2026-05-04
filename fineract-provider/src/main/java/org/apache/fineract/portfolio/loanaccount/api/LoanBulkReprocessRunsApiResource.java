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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.UriInfo;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.loanaccount.data.LoanBulkReprocessFailurePageData;
import org.apache.fineract.portfolio.loanaccount.data.LoanBulkReprocessRunData;
import org.apache.fineract.portfolio.loanaccount.service.LoanBulkReprocessConstants;
import org.apache.fineract.portfolio.loanaccount.service.LoanBulkReprocessRunReadPlatformService;
import org.springframework.stereotype.Component;

@Path("/v1/loans/reprocess/runs")
@Component
@Tag(name = "Loans", description = "Async bulk replay of loan transactions (reprocess) for a list of loan ids.")
@RequiredArgsConstructor
public class LoanBulkReprocessRunsApiResource {

    private static final String RESOURCE_NAME_FOR_PERMISSIONS = "LOAN";

    private final PlatformSecurityContext context;
    private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
    private final LoanBulkReprocessRunReadPlatformService runReadPlatformService;
    private final DefaultToApiJsonSerializer<LoanBulkReprocessRunData> runSerializer;
    private final DefaultToApiJsonSerializer<LoanBulkReprocessFailurePageData> failuresSerializer;
    private final ApiRequestParameterHelper apiRequestParameterHelper;

    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Submit an async bulk loan reprocess run", description = "Creates a durable bulk reprocess run and starts processing in the background. "
            + "Use GET /v1/loans/reprocess/runs/{runId} to poll progress and "
            + "GET /v1/loans/reprocess/runs/{runId}/failures to page through failure reasons. "
            + "Requires BULKREPROCESS_LOAN permission. Routed through CommandSource for audit + idempotency "
            + "(supports the standard Idempotency-Key header).")
    @RequestBody(required = true, content = @Content(examples = {
            @ExampleObject(name = "submit", value = "{\"loanIds\": [1, 2, 3], \"batchSize\": 100}") }))
    @ApiResponses({ @ApiResponse(responseCode = "202", description = "Accepted: returns run id and initial status"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "403", description = "Not authorized") })
    public Response submitRun(@Context final UriInfo uriInfo, final String apiRequestBodyAsJson) {
        final CommandWrapper commandRequest = new CommandWrapperBuilder().submitBulkLoanReprocess().withJson(apiRequestBodyAsJson).build();
        final CommandProcessingResult result = this.commandsSourceWritePlatformService.logCommandSource(commandRequest);
        final LoanBulkReprocessRunData data = this.runReadPlatformService.retrieveRun(result.getResourceId());
        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return Response.status(Status.ACCEPTED).entity(this.runSerializer.serialize(settings, data)).type(MediaType.APPLICATION_JSON)
                .build();
    }

    @GET
    @Path("{runId}")
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Get bulk reprocess run status", description = "Returns current status and counters for the run.")
    public String getRun(@Context final UriInfo uriInfo, @PathParam("runId") final Long runId) {
        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        final LoanBulkReprocessRunData data = this.runReadPlatformService.retrieveRun(runId);
        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.runSerializer.serialize(settings, data);
    }

    @GET
    @Path("{runId}/failures")
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "List failures for a bulk reprocess run", description = "Returns a paginated list of failed loan ids and messages for the given run.")
    public String getFailures(@Context final UriInfo uriInfo, @PathParam("runId") final Long runId,
            @QueryParam("offset") @DefaultValue("0") final Integer offset, @QueryParam("limit") @DefaultValue("200") final Integer limit) {
        this.context.authenticatedUser().validateHasReadPermission(RESOURCE_NAME_FOR_PERMISSIONS);
        final LoanBulkReprocessFailurePageData data = this.runReadPlatformService.retrieveFailures(runId, offset != null ? offset : 0,
                limit != null ? limit : LoanBulkReprocessConstants.DEFAULT_FAILURES_PAGE_LIMIT);
        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.failuresSerializer.serialize(settings, data);
    }

    @POST
    @Path("{runId}/release-pending")
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Release stuck pending items for a run", description = "Operator recovery endpoint. Marks all PENDING items of the run as FAILED and closes the run "
            + "as FINISHED/FINISHED_WITH_FAILURES. Use when a run is stuck after JVM crash or unexpected termination. "
            + "Requires BULKREPROCESS_RELEASE_LOAN permission. Routed through CommandSource for audit + idempotency "
            + "(supports the standard Idempotency-Key header).")
    public String releasePending(@Context final UriInfo uriInfo, @PathParam("runId") final Long runId) {
        final CommandWrapper commandRequest = new CommandWrapperBuilder().releaseBulkLoanReprocessPending(runId).withNoJsonBody().build();
        this.commandsSourceWritePlatformService.logCommandSource(commandRequest);
        final LoanBulkReprocessRunData data = this.runReadPlatformService.retrieveRun(runId);
        final ApiRequestJsonSerializationSettings settings = this.apiRequestParameterHelper.process(uriInfo.getQueryParameters());
        return this.runSerializer.serialize(settings, data);
    }
}
