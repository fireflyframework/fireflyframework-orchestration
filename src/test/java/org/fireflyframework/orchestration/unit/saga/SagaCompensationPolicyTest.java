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
import org.fireflyframework.orchestration.core.context.ExecutionContext;
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
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests all five compensation policies to verify correct compensation behavior.
 */
class SagaCompensationPolicyTest {

    private SagaEngine createEngine(CompensationPolicy policy) {
        var events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events);
        var compensator = new SagaCompensator(events, policy, stepInvoker);
        return new SagaEngine(null, events, orchestrator, null, null, compensator);
    }

    private static StepHandler<Object, String> compensatingHandler(String id, List<String> tracker) {
        return new StepHandler<>() {
            @Override
            public Mono<String> execute(Object input, ExecutionContext ctx) {
                return Mono.just(id);
            }
            @Override
            public Mono<Void> compensate(String result, ExecutionContext ctx) {
                tracker.add(id);
                return Mono.empty();
            }
        };
    }

    @Test
    void groupedParallel_compensatesStepsInLayersConcurrently() {
        List<String> compensationOrder = Collections.synchronizedList(new ArrayList<>());

        // s1 and s2 are parallel (same layer), s3 depends on both and fails
        SagaDefinition saga = SagaBuilder.saga("GroupedParallel")
                .step("s1").handler(compensatingHandler("s1", compensationOrder)).add()
                .step("s2").handler(compensatingHandler("s2", compensationOrder)).add()
                .step("s3").dependsOn("s1", "s2")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("fail")))
                    .add()
                .build();

        SagaEngine engine = createEngine(CompensationPolicy.GROUPED_PARALLEL);

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    // s1 and s2 were both completed and should both be compensated
                    assertThat(compensationOrder).containsExactlyInAnyOrder("s1", "s2");
                    assertThat(result.compensatedSteps()).contains("s1", "s2");
                })
                .verifyComplete();
    }

    @Test
    void bestEffortParallel_compensatesAllCompletedSteps() {
        List<String> compensationOrder = Collections.synchronizedList(new ArrayList<>());

        SagaDefinition saga = SagaBuilder.saga("BestEffort")
                .step("a").handler(compensatingHandler("a", compensationOrder)).add()
                .step("b").dependsOn("a").handler(compensatingHandler("b", compensationOrder)).add()
                .step("c").dependsOn("b")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("fail")))
                    .add()
                .build();

        SagaEngine engine = createEngine(CompensationPolicy.BEST_EFFORT_PARALLEL);

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    assertThat(compensationOrder).containsExactlyInAnyOrder("a", "b");
                })
                .verifyComplete();
    }

    @Test
    void circuitBreaker_stopsCompensationAfterCriticalFailure() {
        List<String> compensated = Collections.synchronizedList(new ArrayList<>());

        StepHandler<Object, String> criticalStep = new StepHandler<>() {
            @Override
            public Mono<String> execute(Object input, ExecutionContext ctx) {
                return Mono.just("critical");
            }
            @Override
            public Mono<Void> compensate(String result, ExecutionContext ctx) {
                return Mono.error(new RuntimeException("critical compensation failed"));
            }
        };

        // s1 -> critical -> s3 (s3 fails, triggering compensation)
        // critical step is marked as compensationCritical; its compensation fails
        // s1 compensation should be skipped because circuit is open
        SagaDefinition saga = SagaBuilder.saga("CircuitBreaker")
                .step("s1").handler(compensatingHandler("s1", compensated)).add()
                .step("critical").dependsOn("s1")
                    .handler(criticalStep)
                    .compensationCritical(true)
                    .add()
                .step("s3").dependsOn("critical")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("fail")))
                    .add()
                .build();

        SagaEngine engine = createEngine(CompensationPolicy.CIRCUIT_BREAKER);

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    // Circuit opened on critical step compensation failure
                    // s1 should NOT have been compensated
                    assertThat(compensated).doesNotContain("s1");
                })
                .verifyComplete();
    }

    @Test
    void retryWithBackoff_compensatesInReverseWithRetrySupport() {
        List<String> compensationOrder = Collections.synchronizedList(new ArrayList<>());

        // Test that RETRY_WITH_BACKOFF compensates all completed steps
        SagaDefinition saga = SagaBuilder.saga("RetryBackoff")
                .step("s1").handler(compensatingHandler("s1", compensationOrder)).add()
                .step("s2").dependsOn("s1")
                    .handler(compensatingHandler("s2", compensationOrder)).add()
                .step("s3").dependsOn("s2")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("fail")))
                    .add()
                .build();

        SagaEngine engine = createEngine(CompensationPolicy.RETRY_WITH_BACKOFF);

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    // RETRY_WITH_BACKOFF compensates in reverse order
                    assertThat(compensationOrder).containsExactly("s2", "s1");
                    assertThat(result.compensatedSteps()).contains("s1", "s2");
                })
                .verifyComplete();
    }

    @Test
    void retryWithBackoff_retriesFailingCompensation() {
        AtomicInteger attempts = new AtomicInteger(0);

        StepHandler<Object, String> retryableStep = new StepHandler<>() {
            @Override
            public Mono<String> execute(Object input, ExecutionContext ctx) {
                return Mono.just("done");
            }
            @Override
            public Mono<Void> compensate(String result, ExecutionContext ctx) {
                int attempt = attempts.incrementAndGet();
                if (attempt < 2) {
                    return Mono.error(new RuntimeException("compensation transient failure"));
                }
                return Mono.empty();
            }
        };

        SagaDefinition saga = SagaBuilder.saga("RetryActual")
                .step("retryable")
                    .handler(retryableStep)
                    .compensationRetry(3)
                    .compensationBackoff(Duration.ofMillis(10))
                    .add()
                .step("failing").dependsOn("retryable")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("fail")))
                    .add()
                .build();

        SagaEngine engine = createEngine(CompensationPolicy.RETRY_WITH_BACKOFF);

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    assertThat(result.compensatedSteps()).contains("retryable");
                    assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
                })
                .verifyComplete();
    }

    @Test
    void strictSequential_compensatesInReverseCompletionOrder() {
        List<String> compensationOrder = Collections.synchronizedList(new ArrayList<>());

        SagaDefinition saga = SagaBuilder.saga("StrictSeq")
                .step("first").handler(compensatingHandler("first", compensationOrder)).add()
                .step("second").dependsOn("first")
                    .handler(compensatingHandler("second", compensationOrder)).add()
                .step("third").dependsOn("second")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("fail")))
                    .add()
                .build();

        SagaEngine engine = createEngine(CompensationPolicy.STRICT_SEQUENTIAL);

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    // Strict sequential reverses the completion order
                    assertThat(compensationOrder).containsExactly("second", "first");
                })
                .verifyComplete();
    }
}
