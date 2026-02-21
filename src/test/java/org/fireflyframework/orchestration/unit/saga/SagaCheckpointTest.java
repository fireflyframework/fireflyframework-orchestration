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
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class SagaCheckpointTest {

    private OrchestrationEvents events;
    private StepInvoker stepInvoker;
    private org.fireflyframework.orchestration.core.event.NoOpEventPublisher noOpPublisher;

    @BeforeEach
    void setUp() {
        events = new OrchestrationEvents() {};
        stepInvoker = new StepInvoker(new ArgumentResolver());
        noOpPublisher = new org.fireflyframework.orchestration.core.event.NoOpEventPublisher();
    }

    @Test
    void checkpoint_savedAfterEachStepSuccess() {
        InMemoryPersistenceProvider persistence = new InMemoryPersistenceProvider();

        // Track how many times save is called
        AtomicInteger saveCount = new AtomicInteger(0);
        ExecutionPersistenceProvider trackingPersistence = new ExecutionPersistenceProvider() {
            @Override
            public Mono<Void> save(ExecutionState state) {
                saveCount.incrementAndGet();
                return persistence.save(state);
            }
            @Override
            public Mono<java.util.Optional<ExecutionState>> findById(String correlationId) {
                return persistence.findById(correlationId);
            }
            @Override
            public Mono<Void> updateStatus(String correlationId, org.fireflyframework.orchestration.core.model.ExecutionStatus status) {
                return persistence.updateStatus(correlationId, status);
            }
            @Override
            public reactor.core.publisher.Flux<ExecutionState> findByPattern(org.fireflyframework.orchestration.core.model.ExecutionPattern pattern) {
                return persistence.findByPattern(pattern);
            }
            @Override
            public reactor.core.publisher.Flux<ExecutionState> findByStatus(org.fireflyframework.orchestration.core.model.ExecutionStatus status) {
                return persistence.findByStatus(status);
            }
            @Override
            public reactor.core.publisher.Flux<ExecutionState> findInFlight() {
                return persistence.findInFlight();
            }
            @Override
            public reactor.core.publisher.Flux<ExecutionState> findStale(java.time.Instant before) {
                return persistence.findStale(before);
            }
            @Override
            public Mono<Long> cleanup(java.time.Duration olderThan) {
                return persistence.cleanup(olderThan);
            }
            @Override
            public Mono<Boolean> isHealthy() {
                return persistence.isHealthy();
            }
        };

        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher, trackingPersistence);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, trackingPersistence, null, compensator, noOpPublisher);

        SagaDefinition saga = SagaBuilder.saga("CheckpointSaga")
                .step("step1")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("result1"))
                    .add()
                .step("step2")
                    .dependsOn("step1")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("result2"))
                    .add()
                .step("step3")
                    .dependsOn("step2")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("result3"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    // 1 initial + 3 step checkpoints + 1 final = 5 total saves
                    assertThat(saveCount.get()).isEqualTo(5);
                })
                .verifyComplete();
    }

    @Test
    void checkpoint_containsCurrentStepResults() {
        List<ExecutionState> savedStates = new ArrayList<>();
        InMemoryPersistenceProvider persistence = new InMemoryPersistenceProvider();

        ExecutionPersistenceProvider capturingPersistence = new ExecutionPersistenceProvider() {
            @Override
            public Mono<Void> save(ExecutionState state) {
                savedStates.add(state);
                return persistence.save(state);
            }
            @Override
            public Mono<java.util.Optional<ExecutionState>> findById(String correlationId) {
                return persistence.findById(correlationId);
            }
            @Override
            public Mono<Void> updateStatus(String correlationId, org.fireflyframework.orchestration.core.model.ExecutionStatus status) {
                return persistence.updateStatus(correlationId, status);
            }
            @Override
            public reactor.core.publisher.Flux<ExecutionState> findByPattern(org.fireflyframework.orchestration.core.model.ExecutionPattern pattern) {
                return persistence.findByPattern(pattern);
            }
            @Override
            public reactor.core.publisher.Flux<ExecutionState> findByStatus(org.fireflyframework.orchestration.core.model.ExecutionStatus status) {
                return persistence.findByStatus(status);
            }
            @Override
            public reactor.core.publisher.Flux<ExecutionState> findInFlight() {
                return persistence.findInFlight();
            }
            @Override
            public reactor.core.publisher.Flux<ExecutionState> findStale(java.time.Instant before) {
                return persistence.findStale(before);
            }
            @Override
            public Mono<Long> cleanup(java.time.Duration olderThan) {
                return persistence.cleanup(olderThan);
            }
            @Override
            public Mono<Boolean> isHealthy() {
                return persistence.isHealthy();
            }
        };

        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher, capturingPersistence);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, capturingPersistence, null, compensator, noOpPublisher);

        SagaDefinition saga = SagaBuilder.saga("ContentCheckpointSaga")
                .step("step1")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("result1"))
                    .add()
                .step("step2")
                    .dependsOn("step1")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("result2"))
                    .add()
                .step("step3")
                    .dependsOn("step2")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("result3"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();

                    // savedStates: [0]=initial, [1]=after step1, [2]=after step2, [3]=after step3, [4]=final
                    assertThat(savedStates).hasSizeGreaterThanOrEqualTo(4);

                    // After step 1 checkpoint (index 1): should contain step1 result
                    ExecutionState afterStep1 = savedStates.get(1);
                    assertThat(afterStep1.stepResults()).containsKey("step1");
                    assertThat(afterStep1.stepStatuses().get("step1")).isEqualTo(StepStatus.DONE);

                    // After step 2 checkpoint (index 2): should contain step1 AND step2 results
                    ExecutionState afterStep2 = savedStates.get(2);
                    assertThat(afterStep2.stepResults()).containsKeys("step1", "step2");
                    assertThat(afterStep2.stepStatuses().get("step1")).isEqualTo(StepStatus.DONE);
                    assertThat(afterStep2.stepStatuses().get("step2")).isEqualTo(StepStatus.DONE);
                })
                .verifyComplete();
    }

    @Test
    void checkpoint_skipped_whenPersistenceNull() {
        // No persistence injected — uses the old 3-arg constructor
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher);

        SagaDefinition saga = SagaBuilder.saga("NullPersistenceSaga")
                .step("step1")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("result1"))
                    .add()
                .step("step2")
                    .dependsOn("step1")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("result2"))
                    .add()
                .build();

        // Should complete without errors even with null persistence
        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                })
                .verifyComplete();
    }
}
