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
package org.apache.fineract.infrastructure.core.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Build-time guard for the invariant behind {@link ExtendedJpaTransactionManager}'s runtime isolation check: no
 * transaction routed to the JPA transaction manager may request a non-default isolation level. The stock EclipseLink
 * dialect would apply such a request by transiently mutating the shared per-session DatabaseLogin, which can bleed a
 * wrong isolation (including the -1 "not set" sentinel) into concurrent transactions. Transactions that need a specific
 * isolation level must target the JDBC transaction manager ("jdbcTransactionManager") instead, which applies isolation
 * per-connection.
 *
 * <p>
 * Scans every org.apache.fineract class on the test classpath via ASM metadata (without loading the classes) and fails
 * on any {@code @Transactional} that declares a non-default {@link Isolation} without explicitly targeting
 * "jdbcTransactionManager". Programmatic isolation (e.g. {@code TransactionTemplate#setIsolationLevel}) is not visible
 * to annotation scanning; that is covered by the runtime guard in {@code ExtendedJpaTransactionManager#doBegin}.
 */
public class NoCustomIsolationOnJpaTransactionManagerTest {

    private static final String JDBC_TRANSACTION_MANAGER = "jdbcTransactionManager";
    private static final String TRANSACTIONAL = Transactional.class.getName();

    @Test
    public void noCustomIsolationLevelIsUsedWithTheJpaTransactionManager() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory(resolver);
        Resource[] resources = resolver.getResources("classpath*:org/apache/fineract/**/*.class");

        List<String> violations = new ArrayList<>();
        int scannedClasses = 0;
        int unparseableClasses = 0;
        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                continue;
            }
            AnnotationMetadata metadata;
            try {
                metadata = metadataReaderFactory.getMetadataReader(resource).getAnnotationMetadata();
            } catch (Throwable t) { // NOSONAR - skip anything that cannot be parsed (module-info, synthetic, etc.)
                unparseableClasses++;
                continue;
            }
            scannedClasses++;

            collectViolation(metadata.getClassName(), "<class-level @Transactional>", metadata.getAnnotations(), violations);
            for (MethodMetadata method : metadata.getAnnotatedMethods(TRANSACTIONAL)) {
                collectViolation(metadata.getClassName(), method.getMethodName() + "()", method.getAnnotations(), violations);
            }
        }

        // Guard against a vacuous pass: if the classpath pattern breaks or ASM cannot parse the bytecode
        // (e.g. a JDK bump ahead of the matching Spring upgrade), the scan would find nothing and the
        // assertion below would be meaningless.
        assertThat(scannedClasses)
                .as("classpath scan parsed suspiciously few org.apache.fineract classes "
                        + "(%d parsed, %d unparseable) — the isolation guard would be vacuous", scannedClasses, unparseableClasses)
                .isGreaterThan(1000);

        assertThat(violations).as("These @Transactional declarations use a non-default isolation level on the JPA transaction manager, "
                + "which is unsafe with the EclipseLink shared-login isolation handling. Route them through the JDBC "
                + "transaction manager instead: @Transactional(transactionManager = \"jdbcTransactionManager\", "
                + "isolation = ...). Offenders:\n%s", String.join("\n", violations)).isEmpty();
    }

    private static void collectViolation(String className, String location, MergedAnnotations annotations, List<String> violations) {
        MergedAnnotation<Transactional> transactional = annotations.get(Transactional.class);
        if (!transactional.isPresent()) {
            return;
        }
        Isolation isolation = transactional.getEnum("isolation", Isolation.class);
        if (isolation == Isolation.DEFAULT) {
            return;
        }
        String transactionManager = transactional.getString("transactionManager");
        if (!JDBC_TRANSACTION_MANAGER.equals(transactionManager)) {
            String target = transactionManager.isEmpty() ? "<default/primary JPA manager>" : transactionManager;
            violations.add("  " + className + "." + location + " -> isolation=" + isolation + ", transactionManager=" + target);
        }
    }
}
