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
package org.apache.fineract.portfolio.externalreference.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.loanaccount.data.LoanTransactionBasicData;
import org.apache.fineract.portfolio.loanaccount.service.LoanTransactionReadService;
import org.springframework.stereotype.Component;

@Component
@Path("/v1/external-reference")
@Tag(name = "External Reference", description = "Retrieve loan transactions by external ID")
@RequiredArgsConstructor
public class ExternalReferenceApiResource {

    private final LoanTransactionReadService loanTransactionReadService;

    @GET
    @Path("/loan-transactions/{externalId}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Retrieve Loan Transaction by External ID", description = "Retrieves a loan transaction using its external reference ID\n\n"
            + "Example Request:\n" + "external-reference/loan-transactions/TXN-123456")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = LoanTransactionBasicData.class))),
            @ApiResponse(responseCode = "404", description = "Loan transaction not found") })
    public LoanTransactionBasicData retrieveLoanTransactionByExternalId(
            @PathParam("externalId") @Parameter(description = "External ID of the loan transaction", required = true) final String externalId) {
        return loanTransactionReadService.retrieveTransactionByExternalId(externalId);
    }

}
