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
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepHandler;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
import org.fireflyframework.orchestration.saga.compensation.CompensationErrorHandler;
import org.fireflyframework.orchestration.saga.compensation.CompensationErrorHandler.CompensationErrorResult;
import org.fireflyframework.orchestration.saga.compensation.DefaultCompensationErrorHandler;
import org.fireflyframework.orchestration.saga.compensation.SagaCompensator;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaExecutionOrchestrator;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests verifying that {@link CompensationErrorHandler} is properly wired into
 * {@link SagaCompensator} and that each result code is honored during compensation.
 */
class CompensationErrorHandlerWiringTest {

    private SagaEngine createEngine(CompensationPolicy policy, CompensationErrorHandler handler) {
        var events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noOpPublisher = new org.fireflyframework.orchestration.core.event.NoOpEventPublisher();
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, policy, stepInvoker, handler);
        return new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher);
    }

    /**
     * Custom handler returns RETRY on first call, then CONTINUE.
     * The compensation step fails once, handler says RETRY, compensation is attempted again.
     * Since RETRY in the non-retry path falls through to CONTINUE, the compensation error is swallowed.
     * We verify the handler was called.
     */
    @Test
    void compensator_usesHandler_onCompensationError() {
        AtomicInteger handlerCalls = new AtomicInteger(0);
        AtomicInteger compensationAttempts = new AtomicInteger(0);

        CompensationErrorHandler handler = (sagaName, stepId, error, attempt) -> {
            int call = handlerCalls.incrementAndGet();
            // Return CONTINUE (which swallows the error and moves on)
            return CompensationErrorResult.CONTINUE;
        };

        StepHandler<Object, String> failingCompensation = new StepHandler<>() {
            @Override
            public Mono<String> execute(Object input, ExecutionContext ctx) {
                return Mono.just("done");
            }
            @Override
            public Mono<Void> compensate(String result, ExecutionContext ctx) {
                compensationAttempts.incrementAndGet();
                return Mono.error(new RuntimeException("compensation failed"));
            }
        };

        SagaDefinition saga = SagaBuilder.saga("HandlerTest")
                .step("s1").handler(failingCompensation).add()
                .step("s2").dependsOn("s1")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("trigger compensation")))
                    .add()
                .build();

        SagaEngine engine = createEngine(CompensationPolicy.STRICT_SEQUENTIAL, handler);

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    // Handler was called when compensation of s1 failed
                    assertThat(handlerCalls.get()).isGreaterThanOrEqualTo(1);
                    // Compensation was attempted
                    assertThat(compensationAttempts.get()).isGreaterThanOrEqualTo(1);
                })
                .verifyComplete();
    }

    /**
     * Handler returns FAIL_SAGA. Compensation error propagates through the engine as
     * a reactive error signal, preventing the normal SagaResult from being constructed.
     */
    @Test
    void compensator_failsSaga_whenHandlerReturnsFail() {
        CompensationErrorHandler handler = (sagaName, stepId, error, attempt) ->
                CompensationErrorResult.FAIL_SAGA;

        StepHandler<Object, String> failingCompensation = new StepHandler<>() {
            @Override
            public Mono<String> execute(Object input, ExecutionContext ctx) {
                return Mono.just("done");
            }
            @Override
            public Mono<Void> compensate(String result, ExecutionContext ctx) {
                return Mono.error(new RuntimeException("compensation exploded"));
            }
        };

        SagaDefinition saga = SagaBuilder.saga("FailSagaTest")
                .step("s1").handler(failingCompensation).add()
                .step("s2").dependsOn("s1")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("trigger")))
                    .add()
                .build();

        SagaEngine engine = createEngine(CompensationPolicy.STRICT_SEQUENTIAL, handler);

        // When FAIL_SAGA is returned, the compensation error propagates through the
        // engine's reactive chain as an error signal (no SagaResult is produced).
        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .expectErrorMatches(err -> err instanceof RuntimeException
                        && err.getMessage().equals("compensation exploded"))
                .verify();
    }

    /**
     * Handler returns SKIP_STEP. Step is skipped silently and compensation continues.
     */
    @Test
    void compensator_skipsStep_whenHandlerReturnsSkip() {
        List<String> compensated = Collections.synchronizedList(new ArrayList<>());

        CompensationErrorHandler handler = (sagaName, stepId, error, attempt) ->
                CompensationErrorResult.SKIP_STEP;

        StepHandler<Object, String> failingCompStep = new StepHandler<>() {
            @Override
            public Mono<String> execute(Object input, ExecutionContext ctx) {
                return Mono.just("done");
            }
            @Override
            public Mono<Void> compensate(String result, ExecutionContext ctx) {
                return Mono.error(new RuntimeException("cannot compensate"));
            }
        };

        StepHandler<Object, String> successCompStep = new StepHandler<>() {
            @Override
            public Mono<String> execute(Object input, ExecutionContext ctx) {
                return Mono.just("ok");
            }
            @Override
            public Mono<Void> compensate(String result, ExecutionContext ctx) {
                compensated.add("s1");
                return Mono.empty();
            }
        };

        // s1 -> s2 (failing compensation) -> s3 (triggers compensation)
        SagaDefinition saga = SagaBuilder.saga("SkipTest")
                .step("s1").handler(successCompStep).add()
                .step("s2").dependsOn("s1").handler(failingCompStep).add()
                .step("s3").dependsOn("s2")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("trigger")))
                    .add()
                .build();

        SagaEngine engine = createEngine(CompensationPolicy.STRICT_SEQUENTIAL, handler);

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    // s2's compensation failed but was skipped; s1 compensation succeeded
                    assertThat(compensated).contains("s1");
                })
                .verifyComplete();
    }

    /**
     * Handler returns MARK_COMPENSATED. Step status is set to COMPENSATED despite the error.
     */
    @Test
    void compensator_marksCompensated_whenHandlerSaysSo() {
        CompensationErrorHandler handler = (sagaName, stepId, error, attempt) ->
                CompensationErrorResult.MARK_COMPENSATED;

        StepHandler<Object, String> failingCompensation = new StepHandler<>() {
            @Override
            public Mono<String> execute(Object input, ExecutionContext ctx) {
                return Mono.just("done");
            }
            @Override
            public Mono<Void> compensate(String result, ExecutionContext ctx) {
                return Mono.error(new RuntimeException("compensation failed but mark it"));
            }
        };

        SagaDefinition saga = SagaBuilder.saga("MarkCompensatedTest")
                .step("s1").handler(failingCompensation).add()
                .step("s2").dependsOn("s1")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("trigger")))
                    .add()
                .build();

        SagaEngine engine = createEngine(CompensationPolicy.STRICT_SEQUENTIAL, handler);

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    // Even though compensation errored, step should be marked compensated
                    assertThat(result.compensatedSteps()).contains("s1");
                })
                .verifyComplete();
    }

    /**
     * Handler returns RETRY. Compensation is re-attempted once.
     * If retry succeeds, step is compensated normally.
     */
    @Test
    void compensator_retriesOnce_whenHandlerReturnsRetry() {
        AtomicInteger compensationAttempts = new AtomicInteger(0);

        CompensationErrorHandler handler = (sagaName, stepId, error, attempt) ->
                CompensationErrorResult.RETRY;

        StepHandler<Object, String> failOnceCompensation = new StepHandler<>() {
            @Override
            public Mono<String> execute(Object input, ExecutionContext ctx) {
                return Mono.just("done");
            }
            @Override
            public Mono<Void> compensate(String result, ExecutionContext ctx) {
                int attempt = compensationAttempts.incrementAndGet();
                if (attempt == 1) {
                    return Mono.error(new RuntimeException("first compensation fails"));
                }
                return Mono.empty(); // second attempt succeeds
            }
        };

        SagaDefinition saga = SagaBuilder.saga("RetryTest")
                .step("s1").handler(failOnceCompensation).add()
                .step("s2").dependsOn("s1")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("trigger compensation")))
                    .add()
                .build();

        SagaEngine engine = createEngine(CompensationPolicy.STRICT_SEQUENTIAL, handler);

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    // Compensation should have been attempted twice (initial + retry)
                    assertThat(compensationAttempts.get()).isEqualTo(2);
                    // Step should be successfully compensated on retry
                    assertThat(result.compensatedSteps()).contains("s1");
                })
                .verifyComplete();
    }

    /**
     * Handler returns RETRY but retry also fails.
     * Should fall back to CONTINUE behavior (swallow error, move on).
     */
    @Test
    void compensator_retryAlsoFails_fallsBackToContinue() {
        AtomicInteger compensationAttempts = new AtomicInteger(0);

        CompensationErrorHandler handler = (sagaName, stepId, error, attempt) ->
                CompensationErrorResult.RETRY;

        StepHandler<Object, String> alwaysFailCompensation = new StepHandler<>() {
            @Override
            public Mono<String> execute(Object input, ExecutionContext ctx) {
                return Mono.just("done");
            }
            @Override
            public Mono<Void> compensate(String result, ExecutionContext ctx) {
                compensationAttempts.incrementAndGet();
                return Mono.error(new RuntimeException("always fails"));
            }
        };

        SagaDefinition saga = SagaBuilder.saga("RetryFailTest")
                .step("s1").handler(alwaysFailCompensation).add()
                .step("s2").dependsOn("s1")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("trigger compensation")))
                    .add()
                .build();

        SagaEngine engine = createEngine(CompensationPolicy.STRICT_SEQUENTIAL, handler);

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    // Initial attempt + retry attempt
                    assertThat(compensationAttempts.get()).isEqualTo(2);
                })
                .verifyComplete();
    }

    /**
     * DefaultCompensationErrorHandler returns CONTINUE. Compensation continues despite error.
     */
    @Test
    void compensator_defaultHandler_returnsContinue() {
        // Verify the default handler returns CONTINUE
        DefaultCompensationErrorHandler defaultHandler = new DefaultCompensationErrorHandler();
        assertThat(defaultHandler.handle("test", "step1", new RuntimeException("err"), 0))
                .isEqualTo(CompensationErrorResult.CONTINUE);

        // Use null handler (falls back to DefaultCompensationErrorHandler)
        List<String> compensated = Collections.synchronizedList(new ArrayList<>());

        StepHandler<Object, String> failingCompStep = new StepHandler<>() {
            @Override
            public Mono<String> execute(Object input, ExecutionContext ctx) {
                return Mono.just("done");
            }
            @Override
            public Mono<Void> compensate(String result, ExecutionContext ctx) {
                return Mono.error(new RuntimeException("compensation failed"));
            }
        };

        StepHandler<Object, String> successCompStep = new StepHandler<>() {
            @Override
            public Mono<String> execute(Object input, ExecutionContext ctx) {
                return Mono.just("ok");
            }
            @Override
            public Mono<Void> compensate(String result, ExecutionContext ctx) {
                compensated.add("s1");
                return Mono.empty();
            }
        };

        // s1 -> s2 (failing comp) -> s3 (triggers compensation)
        SagaDefinition saga = SagaBuilder.saga("DefaultHandlerTest")
                .step("s1").handler(successCompStep).add()
                .step("s2").dependsOn("s1").handler(failingCompStep).add()
                .step("s3").dependsOn("s2")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("trigger")))
                    .add()
                .build();

        // Pass null handler — should use DefaultCompensationErrorHandler internally
        SagaEngine engine = createEngine(CompensationPolicy.STRICT_SEQUENTIAL, null);

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    // Default handler returns CONTINUE, so s2 error is swallowed and s1 compensates
                    assertThat(compensated).contains("s1");
                })
                .verifyComplete();
    }
}
