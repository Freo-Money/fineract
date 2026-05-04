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
package org.apache.fineract.infrastructure.configuration.async;

import org.apache.fineract.cob.loan.ContextAwareTaskDecorator;
import org.apache.fineract.infrastructure.core.config.TaskExecutorConstant;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class SpringAsyncConfig implements AsyncConfigurer {

    @Bean(name = TaskExecutorConstant.LOAN_COB_CATCH_UP_TASK_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor loanCOBCatchUpThreadPoolTaskExecutor() {
        ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
        threadPoolTaskExecutor.setMaxPoolSize(1);
        return threadPoolTaskExecutor;
    }

    /**
     * Dedicated thread pool for async exception logging. Small pool to avoid resource contention. CallerRunsPolicy
     * ensures no log is silently dropped if the queue fills up. ContextAwareTaskDecorator propagates tenant context to
     * async threads.
     */
    @Bean(name = TaskExecutorConstant.EXCEPTION_LOGGING_TASK_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor exceptionLoggingThreadPoolTaskExecutor() {
        ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
        threadPoolTaskExecutor.setCorePoolSize(2);
        threadPoolTaskExecutor.setMaxPoolSize(4);
        threadPoolTaskExecutor.setQueueCapacity(500);
        threadPoolTaskExecutor.setThreadNamePrefix("exception-log-");
        threadPoolTaskExecutor.setTaskDecorator(new ContextAwareTaskDecorator());
        threadPoolTaskExecutor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        return threadPoolTaskExecutor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new CustomAsyncExceptionHandler();
    }
}
