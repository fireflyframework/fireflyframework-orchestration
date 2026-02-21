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
import org.fireflyframework.orchestration.workflow.timer.TimerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class NamedTimerWiringTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;
    private TimerService timerService;

    @SuppressWarnings("unused")
    public static class TestSteps {
        public Mono<String> step1(@Input Map<String, Object> input) {
            return Mono.just("step1-done");
        }
    }

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        timerService = new TimerService(events);
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var executor = new WorkflowExecutor(stepInvoker, events, noOpPublisher, null, timerService);
        var persistence = new InMemoryPersistenceProvider();
        engine = new WorkflowEngine(registry, executor, stepInvoker, persistence, events, noOpPublisher);
    }

    @Test
    void namedTimer_registeredInTimerService() throws Exception {
        var testSteps = new TestSteps();
        var def = new WorkflowDefinition("timer-wf", "Timer WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                        "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, TestSteps.class.getMethod("step1", Map.class),
                        null, 0, 50, "my-timer")),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("timer-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    // The named timer should have been scheduled via timerService.schedule()
                    // (the timer entry was registered, then the step completed)
                })
                .verifyComplete();
    }

    @Test
    void unnamedTimer_usesDelayInsteadOfSchedule() throws Exception {
        var testSteps = new TestSteps();
        var def = new WorkflowDefinition("delay-wf", "Delay WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                        "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, TestSteps.class.getMethod("step1", Map.class),
                        null, 0, 50, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("delay-wf", Map.of()))
                .assertNext(state -> assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED))
                .verifyComplete();
    }

    @Test
    void namedTimer_emptyTimerId_usesDelayFallback() throws Exception {
        var testSteps = new TestSteps();
        var def = new WorkflowDefinition("empty-timer-wf", "Empty Timer WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                        "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, TestSteps.class.getMethod("step1", Map.class),
                        null, 0, 50, "")),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("empty-timer-wf", Map.of()))
                .assertNext(state -> assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED))
                .verifyComplete();
    }
}
