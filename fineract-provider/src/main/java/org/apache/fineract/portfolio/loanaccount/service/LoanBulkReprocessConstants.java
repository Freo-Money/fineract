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
package org.apache.fineract.portfolio.loanaccount.service;

/**
 * Constants shared by bulk loan reprocess (async runs, validation limits).
 */
public final class LoanBulkReprocessConstants {

    private LoanBulkReprocessConstants() {}

    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final int MIN_BATCH_SIZE = 1;
    public static final int MAX_BATCH_SIZE = 500;

    /**
     * Reject requests larger than this (after deduplication) to protect the server. Bounds the synchronous batch-insert
     * in {@code submitAsyncRun} (which pins one DB connection and blocks the API thread for the duration) and the
     * in-memory dedup. Larger logical runs should be split client-side into multiple submissions.
     */
    public static final int MAX_LOAN_IDS_PER_REQUEST = 10_000;

    /** Maximum failure rows returned in a single response (used by failures paging limit clamp). */
    public static final int MAX_FAILURE_DETAILS_IN_RESPONSE = 5_000;

    /** JDBC fetch size for reading large run item sets. */
    public static final int JDBC_FETCH_SIZE = 1_000;

    /** Batch size for JDBC batch operations (status updates within a chunk). */
    public static final int JDBC_BATCH_SIZE_DEFAULT = 500;

    /** Default page size for failures endpoint (server-side clamped). */
    public static final int DEFAULT_FAILURES_PAGE_LIMIT = 200;

    /**
     * Bean name of the dedicated single-worker {@code ThreadPoolTaskExecutor} for the bulk reprocess Spring Batch job.
     */
    public static final String JOB_TASK_EXECUTOR_BEAN_NAME = "loanBulkReprocessTaskExecutor";

    /** Bean name of the dedicated async {@code JobLauncher} for the bulk reprocess Spring Batch job. */
    public static final String JOB_LAUNCHER_BEAN_NAME = "loanBulkReprocessJobLauncher";

    /** Queue capacity for the bulk reprocess job task executor (number of pending submissions before reject). */
    public static final int JOB_LAUNCHER_QUEUE_CAPACITY = 100;
}
