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
import org.fireflyframework.orchestration.core.dlq.DeadLetterService;
import org.fireflyframework.orchestration.core.dlq.InMemoryDeadLetterStore;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.StepTriggerMode;
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

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDlqTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;
    private InMemoryDeadLetterStore dlqStore;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, noOpPublisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        dlqStore = new InMemoryDeadLetterStore();
        var dlqService = new DeadLetterService(dlqStore, events);
        engine = new WorkflowEngine(registry, executor, persistence, events, noOpPublisher, dlqService);
    }

    @SuppressWarnings("unused")
    public static class FailingSteps {
        public Mono<String> failStep(@Input Map<String, Object> input) {
            return Mono.error(new RuntimeException("workflow step failed"));
        }
    }

    @Test
    void failedWorkflowIsSavedToDlq() throws Exception {
        var testSteps = new FailingSteps();
        var def = new WorkflowDefinition("dlq-wf", "DLQ Workflow", "test", "1.0",
                List.of(new WorkflowStepDefinition("fail-step", "Fail Step", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, FailingSteps.class.getMethod("failStep", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("dlq-wf", Map.of("key", "value")))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED);
                })
                .verifyComplete();

        // Verify DLQ entry was created
        StepVerifier.create(dlqStore.findAll())
                .assertNext(entry -> {
                    assertThat(entry.executionName()).isEqualTo("dlq-wf");
                    assertThat(entry.pattern()).isEqualTo(ExecutionPattern.WORKFLOW);
                    assertThat(entry.stepId()).isEqualTo("fail-step");
                    assertThat(entry.errorMessage()).contains("workflow step failed");
                    assertThat(entry.statusAtFailure()).isEqualTo(ExecutionStatus.FAILED);
                })
                .verifyComplete();
    }

    @Test
    void failedWorkflowWithNoDlqServiceDoesNotFail() throws Exception {
        // Create engine WITHOUT DLQ service
        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, noOpPublisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        var noDlqEngine = new WorkflowEngine(registry, executor, persistence, events, noOpPublisher);

        var testSteps = new FailingSteps();
        var def = new WorkflowDefinition("no-dlq-wf", "No DLQ Workflow", "test", "1.0",
                List.of(new WorkflowStepDefinition("fail-step", "Fail Step", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, FailingSteps.class.getMethod("failStep", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        // Should complete normally without DLQ, just FAILED status
        StepVerifier.create(noDlqEngine.startWorkflow("no-dlq-wf", Map.of()))
                .assertNext(state -> assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED))
                .verifyComplete();
    }
}
