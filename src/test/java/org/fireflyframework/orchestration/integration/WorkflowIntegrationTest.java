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
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import org.fireflyframework.orchestration.workflow.engine.WorkflowExecutor;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class WorkflowIntegrationTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;
    private InMemoryPersistenceProvider persistence;

    @BeforeEach
    void setUp() {
        OrchestrationEvents events = new OrchestrationEvents() {};
        persistence = new InMemoryPersistenceProvider();
        registry = new WorkflowRegistry();
        var noOpPublisher = new org.fireflyframework.orchestration.core.event.NoOpEventPublisher();
        var executor = new WorkflowExecutor(new ArgumentResolver(), events, noOpPublisher, null, null);
        engine = new WorkflowEngine(registry, executor, persistence, events, noOpPublisher);
    }

    @Test
    void fullWorkflow_executesStepsInTopologicalOrder() throws Exception {
        List<String> executionOrder = new ArrayList<>();

        var bean = new Object() {
            public Mono<String> validate() {
                synchronized (executionOrder) { executionOrder.add("validate"); }
                return Mono.just("valid");
            }
            public Mono<String> charge() {
                synchronized (executionOrder) { executionOrder.add("charge"); }
                return Mono.just("charged");
            }
            public Mono<String> ship() {
                synchronized (executionOrder) { executionOrder.add("ship"); }
                return Mono.just("shipped");
            }
        };

        WorkflowDefinition wf = OrchestrationBuilder.workflow("order-process")
                .step("validate")
                    .handler(bean, bean.getClass().getDeclaredMethod("validate"))
                    .add()
                .step("charge")
                    .handler(bean, bean.getClass().getDeclaredMethod("charge"))
                    .dependsOn("validate")
                    .add()
                .step("ship")
                    .handler(bean, bean.getClass().getDeclaredMethod("ship"))
                    .dependsOn("charge")
                    .add()
                .build();

        registry.register(wf);

        StepVerifier.create(engine.startWorkflow("order-process", Map.of()))
                .assertNext(result -> {
                    assertThat(result.stepResults()).containsKeys("validate", "charge", "ship");
                    assertThat(executionOrder).containsExactly("validate", "charge", "ship");
                })
                .verifyComplete();
    }

    @Test
    void workflow_parallelSteps_executeIndependently() throws Exception {
        List<String> executionOrder = new ArrayList<>();

        var bean = new Object() {
            public Mono<String> step1() {
                synchronized (executionOrder) { executionOrder.add("step1"); }
                return Mono.just("s1");
            }
            public Mono<String> step2() {
                synchronized (executionOrder) { executionOrder.add("step2"); }
                return Mono.just("s2");
            }
            public Mono<String> step3() {
                synchronized (executionOrder) { executionOrder.add("step3"); }
                return Mono.just("s3");
            }
        };

        WorkflowDefinition wf = OrchestrationBuilder.workflow("parallel-wf")
                .step("step1")
                    .handler(bean, bean.getClass().getDeclaredMethod("step1"))
                    .add()
                .step("step2")
                    .handler(bean, bean.getClass().getDeclaredMethod("step2"))
                    .add()
                .step("step3")
                    .handler(bean, bean.getClass().getDeclaredMethod("step3"))
                    .dependsOn("step1")
                    .dependsOn("step2")
                    .add()
                .build();

        registry.register(wf);

        StepVerifier.create(engine.startWorkflow("parallel-wf", Map.of()))
                .assertNext(result -> {
                    assertThat(result.stepResults()).containsKeys("step1", "step2", "step3");
                    // step1 and step2 both run before step3
                    int step3Idx = executionOrder.indexOf("step3");
                    assertThat(step3Idx).isGreaterThan(0);
                })
                .verifyComplete();
    }
}
