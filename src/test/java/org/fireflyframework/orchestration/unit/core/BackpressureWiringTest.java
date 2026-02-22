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

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.backpressure.AdaptiveBackpressureStrategy;
import org.fireflyframework.orchestration.core.backpressure.BackpressureStrategy;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepHandler;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
import org.fireflyframework.orchestration.saga.compensation.SagaCompensator;
import org.fireflyframework.orchestration.saga.composition.CompositionContext;
import org.fireflyframework.orchestration.saga.composition.CompositionDataFlowManager;
import org.fireflyframework.orchestration.saga.composition.CompositionExecutionOrchestrator;
import org.fireflyframework.orchestration.saga.composition.SagaComposition;
import org.fireflyframework.orchestration.saga.composition.SagaCompositionBuilder;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaExecutionOrchestrator;
import org.fireflyframework.orchestration.saga.engine.SagaResult;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.tcc.composition.TccCompositionBuilder;
import org.fireflyframework.orchestration.tcc.composition.TccCompositionCompensationManager;
import org.fireflyframework.orchestration.tcc.composition.TccCompositionDataFlowManager;
import org.fireflyframework.orchestration.tcc.composition.TccCompositor;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that {@link BackpressureStrategy} is properly wired into the saga
 * execution orchestrator, saga composition orchestrator, and TCC compositor.
 */
@ExtendWith(MockitoExtension.class)
class BackpressureWiringTest {

    private OrchestrationEvents events;
    private StepInvoker stepInvoker;
    private NoOpEventPublisher noOpPublisher;

    @Mock
    private TccEngine tccEngine;

    @Mock
    private SagaEngine sagaEngine;

    @BeforeEach
    void setUp() {
        events = new OrchestrationEvents() {};
        stepInvoker = new StepInvoker(new ArgumentResolver());
        noOpPublisher = new NoOpEventPublisher();
    }

    @Test
    void sagaOrchestrator_withBackpressure_appliesStrategy() {
        // Create a spy backpressure strategy to verify it is called
        var realStrategy = new AdaptiveBackpressureStrategy(2, 8, 1, 0.1);
        var strategyUsed = new AtomicBoolean(false);

        BackpressureStrategy spyStrategy = new BackpressureStrategy() {
            @Override
            public <T, R> Flux<R> applyBackpressure(List<T> items, ItemProcessor<T, R> processor) {
                strategyUsed.set(true);
                return realStrategy.applyBackpressure(items, processor);
            }
        };

        var orchestrator = new SagaExecutionOrchestrator(
                stepInvoker, events, noOpPublisher, null, spyStrategy);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher);

        // Build a saga with two independent steps (same layer -- no deps between them)
        SagaDefinition saga = SagaBuilder.saga("BackpressureSaga")
                .step("s1")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("result-1"))
                    .add()
                .step("s2")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("result-2"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.steps().get("s1").status()).isEqualTo(StepStatus.DONE);
                    assertThat(result.steps().get("s2").status()).isEqualTo(StepStatus.DONE);
                })
                .verifyComplete();

        // Verify backpressure strategy was invoked for the multi-step layer
        assertThat(strategyUsed.get()).isTrue();
    }

    @Test
    void sagaOrchestrator_withoutBackpressure_usesDefault() {
        // Create orchestrator without backpressure strategy (null)
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher);

        // Build a saga with two independent steps
        SagaDefinition saga = SagaBuilder.saga("DefaultSaga")
                .step("s1")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("result-1"))
                    .add()
                .step("s2")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("result-2"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.steps().get("s1").status()).isEqualTo(StepStatus.DONE);
                    assertThat(result.steps().get("s2").status()).isEqualTo(StepStatus.DONE);
                })
                .verifyComplete();
    }

    @Test
    void tccCompositor_withBackpressure_appliesStrategy() {
        var strategyUsed = new AtomicBoolean(false);
        var realStrategy = new AdaptiveBackpressureStrategy(2, 8, 1, 0.1);

        BackpressureStrategy spyStrategy = new BackpressureStrategy() {
            @Override
            public <T, R> Flux<R> applyBackpressure(List<T> items, ItemProcessor<T, R> processor) {
                strategyUsed.set(true);
                return realStrategy.applyBackpressure(items, processor);
            }
        };

        var dataFlowManager = new TccCompositionDataFlowManager();
        var compensationManager = new TccCompositionCompensationManager(
                tccEngine, CompensationPolicy.STRICT_SEQUENTIAL);
        var compositor = new TccCompositor(
                tccEngine, events, dataFlowManager, compensationManager, spyStrategy);

        // Build composition with two parallel TCCs (same layer, no deps)
        var composition = TccCompositionBuilder.composition("bp-tcc-flow")
                .tcc("payment").tccName("PaymentTcc").add()
                .tcc("inventory").tccName("InventoryTcc").add()
                .build();

        when(tccEngine.execute(eq("PaymentTcc"), anyMap()))
                .thenReturn(Mono.just(confirmedTccResult("PaymentTcc")));
        when(tccEngine.execute(eq("InventoryTcc"), anyMap()))
                .thenReturn(Mono.just(confirmedTccResult("InventoryTcc")));

        StepVerifier.create(compositor.compose(composition, Map.of()))
                .assertNext(result -> {
                    assertThat(result.success()).isTrue();
                    assertThat(result.tccResults()).hasSize(2);
                    assertThat(result.tccResults().get("payment").isConfirmed()).isTrue();
                    assertThat(result.tccResults().get("inventory").isConfirmed()).isTrue();
                })
                .verifyComplete();

        // Verify backpressure strategy was invoked for the multi-TCC layer
        assertThat(strategyUsed.get()).isTrue();
    }

    @Test
    void compositionOrchestrator_withBackpressure_appliesStrategy() {
        var strategyUsed = new AtomicBoolean(false);
        var realStrategy = new AdaptiveBackpressureStrategy(2, 8, 1, 0.1);

        BackpressureStrategy spyStrategy = new BackpressureStrategy() {
            @Override
            public <T, R> Flux<R> applyBackpressure(List<T> items, ItemProcessor<T, R> processor) {
                strategyUsed.set(true);
                return realStrategy.applyBackpressure(items, processor);
            }
        };

        // Mock the SagaEngine to return successful results when called by name
        when(sagaEngine.execute(eq("SagaA"), anyMap()))
                .thenReturn(Mono.just(successSagaResult("SagaA")));
        when(sagaEngine.execute(eq("SagaB"), anyMap()))
                .thenReturn(Mono.just(successSagaResult("SagaB")));

        var dataFlowManager = new CompositionDataFlowManager();
        var compositionOrchestrator = new CompositionExecutionOrchestrator(
                sagaEngine, dataFlowManager, spyStrategy);

        // Build composition with two parallel sagas (same layer, no deps)
        SagaComposition composition = SagaCompositionBuilder.composition("bp-saga-comp")
                .saga("a").sagaName("SagaA").add()
                .saga("b").sagaName("SagaB").add()
                .build();

        var layers = composition.getExecutableLayers();
        var context = new CompositionContext("test-corr");

        StepVerifier.create(compositionOrchestrator.executeLayers(
                        composition, layers, Map.of(), context, 0))
                .assertNext(success -> assertThat(success).isTrue())
                .verifyComplete();

        // Verify backpressure strategy was invoked for the multi-saga layer
        assertThat(strategyUsed.get()).isTrue();
    }

    // --- Helper methods ---

    private TccResult confirmedTccResult(String tccName) {
        ExecutionContext ctx = ExecutionContext.forTcc(null, tccName);
        return TccResult.confirmed(tccName, ctx, Map.of());
    }

    private SagaResult successSagaResult(String sagaName) {
        return SagaResult.from(sagaName,
                ExecutionContext.forSaga(null, sagaName),
                Map.of(), Map.of(), List.of());
    }
}
