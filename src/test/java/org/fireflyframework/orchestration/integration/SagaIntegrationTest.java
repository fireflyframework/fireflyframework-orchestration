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
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.step.StepInvoker;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class SagaIntegrationTest {

    private SagaEngine engine;
    private OrchestrationEvents events;
    private DeadLetterService dlqService;

    @BeforeEach
    void setUp() {
        events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events);
        var persistence = new InMemoryPersistenceProvider();
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var dlqStore = new InMemoryDeadLetterStore();
        dlqService = new DeadLetterService(dlqStore, events);
        engine = new SagaEngine(null, events, CompensationPolicy.STRICT_SEQUENTIAL,
                orchestrator, persistence, dlqService, compensator);
    }

    @Test
    void saga_fullSuccess_allStepsComplete() {
        SagaDefinition saga = OrchestrationBuilder.saga("order-saga")
                .step("validate").handler(() -> Mono.just("valid")).add()
                .step("charge").handler(() -> Mono.just("charged")).dependsOn("validate").add()
                .step("fulfill").handler(() -> Mono.just("fulfilled")).dependsOn("charge").add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.steps()).containsKeys("validate", "charge", "fulfill");
                })
                .verifyComplete();
    }

    @Test
    void saga_stepFailure_triggersCompensation() {
        AtomicBoolean compensated = new AtomicBoolean(false);

        SagaDefinition saga = OrchestrationBuilder.saga("comp-saga")
                .step("debit")
                    .handler(() -> Mono.just("debited"))
                    .compensation((arg, ctx) -> {
                        compensated.set(true);
                        return Mono.empty();
                    })
                    .add()
                .step("credit")
                    .handler(() -> Mono.error(new RuntimeException("insufficient funds")))
                    .dependsOn("debit")
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    assertThat(compensated.get()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void saga_parallelSteps_executeAndCompensateCorrectly() {
        List<String> compensationOrder = new ArrayList<>();

        SagaDefinition saga = OrchestrationBuilder.saga("parallel-saga")
                .step("a").handler(() -> Mono.just("a-done"))
                    .compensation((arg, ctx) -> {
                        synchronized (compensationOrder) { compensationOrder.add("a"); }
                        return Mono.empty();
                    }).add()
                .step("b").handler(() -> Mono.just("b-done"))
                    .compensation((arg, ctx) -> {
                        synchronized (compensationOrder) { compensationOrder.add("b"); }
                        return Mono.empty();
                    }).add()
                .step("c").handler(() -> Mono.error(new RuntimeException("fail")))
                    .dependsOn("a").dependsOn("b").add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    // Both a and b should be compensated
                    assertThat(compensationOrder).containsExactlyInAnyOrder("a", "b");
                })
                .verifyComplete();
    }

    @Test
    void saga_withInputs_passesDataThroughSteps() {
        SagaDefinition saga = OrchestrationBuilder.saga("input-saga")
                .step("process")
                    .<String, String>handlerInput((input) -> Mono.just("processed:" + input))
                    .add()
                .build();

        StepInputs inputs = StepInputs.builder().forStepId("process", "my-data").build();

        StepVerifier.create(engine.execute(saga, inputs))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.resultOf("process", String.class)).hasValue("processed:my-data");
                })
                .verifyComplete();
    }
}
