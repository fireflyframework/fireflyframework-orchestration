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
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkflowCheckpointTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;
    private ExecutionPersistenceProvider mockPersistence;

    @SuppressWarnings("unused")
    public static class CheckpointSteps {
        public Mono<String> step1(@Input Map<String, Object> input) {
            return Mono.just("result-1");
        }

        public Mono<String> step2(@Input Map<String, Object> input) {
            return Mono.just("result-2");
        }
    }

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        var stepInvoker = new StepInvoker(new ArgumentResolver());

        mockPersistence = spy(new InMemoryPersistenceProvider());

        var executor = new WorkflowExecutor(stepInvoker, events, noOpPublisher, null, null, mockPersistence);
        engine = new WorkflowEngine(registry, executor, stepInvoker, mockPersistence, events, noOpPublisher);
    }

    @Test
    void checkpoint_savedAfterEachStep() throws Exception {
        var steps = new CheckpointSteps();
        var step1Method = CheckpointSteps.class.getMethod("step1", Map.class);
        var step2Method = CheckpointSteps.class.getMethod("step2", Map.class);

        var def = new WorkflowDefinition("checkpoint-wf", "Checkpoint WF", "test", "1.0",
                List.of(
                        new WorkflowStepDefinition("s1", "Step 1", "", List.of(), 0,
                                "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                steps, step1Method, null, 0, 0, null),
                        new WorkflowStepDefinition("s2", "Step 2", "", List.of("s1"), 0,
                                "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                steps, step2Method, null, 0, 0, null)
                ),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("checkpoint-wf", Map.of(), null, "test", false))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(state.stepStatuses().get("s1")).isEqualTo(StepStatus.DONE);
                    assertThat(state.stepStatuses().get("s2")).isEqualTo(StepStatus.DONE);
                })
                .verifyComplete();

        // save() called by executor after each step (2 checkpoints) + engine saves initial/final state
        verify(mockPersistence, atLeast(2)).save(argThat(es ->
                es.pattern() == ExecutionPattern.WORKFLOW && es.status() == ExecutionStatus.RUNNING));
    }

    @Test
    void checkpoint_nullPersistence_noError() throws Exception {
        // Create executor without persistence (null) — should still work
        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noPersistenceExecutor = new WorkflowExecutor(stepInvoker, events, noOpPublisher, null, null);

        var noPersistenceRegistry = new WorkflowRegistry();
        var inMemoryPersistence = new InMemoryPersistenceProvider();
        var noPersistenceEngine = new WorkflowEngine(noPersistenceRegistry, noPersistenceExecutor,
                stepInvoker, inMemoryPersistence, events, noOpPublisher);

        var steps = new CheckpointSteps();
        var method = CheckpointSteps.class.getMethod("step1", Map.class);

        var def = new WorkflowDefinition("no-ckpt-wf", "No Checkpoint WF", "test", "1.0",
                List.of(
                        new WorkflowStepDefinition("s1", "Step 1", "", List.of(), 0,
                                "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                steps, method, null, 0, 0, null)
                ),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        noPersistenceRegistry.register(def);

        StepVerifier.create(noPersistenceEngine.startWorkflow("no-ckpt-wf", Map.of(), null, "test", false))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(state.stepStatuses().get("s1")).isEqualTo(StepStatus.DONE);
                })
                .verifyComplete();
    }
}
