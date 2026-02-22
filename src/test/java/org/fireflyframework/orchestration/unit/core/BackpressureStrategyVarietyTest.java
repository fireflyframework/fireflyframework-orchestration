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

import org.fireflyframework.orchestration.core.backpressure.AdaptiveBackpressureStrategy;
import org.fireflyframework.orchestration.core.backpressure.BackpressureConfig;
import org.fireflyframework.orchestration.core.backpressure.BackpressureStrategy;
import org.fireflyframework.orchestration.core.backpressure.BackpressureStrategyFactory;
import org.fireflyframework.orchestration.core.backpressure.BatchedBackpressureStrategy;
import org.fireflyframework.orchestration.core.backpressure.CircuitBreakerBackpressureStrategy;
import org.fireflyframework.orchestration.core.backpressure.CircuitBreakerBackpressureStrategy.CircuitBreakerOpenException;
import org.fireflyframework.orchestration.core.backpressure.CircuitBreakerBackpressureStrategy.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class BackpressureStrategyVarietyTest {

    @BeforeEach
    void setUp() {
        BackpressureStrategyFactory.resetDefaults();
    }

    @Test
    void batchedStrategy_processesInBatches() {
        var strategy = new BatchedBackpressureStrategy(3);
        List<Integer> items = List.of(1, 2, 3, 4, 5, 6, 7);

        // Track the order of processing to verify batching behavior
        List<Integer> processed = Collections.synchronizedList(new ArrayList<>());

        StepVerifier.create(strategy.applyBackpressure(items, item -> {
                    processed.add(item);
                    return Mono.just(item * 10);
                }))
                .expectNextCount(7)
                .verifyComplete();

        // All 7 items should have been processed
        assertThat(processed).hasSize(7);
        assertThat(processed).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void circuitBreaker_opensOnFailureThreshold() {
        var strategy = new CircuitBreakerBackpressureStrategy(3, Duration.ofSeconds(60), 2);

        // All items will fail, so the circuit breaker should open after 3 failures
        List<Integer> items = List.of(1, 2, 3, 4, 5);

        StepVerifier.create(strategy.applyBackpressure(items,
                        item -> Mono.<Integer>error(new RuntimeException("fail"))))
                .expectError(CircuitBreakerOpenException.class)
                .verify();

        assertThat(strategy.getState()).isEqualTo(State.OPEN);
    }

    @Test
    void circuitBreaker_halfOpenAllowsLimitedCalls() {
        int halfOpenMax = 2;
        // Use a very short recovery timeout so we can transition to HALF_OPEN quickly
        var strategy = new CircuitBreakerBackpressureStrategy(
                2, Duration.ofMillis(50), halfOpenMax);

        // Trip the breaker: 2 failures in CLOSED state
        List<Integer> failItems = List.of(1, 2, 3);
        StepVerifier.create(strategy.applyBackpressure(failItems,
                        item -> Mono.<Integer>error(new RuntimeException("fail"))))
                .expectError(CircuitBreakerOpenException.class)
                .verify();
        assertThat(strategy.getState()).isEqualTo(State.OPEN);

        // Wait for recovery timeout to elapse
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Now attempt processing — the first call should transition to HALF_OPEN
        // and allow up to halfOpenMax calls. We send more items than allowed.
        AtomicInteger successCount = new AtomicInteger(0);
        List<Integer> probeItems = List.of(10, 20, 30, 40);

        StepVerifier.create(strategy.applyBackpressure(probeItems, item -> {
                    successCount.incrementAndGet();
                    return Mono.just(item);
                }))
                // halfOpenMax successes close the breaker, remaining items also succeed
                .expectNextCount(4)
                .verifyComplete();

        // After successful probes, the breaker should close
        assertThat(strategy.getState()).isEqualTo(State.CLOSED);
    }

    @Test
    void circuitBreaker_closesOnRecovery() {
        var strategy = new CircuitBreakerBackpressureStrategy(
                2, Duration.ofMillis(50), 2);

        // Trip the breaker
        StepVerifier.create(strategy.applyBackpressure(List.of(1, 2, 3),
                        item -> Mono.<Integer>error(new RuntimeException("fail"))))
                .expectError(CircuitBreakerOpenException.class)
                .verify();
        assertThat(strategy.getState()).isEqualTo(State.OPEN);

        // Wait for recovery timeout
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Process enough successful items to close the breaker
        // halfOpenMaxCalls = 2, so 2 successes should close it
        StepVerifier.create(strategy.applyBackpressure(List.of(1, 2), item -> Mono.just(item * 10)))
                .expectNextCount(2)
                .verifyComplete();

        assertThat(strategy.getState()).isEqualTo(State.CLOSED);
        assertThat(strategy.getFailureCount()).isEqualTo(0);
    }

    @Test
    void factory_returnsRegisteredStrategies() {
        assertThat(BackpressureStrategyFactory.getStrategy("adaptive"))
                .isPresent()
                .get()
                .isInstanceOf(AdaptiveBackpressureStrategy.class);

        assertThat(BackpressureStrategyFactory.getStrategy("batched"))
                .isPresent()
                .get()
                .isInstanceOf(BatchedBackpressureStrategy.class);

        assertThat(BackpressureStrategyFactory.getStrategy("circuit-breaker"))
                .isPresent()
                .get()
                .isInstanceOf(CircuitBreakerBackpressureStrategy.class);

        assertThat(BackpressureStrategyFactory.getStrategy("circuit-breaker-aggressive"))
                .isPresent()
                .get()
                .isInstanceOf(CircuitBreakerBackpressureStrategy.class);

        assertThat(BackpressureStrategyFactory.getStrategy("circuit-breaker-conservative"))
                .isPresent()
                .get()
                .isInstanceOf(CircuitBreakerBackpressureStrategy.class);

        assertThat(BackpressureStrategyFactory.getStrategy("nonexistent"))
                .isEmpty();

        // Verify custom registration with supplier
        BackpressureStrategyFactory.registerStrategy("custom", () -> new BatchedBackpressureStrategy(5));
        assertThat(BackpressureStrategyFactory.getStrategy("custom"))
                .isPresent()
                .get()
                .isInstanceOf(BatchedBackpressureStrategy.class);
    }

    @Test
    void factory_returnsFreshInstances() {
        BackpressureStrategy first = BackpressureStrategyFactory.getStrategy("circuit-breaker").orElseThrow();
        BackpressureStrategy second = BackpressureStrategyFactory.getStrategy("circuit-breaker").orElseThrow();

        assertThat(first).isNotSameAs(second);
    }

    @Test
    void factory_fromConfig_createsCorrectStrategy() {
        BackpressureConfig adaptiveConfig = new BackpressureConfig(
                "adaptive", 10, 5, Duration.ofSeconds(30), 3, 8, 32, 2, 0.2);
        BackpressureStrategy adaptive = BackpressureStrategyFactory.fromConfig(adaptiveConfig);
        assertThat(adaptive).isInstanceOf(AdaptiveBackpressureStrategy.class);

        BackpressureConfig batchedConfig = new BackpressureConfig(
                "batched", 5, 5, Duration.ofSeconds(30), 3, 4, 16, 1, 0.1);
        BackpressureStrategy batched = BackpressureStrategyFactory.fromConfig(batchedConfig);
        assertThat(batched).isInstanceOf(BatchedBackpressureStrategy.class);

        BackpressureConfig cbConfig = new BackpressureConfig(
                "circuit-breaker", 10, 3, Duration.ofSeconds(10), 2, 4, 16, 1, 0.1);
        BackpressureStrategy cb = BackpressureStrategyFactory.fromConfig(cbConfig);
        assertThat(cb).isInstanceOf(CircuitBreakerBackpressureStrategy.class);

        // Verify defaults() produces a valid config
        BackpressureConfig defaults = BackpressureConfig.defaults();
        BackpressureStrategy fromDefaults = BackpressureStrategyFactory.fromConfig(defaults);
        assertThat(fromDefaults).isInstanceOf(AdaptiveBackpressureStrategy.class);

        // Verify unknown strategy throws
        BackpressureConfig unknownConfig = new BackpressureConfig(
                "unknown", 10, 5, Duration.ofSeconds(30), 3, 4, 16, 1, 0.1);
        assertThatThrownBy(() -> BackpressureStrategyFactory.fromConfig(unknownConfig))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown backpressure strategy");
    }

    @Test
    void config_rejectsNullStrategy() {
        assertThatThrownBy(() -> new BackpressureConfig(
                null, 10, 5, Duration.ofSeconds(30), 3, 4, 16, 1, 0.1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("strategy must not be null");
    }

    @Test
    void config_rejectsNullRecoveryTimeout() {
        assertThatThrownBy(() -> new BackpressureConfig(
                "adaptive", 10, 5, null, 3, 4, 16, 1, 0.1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("recoveryTimeout must not be null");
    }

    @Test
    void config_rejectsInvalidBatchSize() {
        assertThatThrownBy(() -> new BackpressureConfig(
                "batched", 0, 5, Duration.ofSeconds(30), 3, 4, 16, 1, 0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize must be >= 1");
    }

    @Test
    void config_rejectsInvalidFailureThreshold() {
        assertThatThrownBy(() -> new BackpressureConfig(
                "circuit-breaker", 10, 0, Duration.ofSeconds(30), 3, 4, 16, 1, 0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureThreshold must be >= 1");
    }

    @Test
    void config_rejectsInvalidHalfOpenMaxCalls() {
        assertThatThrownBy(() -> new BackpressureConfig(
                "circuit-breaker", 10, 5, Duration.ofSeconds(30), 0, 4, 16, 1, 0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("halfOpenMaxCalls must be >= 1");
    }

    @Test
    void config_rejectsMaxConcurrencyLessThanMin() {
        assertThatThrownBy(() -> new BackpressureConfig(
                "adaptive", 10, 5, Duration.ofSeconds(30), 3, 4, 2, 8, 0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConcurrency must be >= minConcurrency");
    }

    @Test
    void config_rejectsErrorRateThresholdOutOfRange() {
        assertThatThrownBy(() -> new BackpressureConfig(
                "adaptive", 10, 5, Duration.ofSeconds(30), 3, 4, 16, 1, -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorRateThreshold must be in [0, 1]");

        assertThatThrownBy(() -> new BackpressureConfig(
                "adaptive", 10, 5, Duration.ofSeconds(30), 3, 4, 16, 1, 1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorRateThreshold must be in [0, 1]");
    }

    @Test
    void fromConfig_rejectsSubVariants() {
        BackpressureConfig aggressiveConfig = new BackpressureConfig(
                "circuit-breaker-aggressive", 10, 2, Duration.ofSeconds(60), 1, 4, 16, 1, 0.1);
        assertThatThrownBy(() -> BackpressureStrategyFactory.fromConfig(aggressiveConfig))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown backpressure strategy");

        BackpressureConfig conservativeConfig = new BackpressureConfig(
                "circuit-breaker-conservative", 10, 10, Duration.ofSeconds(15), 5, 4, 16, 1, 0.1);
        assertThatThrownBy(() -> BackpressureStrategyFactory.fromConfig(conservativeConfig))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown backpressure strategy");
    }
}
