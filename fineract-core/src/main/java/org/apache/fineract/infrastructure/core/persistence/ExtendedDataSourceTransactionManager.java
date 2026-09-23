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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * JDBC transaction manager ("jdbcTransactionManager") for transactions that need a specific isolation level. Unlike
 * {@link ExtendedJpaTransactionManager} — which rejects custom isolation because the EclipseLink dialect would apply it
 * through shared session state — this manager applies isolation per-connection, which is safe under concurrency.
 */
// Extends JdbcTransactionManager (not DataSourceTransactionManager) so commit-time SQLExceptions are translated
// into Spring's DataAccessException hierarchy — a serialization failure or deadlock at commit surfaces as
// ConcurrencyFailureException, which Fineract's retry configuration recognizes, instead of an opaque
// TransactionSystemException. That matters here specifically: this manager exists to host the isolation-sensitive
// transactions most likely to hit commit-time conflicts.
public class ExtendedDataSourceTransactionManager extends JdbcTransactionManager {

    private final List<TransactionLifecycleCallback> lifecycleCallbacks = new CopyOnWriteArrayList<>();

    private final boolean readOnly;

    public ExtendedDataSourceTransactionManager(boolean readOnly) {
        this.readOnly = readOnly;
        setValidateExistingTransaction(true);
        // Set here rather than in doBegin: the flag is consulted by prepareTransactionalConnection
        // during super.doBegin, so setting it later would miss the first read-only transaction. Note the
        // enforcement ("SET TRANSACTION READ ONLY") fires only when the individual transaction definition
        // is ALSO marked read-only; a non-read-only definition on a read-only instance relies on the
        // Hikari pool's connection-level readOnly flag, set from the same mode property.
        if (readOnly) {
            setEnforceReadOnly(true);
        }
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        // A ConnectionHolder bound for our DataSource at doBegin time is necessarily foreign: this manager's own
        // transactions never reach doBegin with one (a join skips doBegin, REQUIRES_NEW suspends and unbinds
        // first). The JPA manager binds one this way to expose its EclipseLink connection; super.doBegin would
        // silently adopt that live connection and commit it mid-transaction, so fail loudly instead.
        if (TransactionSynchronizationManager.hasResource(obtainDataSource())) {
            throw new IllegalTransactionStateException(
                    "Pre-bound JDBC connection found — the JDBC transaction manager cannot begin a transaction inside "
                            + "an active JPA transaction on the same DataSource; complete or suspend the JPA transaction first");
        }

        super.doBegin(transaction, definition);
        invokeLifecycleCallbacks(TransactionLifecycleCallback::afterBegin);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        super.doCommit(status);
        invokeLifecycleCallbacks(TransactionLifecycleCallback::afterCommit);
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        super.doCleanupAfterCompletion(transaction);
        invokeLifecycleCallbacks(TransactionLifecycleCallback::afterCompletion);
    }

    private void invokeLifecycleCallbacks(Consumer<TransactionLifecycleCallback> f) {
        lifecycleCallbacks.forEach(f::accept);
    }

    public void setLifecycleCallbacks(List<TransactionLifecycleCallback> lifecycleCallbacks) {
        this.lifecycleCallbacks.addAll(lifecycleCallbacks);
    }
}
