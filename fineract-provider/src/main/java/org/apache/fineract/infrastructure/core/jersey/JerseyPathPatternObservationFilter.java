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
package org.apache.fineract.infrastructure.core.jersey;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.uri.UriTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Spring's {@code http.server.requests} metric tags every request with the {@code uri} it was dispatched to, but that
 * tag is only ever filled in by a Spring MVC {@code HandlerMapping} (see
 * {@code ServerRequestObservationContext#setPathPattern}). Fineract's REST API is served by Jersey, which resolves
 * routing on its own, so without this filter every JAX-RS request is reported with {@code uri=UNKNOWN} while only
 * Spring-MVC-backed actuator endpoints resolve correctly. This filter runs post-matching, once Jersey has resolved the
 * matched {@code @Path} templates, and feeds that template into the current observation context so the existing
 * {@code http.server.requests} / {@code http_server_requests_seconds_count} metric gets the real path pattern instead.
 */
@Provider
@Component
@Slf4j
public class JerseyPathPatternObservationFilter implements ContainerRequestFilter {

    private static final Pattern MULTIPLE_SLASHES = Pattern.compile("/+");
    private static final Pattern TRAILING_SLASH = Pattern.compile("/+$");

    // Injected as per-request proxies by Jersey/JAX-RS even though this filter is a singleton bean;
    // each field access resolves against the thread's current request, so this is safe under concurrency.
    @Context
    private HttpServletRequest servletRequest;

    @Context
    private ExtendedUriInfo uriInfo;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        try {
            String pattern = matchedPathPattern();
            if (pattern != null) {
                ServerHttpObservationFilter.findObservationContext(servletRequest).ifPresent(context -> context.setPathPattern(pattern));
            }
        } catch (RuntimeException | LinkageError e) {
            log.warn("Failed to record observed URI path pattern for {} {}", requestContext.getMethod(), requestContext.getUriInfo(), e);
        }
    }

    private String matchedPathPattern() {
        List<UriTemplate> templates = uriInfo.getMatchedTemplates();
        if (templates.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder(uriInfo.getBaseUri().getPath());
        for (int i = templates.size() - 1; i >= 0; i--) {
            builder.append(templates.get(i).getTemplate());
        }
        String path = MULTIPLE_SLASHES.matcher(builder).replaceAll("/");
        return path.length() > 1 ? TRAILING_SLASH.matcher(path).replaceAll("") : path;
    }
}
