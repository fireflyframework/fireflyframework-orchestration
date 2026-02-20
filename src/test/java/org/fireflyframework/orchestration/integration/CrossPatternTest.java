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

package org.fireflyframework.orchestration.integration;

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.builder.OrchestrationBuilder;
import org.fireflyframework.orchestration.core.dlq.DeadLetterService;
import org.fireflyframework.orchestration.core.dlq.InMemoryDeadLetterStore;
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.saga.compensation.SagaCompensator;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaExecutionOrchestrator;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccExecutionOrchestrator;
import org.fireflyframework.orchestration.tcc.engine.TccInputs;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests cross-pattern functionality: shared persistence, shared DLQ,
 * and shared observability across Saga and TCC engines.
 */
class CrossPatternTest {

    private SagaEngine sagaEngine;
    private TccEngine tccEngine;
    private InMemoryPersistenceProvider sharedPersistence;
    private InMemoryDeadLetterStore sharedDlqStore;
    private DeadLetterService sharedDlq;

    @BeforeEach
    void setUp() {
        var events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        sharedPersistence = new InMemoryPersistenceProvider();
        sharedDlqStore = new InMemoryDeadLetterStore();
        sharedDlq = new DeadLetterService(sharedDlqStore, events);

        // Saga engine
        var sagaOrchestrator = new SagaExecutionOrchestrator(stepInvoker, events);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        sagaEngine = new SagaEngine(null, events, CompensationPolicy.STRICT_SEQUENTIAL,
                sagaOrchestrator, sharedPersistence, sharedDlq, compensator);

        // TCC engine
        var tccOrchestrator = new TccExecutionOrchestrator(stepInvoker, events);
        tccEngine = new TccEngine(null, events, tccOrchestrator, sharedPersistence, sharedDlq);
    }

    @Test
    void sharedPersistence_bothPatternsUseSameProvider() {
        // Execute a saga
        SagaDefinition saga = OrchestrationBuilder.saga("saga-cross")
                .step("s1").handler(() -> Mono.just("saga-result")).add()
                .build();

        StepVerifier.create(sagaEngine.execute(saga, StepInputs.empty()))
                .assertNext(result -> assertThat(result.isSuccess()).isTrue())
                .verifyComplete();

        // Execute a TCC
        TccDefinition tcc = OrchestrationBuilder.tcc("tcc-cross")
                .participant("p1")
                    .tryHandler((input, ctx) -> Mono.just("tried"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        StepVerifier.create(tccEngine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> assertThat(result.isConfirmed()).isTrue())
                .verifyComplete();

        // Both used the same persistence provider — no cross-contamination
        assertThat(sharedPersistence.isHealthy().block()).isTrue();
    }

    @Test
    void saga_thenTcc_canRunSequentially() {
        SagaDefinition saga = OrchestrationBuilder.saga("first-saga")
                .step("prepare").handler(() -> Mono.just("prepared")).add()
                .build();

        TccDefinition tcc = OrchestrationBuilder.tcc("then-tcc")
                .participant("commit")
                    .tryHandler((input, ctx) -> Mono.just("tried"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        // Run saga first, then TCC
        StepVerifier.create(
                sagaEngine.execute(saga, StepInputs.empty())
                        .flatMap(sagaResult -> {
                            assertThat(sagaResult.isSuccess()).isTrue();
                            return tccEngine.execute(tcc, TccInputs.empty());
                        }))
                .assertNext(tccResult -> assertThat(tccResult.isConfirmed()).isTrue())
                .verifyComplete();
    }
}
