package org.apache.fineract.infrastructure.exception.mapper;

import jakarta.annotation.Priority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.exception.service.ErrorLoggingService;
import org.springframework.stereotype.Component;

@Provider
@Priority(Priorities.AUTHENTICATION)
@Slf4j
@Component
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    @Context
    private HttpServletRequest request;

    private final ErrorLoggingService service;

    public GlobalExceptionMapper(ErrorLoggingService service) {
        this.service = service;
    }

    @Override
    public Response toResponse(Throwable ex) {
        Response originalResponse = null;
        int status = 500;
        String message = ex.getMessage();

        if (ex instanceof WebApplicationException webApplicationException && webApplicationException.getResponse() != null) {
            originalResponse = webApplicationException.getResponse();
            status = originalResponse.getStatus();
            if (status < 500) {

                return originalResponse;
            }


            Object entity = originalResponse.getEntity();
            if (entity instanceof String stringEntity && !stringEntity.isBlank()) {
                message = stringEntity;
            }
        }

        if (message == null || message.isBlank()) {
            message = "Internal Server Error";
        }

        String traceId = UUID.randomUUID().toString();
        String path = request != null ? request.getRequestURI() : "unknown";
        String method = request != null ? request.getMethod() : "unknown";

        service.logException(ex, traceId, path, method, status, message);
        log.error("Unhandled exception traceId={} path={} method={} status={}", traceId, path, method, status, ex);

        Map<String, Object> response = new HashMap<>();
        response.put("status", status);
        response.put("message", message);
        response.put("traceId", traceId);

        if (originalResponse != null) {

            return Response.status(status).entity(response).build();
        }

        return Response.status(status).entity(response).build();
    }
}
