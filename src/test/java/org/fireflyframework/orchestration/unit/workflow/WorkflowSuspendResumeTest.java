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
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.StepTriggerMode;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests workflow suspend/resume lifecycle.
 */
class WorkflowSuspendResumeTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;
    private InMemoryPersistenceProvider persistence;

    @SuppressWarnings("unused")
    public static class TestSteps {
        public Mono<String> step1(@Input Map<String, Object> input) {
            return Mono.just("step1-result");
        }
        public Mono<String> step2(@Input Map<String, Object> input) {
            return Mono.just("step2-result");
        }
    }

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        var events = new OrchestrationEvents() {};
        var executor = new WorkflowExecutor(new ArgumentResolver(), events);
        persistence = new InMemoryPersistenceProvider();
        engine = new WorkflowEngine(registry, executor, persistence, events);
    }

    private WorkflowDefinition createSimpleWorkflow() throws Exception {
        var testSteps = new TestSteps();
        return new WorkflowDefinition("suspend-wf", "Suspend Workflow", "test", "1.0",
                List.of(
                        new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                testSteps, TestSteps.class.getMethod("step1", Map.class)),
                        new WorkflowStepDefinition("step2", "Step 2", "", List.of("step1"), 1,
                                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                testSteps, TestSteps.class.getMethod("step2", Map.class))
                ),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
    }

    @Test
    void suspendRunningWorkflow_transitionsToSuspended() {
        // Create a RUNNING state in persistence (simulating in-progress workflow)
        ExecutionState runningState = new ExecutionState(
                "suspend-test-id", "suspend-wf", ExecutionPattern.WORKFLOW,
                ExecutionStatus.RUNNING, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("key", "value"), Map.of(), Set.of(), List.of(), null,
                Instant.now(), Instant.now());

        StepVerifier.create(persistence.save(runningState)
                .then(engine.suspendWorkflow("suspend-test-id", "user requested")))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.SUSPENDED);
                    assertThat(state.correlationId()).isEqualTo("suspend-test-id");
                })
                .verifyComplete();
    }

    @Test
    void resumeSuspendedWorkflow_transitionsToRunningAndCompletes() throws Exception {
        var def = createSimpleWorkflow();
        registry.register(def);

        // Create a SUSPENDED state in persistence
        ExecutionState suspendedState = new ExecutionState(
                "resume-test-id", "suspend-wf", ExecutionPattern.WORKFLOW,
                ExecutionStatus.SUSPENDED, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("key", "value"), Map.of(), Set.of(), List.of(), null,
                Instant.now(), Instant.now());

        StepVerifier.create(persistence.save(suspendedState)
                .then(engine.resumeWorkflow("resume-test-id")))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(state.stepResults()).containsKeys("step1", "step2");
                })
                .verifyComplete();
    }

    @Test
    void suspendCompletedWorkflow_rejectsWithError() {
        ExecutionState completedState = new ExecutionState(
                "completed-id", "suspend-wf", ExecutionPattern.WORKFLOW,
                ExecutionStatus.COMPLETED, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Set.of(), List.of(), null,
                Instant.now(), Instant.now());

        StepVerifier.create(persistence.save(completedState)
                .then(engine.suspendWorkflow("completed-id")))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void resumeRunningWorkflow_rejectsWithError() {
        ExecutionState runningState = new ExecutionState(
                "running-id", "suspend-wf", ExecutionPattern.WORKFLOW,
                ExecutionStatus.RUNNING, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Set.of(), List.of(), null,
                Instant.now(), Instant.now());

        StepVerifier.create(persistence.save(runningState)
                .then(engine.resumeWorkflow("running-id")))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void suspendNonexistentWorkflow_throwsNotFound() {
        StepVerifier.create(engine.suspendWorkflow("does-not-exist"))
                .expectError(org.fireflyframework.orchestration.core.exception.ExecutionNotFoundException.class)
                .verify();
    }
}
