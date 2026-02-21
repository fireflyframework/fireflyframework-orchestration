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
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.StepStatus;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDryRunEndToEndTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;

    @SuppressWarnings("unused")
    public static class TestSteps {
        public static final AtomicBoolean step1Called = new AtomicBoolean(false);
        public static final AtomicBoolean step2Called = new AtomicBoolean(false);

        public Mono<String> step1(@Input Map<String, Object> input) {
            step1Called.set(true);
            return Mono.just("step1-result");
        }

        public Mono<String> step2(@Input Map<String, Object> input) {
            step2Called.set(true);
            return Mono.just("step2-result");
        }
    }

    @BeforeEach
    void setUp() {
        TestSteps.step1Called.set(false);
        TestSteps.step2Called.set(false);

        registry = new WorkflowRegistry();
        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var executor = new WorkflowExecutor(stepInvoker, events, noOpPublisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        engine = new WorkflowEngine(registry, executor, stepInvoker, persistence, events, noOpPublisher);
    }

    @Test
    void dryRun_allStepsSkipped_handlersNotInvoked() throws Exception {
        var testSteps = new TestSteps();
        var step1Method = TestSteps.class.getMethod("step1", Map.class);
        var step2Method = TestSteps.class.getMethod("step2", Map.class);

        var def = new WorkflowDefinition("dry-run-wf", "Dry Run WF", "test", "1.0",
                List.of(
                        new WorkflowStepDefinition("s1", "Step 1", "", List.of(), 0,
                                "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                testSteps, step1Method, null, 0, 0, null),
                        new WorkflowStepDefinition("s2", "Step 2", "", List.of("s1"), 0,
                                "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                testSteps, step2Method, null, 0, 0, null)
                ),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("dry-run-wf", Map.of(), null, "test", true))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    // All steps should be SKIPPED
                    assertThat(state.stepStatuses().get("s1")).isEqualTo(StepStatus.SKIPPED);
                    assertThat(state.stepStatuses().get("s2")).isEqualTo(StepStatus.SKIPPED);
                    // Handlers should NOT have been invoked
                    assertThat(TestSteps.step1Called.get()).isFalse();
                    assertThat(TestSteps.step2Called.get()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void dryRun_false_stepsExecuteNormally() throws Exception {
        var testSteps = new TestSteps();
        var method = TestSteps.class.getMethod("step1", Map.class);

        var def = new WorkflowDefinition("normal-wf", "Normal WF", "test", "1.0",
                List.of(
                        new WorkflowStepDefinition("s1", "Step 1", "", List.of(), 0,
                                "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                testSteps, method, null, 0, 0, null)
                ),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("normal-wf", Map.of(), null, "test", false))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(state.stepStatuses().get("s1")).isEqualTo(StepStatus.DONE);
                    assertThat(TestSteps.step1Called.get()).isTrue();
                })
                .verifyComplete();
    }
}
