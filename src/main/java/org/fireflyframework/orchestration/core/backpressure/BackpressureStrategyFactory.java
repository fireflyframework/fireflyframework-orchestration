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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Factory and registry for {@link BackpressureStrategy} instances.
 * <p>
 * Pre-registers the following strategy suppliers:
 * <ul>
 *     <li>{@code "adaptive"} — adaptive concurrency based on error rate</li>
 *     <li>{@code "batched"} — fixed-size batch processing</li>
 *     <li>{@code "circuit-breaker"} — circuit breaker with default thresholds</li>
 *     <li>{@code "circuit-breaker-aggressive"} — circuit breaker with low failure threshold</li>
 *     <li>{@code "circuit-breaker-conservative"} — circuit breaker with high failure threshold</li>
 * </ul>
 * <p>
 * Each call to {@link #getStrategy(String)} returns a fresh instance to avoid
 * shared mutable state between callers.
 */
public final class BackpressureStrategyFactory {

    private static final ConcurrentHashMap<String, Supplier<BackpressureStrategy>> REGISTRY =
            new ConcurrentHashMap<>();

    static {
        registerDefaults();
    }

    private BackpressureStrategyFactory() {
        // Utility class — no instantiation
    }

    /**
     * Returns a fresh strategy instance registered under the given name.
     *
     * @param name the strategy name
     * @return an {@link Optional} containing a new strategy instance, or empty if not found
     */
    public static Optional<BackpressureStrategy> getStrategy(String name) {
        Supplier<BackpressureStrategy> supplier = REGISTRY.get(name);
        return supplier != null ? Optional.of(supplier.get()) : Optional.empty();
    }

    /**
     * Registers a strategy supplier under the given name, replacing any previous registration.
     *
     * @param name     the strategy name
     * @param supplier the strategy supplier that creates fresh instances
     */
    public static void registerStrategy(String name, Supplier<BackpressureStrategy> supplier) {
        REGISTRY.put(name, supplier);
    }

    /**
     * Creates a {@link BackpressureStrategy} from the given configuration.
     * <p>
     * The strategy type is determined by the {@link BackpressureConfig#strategy()} field.
     * Only the base strategy names ({@code "adaptive"}, {@code "batched"},
     * {@code "circuit-breaker"}) are supported. For sub-variant presets such as
     * {@code "circuit-breaker-aggressive"}, use {@link #getStrategy(String)} directly.
     *
     * @param config the backpressure configuration
     * @return the configured strategy
     * @throws IllegalArgumentException if the strategy name is not recognized
     */
    public static BackpressureStrategy fromConfig(BackpressureConfig config) {
        return switch (config.strategy()) {
            case "adaptive" -> new AdaptiveBackpressureStrategy(
                    config.initialConcurrency(),
                    config.maxConcurrency(),
                    config.minConcurrency(),
                    config.errorRateThreshold()
            );
            case "batched" -> new BatchedBackpressureStrategy(config.batchSize());
            case "circuit-breaker" -> new CircuitBreakerBackpressureStrategy(
                    config.failureThreshold(),
                    config.recoveryTimeout(),
                    config.halfOpenMaxCalls()
            );
            default -> throw new IllegalArgumentException(
                    "Unknown backpressure strategy: " + config.strategy());
        };
    }

    /**
     * Resets the registry to its default state, removing any custom registrations.
     */
    public static void resetDefaults() {
        REGISTRY.clear();
        registerDefaults();
    }

    private static void registerDefaults() {
        REGISTRY.put("adaptive", AdaptiveBackpressureStrategy::new);
        REGISTRY.put("batched", BatchedBackpressureStrategy::new);
        REGISTRY.put("circuit-breaker", CircuitBreakerBackpressureStrategy::new);
        REGISTRY.put("circuit-breaker-aggressive",
                () -> new CircuitBreakerBackpressureStrategy(2, Duration.ofSeconds(60), 1));
        REGISTRY.put("circuit-breaker-conservative",
                () -> new CircuitBreakerBackpressureStrategy(10, Duration.ofSeconds(15), 5));
    }
}
