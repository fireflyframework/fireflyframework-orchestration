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

package org.fireflyframework.orchestration.core.backpressure;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for backpressure strategies.
 *
 * @param strategy             the strategy name (e.g. "adaptive", "batched", "circuit-breaker")
 * @param batchSize            the number of items per batch for batched processing
 * @param failureThreshold     the number of consecutive failures before opening a circuit breaker
 * @param recoveryTimeout      the duration to wait before transitioning from OPEN to HALF_OPEN
 * @param halfOpenMaxCalls     the maximum number of calls allowed in the HALF_OPEN state
 * @param initialConcurrency   the starting concurrency level for adaptive strategies
 * @param maxConcurrency       the maximum concurrency level for adaptive strategies
 * @param minConcurrency       the minimum concurrency level for adaptive strategies
 * @param errorRateThreshold   the error rate threshold for adaptive concurrency adjustment
 */
public record BackpressureConfig(
        String strategy,
        int batchSize,
        int failureThreshold,
        Duration recoveryTimeout,
        int halfOpenMaxCalls,
        int initialConcurrency,
        int maxConcurrency,
        int minConcurrency,
        double errorRateThreshold
) {

    public BackpressureConfig {
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(recoveryTimeout, "recoveryTimeout must not be null");
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be >= 1");
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
        if (halfOpenMaxCalls < 1) throw new IllegalArgumentException("halfOpenMaxCalls must be >= 1");
        if (maxConcurrency < minConcurrency)
            throw new IllegalArgumentException("maxConcurrency must be >= minConcurrency");
        if (errorRateThreshold < 0 || errorRateThreshold > 1)
            throw new IllegalArgumentException("errorRateThreshold must be in [0, 1]");
    }

    /**
     * Returns a default configuration suitable for general-purpose use.
     */
    public static BackpressureConfig defaults() {
        return new BackpressureConfig(
                "adaptive",
                10,
                5,
                Duration.ofSeconds(30),
                3,
                4,
                16,
                1,
                0.1
        );
    }
}
