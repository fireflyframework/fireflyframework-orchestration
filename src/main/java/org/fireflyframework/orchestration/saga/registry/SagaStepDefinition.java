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

package org.fireflyframework.orchestration.saga.registry;

import org.fireflyframework.orchestration.core.step.StepHandler;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

/**
 * Mutable metadata for a single saga step.
 * Fields set during registry scanning (compensation methods, invocation methods, beans)
 * are mutable to support the multi-pass discovery process.
 */
public class SagaStepDefinition {

    public static final Duration DEFAULT_BACKOFF = Duration.ofMillis(100);
    public static final Duration DEFAULT_TIMEOUT = Duration.ZERO;

    public final String id;
    public final String compensateName;
    public List<String> dependsOn;
    public final int retry;
    public final Duration backoff;
    public final Duration timeout;
    public final String idempotencyKey;
    public final boolean jitter;
    public final double jitterFactor;
    public final boolean cpuBound;
    public final Method stepMethod;

    public Method stepInvocationMethod;
    public Object stepBean;
    public Method compensateMethod;
    public Method compensateInvocationMethod;
    public Object compensateBean;
    public StepHandler<?, ?> handler;

    public Integer compensationRetry;
    public Duration compensationBackoff;
    public Duration compensationTimeout;
    public boolean compensationCritical;

    public StepEventConfig stepEvent;

    public SagaStepDefinition(String id, String compensateName, List<String> dependsOn,
                              int retry, Duration backoff, Duration timeout,
                              String idempotencyKey, boolean jitter, double jitterFactor,
                              boolean cpuBound, Method stepMethod) {
        this.id = id;
        this.compensateName = compensateName;
        this.dependsOn = dependsOn;
        this.retry = retry;
        this.backoff = backoff != null ? backoff : DEFAULT_BACKOFF;
        this.timeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        this.idempotencyKey = idempotencyKey;
        this.jitter = jitter;
        this.jitterFactor = jitterFactor;
        this.cpuBound = cpuBound;
        this.stepMethod = stepMethod;
    }
}
