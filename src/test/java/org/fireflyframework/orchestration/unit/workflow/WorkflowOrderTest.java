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

package org.fireflyframework.orchestration.unit.workflow;

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.argument.Input;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import org.fireflyframework.orchestration.workflow.engine.WorkflowExecutor;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class WorkflowOrderTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;

    @SuppressWarnings("unused")
    public static class TestSteps {
        public Mono<String> step(@Input Map<String, Object> input) {
            return Mono.just("done");
        }
    }

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var executor = new WorkflowExecutor(stepInvoker, events, noOpPublisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        engine = new WorkflowEngine(registry, executor, stepInvoker, persistence, events, noOpPublisher);
    }

    @Test
    void orderField_usedAsTiebreaker_withinSameLayer() throws Exception {
        var testSteps = new TestSteps();
        var method = TestSteps.class.getMethod("step", Map.class);

        // 3 steps with no dependencies (same layer), order=3,1,2
        var def = new WorkflowDefinition("order-wf", "Order WF", "test", "1.0",
                List.of(
                        new WorkflowStepDefinition("stepC", "Step C", "", List.of(), 3,
                                "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                testSteps, method, null, 0, 0, null),
                        new WorkflowStepDefinition("stepA", "Step A", "", List.of(), 1,
                                "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                testSteps, method, null, 0, 0, null),
                        new WorkflowStepDefinition("stepB", "Step B", "", List.of(), 2,
                                "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                testSteps, method, null, 0, 0, null)
                ),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("order-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    // Verify topology layers reflect order sorting
                    List<List<String>> layers = state.topologyLayers();
                    assertThat(layers).hasSize(1);
                    // Within the single layer, steps should be sorted by order: 1, 2, 3
                    assertThat(layers.get(0)).containsExactly("stepA", "stepB", "stepC");
                })
                .verifyComplete();
    }

    @Test
    void orderField_defaultZero_preservesOriginalOrder() throws Exception {
        var testSteps = new TestSteps();
        var method = TestSteps.class.getMethod("step", Map.class);

        // All steps have order=0 (default) — original insertion order preserved
        var def = new WorkflowDefinition("default-order-wf", "Default Order WF", "test", "1.0",
                List.of(
                        new WorkflowStepDefinition("alpha", "Alpha", "", List.of(), 0,
                                "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                testSteps, method, null, 0, 0, null),
                        new WorkflowStepDefinition("beta", "Beta", "", List.of(), 0,
                                "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                testSteps, method, null, 0, 0, null)
                ),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("default-order-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    // Same order value → stable sort preserves insertion order
                    assertThat(state.topologyLayers().get(0)).containsExactly("alpha", "beta");
                })
                .verifyComplete();
    }

    @Test
    void orderField_onlyAffectsWithinLayer_notAcrossLayers() throws Exception {
        var testSteps = new TestSteps();
        var method = TestSteps.class.getMethod("step", Map.class);

        // stepB depends on stepA, so they're in different layers — order doesn't cross layers
        var def = new WorkflowDefinition("cross-layer-wf", "Cross Layer WF", "test", "1.0",
                List.of(
                        new WorkflowStepDefinition("stepA", "Step A", "", List.of(), 10,
                                "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                testSteps, method, null, 0, 0, null),
                        new WorkflowStepDefinition("stepB", "Step B", "", List.of("stepA"), 1,
                                "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                testSteps, method, null, 0, 0, null)
                ),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("cross-layer-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(state.topologyLayers()).hasSize(2);
                    assertThat(state.topologyLayers().get(0)).containsExactly("stepA");
                    assertThat(state.topologyLayers().get(1)).containsExactly("stepB");
                })
                .verifyComplete();
    }
}
