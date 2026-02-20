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
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.StepTriggerMode;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
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

class WorkflowEngineTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;
    private InMemoryPersistenceProvider persistence;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        var events = new OrchestrationEvents() {};
        var noOpPublisher = new org.fireflyframework.orchestration.core.event.NoOpEventPublisher();
        var executor = new WorkflowExecutor(new ArgumentResolver(), events, noOpPublisher, null, null);
        persistence = new InMemoryPersistenceProvider();
        engine = new WorkflowEngine(registry, executor, persistence, events, noOpPublisher);
    }

    // Test bean for step execution
    @SuppressWarnings("unused")
    public static class TestSteps {
        public Mono<String> step1(@Input Map<String, Object> input) {
            return Mono.just("step1-result");
        }

        public Mono<String> step2(@Input Map<String, Object> input) {
            return Mono.just("step2-result");
        }

        public Mono<String> failStep(@Input Map<String, Object> input) {
            return Mono.error(new RuntimeException("step failed"));
        }
    }

    private WorkflowDefinition createSimpleWorkflow() throws Exception {
        var testSteps = new TestSteps();
        return new WorkflowDefinition("simple-wf", "Simple Workflow", "test", "1.0",
                List.of(
                        new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                testSteps, TestSteps.class.getMethod("step1", Map.class),
                                null, 0, 0, null),
                        new WorkflowStepDefinition("step2", "Step 2", "", List.of("step1"), 1,
                                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                testSteps, TestSteps.class.getMethod("step2", Map.class),
                                null, 0, 0, null)
                ),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
    }

    @Test
    void startWorkflow_executesAllSteps() throws Exception {
        var def = createSimpleWorkflow();
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("simple-wf", Map.of("key", "value")))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(state.executionName()).isEqualTo("simple-wf");
                    assertThat(state.stepResults()).containsKeys("step1", "step2");
                })
                .verifyComplete();
    }

    @Test
    void startWorkflow_failingStep_setsFailedStatus() throws Exception {
        var testSteps = new TestSteps();
        var def = new WorkflowDefinition("failing-wf", "Failing Workflow", "test", "1.0",
                List.of(new WorkflowStepDefinition("fail", "Fail Step", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, TestSteps.class.getMethod("failStep", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("failing-wf", Map.of()))
                .assertNext(state -> assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED))
                .verifyComplete();
    }

    @Test
    void startWorkflow_unknownId_throwsException() {
        StepVerifier.create(engine.startWorkflow("nonexistent", Map.of()))
                .expectError(org.fireflyframework.orchestration.core.exception.ExecutionNotFoundException.class)
                .verify();
    }

    @Test
    void cancelWorkflow_runningWorkflow_cancelledSuccessfully() {
        // Directly persist a RUNNING state to simulate an in-progress workflow
        var runningState = new org.fireflyframework.orchestration.core.persistence.ExecutionState(
                "cancel-test-id", "simple-wf",
                org.fireflyframework.orchestration.core.model.ExecutionPattern.WORKFLOW,
                ExecutionStatus.RUNNING, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), java.util.Set.of(), List.of(), null,
                java.time.Instant.now(), java.time.Instant.now());

        StepVerifier.create(persistence.save(runningState)
                .then(engine.cancelWorkflow("cancel-test-id")))
                .assertNext(state -> assertThat(state.status()).isEqualTo(ExecutionStatus.CANCELLED))
                .verifyComplete();
    }

    @Test
    void cancelWorkflow_completedWorkflow_rejectsWithError() throws Exception {
        var def = createSimpleWorkflow();
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("simple-wf", Map.of())
                .flatMap(state -> engine.cancelWorkflow(state.correlationId())))
                .expectError(IllegalStateException.class)
                .verify();
    }
}
