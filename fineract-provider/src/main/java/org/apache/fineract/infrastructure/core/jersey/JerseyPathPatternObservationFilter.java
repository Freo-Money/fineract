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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.uri.UriTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.ServerHttpObservationFilter;

@Provider
@Component
@Slf4j
public class JerseyPathPatternObservationFilter implements ContainerRequestFilter {

    private static final Pattern MULTIPLE_SLASHES = Pattern.compile("/+");
    private static final Pattern TRAILING_SLASH = Pattern.compile("/+$");
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean(false);

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
            if (FAILURE_LOGGED.compareAndSet(false, true)) {
                log.warn("Failed to record observed URI path pattern for {} {}; further occurrences logged at debug level",
                        requestContext.getMethod(), requestContext.getUriInfo().getRequestUri(), e);
            } else if (log.isDebugEnabled()) {
                log.debug("Failed to record observed URI path pattern for {} {}", requestContext.getMethod(),
                        requestContext.getUriInfo().getRequestUri(), e);
            }
        }
    }

    private String matchedPathPattern() {
        List<UriTemplate> templates = uriInfo.getMatchedTemplates();
        if (templates.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder(pathWithoutContext());
        for (int i = templates.size() - 1; i >= 0; i--) {
            builder.append(templates.get(i).getTemplate());
        }
        String path = MULTIPLE_SLASHES.matcher(builder).replaceAll("/");
        return path.length() > 1 ? TRAILING_SLASH.matcher(path).replaceAll("") : path;
    }

    private String pathWithoutContext() {
        String basePath = uriInfo.getBaseUri().getPath();
        String contextPath = servletRequest.getContextPath();
        if (!contextPath.isEmpty() && basePath.startsWith(contextPath)) {
            return basePath.substring(contextPath.length());
        }
        return basePath;
    }
}
