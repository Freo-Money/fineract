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

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.jdbc.datasource.ConnectionHandle;
import org.springframework.jdbc.datasource.JdbcTransactionObjectSupport;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.vendor.EclipseLinkJpaDialect;
import org.springframework.transaction.InvalidIsolationLevelException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class ExtendedJpaTransactionManager extends JpaTransactionManager {

    private final List<TransactionLifecycleCallback> lifecycleCallbacks = new CopyOnWriteArrayList<>();

    private final boolean readOnly;

    public ExtendedJpaTransactionManager(boolean readOnly) {
        this.readOnly = readOnly;
        setValidateExistingTransaction(true);
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public void afterPropertiesSet() {
        super.afterPropertiesSet();
        if (getJpaDialect() instanceof EclipseLinkJpaDialect) {
            setJpaDialect(new LockFreeEclipseLinkJpaDialect());
        }
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        // Custom isolation levels are forbidden on this (JPA/EclipseLink) transaction manager. The stock
        // EclipseLinkJpaDialect applies a non-default isolation by transiently mutating the shared per-session
        // DatabaseLogin, whose "not set" sentinel (-1) can bleed into a concurrent transaction's
        // Connection.setTransactionIsolation call; the lock-free dialect below additionally removes the lock that
        // partially guarded that mutation. Transactions that genuinely need a specific isolation level must run
        // through the JDBC transaction manager ("jdbcTransactionManager"), which applies isolation per-connection.
        if (definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT) {
            throw new InvalidIsolationLevelException("Custom isolation level " + definition.getIsolationLevel()
                    + " is not supported by the JPA transaction manager; use the JDBC transaction manager (\"jdbcTransactionManager\") "
                    + "for transactions that require a specific isolation level");
        }

        super.doBegin(transaction, definition);

        if (definition.isReadOnly() || isReadOnlyTx(transaction) || readOnly) {
            EntityManager entityManager = getCurrentEntityManager();
            if (entityManager != null) {
                entityManager.setFlushMode(FlushModeType.COMMIT);
            }
        }

        invokeLifecycleCallbacks(TransactionLifecycleCallback::afterBegin);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        if (isReadOnlyTx(status.getTransaction()) || readOnly) {
            EntityManager entityManager = getCurrentEntityManager();
            if (entityManager != null) {
                entityManager.clear();
            }
        }

        super.doCommit(status);
        invokeLifecycleCallbacks(TransactionLifecycleCallback::afterCommit);
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        super.doCleanupAfterCompletion(transaction);
        invokeLifecycleCallbacks(TransactionLifecycleCallback::afterCompletion);
    }

    private boolean isReadOnlyTx(Object transaction) {
        JdbcTransactionObjectSupport txObject = (JdbcTransactionObjectSupport) transaction;
        return txObject.isReadOnly();
    }

    private EntityManager getCurrentEntityManager() {
        EntityManagerHolder holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        if (holder != null) {
            return holder.getEntityManager();
        }
        return null;
    }

    private void invokeLifecycleCallbacks(Consumer<TransactionLifecycleCallback> f) {
        lifecycleCallbacks.forEach(f::accept);
    }

    public void setLifecycleCallbacks(List<TransactionLifecycleCallback> lifecycleCallbacks) {
        this.lifecycleCallbacks.addAll(lifecycleCallbacks);
    }

    private static final class LockFreeEclipseLinkJpaDialect extends EclipseLinkJpaDialect {

        LockFreeEclipseLinkJpaDialect() {
            // lazyDatabaseTransaction=true is the load-bearing setting: without it the stock dialect forces an
            // early JDBC connection acquisition in beginTransaction for every non-read-only transaction, whether
            // or not it ever runs SQL. It is safe only because doBegin above rejects custom isolation levels, so
            // the dialect's custom-isolation branch (which transiently mutates the shared per-session
            // DatabaseLogin under the dialect-wide transactionIsolationLock) is unreachable.
            setLazyDatabaseTransaction(true);
        }

        // Note: in the currently resolved spring-orm (6.2.x) the stock EclipseLinkConnectionHandle takes no lock,
        // so this handle is equivalent to it; newer Spring versions guard getConnection() with the dialect-wide
        // transactionIsolationLock, which this override keeps connection unwrapping free of across upgrades.

        @Override
        public ConnectionHandle getJdbcConnection(EntityManager em, boolean readOnly) {
            return new LockFreeConnectionHandle(em);
        }

        private static final class LockFreeConnectionHandle implements ConnectionHandle {

            private final EntityManager em;
            private Connection connection;

            LockFreeConnectionHandle(EntityManager em) {
                this.em = em;
            }

            @Override
            public Connection getConnection() {
                if (connection == null) {
                    connection = em.unwrap(Connection.class);
                }
                return connection;
            }
        }
    }
}
