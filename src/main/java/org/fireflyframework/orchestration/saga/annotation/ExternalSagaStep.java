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
 * Annotates a method defined outside the {@link Saga}-annotated class
 * as a step that participates in the named saga. This allows saga steps
 * to be contributed from external beans.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExternalSagaStep {

    /**
     * The name of the saga this step belongs to.
     */
    String saga();

    /**
     * Unique step identifier within the target saga.
     */
    String id();

    /**
     * Name of the compensation method in the same class.
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
     * Jitter factor (0.0 to 1.0) controlling the range of randomization.
     */
    double jitterFactor() default 0.5d;

    /**
     * SpEL expression for idempotency key generation.
     */
    String idempotencyKey() default "";

    /**
     * Whether this step is CPU-bound.
     */
    boolean cpuBound() default false;

    /**
     * Retry attempts for compensation (-1 = use step retry).
     */
    int compensationRetry() default -1;

    /**
     * Timeout for compensation in milliseconds (-1 = use step timeout).
     */
    long compensationTimeoutMs() default -1;

    /**
     * Backoff for compensation retries in milliseconds (-1 = use step backoff).
     */
    long compensationBackoffMs() default -1;

    /**
     * Whether compensation failure is critical (affects CIRCUIT_BREAKER policy).
     */
    boolean compensationCritical() default false;
}
