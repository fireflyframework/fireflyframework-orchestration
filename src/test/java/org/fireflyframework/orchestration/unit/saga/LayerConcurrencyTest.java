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

package org.fireflyframework.orchestration.unit.saga;

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepHandler;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
import org.fireflyframework.orchestration.saga.compensation.SagaCompensator;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaExecutionOrchestrator;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@code layerConcurrency} in {@link SagaDefinition} is enforced
 * by {@link SagaExecutionOrchestrator} when executing steps within a single
 * topology layer.
 */
class LayerConcurrencyTest {

    private SagaEngine engine;

    @BeforeEach
    void setUp() {
        OrchestrationEvents events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noOpPublisher = new NoOpEventPublisher();
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        engine = new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher);
    }

    @Test
    void executeLayer_limitsParallelism_whenConcurrencySet() {
        // Use a CountDownLatch gate to detect if more than 2 steps run concurrently.
        // If concurrency is truly limited to 2, the 3rd step cannot start until one
        // of the first 2 completes.
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicBoolean exceededLimit = new AtomicBoolean(false);

        // layerConcurrency = 2, layer has 4 steps (all independent = same layer)
        SagaDefinition saga = SagaBuilder.saga("BoundedSaga", 2)
                .step("s1").handler(boundedStep(concurrent, exceededLimit, 2, 150)).add()
                .step("s2").handler(boundedStep(concurrent, exceededLimit, 2, 150)).add()
                .step("s3").handler(boundedStep(concurrent, exceededLimit, 2, 150)).add()
                .step("s4").handler(boundedStep(concurrent, exceededLimit, 2, 150)).add()
                .build();

        long start = System.currentTimeMillis();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    long elapsed = System.currentTimeMillis() - start;
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.steps()).hasSize(4);
                    // With concurrency=2 and 4 steps each taking 150ms,
                    // minimum time is ~300ms (two batches of 2).
                    // If all ran in parallel it would be ~150ms.
                    assertThat(elapsed).isGreaterThanOrEqualTo(250L);
                    // The concurrent counter should never have exceeded 2.
                    assertThat(exceededLimit.get())
                            .as("concurrent executions should never exceed limit of 2")
                            .isFalse();
                })
                .verifyComplete();
    }

    @Test
    void executeLayer_unbounded_whenConcurrencyZero() {
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        // layerConcurrency = 0 means unbounded
        SagaDefinition saga = SagaBuilder.saga("UnboundedZeroSaga", 0)
                .step("s1").handler(delayedStep(concurrent, maxConcurrent, 150)).add()
                .step("s2").handler(delayedStep(concurrent, maxConcurrent, 150)).add()
                .step("s3").handler(delayedStep(concurrent, maxConcurrent, 150)).add()
                .step("s4").handler(delayedStep(concurrent, maxConcurrent, 150)).add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.steps()).hasSize(4);
                    // All 4 should run concurrently when unbounded
                    assertThat(maxConcurrent.get()).isEqualTo(4);
                })
                .verifyComplete();
    }

    @Test
    void executeLayer_unbounded_whenConcurrencyNegative() {
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        // layerConcurrency = -1 means unbounded (same as 0)
        SagaDefinition saga = SagaBuilder.saga("UnboundedNegSaga", -1)
                .step("s1").handler(delayedStep(concurrent, maxConcurrent, 150)).add()
                .step("s2").handler(delayedStep(concurrent, maxConcurrent, 150)).add()
                .step("s3").handler(delayedStep(concurrent, maxConcurrent, 150)).add()
                .step("s4").handler(delayedStep(concurrent, maxConcurrent, 150)).add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.steps()).hasSize(4);
                    // All 4 should run concurrently when unbounded
                    assertThat(maxConcurrent.get()).isEqualTo(4);
                })
                .verifyComplete();
    }

    /**
     * Creates a step handler that tracks concurrent executions and flags if the
     * limit is ever exceeded. Uses increment-at-start and decrement-before-completion
     * to avoid races with Flux.flatMap subscription timing.
     */
    private StepHandler<Object, String> boundedStep(AtomicInteger concurrent,
                                                     AtomicBoolean exceededLimit,
                                                     int limit, long delayMs) {
        return (StepHandler<Object, String>) (input, ctx) -> {
            int cur = concurrent.incrementAndGet();
            if (cur > limit) {
                exceededLimit.set(true);
            }
            return Mono.delay(Duration.ofMillis(delayMs))
                    .map(tick -> {
                        concurrent.decrementAndGet();
                        return "done";
                    });
        };
    }

    /**
     * Creates a step handler that tracks concurrent executions by incrementing
     * an atomic counter on start, delaying to create overlap, then decrementing
     * before completion. Records the max concurrent value observed.
     */
    private StepHandler<Object, String> delayedStep(AtomicInteger concurrent,
                                                     AtomicInteger maxConcurrent,
                                                     long delayMs) {
        return (StepHandler<Object, String>) (input, ctx) -> {
            int cur = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(cur, Math::max);
            return Mono.delay(Duration.ofMillis(delayMs))
                    .map(tick -> {
                        concurrent.decrementAndGet();
                        return "done";
                    });
        };
    }
}
