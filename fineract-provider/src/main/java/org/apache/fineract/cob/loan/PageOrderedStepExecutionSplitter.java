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
package org.apache.fineract.cob.loan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.partition.support.PartitionNameProvider;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.partition.support.SimpleStepExecutionSplitter;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.util.Assert;

/**
 * A {@link SimpleStepExecutionSplitter} that creates, persists and hands over the partition step executions in
 * ascending page order, so that the manager dispatches LOAN_COB partitions to the workers lowest loan id first.
 * <p>
 * The stock splitter collects the step executions into a {@code HashSet}, which loses the order produced by
 * {@link LoanCOBPartitioner}; the remote partition handler then sends one message per step execution in set-iteration
 * order. This subclass sorts the partitioner's entries by the numeric suffix of the partition key ({@code partition_N})
 * <em>before</em> the step executions are created, so the persisted {@code step_execution_id}s, the job execution's
 * step list and the returned set all follow the page order. Keying on the name rather than on a context entry keeps the
 * order correct on a restart, where the parent swaps in the previous run's context.
 * <p>
 * {@link #split(StepExecution, int)} is adapted from Spring Batch 5.2.2 {@code SimpleStepExecutionSplitter}
 * (Apache-2.0): the parent's {@code getContexts} is private, so the parts that apply to this partitioner are reproduced
 * here. Re-diff against the parent on a Spring Batch upgrade.
 */
@Slf4j
public class PageOrderedStepExecutionSplitter extends SimpleStepExecutionSplitter {

    private static final String STEP_NAME_SEPARATOR = ":";

    /**
     * Derived exactly as the parent derives it, so a restart finds the grid size persisted by the parent (or by an
     * earlier build) in the manager step's execution context.
     */
    static final String GRID_SIZE_KEY = SimpleStepExecutionSplitter.class.getSimpleName() + ".GRID_SIZE";

    private static final Pattern PAGE_SUFFIX = Pattern.compile("(\\d+)$");

    private static final Comparator<Entry<String, ExecutionContext>> PAGE_ORDER = Comparator
            .comparingLong((Entry<String, ExecutionContext> entry) -> pageNo(entry.getKey())).thenComparing(Entry::getKey);

    // The parent keeps these private without getters; the reproduced split() needs them.
    private final JobRepository jobRepository;
    private final Partitioner partitioner;
    private final String stepName;

    public PageOrderedStepExecutionSplitter(JobRepository jobRepository, boolean allowStartIfComplete, String stepName,
            Partitioner partitioner) {
        super(jobRepository, allowStartIfComplete, stepName, partitioner);
        // PartitionStepBuilder does not call afterPropertiesSet() on an explicit splitter, so check here.
        Assert.notNull(jobRepository, "A JobRepository is required");
        Assert.hasText(stepName, "A step name is required");
        Assert.notNull(partitioner, "A Partitioner is required");
        // The parent's PartitionNameProvider restart branch is not reproduced below; fail fast if it ever applies.
        Assert.state(!(partitioner instanceof PartitionNameProvider),
                "PageOrderedStepExecutionSplitter does not support PartitionNameProvider partitioners");
        this.jobRepository = jobRepository;
        this.partitioner = partitioner;
        this.stepName = stepName;
    }

    @Override
    public Set<StepExecution> split(StepExecution stepExecution, int gridSize) throws JobExecutionException {
        JobExecution jobExecution = stepExecution.getJobExecution();

        List<Entry<String, ExecutionContext>> orderedContexts = new ArrayList<>(getContexts(stepExecution, gridSize).entrySet());
        orderedContexts.sort(PAGE_ORDER);

        List<StepExecution> startableExecutions = new ArrayList<>(orderedContexts.size());
        for (Entry<String, ExecutionContext> context : orderedContexts) {
            // Make the step execution name unique and repeatable
            StepExecution currentStepExecution = jobExecution.createStepExecution(stepName + STEP_NAME_SEPARATOR + context.getKey());
            if (isStartable(currentStepExecution, context.getValue())) {
                startableExecutions.add(currentStepExecution);
            }
        }

        // Persist in page order so the assigned step execution ids follow the dispatch order.
        jobRepository.addAll(startableExecutions);

        if (!startableExecutions.isEmpty()) {
            log.info("Dispatching {} {} partitions in page order (first={}, last={})", startableExecutions.size(), stepName,
                    startableExecutions.get(0).getStepName(), startableExecutions.get(startableExecutions.size() - 1).getStepName());
        }
        return new LinkedHashSet<>(startableExecutions);
    }

    private Map<String, ExecutionContext> getContexts(StepExecution stepExecution, int gridSize) {
        ExecutionContext context = stepExecution.getExecutionContext();

        // If this is a restart we must retain the same grid size, ignoring the one passed in...
        int splitSize = (int) context.getLong(GRID_SIZE_KEY, gridSize);
        context.putLong(GRID_SIZE_KEY, splitSize);

        if (context.isDirty()) {
            // The context changed so we didn't already know the partitions
            jobRepository.updateExecutionContext(stepExecution);
        }
        // Without a PartitionNameProvider the parent recomputes the partitions on restart as well.
        return partitioner.partition(splitSize);
    }

    /** Numeric suffix of a partition key such as {@code partition_12}; keys without one sort last. */
    static long pageNo(String partitionKey) {
        Matcher matcher = PAGE_SUFFIX.matcher(partitionKey);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : Long.MAX_VALUE;
    }
}
