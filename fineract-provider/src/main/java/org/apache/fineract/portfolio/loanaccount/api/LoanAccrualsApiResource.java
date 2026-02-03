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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.portfolio.loanaccount.service.LoanReadPlatformService;
import org.springframework.stereotype.Component;

@Path("/v1/loans")
@Component
@Tag(name = "Loan Accruals", description = "Post periodic accruals for a single loan up to a given date (same logic as runaccruals / COB for that loan).")
@RequiredArgsConstructor
public class LoanAccrualsApiResource {

    private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;
    private final DefaultToApiJsonSerializer<CommandProcessingResult> toApiJsonSerializer;
    private final LoanReadPlatformService loanReadPlatformService;

    @POST
    @Path("{loanId}/accruals/post-till-date")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Post accruals till date for a single loan", description = "Posts periodic accrual accounting entries for the given loan up to the specified tillDate. "
            + "Same logic as the batch API POST /runaccruals but for one loan. The loan product must have periodic accrual accounting enabled. tillDate must not be in the future.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = LoanAccrualsApiResourceSwagger.PostLoansLoanIdAccrualsPostTillDateRequest.class)))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = LoanAccrualsApiResourceSwagger.PostLoansLoanIdAccrualsPostTillDateResponse.class))) })
    public String postAccrualsTillDate(@PathParam("loanId") @Parameter(description = "loanId") final Long loanId,
            @Parameter(hidden = true) final String apiRequestBodyAsJson) {
        return postAccrualsTillDateInternal(loanId, apiRequestBodyAsJson);
    }

    @POST
    @Path("external-id/{loanExternalId}/accruals/post-till-date")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Post accruals till date for a single loan (by external ID)", description = "Same as POST /loans/{loanId}/accruals/post-till-date but uses loan external ID.")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = LoanAccrualsApiResourceSwagger.PostLoansLoanIdAccrualsPostTillDateRequest.class)))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = LoanAccrualsApiResourceSwagger.PostLoansLoanIdAccrualsPostTillDateResponse.class))) })
    public String postAccrualsTillDate(@PathParam("loanExternalId") @Parameter(description = "loanExternalId") final String loanExternalId,
            @Parameter(hidden = true) final String apiRequestBodyAsJson) {
        final Long loanId = loanReadPlatformService.getResolvedLoanId(ExternalIdFactory.produce(loanExternalId));
        return postAccrualsTillDateInternal(loanId, apiRequestBodyAsJson);
    }

    private String postAccrualsTillDateInternal(final Long loanId, final String apiRequestBodyAsJson) {
        final CommandWrapper commandRequest = new CommandWrapperBuilder().postAccrualsTillDateForLoan(loanId)
                .withJson(apiRequestBodyAsJson != null ? apiRequestBodyAsJson : "{}").build();
        final CommandProcessingResult result = commandsSourceWritePlatformService.logCommandSource(commandRequest);
        return toApiJsonSerializer.serialize(result);
    }
}
