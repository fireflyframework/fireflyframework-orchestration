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
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.workflow.annotation.OnWorkflowError;
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import org.fireflyframework.orchestration.workflow.engine.WorkflowExecutor;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SuppressErrorLogicTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;
    private InMemoryPersistenceProvider persistence;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, noOpPublisher, null, null);
        persistence = new InMemoryPersistenceProvider();
        engine = new WorkflowEngine(registry, executor, new StepInvoker(new ArgumentResolver()), persistence, events, noOpPublisher);
    }

    // --- Test beans ---

    @SuppressWarnings("unused")
    public static class FailWithRuntimeSteps {
        public Mono<String> failStep(@Input Map<String, Object> input) {
            return Mono.error(new NullPointerException("null value"));
        }

        // This handler is for IOException only, with suppressError=true
        // It should NOT suppress a NullPointerException
        @OnWorkflowError(errorTypes = IOException.class, suppressError = true)
        public void handleIoError(Throwable error) {
            // no-op
        }
    }

    @SuppressWarnings("unused")
    public static class MatchingSuppressSteps {
        public Mono<String> failStep(@Input Map<String, Object> input) {
            return Mono.error(new RuntimeException("runtime error"));
        }

        // This handler matches RuntimeException and has suppressError=true
        @OnWorkflowError(errorTypes = RuntimeException.class, suppressError = true)
        public void handleRuntimeError(Throwable error) {
            // no-op
        }
    }

    @SuppressWarnings("unused")
    public static class NonMatchingStepIdSuppressSteps {
        public Mono<String> failStep(@Input Map<String, Object> input) {
            return Mono.error(new RuntimeException("runtime error"));
        }

        // This handler is for step "other-step" only, with suppressError=true
        // It should NOT suppress errors from "fail" step
        @OnWorkflowError(stepIds = "other-step", suppressError = true)
        public void handleOtherStepError(Throwable error) {
            // no-op
        }
    }

    // --- Tests ---

    @Test
    void suppressErrorOnlyAppliesToMatchingErrorType() throws Exception {
        var testSteps = new FailWithRuntimeSteps();
        var def = new WorkflowDefinition("suppress-test-1", "Suppress Test 1", "test", "1.0",
                List.of(new WorkflowStepDefinition("fail", "Fail Step", "", List.of(), 0,
                        "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, FailWithRuntimeSteps.class.getMethod("failStep", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, testSteps,
                null, null,
                List.of(FailWithRuntimeSteps.class.getDeclaredMethod("handleIoError", Throwable.class)));
        registry.register(def);

        // The IOException handler with suppressError=true should NOT suppress a NullPointerException
        StepVerifier.create(engine.startWorkflow("suppress-test-1", Map.of()))
                .assertNext(state -> assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED))
                .verifyComplete();
    }

    @Test
    void suppressErrorAppliesWhenHandlerMatches() throws Exception {
        var testSteps = new MatchingSuppressSteps();
        var def = new WorkflowDefinition("suppress-test-2", "Suppress Test 2", "test", "1.0",
                List.of(new WorkflowStepDefinition("fail", "Fail Step", "", List.of(), 0,
                        "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, MatchingSuppressSteps.class.getMethod("failStep", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, testSteps,
                null, null,
                List.of(MatchingSuppressSteps.class.getDeclaredMethod("handleRuntimeError", Throwable.class)));
        registry.register(def);

        // The RuntimeException handler with suppressError=true SHOULD suppress a RuntimeException
        StepVerifier.create(engine.startWorkflow("suppress-test-2", Map.of()))
                .assertNext(state -> assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED))
                .verifyComplete();
    }

    @Test
    void suppressErrorDoesNotApplyForNonMatchingStepId() throws Exception {
        var testSteps = new NonMatchingStepIdSuppressSteps();
        var def = new WorkflowDefinition("suppress-test-3", "Suppress Test 3", "test", "1.0",
                List.of(new WorkflowStepDefinition("fail", "Fail Step", "", List.of(), 0,
                        "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, NonMatchingStepIdSuppressSteps.class.getMethod("failStep", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, testSteps,
                null, null,
                List.of(NonMatchingStepIdSuppressSteps.class.getDeclaredMethod("handleOtherStepError", Throwable.class)));
        registry.register(def);

        // The "other-step" handler with suppressError=true should NOT suppress errors from "fail" step
        StepVerifier.create(engine.startWorkflow("suppress-test-3", Map.of()))
                .assertNext(state -> assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED))
                .verifyComplete();
    }
}
