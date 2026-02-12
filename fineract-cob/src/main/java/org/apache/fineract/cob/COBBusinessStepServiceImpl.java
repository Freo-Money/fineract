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
package org.apache.fineract.cob;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.cob.data.BusinessStepNameAndOrder;
import org.apache.fineract.cob.domain.BatchBusinessStep;
import org.apache.fineract.cob.domain.BatchBusinessStepRepository;
import org.apache.fineract.cob.exceptions.BusinessStepException;
import org.apache.fineract.cob.service.ReloaderService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.domain.AbstractPersistableCustom;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class COBBusinessStepServiceImpl implements COBBusinessStepService {

    private final BatchBusinessStepRepository batchBusinessStepRepository;
    private final ApplicationContext applicationContext;
    private final ListableBeanFactory beanFactory;
    private final BusinessEventNotifierService businessEventNotifierService;
    private final ConfigurationDomainService configurationDomainService;

    private final ReloaderService reloaderService;

    @SuppressWarnings({ "unchecked" })
    @Override
    public <T extends COBBusinessStep<S>, S extends AbstractPersistableCustom<Long>> S run(TreeMap<Long, String> executionMap, S item) {
        if (executionMap == null || executionMap.isEmpty()) {
            throw new BusinessStepException("Execution map is empty! COB Business step execution skipped!");
        }
        boolean bulkEventEnabled = configurationDomainService.isCOBBulkEventEnabled();
        // Extra safety net to avoid event leaking
        try {
            if (bulkEventEnabled) {
                businessEventNotifierService.startExternalEventRecording();
            }

            for (String businessStep : executionMap.values()) {
                try {
                    ThreadLocalContextUtil.setActionContext(ActionContext.COB);
                    COBBusinessStep<S> businessStepBean = (COBBusinessStep<S>) applicationContext.getBean(businessStep);
                    item = reloaderService.reload(item);
                    item = businessStepBean.execute(item);
                } catch (Exception e) {
                    Throwable rootCause = getRootCause(e);
                    String rootCauseLocation = getRootCauseLocation(rootCause);
                    String fullStackTrace = getFullStackTrace(e);
                    log.error("COB Business step [{}] failed for item id [{}]. Root cause: {}. Exception occurred at: {}", businessStep,
                            item.getId(), getRootCauseMessage(e), rootCauseLocation);
                    if (rootCause instanceof NullPointerException) {
                        log.error("NullPointerException tip: add JVM flag -XX:+ShowCodeDetailsInExceptionMessages (Java 14+)");
                    }
                    log.error("Full stack trace for item id [{}]:\n{}", item.getId(), fullStackTrace);
                    throw new BusinessStepException("Error happened during business step execution", e);
                } finally {
                    // Fallback to COB action context after each business step
                    ThreadLocalContextUtil.setActionContext(ActionContext.COB);
                }
            }
            if (bulkEventEnabled) {
                businessEventNotifierService.stopExternalEventRecording();
            }
        } catch (Exception e) {
            if (bulkEventEnabled) {
                businessEventNotifierService.resetEventRecording();
            }
            throw e;
        }
        return item;
    }

    @NonNull
    @Override
    public <T extends COBBusinessStep<S>, S extends AbstractPersistableCustom<Long>> Set<BusinessStepNameAndOrder> getCOBBusinessSteps(
            Class<T> businessStepClass, String cobJobName) {
        List<BatchBusinessStep> cobStepConfigs = batchBusinessStepRepository.findAllByJobName(cobJobName);
        List<String> businessSteps = Arrays.stream(beanFactory.getBeanNamesForType(businessStepClass)).toList();
        Set<BusinessStepNameAndOrder> executionMap = new HashSet<>();
        for (String businessStep : businessSteps) {
            COBBusinessStep<S> businessStepBean = (COBBusinessStep<S>) applicationContext.getBean(businessStep);
            Optional<BatchBusinessStep> businessStepConfig = cobStepConfigs.stream()
                    .filter(stepConfig -> businessStepBean.getEnumStyledName().equals(stepConfig.getStepName())).findFirst();
            businessStepConfig.ifPresent(
                    batchBusinessStep -> executionMap.add(new BusinessStepNameAndOrder(businessStep, batchBusinessStep.getStepOrder())));
        }
        return executionMap;
    }

    private static Throwable getRootCause(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    private static String getRootCauseMessage(Throwable t) {
        Throwable root = getRootCause(t);
        return root.getClass().getName() + ": " + root.getMessage();
    }

    /**
     * Returns the exact file and line where the root cause (e.g. NPE) occurred. Format:
     * className.methodName(fileName:lineNumber)
     */
    private static String getRootCauseLocation(Throwable rootCause) {
        if (rootCause == null) {
            return "unknown";
        }
        StackTraceElement[] stackTrace = rootCause.getStackTrace();
        if (stackTrace == null || stackTrace.length == 0) {
            return "no stack trace available";
        }
        StackTraceElement top = stackTrace[0];
        return String.format("%s.%s(%s:%d)", top.getClassName(), top.getMethodName(), top.getFileName(), top.getLineNumber());
    }

    /**
     * Returns full stack trace including all causes (Caused by: ...) as string.
     */
    private static String getFullStackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder(256);
        appendThrowable(sb, t);
        return sb.toString();
    }

    private static void appendThrowable(StringBuilder sb, Throwable t) {
        if (t == null) {
            sb.append("null throwable");
            return;
        }

        sb.append(t);

        StackTraceElement[] stackTraceElements = t.getStackTrace();
        if (stackTraceElements != null) {
            for (StackTraceElement element : stackTraceElements) {
                sb.append("\n\tat ").append(element);
            }
        }

        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append("\nCaused by: ");
            appendThrowable(sb, cause);
        }
    }
}
