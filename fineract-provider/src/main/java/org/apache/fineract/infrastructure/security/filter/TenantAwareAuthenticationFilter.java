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
package org.apache.fineract.infrastructure.security.filter;

import com.nimbusds.jwt.JWTParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.security.exception.InvalidTenantIdentifierException;
import org.apache.fineract.infrastructure.security.service.AuthTenantDetailsService;
import org.springframework.lang.NonNull;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class TenantAwareAuthenticationFilter extends OncePerRequestFilter {

    private static final String TENANT_ID_REQUEST_HEADER = "Fineract-Platform-TenantId";
    private final BearerTokenResolver resolver;
    private final AuthTenantDetailsService tenantDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = resolver.resolve(request);
            String tenantId = null;
            if (token != null) {
                try {
                    var jwt = JWTParser.parse(token); // not validated here!
                    var claims = jwt.getJWTClaimsSet();
                    tenantId = (String) claims.getClaim("tenant");
                } catch (Exception e) {
                    // If JWT parsing fails, continue to find tenant id in header or parameter
                }
            }

            if (tenantId == null || tenantId.isEmpty()) {
                tenantId = request.getHeader(TENANT_ID_REQUEST_HEADER);
            }

            if (tenantId == null || tenantId.isEmpty()) {
                tenantId = request.getParameter("tenantId");
            }

            if (tenantId == null || tenantId.isEmpty()) {
                tenantId = request.getParameter("tenantIdentifier");
            }

            if (tenantId == null || tenantId.isEmpty()) {
                throw new InvalidTenantIdentifierException("No tenant identifier found: Add request header of '" + TENANT_ID_REQUEST_HEADER
                        + "' or add the parameter 'tenantIdentifier' to query string of request URL.");
            }
            ThreadLocalContextUtil.setTenant(tenantDetailsService.loadTenantById(tenantId, false));
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            filterChain.doFilter(request, response); // don't block; real auth will fail later if token is bad
        } finally {
            ThreadLocalContextUtil.reset();
        }
    }
}
