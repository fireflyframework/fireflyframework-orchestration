/*
 * Copyright 2024-2026 Firefly Software Solutions Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fireflyframework.orchestration.saga.annotation;

import java.lang.annotation.*;

/**
 * Annotates a method within a {@link Saga}-annotated class as a saga step.
 * The method will be invoked as part of the saga execution pipeline.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SagaStep {

    /**
     * Unique step identifier within this saga.
     */
    String id();

    /**
     * Name of the compensation method in the same class, invoked on saga failure.
     */
    String compensate() default "";

    /**
     * Step IDs that must complete before this step can execute.
     */
    String[] dependsOn() default {};

    /**
     * Number of retry attempts on failure (0 = no retries).
     */
    int retry() default 0;

    /**
     * Base backoff delay between retries in milliseconds (-1 = use default).
     */
    long backoffMs() default -1;

    /**
     * Step execution timeout in milliseconds (-1 = no timeout).
     */
    long timeoutMs() default -1;

    /**
     * Whether to apply jitter to the backoff delay.
     */
    boolean jitter() default false;

    /**
     * Jitter factor (0.0 to 1.0) controlling the range of randomization
     * around the base backoff.
     */
    double jitterFactor() default 0.5d;

    /**
     * SpEL expression evaluated against the input to produce an idempotency key.
     * If the key already exists in the context, the step is skipped.
     */
    String idempotencyKey() default "";

    /**
     * Whether this step performs CPU-intensive work and should be scheduled
     * on the parallel scheduler instead of the default bounded-elastic.
     */
    boolean cpuBound() default false;

    /**
     * Retry attempts for the compensation method (-1 = use step retry).
     */
    int compensationRetry() default -1;

    /**
     * Timeout for the compensation method in milliseconds (-1 = use step timeout).
     */
    long compensationTimeoutMs() default -1;

    /**
     * Backoff for compensation retries in milliseconds (-1 = use step backoff).
     */
    long compensationBackoffMs() default -1;

    /**
     * Whether this compensation step is critical. In CIRCUIT_BREAKER mode,
     * a critical compensation failure halts the entire compensation chain.
     */
    boolean compensationCritical() default false;
}
