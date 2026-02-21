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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class WorkflowCompensationTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, noOpPublisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        engine = new WorkflowEngine(registry, executor, new StepInvoker(new ArgumentResolver()), persistence, events, noOpPublisher);
    }

    // ——————————— Test beans ———————————

    /**
     * Bean with compensatable steps A and B, and a failing step C.
     * Tracks compensation invocation order via a shared list.
     */
    @SuppressWarnings("unused")
    public static class CompensatableStepsBean {
        final List<String> compensationOrder = Collections.synchronizedList(new ArrayList<>());

        public Mono<String> stepA(@Input Map<String, Object> input) {
            return Mono.just("result-A");
        }

        public void compensateA(Object result) {
            compensationOrder.add("compensateA");
        }

        public Mono<String> stepB(@Input Map<String, Object> input) {
            return Mono.just("result-B");
        }

        public void compensateB(Object result) {
            compensationOrder.add("compensateB");
        }

        public Mono<String> stepC(@Input Map<String, Object> input) {
            return Mono.error(new RuntimeException("step C failed"));
        }
    }

    /**
     * Bean that captures the step result passed to the compensation method.
     */
    @SuppressWarnings("unused")
    public static class ResultCapturingBean {
        final AtomicReference<Object> capturedResult = new AtomicReference<>();

        public Mono<String> doWork(@Input Map<String, Object> input) {
            return Mono.just("important-data");
        }

        public void compensateWork(Object result) {
            capturedResult.set(result);
        }

        public Mono<String> failingStep(@Input Map<String, Object> input) {
            return Mono.error(new RuntimeException("downstream failure"));
        }
    }

    /**
     * Bean with a mix of compensatable and non-compensatable steps.
     */
    @SuppressWarnings("unused")
    public static class MixedStepsBean {
        final List<String> compensationOrder = Collections.synchronizedList(new ArrayList<>());

        public Mono<String> nonCompensatableStep(@Input Map<String, Object> input) {
            return Mono.just("result-NC");
        }

        public Mono<String> compensatableStep(@Input Map<String, Object> input) {
            return Mono.just("result-C");
        }

        public void compensateStep(Object result) {
            compensationOrder.add("compensated");
        }

        public Mono<String> failStep(@Input Map<String, Object> input) {
            return Mono.error(new RuntimeException("failure"));
        }
    }

    // ——————————— Helpers ———————————

    private WorkflowStepDefinition stepDef(String stepId, List<String> dependsOn,
                                            boolean compensatable, String compensationMethod,
                                            Object bean, String methodName) throws Exception {
        return new WorkflowStepDefinition(
                stepId, stepId, "", dependsOn, 0,
                "", 5000, RetryPolicy.NO_RETRY,
                "", false, compensatable, compensationMethod,
                bean, bean.getClass().getMethod(methodName, Map.class),
                null, 0, 0, null);
    }

    private WorkflowDefinition workflowDef(String id, List<WorkflowStepDefinition> steps) {
        return new WorkflowDefinition(id, id, "", "1.0", steps,
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT,
                null, null, null, null);
    }

    // ——————————— Tests ———————————

    @Test
    void compensatableStep_runsCompensation_onLaterStepFailure() throws Exception {
        var bean = new CompensatableStepsBean();

        var def = workflowDef("comp-wf", List.of(
                stepDef("stepA", List.of(), true, "compensateA", bean, "stepA"),
                stepDef("stepB", List.of("stepA"), true, "compensateB", bean, "stepB"),
                stepDef("stepC", List.of("stepB"), false, "", bean, "stepC")));
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("comp-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED);

                    // Both compensatable steps should have been compensated in reverse order
                    assertThat(bean.compensationOrder).hasSize(2);
                    assertThat(bean.compensationOrder.get(0)).isEqualTo("compensateB");
                    assertThat(bean.compensationOrder.get(1)).isEqualTo("compensateA");
                })
                .verifyComplete();
    }

    @Test
    void nonCompensatableStep_skipped_inCompensation() throws Exception {
        var bean = new MixedStepsBean();

        var def = workflowDef("mixed-comp-wf", List.of(
                // Step A: not compensatable
                stepDef("stepA", List.of(), false, "", bean, "nonCompensatableStep"),
                // Step B: compensatable
                stepDef("stepB", List.of("stepA"), true, "compensateStep", bean, "compensatableStep"),
                // Step C: fails
                stepDef("stepC", List.of("stepB"), false, "", bean, "failStep")));
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("mixed-comp-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED);

                    // Only step B should be compensated, step A is not compensatable
                    assertThat(bean.compensationOrder).hasSize(1);
                    assertThat(bean.compensationOrder.get(0)).isEqualTo("compensated");
                })
                .verifyComplete();
    }

    @Test
    void compensationMethod_receivesStepResult() throws Exception {
        var bean = new ResultCapturingBean();

        var def = workflowDef("result-comp-wf", List.of(
                stepDef("work", List.of(), true, "compensateWork", bean, "doWork"),
                stepDef("fail", List.of("work"), false, "", bean, "failingStep")));
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("result-comp-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED);

                    // The compensation method should have received the step result
                    assertThat(bean.capturedResult.get()).isEqualTo("important-data");
                })
                .verifyComplete();
    }

    @Test
    void compensationFailure_doesNotAbortOtherCompensations() throws Exception {
        var bean = new Object() {
            final List<String> compensationOrder = Collections.synchronizedList(new ArrayList<>());

            @SuppressWarnings("unused")
            public Mono<String> stepA(@Input Map<String, Object> input) {
                return Mono.just("result-A");
            }

            @SuppressWarnings("unused")
            public void compensateA(Object result) {
                compensationOrder.add("compensateA");
            }

            @SuppressWarnings("unused")
            public Mono<String> stepB(@Input Map<String, Object> input) {
                return Mono.just("result-B");
            }

            @SuppressWarnings("unused")
            public void compensateB(Object result) {
                throw new RuntimeException("compensation B exploded");
            }

            @SuppressWarnings("unused")
            public Mono<String> stepC(@Input Map<String, Object> input) {
                return Mono.error(new RuntimeException("step C failed"));
            }
        };

        var def = workflowDef("comp-fail-wf", List.of(
                new WorkflowStepDefinition(
                        "stepA", "stepA", "", List.of(), 0,
                        "", 5000, RetryPolicy.NO_RETRY,
                        "", false, true, "compensateA",
                        bean, bean.getClass().getMethod("stepA", Map.class),
                        null, 0, 0, null),
                new WorkflowStepDefinition(
                        "stepB", "stepB", "", List.of("stepA"), 0,
                        "", 5000, RetryPolicy.NO_RETRY,
                        "", false, true, "compensateB",
                        bean, bean.getClass().getMethod("stepB", Map.class),
                        null, 0, 0, null),
                new WorkflowStepDefinition(
                        "stepC", "stepC", "", List.of("stepB"), 0,
                        "", 5000, RetryPolicy.NO_RETRY,
                        "", false, false, "",
                        bean, bean.getClass().getMethod("stepC", Map.class),
                        null, 0, 0, null)));
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("comp-fail-wf", Map.of()))
                .assertNext(state -> {
                    // Workflow should still end as FAILED
                    assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED);

                    // compensateB throws, but compensateA should still run
                    assertThat(bean.compensationOrder).contains("compensateA");
                })
                .verifyComplete();
    }

    @Test
    void noCompensatableSteps_noCompensationRuns() throws Exception {
        var bean = new CompensatableStepsBean();

        // Only stepC which fails, no compensatable steps
        var def = workflowDef("no-comp-wf", List.of(
                stepDef("stepC", List.of(), false, "", bean, "stepC")));
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("no-comp-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED);
                    // No compensation should have run
                    assertThat(bean.compensationOrder).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void compensationEmitsObservabilityEvents() throws Exception {
        var compensationEvents = Collections.synchronizedList(new ArrayList<String>());
        var trackingEvents = new OrchestrationEvents() {
            @Override public void onCompensationStarted(String name, String correlationId) {
                compensationEvents.add("compensationStarted:" + name);
            }
            @Override public void onStepCompensated(String name, String correlationId, String stepId) {
                compensationEvents.add("stepCompensated:" + stepId);
            }
            @Override public void onStepCompensationFailed(String name, String correlationId, String stepId, Throwable error) {
                compensationEvents.add("stepCompensationFailed:" + stepId);
            }
        };

        var noOpPublisher = new NoOpEventPublisher();
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), trackingEvents, noOpPublisher, null, null);
        var localRegistry = new WorkflowRegistry();
        var persistence = new InMemoryPersistenceProvider();
        var localEngine = new WorkflowEngine(localRegistry, executor, new StepInvoker(new ArgumentResolver()), persistence, trackingEvents, noOpPublisher);

        var bean = new CompensatableStepsBean();
        var def = workflowDef("obs-comp-wf", List.of(
                stepDef("stepA", List.of(), true, "compensateA", bean, "stepA"),
                stepDef("stepB", List.of("stepA"), true, "compensateB", bean, "stepB"),
                stepDef("stepC", List.of("stepB"), false, "", bean, "stepC")));
        localRegistry.register(def);

        StepVerifier.create(localEngine.startWorkflow("obs-comp-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(compensationEvents).contains(
                            "compensationStarted:obs-comp-wf",
                            "stepCompensated:stepB",
                            "stepCompensated:stepA");
                })
                .verifyComplete();
    }
}
