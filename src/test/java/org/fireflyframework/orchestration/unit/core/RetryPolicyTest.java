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

package org.fireflyframework.orchestration.unit.core;

import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class RetryPolicyTest {

    @Test
    void shouldRetry_withinMaxAttempts_returnsTrue() {
        var policy = RetryPolicy.DEFAULT;
        assertThat(policy.shouldRetry(0)).isTrue();
        assertThat(policy.shouldRetry(1)).isTrue();
        assertThat(policy.shouldRetry(2)).isTrue();
    }

    @Test
    void shouldRetry_atMaxAttempts_returnsFalse() {
        var policy = RetryPolicy.DEFAULT;
        assertThat(policy.shouldRetry(3)).isFalse();
        assertThat(policy.shouldRetry(4)).isFalse();
    }

    @Test
    void calculateDelay_exponentialBackoff_correctValues() {
        var policy = new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(10), 2.0, 0.0, new String[]{});
        assertThat(policy.calculateDelay(0)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.calculateDelay(1)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.calculateDelay(2)).isEqualTo(Duration.ofMillis(400));
    }

    @Test
    void calculateDelay_cappedAtMaxDelay() {
        var policy = new RetryPolicy(10, Duration.ofMillis(100), Duration.ofMillis(500), 2.0, 0.0, new String[]{});
        assertThat(policy.calculateDelay(5).toMillis()).isLessThanOrEqualTo(500);
        assertThat(policy.calculateDelay(10).toMillis()).isLessThanOrEqualTo(500);
    }

    @Test
    void calculateDelay_withJitter_withinBounds() {
        var policy = new RetryPolicy(3, Duration.ofMillis(1000), Duration.ofSeconds(10), 2.0, 0.5, new String[]{});
        for (int i = 0; i < 100; i++) {
            long delayMs = policy.calculateDelay(1).toMillis();
            // Base would be 2000ms, jitter of 0.5 means +/- 1000ms, so range [1000, 3000]
            assertThat(delayMs).isBetween(0L, 10000L);
        }
    }

    @Test
    void DEFAULT_policy_hasCorrectValues() {
        var policy = RetryPolicy.DEFAULT;
        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.initialDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.maxDelay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.multiplier()).isEqualTo(2.0);
        assertThat(policy.jitterFactor()).isEqualTo(0.0);
    }

    @Test
    void NO_RETRY_policy_doesNotRetry() {
        var policy = RetryPolicy.NO_RETRY;
        assertThat(policy.maxAttempts()).isEqualTo(1);
        assertThat(policy.shouldRetry(0)).isTrue();
        assertThat(policy.shouldRetry(1)).isFalse();
    }

    @Test
    void invalidMaxAttempts_throwsException() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(5), 2.0, 0.0, new String[]{}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidMultiplier_throwsException() {
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(5), 0.5, 0.0, new String[]{}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidJitterFactor_throwsException() {
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(5), 2.0, 1.5, new String[]{}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
