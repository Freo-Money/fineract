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
package org.apache.fineract.infrastructure.dataqueries.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.serialization.ApiRequestJsonSerializationSettings;
import org.apache.fineract.infrastructure.core.serialization.ToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.apache.fineract.infrastructure.dataqueries.data.AdvancedRunReportData;
import org.apache.fineract.infrastructure.dataqueries.data.AdvancedRunReportResponse;
import org.apache.fineract.infrastructure.dataqueries.service.AdvancedRunReportExecutionService;
import org.apache.fineract.infrastructure.dataqueries.service.AdvancedRunReportReadPlatformService;
import org.apache.fineract.infrastructure.documentmanagement.contentrepository.S3ContentRepository;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.security.service.SqlValidator;
import org.apache.fineract.useradministration.domain.AppUser;
import org.glassfish.jersey.internal.util.collection.MultivaluedStringMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

@Path("/v1/advancedrunreports/")
@Component
@Tag(name = "Async Run Reports", description = "API for executing predefined reports asynchronously with results stored in S3")
@Slf4j
public class AdvancedRunreportsApiResource {

    private static final String IS_SELF_SERVICE_USER_REPORT_PARAMETER = "isSelfServiceUserReport";

    private final PlatformSecurityContext context;
    private final AdvancedRunReportExecutionService advancedRunReportExecutionService;
    private final AdvancedRunReportReadPlatformService advancedRunReportReadPlatformService;
    private final SqlValidator sqlValidator;
    private final ApiRequestParameterHelper apiRequestParameterHelper;
    private final ToApiJsonSerializer<AdvancedRunReportData> runreportrequestToApiJsonSerializer;
    private final S3ContentRepository s3ContentRepository;

    public AdvancedRunreportsApiResource(final PlatformSecurityContext context,
            final AdvancedRunReportExecutionService advancedRunReportExecutionService,
            final AdvancedRunReportReadPlatformService advancedRunReportReadPlatformService, final SqlValidator sqlValidator,
            final ApiRequestParameterHelper apiRequestParameterHelper,
            final ToApiJsonSerializer<AdvancedRunReportData> runreportrequestToApiJsonSerializer,
            final S3ContentRepository s3ContentRepository) {
        this.context = context;
        this.advancedRunReportExecutionService = advancedRunReportExecutionService;
        this.advancedRunReportReadPlatformService = advancedRunReportReadPlatformService;
        this.sqlValidator = sqlValidator;
        this.apiRequestParameterHelper = apiRequestParameterHelper;
        this.runreportrequestToApiJsonSerializer = runreportrequestToApiJsonSerializer;
        this.s3ContentRepository = s3ContentRepository;
    }

    @POST
    @Path("{reportName}")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Submit a report for asynchronous execution", description = "Submits a predefined report for background execution. "
            + "The report will be generated as CSV and uploaded to S3. " + "Returns a request ID that can be used to poll for status.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted - RunReport request submitted successfully", content = @Content(schema = @Schema(implementation = AdvancedRunreportsApiResourceSwagger.SubmitAsyncReportResponse.class))),
            @ApiResponse(responseCode = "400", description = "Bad Request - Missing or invalid parameters"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Not authorized to run this report"),
            @ApiResponse(responseCode = "500", description = "Internal Server Error") })
    public Response submitAsyncReport(
            @PathParam("reportName") @Parameter(description = "The name of the report to execute", example = "Client Listing", required = true) final String reportName,
            @Context final UriInfo uriInfo,

            @DefaultValue("false") @QueryParam(IS_SELF_SERVICE_USER_REPORT_PARAMETER) @Parameter(description = "Whether this is a self-service user report", example = "false") final boolean isSelfServiceUserReport,

            @QueryParam("R_officeId") @Parameter(description = "Office ID filter", example = "1") final String rOfficeId,

            @QueryParam("R_loanOfficerId") @Parameter(description = "Loan officer ID filter", example = "5") final String rLoanOfficerId,

            @QueryParam("R_fromDate") @Parameter(description = "Start date filter (yyyy-MM-dd)", example = "2023-01-01") final String rFromDate,

            @QueryParam("R_toDate") @Parameter(description = "End date filter (yyyy-MM-dd)", example = "2023-12-31") final String rToDate,

            @QueryParam("R_currencyId") @Parameter(description = "Currency ID filter", example = "USD") final String rCurrencyId,

            @QueryParam("R_accountNo") @Parameter(description = "Account number filter", example = "00010001") final String rAccountNo) {

        checkUserPermissionForReport(reportName);

        MultivaluedMap<String, String> queryParams = new MultivaluedStringMap();
        queryParams.putAll(uriInfo.getQueryParameters());
        queryParams.putSingle(IS_SELF_SERVICE_USER_REPORT_PARAMETER, Boolean.toString(isSelfServiceUserReport));

        AdvancedRunReportResponse requestId = advancedRunReportExecutionService.submitReportRequest(reportName, queryParams,
                isSelfServiceUserReport);

        return Response.status(Response.Status.ACCEPTED).entity("{\"requestId\": " + requestId + "}").type(MediaType.APPLICATION_JSON)
                .build();
    }

    private void checkUserPermissionForReport(final String reportName) {
        final AppUser currentUser = this.context.authenticatedUser();
        if (currentUser.hasNotPermissionForReport(reportName)) {
            throw new NoAuthorizationException("Not authorised to run report: " + reportName);
        }
    }

    @GET
    @Path("all-reports")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Retrieve all async runreport request for the authenticated user", description = "Returns paginated async runreport requets submitted by the currently authenticated user.\n"
            + "\n" + "Example Requests:\n" + "\n" + "reports/async-runreport-requests\n" + "\n"
            + "reports/async-runreportrequests?offset=0&limit=20\n" + "\n"
            + "reports/async-runreport-request?orderBy=createdAt&sortOrder=DESC")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = AdvancedRunReportData.class))) })
    public String retrieveAllUserReports(@Context final UriInfo uriInfo,
            @QueryParam("offset") @Parameter(description = "offset") final Integer offset,
            @QueryParam("limit") @Parameter(description = "limit") final Integer limit,
            @QueryParam("orderBy") @Parameter(description = "orderBy") final String orderBy,
            @QueryParam("sortOrder") @Parameter(description = "sortOrder") final String sortOrder) {

        context.authenticatedUser().validateHasReadPermission("REPORT");

        sqlValidator.validate(orderBy);
        sqlValidator.validate(sortOrder);

        final SearchParameters searchParameters = SearchParameters.builder().offset(offset).limit(limit).orderBy(orderBy)
                .sortOrder(sortOrder).build();

        final Page<AdvancedRunReportData> reportData = advancedRunReportReadPlatformService.retrieveAllUserReports(searchParameters);

        final ApiRequestJsonSerializationSettings settings = apiRequestParameterHelper.process(uriInfo.getQueryParameters());

        return runreportrequestToApiJsonSerializer.serialize(settings, reportData);
    }

    @GET
    @Path("{requestId}/status")
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_JSON })
    @Operation(summary = "Get status of an async runreport request", description = "Returns the current status of a specific async runreport request.\n"
            + "Can be used to poll for request completion.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK", content = @Content(schema = @Schema(implementation = AdvancedRunReportData.class))),
            @ApiResponse(responseCode = "404", description = "Not Found - request ID does not exist"),
            @ApiResponse(responseCode = "500", description = "Internal Server Error") })
    public String retrieverequestStatus(
            @PathParam("requestId") @Parameter(description = "The async runreport request Id", example = "1", required = true) final Long requestId,
            @Context final UriInfo uriInfo) {

        context.authenticatedUser().validateHasReadPermission("REPORT");

        final AdvancedRunReportData reportData = advancedRunReportReadPlatformService.retrieveReportRequestStatus(requestId);

        final ApiRequestJsonSerializationSettings settings = apiRequestParameterHelper.process(uriInfo.getQueryParameters());

        return runreportrequestToApiJsonSerializer.serialize(settings, reportData);
    }

    @GET
    @Path("{requestId}/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @ConditionalOnProperty(name = "fineract.content.s3.enabled", havingValue = "true")
    @Operation(summary = "Download a completed  report", description = "Downloads the report file for a completed async runreport request. "
            + "File is returned as binary attachment. Only completed reports can be downloaded.")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "OK - File returned as attachment"),
            @ApiResponse(responseCode = "400", description = "Bad Request - Report not completed yet"),
            @ApiResponse(responseCode = "404", description = "Not Found - request ID does not exist"),
            @ApiResponse(responseCode = "500", description = "Internal Server Error") })
    public Response downloadReport(
            @PathParam("requestId") @Parameter(description = "The async request ID", example = "1", required = true) final Long requestId) {

        this.context.authenticatedUser();

        try {
            final AdvancedRunReportData reportData = this.advancedRunReportReadPlatformService.retrieveReportForDownload(requestId);

            if (this.s3ContentRepository == null) {
                log.error("S3ContentRepository is not available. S3 is likely not enabled.");
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"errorMessage\": \"S3 storage is not configured or enabled\"}").type(MediaType.APPLICATION_JSON).build();
            }

            final InputStream fileStream = getFileFromS3(reportData.getS3FilePath());
            final String filename = extractFilename(reportData);

            return Response.ok(fileStream).type(MediaType.APPLICATION_OCTET_STREAM)
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"").build();

        } catch (IllegalStateException e) {
            log.warn("Report download rejected - report not in completed state: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"errorMessage\": \"" + e.getMessage() + "\"}")
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            log.error("Error downloading report with requestId: {}", requestId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"errorMessage\": \"Unable to download report: " + e.getMessage() + "\"}").type(MediaType.APPLICATION_JSON)
                    .build();
        }
    }

    private InputStream getFileFromS3(final String filePath) {
        try {
            if (filePath == null || filePath.trim().isEmpty()) {
                throw new IllegalArgumentException("S3 file path is null or empty");
            }
            log.debug("Downloading file from S3: {}", filePath);
            final ResponseBytes<GetObjectResponse> s3Object = this.s3ContentRepository.getObject(filePath);
            return s3Object.asInputStream();
        } catch (Exception e) {
            log.error("Failed to retrieve file from S3: {}", filePath, e);
            throw new RuntimeException("Failed to retrieve file from S3: " + filePath + " - " + e.getMessage(), e);
        }
    }

    private String extractFilename(final AdvancedRunReportData reportData) {
        final String path = reportData.getS3FilePath();
        if (path != null && path.contains("/")) {
            return path.substring(path.lastIndexOf("/") + 1);
        }
        return reportData.getReportName() + ".csv";
    }
}
