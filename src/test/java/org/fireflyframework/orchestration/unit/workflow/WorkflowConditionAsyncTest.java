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
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.StepStatus;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class WorkflowConditionAsyncTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;
    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, noOpPublisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        engine = new WorkflowEngine(registry, executor, persistence, events, noOpPublisher);
    }

    // ——————————— Test beans ———————————

    @SuppressWarnings("unused")
    public static class TestSteps {
        public Mono<String> normalStep(@Input Map<String, Object> input) {
            return Mono.just("executed");
        }

        public Mono<String> slowStep(@Input Map<String, Object> input) {
            return Mono.delay(Duration.ofMillis(200))
                    .map(tick -> "slow-result");
        }
    }

    // ——————————— Helpers ———————————

    private WorkflowStepDefinition stepDef(String stepId, String condition, boolean async,
                                            List<String> dependsOn, Object bean, String methodName) throws Exception {
        return new WorkflowStepDefinition(
                stepId, stepId, "", dependsOn, 0,
                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY,
                condition, async, false, "",
                bean, bean.getClass().getMethod(methodName, Map.class),
                null, 0, 0, null);
    }

    private WorkflowDefinition workflowDef(String id, List<WorkflowStepDefinition> steps) {
        return new WorkflowDefinition(id, id, "", "1.0", steps,
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT,
                null, null, null, null);
    }

    // ——————————— Condition tests ———————————

    @Test
    void condition_skipsStep_whenFalse() throws Exception {
        var bean = new TestSteps();
        var def = workflowDef("cond-false-wf", List.of(
                stepDef("guarded", "false", false, List.of(), bean, "normalStep")));
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("cond-false-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.stepStatuses().get("guarded")).isEqualTo(StepStatus.SKIPPED);
                    assertThat(state.stepResults()).doesNotContainKey("guarded");
                })
                .verifyComplete();
    }

    @Test
    void condition_executesStep_whenTrue() throws Exception {
        var bean = new TestSteps();
        var def = workflowDef("cond-true-wf", List.of(
                stepDef("guarded", "true", false, List.of(), bean, "normalStep")));
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("cond-true-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.stepStatuses().get("guarded")).isEqualTo(StepStatus.DONE);
                    assertThat(state.stepResults().get("guarded")).isEqualTo("executed");
                })
                .verifyComplete();
    }

    @Test
    void condition_evaluatesSpEL_withContextVariables_true() throws Exception {
        var bean = new TestSteps();
        var def = workflowDef("cond-spel-true-wf", List.of(
                stepDef("guarded", "#variables['approved']", false, List.of(), bean, "normalStep")));
        registry.register(def);

        // Start workflow with approved=true
        StepVerifier.create(engine.startWorkflow("cond-spel-true-wf", Map.of("approved", true)))
                .assertNext(state -> {
                    assertThat(state.stepStatuses().get("guarded")).isEqualTo(StepStatus.DONE);
                    assertThat(state.stepResults().get("guarded")).isEqualTo("executed");
                })
                .verifyComplete();
    }

    @Test
    void condition_evaluatesSpEL_withContextVariables_false() throws Exception {
        var bean = new TestSteps();
        var def = workflowDef("cond-spel-false-wf", List.of(
                stepDef("guarded", "#variables['approved']", false, List.of(), bean, "normalStep")));
        registry.register(def);

        // Start workflow with approved=false
        StepVerifier.create(engine.startWorkflow("cond-spel-false-wf", Map.of("approved", false)))
                .assertNext(state -> {
                    assertThat(state.stepStatuses().get("guarded")).isEqualTo(StepStatus.SKIPPED);
                    assertThat(state.stepResults()).doesNotContainKey("guarded");
                })
                .verifyComplete();
    }

    @Test
    void condition_failsStep_onInvalidExpression() throws Exception {
        var bean = new TestSteps();
        var def = workflowDef("cond-invalid-wf", List.of(
                stepDef("guarded", "invalid!!!expr", false, List.of(), bean, "normalStep")));
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("cond-invalid-wf", Map.of()))
                .assertNext(state -> {
                    // The workflow overall should be marked as FAILED
                    assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED);
                    // The step result should not be present
                    assertThat(state.stepResults()).doesNotContainKey("guarded");
                })
                .verifyComplete();
    }

    // ——————————— Async tests ———————————

    @Test
    void asyncStep_completesImmediately() throws Exception {
        var bean = new TestSteps();
        var def = workflowDef("async-wf", List.of(
                stepDef("async-step", "", true, List.of(), bean, "slowStep")));
        registry.register(def);

        // The workflow should complete nearly immediately because async step is fire-and-forget.
        // The slow step sleeps 200ms; we assert the workflow returns well before that.
        long start = System.nanoTime();
        StepVerifier.create(engine.startWorkflow("async-wf", Map.of()))
                .assertNext(state -> {
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    // Workflow should return before the 200ms sleep completes
                    assertThat(elapsed).isLessThan(150);
                })
                .verifyComplete();
    }

    @Test
    void asyncStep_doesNotPropagateErrors() throws Exception {
        // Use a bean whose step method throws an error
        var errorBean = new Object() {
            @SuppressWarnings("unused")
            public Mono<String> failStep(@Input Map<String, Object> input) {
                return Mono.error(new RuntimeException("async failure"));
            }
        };
        var stepMethod = errorBean.getClass().getMethod("failStep", Map.class);
        var step = new WorkflowStepDefinition(
                "async-fail", "async-fail", "", List.of(), 0,
                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY,
                "", true, false, "",
                errorBean, stepMethod,
                null, 0, 0, null);
        var def = workflowDef("async-fail-wf", List.of(step));
        registry.register(def);

        // Workflow should complete successfully even though async step fails
        StepVerifier.create(engine.startWorkflow("async-fail-wf", Map.of()))
                .assertNext(state -> {
                    // The workflow completes; the async error does not propagate
                    assertThat(state).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void condition_emptyString_executesNormally() throws Exception {
        // Empty condition string should mean "always execute"
        var bean = new TestSteps();
        var def = workflowDef("cond-empty-wf", List.of(
                stepDef("guarded", "", false, List.of(), bean, "normalStep")));
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("cond-empty-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.stepStatuses().get("guarded")).isEqualTo(StepStatus.DONE);
                    assertThat(state.stepResults().get("guarded")).isEqualTo("executed");
                })
                .verifyComplete();
    }

    @Test
    void condition_nullCondition_executesNormally() throws Exception {
        // Null condition should mean "always execute"
        var bean = new TestSteps();
        var step = new WorkflowStepDefinition(
                "guarded", "guarded", "", List.of(), 0,
                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY,
                null, false, false, "",
                bean, TestSteps.class.getMethod("normalStep", Map.class),
                null, 0, 0, null);
        var def = workflowDef("cond-null-wf", List.of(step));
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("cond-null-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.stepStatuses().get("guarded")).isEqualTo(StepStatus.DONE);
                    assertThat(state.stepResults().get("guarded")).isEqualTo("executed");
                })
                .verifyComplete();
    }
}
