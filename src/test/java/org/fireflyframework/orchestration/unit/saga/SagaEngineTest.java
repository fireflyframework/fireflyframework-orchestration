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
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepHandler;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
import org.fireflyframework.orchestration.saga.compensation.SagaCompensator;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaExecutionOrchestrator;
import org.fireflyframework.orchestration.saga.engine.SagaResult;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class SagaEngineTest {

    private SagaEngine engine;
    private OrchestrationEvents events;

    @BeforeEach
    void setUp() {
        events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noOpPublisher = new org.fireflyframework.orchestration.core.event.NoOpEventPublisher();
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        engine = new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher);
    }

    @Test
    void execute_successfulSaga_completesAllSteps() {
        SagaDefinition saga = SagaBuilder.saga("OrderSaga")
                .step("reserve")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("reserved"))
                    .add()
                .step("charge")
                    .dependsOn("reserve")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("charged"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.sagaName()).isEqualTo("OrderSaga");
                    assertThat(result.steps()).containsKeys("reserve", "charge");
                    assertThat(result.steps().get("reserve").status()).isEqualTo(StepStatus.DONE);
                    assertThat(result.steps().get("charge").status()).isEqualTo(StepStatus.DONE);
                })
                .verifyComplete();
    }

    @Test
    void execute_failingStep_triggersCompensation() {
        AtomicBoolean compensated = new AtomicBoolean(false);

        StepHandler<Object, String> reserveHandler = new StepHandler<>() {
            @Override
            public Mono<String> execute(Object input, org.fireflyframework.orchestration.core.context.ExecutionContext ctx) {
                return Mono.just("reserved");
            }

            @Override
            public Mono<Void> compensate(String result, org.fireflyframework.orchestration.core.context.ExecutionContext ctx) {
                compensated.set(true);
                return Mono.empty();
            }
        };

        SagaDefinition saga = SagaBuilder.saga("FailingSaga")
                .step("reserve")
                    .handler(reserveHandler)
                    .add()
                .step("charge")
                    .dependsOn("reserve")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("payment failed")))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    assertThat(result.failedSteps()).contains("charge");
                    assertThat(result.error()).isPresent();
                    assertThat(result.error().get().getMessage()).isEqualTo("payment failed");
                    assertThat(compensated.get()).isTrue();
                    assertThat(result.compensatedSteps()).contains("reserve");
                })
                .verifyComplete();
    }

    @Test
    void execute_withStepInputs_passesInputsToHandlers() {
        SagaDefinition saga = SagaBuilder.saga("InputSaga")
                .step("greet")
                    .handler((StepHandler<String, String>) (input, ctx) ->
                            Mono.just("Hello " + input))
                    .add()
                .build();

        StepInputs inputs = StepInputs.of("greet", "World");

        StepVerifier.create(engine.execute(saga, inputs))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.resultOf("greet", String.class))
                            .hasValue("Hello World");
                })
                .verifyComplete();
    }

    @Test
    void execute_parallelSteps_executeConcurrently() {
        List<String> order = new ArrayList<>();

        SagaDefinition saga = SagaBuilder.saga("ParallelSaga")
                .step("a")
                    .handler((StepHandler<Object, String>) (input, ctx) -> {
                        synchronized (order) { order.add("a"); }
                        return Mono.just("a-done");
                    })
                    .add()
                .step("b")
                    .handler((StepHandler<Object, String>) (input, ctx) -> {
                        synchronized (order) { order.add("b"); }
                        return Mono.just("b-done");
                    })
                    .add()
                .step("c")
                    .dependsOn("a", "b")
                    .handler((StepHandler<Object, String>) (input, ctx) -> {
                        synchronized (order) { order.add("c"); }
                        return Mono.just("c-done");
                    })
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.steps()).hasSize(3);
                    // "c" depends on both "a" and "b", so it must come after both
                    assertThat(order.indexOf("c")).isGreaterThan(order.indexOf("a"));
                    assertThat(order.indexOf("c")).isGreaterThan(order.indexOf("b"));
                })
                .verifyComplete();
    }

    @Test
    void execute_withBuilderHandlerOverloads_works() {
        SagaDefinition saga = SagaBuilder.saga("OverloadSaga")
                .step("supplier")
                    .handler(() -> Mono.just("from-supplier"))
                    .add()
                .step("ctxOnly")
                    .dependsOn("supplier")
                    .handlerCtx(ctx -> Mono.just("ctx-result:" + ctx.getResult("supplier")))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.resultOf("supplier", String.class)).hasValue("from-supplier");
                    assertThat(result.resultOf("ctxOnly", String.class)).hasValue("ctx-result:from-supplier");
                })
                .verifyComplete();
    }

    @Test
    void execute_singleStepFails_noCompensationNeeded() {
        SagaDefinition saga = SagaBuilder.saga("SingleFailSaga")
                .step("only")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new IllegalStateException("boom")))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    assertThat(result.failedSteps()).contains("only");
                    assertThat(result.compensatedSteps()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void execute_duplicateStepId_throwsOnBuild() {
        assertThatThrownBy(() ->
                SagaBuilder.saga("DupSaga")
                        .step("same").handler(() -> Mono.just("a")).add()
                        .step("same").handler(() -> Mono.just("b")).add()
                        .build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("Duplicate step id");
    }

    @Test
    void execute_missingHandler_throwsOnAdd() {
        assertThatThrownBy(() ->
                SagaBuilder.saga("NoHandler")
                        .step("broken").add()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("Missing handler");
    }

    @Test
    void execute_compensationWithResult_receivesStepResult() {
        List<Object> compensationArgs = new ArrayList<>();

        StepHandler<Object, String> step1 = new StepHandler<>() {
            @Override
            public Mono<String> execute(Object input, org.fireflyframework.orchestration.core.context.ExecutionContext ctx) {
                return Mono.just("reservation-123");
            }

            @Override
            public Mono<Void> compensate(String result, org.fireflyframework.orchestration.core.context.ExecutionContext ctx) {
                compensationArgs.add(result);
                return Mono.empty();
            }
        };

        SagaDefinition saga = SagaBuilder.saga("CompArgSaga")
                .step("reserve")
                    .handler(step1)
                    .add()
                .step("fail")
                    .dependsOn("reserve")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("fail")))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    assertThat(compensationArgs).hasSize(1);
                    // Compensation receives the step result or input
                    assertThat(compensationArgs.get(0)).isNotNull();
                })
                .verifyComplete();
    }
}
