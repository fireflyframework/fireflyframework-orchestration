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

package org.fireflyframework.orchestration.core.model;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public record RetryPolicy(
        int maxAttempts,
        Duration initialDelay,
        Duration maxDelay,
        double multiplier,
        double jitterFactor,
        String[] retryableExceptions
) {
    public static final RetryPolicy DEFAULT = new RetryPolicy(
            3, Duration.ofSeconds(1), Duration.ofMinutes(5), 2.0, 0.0, new String[]{});

    public static final RetryPolicy NO_RETRY = new RetryPolicy(
            1, Duration.ZERO, Duration.ZERO, 1.0, 0.0, new String[]{});

    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be >= 1.0");
        if (jitterFactor < 0.0 || jitterFactor > 1.0) throw new IllegalArgumentException("jitterFactor must be between 0.0 and 1.0");
    }

    public Duration calculateDelay(int attempt) {
        if (attempt <= 0) return initialDelay;
        long delayMs = initialDelay.toMillis();
        for (int i = 0; i < attempt; i++) {
            delayMs = (long) (delayMs * multiplier);
        }
        delayMs = Math.min(delayMs, maxDelay.toMillis());
        if (jitterFactor > 0.0) {
            long jitter = (long) (delayMs * jitterFactor);
            delayMs = delayMs - jitter + ThreadLocalRandom.current().nextLong(2 * jitter + 1);
            delayMs = Math.max(0, delayMs);
        }
        return Duration.ofMillis(delayMs);
    }

    public boolean shouldRetry(int currentAttempt) {
        return currentAttempt < maxAttempts;
    }
}
