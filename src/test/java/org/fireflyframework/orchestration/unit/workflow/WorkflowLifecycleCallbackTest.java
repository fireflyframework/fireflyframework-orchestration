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
import org.fireflyframework.orchestration.core.model.StepTriggerMode;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.workflow.annotation.OnStepComplete;
import org.fireflyframework.orchestration.workflow.annotation.OnWorkflowComplete;
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for workflow lifecycle callbacks: {@code @OnStepComplete}, {@code @OnWorkflowComplete},
 * and {@code @OnWorkflowError}.
 */
class WorkflowLifecycleCallbackTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;
    private InMemoryPersistenceProvider persistence;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        var executor = new WorkflowExecutor(new ArgumentResolver(), events, noOpPublisher);
        persistence = new InMemoryPersistenceProvider();
        engine = new WorkflowEngine(registry, executor, persistence, events, noOpPublisher);
    }

    // ── Test beans ────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    public static class StepCompleteBean {
        final AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        final List<String> completedStepIds = Collections.synchronizedList(new ArrayList<>());

        public Mono<String> step1(@Input Map<String, Object> input) {
            return Mono.just("step1-result");
        }

        public Mono<String> step2(@Input Map<String, Object> input) {
            return Mono.just("step2-result");
        }

        @OnStepComplete
        public void onStepDone(String stepId, Object result) {
            callbackInvoked.set(true);
            completedStepIds.add(stepId);
        }
    }

    @SuppressWarnings("unused")
    public static class StepCompleteFilterBean {
        final List<String> completedStepIds = Collections.synchronizedList(new ArrayList<>());

        public Mono<String> step1(@Input Map<String, Object> input) {
            return Mono.just("step1-result");
        }

        public Mono<String> step2(@Input Map<String, Object> input) {
            return Mono.just("step2-result");
        }

        @OnStepComplete(stepIds = {"step1"})
        public void onStepDone(String stepId, Object result) {
            completedStepIds.add(stepId);
        }
    }

    @SuppressWarnings("unused")
    public static class WorkflowCompleteBean {
        final AtomicBoolean callbackInvoked = new AtomicBoolean(false);

        public Mono<String> step1(@Input Map<String, Object> input) {
            return Mono.just("step1-result");
        }

        @OnWorkflowComplete
        public void onComplete() {
            callbackInvoked.set(true);
        }
    }

    @SuppressWarnings("unused")
    public static class WorkflowErrorBean {
        final AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        volatile Throwable capturedError;

        public Mono<String> failStep(@Input Map<String, Object> input) {
            return Mono.error(new RuntimeException("step failed intentionally"));
        }

        @OnWorkflowError
        public void onError(Throwable error) {
            callbackInvoked.set(true);
            capturedError = error;
        }
    }

    @SuppressWarnings("unused")
    public static class WorkflowErrorSuppressBean {
        final AtomicBoolean callbackInvoked = new AtomicBoolean(false);

        public Mono<String> failStep(@Input Map<String, Object> input) {
            return Mono.error(new RuntimeException("step failed"));
        }

        @OnWorkflowError(suppressError = true)
        public void onError(Throwable error) {
            callbackInvoked.set(true);
        }
    }

    @SuppressWarnings("unused")
    public static class MultipleCallbacksBean {
        final AtomicInteger callbackCount = new AtomicInteger(0);
        final List<String> callbackNames = Collections.synchronizedList(new ArrayList<>());

        public Mono<String> step1(@Input Map<String, Object> input) {
            return Mono.just("step1-result");
        }

        @OnStepComplete(priority = 1)
        public void onStepDoneFirst(String stepId) {
            callbackCount.incrementAndGet();
            callbackNames.add("first");
        }

        @OnStepComplete(priority = 2)
        public void onStepDoneSecond(String stepId) {
            callbackCount.incrementAndGet();
            callbackNames.add("second");
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────

    @Test
    void onStepComplete_invokedAfterStepSuccess() throws Exception {
        var bean = new StepCompleteBean();
        List<Method> onStepCompleteMethods = List.of(
                StepCompleteBean.class.getMethod("onStepDone", String.class, Object.class));

        var def = new WorkflowDefinition("step-complete-wf", "Step Complete WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        bean, StepCompleteBean.class.getMethod("step1", Map.class))),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, bean,
                onStepCompleteMethods, List.of(), List.of());
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("step-complete-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(bean.callbackInvoked.get()).isTrue();
                    assertThat(bean.completedStepIds).containsExactly("step1");
                })
                .verifyComplete();
    }

    @Test
    void onStepComplete_filtersOnStepIds() throws Exception {
        var bean = new StepCompleteFilterBean();
        List<Method> onStepCompleteMethods = List.of(
                StepCompleteFilterBean.class.getMethod("onStepDone", String.class, Object.class));

        var def = new WorkflowDefinition("step-filter-wf", "Step Filter WF", "test", "1.0",
                List.of(
                        new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                bean, StepCompleteFilterBean.class.getMethod("step1", Map.class)),
                        new WorkflowStepDefinition("step2", "Step 2", "", List.of("step1"), 1,
                                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                bean, StepCompleteFilterBean.class.getMethod("step2", Map.class))
                ),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, bean,
                onStepCompleteMethods, List.of(), List.of());
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("step-filter-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    // Only step1 should have triggered the callback (stepIds={"step1"})
                    assertThat(bean.completedStepIds).containsExactly("step1");
                })
                .verifyComplete();
    }

    @Test
    void onWorkflowComplete_invokedOnSuccess() throws Exception {
        var bean = new WorkflowCompleteBean();
        List<Method> onWorkflowCompleteMethods = List.of(
                WorkflowCompleteBean.class.getMethod("onComplete"));

        var def = new WorkflowDefinition("wf-complete-wf", "WF Complete WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        bean, WorkflowCompleteBean.class.getMethod("step1", Map.class))),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, bean,
                List.of(), onWorkflowCompleteMethods, List.of());
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("wf-complete-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(bean.callbackInvoked.get()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void onWorkflowError_invokedOnFailure() throws Exception {
        var bean = new WorkflowErrorBean();
        List<Method> onWorkflowErrorMethods = List.of(
                WorkflowErrorBean.class.getMethod("onError", Throwable.class));

        var def = new WorkflowDefinition("wf-error-wf", "WF Error WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("fail", "Fail Step", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        bean, WorkflowErrorBean.class.getMethod("failStep", Map.class))),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, bean,
                List.of(), List.of(), onWorkflowErrorMethods);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("wf-error-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(bean.callbackInvoked.get()).isTrue();
                    assertThat(bean.capturedError).isNotNull();
                    assertThat(bean.capturedError.getMessage()).contains("step failed intentionally");
                })
                .verifyComplete();
    }

    @Test
    void onWorkflowError_suppressError_marksCompleted() throws Exception {
        var bean = new WorkflowErrorSuppressBean();
        List<Method> onWorkflowErrorMethods = List.of(
                WorkflowErrorSuppressBean.class.getMethod("onError", Throwable.class));

        var def = new WorkflowDefinition("wf-suppress-wf", "WF Suppress WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("fail", "Fail Step", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        bean, WorkflowErrorSuppressBean.class.getMethod("failStep", Map.class))),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, bean,
                List.of(), List.of(), onWorkflowErrorMethods);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("wf-suppress-wf", Map.of()))
                .assertNext(state -> {
                    // suppressError=true should result in COMPLETED status instead of FAILED
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(bean.callbackInvoked.get()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void multipleCallbacks_allInvoked() throws Exception {
        var bean = new MultipleCallbacksBean();
        List<Method> onStepCompleteMethods = List.of(
                MultipleCallbacksBean.class.getMethod("onStepDoneFirst", String.class),
                MultipleCallbacksBean.class.getMethod("onStepDoneSecond", String.class));

        var def = new WorkflowDefinition("multi-cb-wf", "Multi Callback WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        bean, MultipleCallbacksBean.class.getMethod("step1", Map.class))),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, bean,
                onStepCompleteMethods, List.of(), List.of());
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("multi-cb-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    // Both callbacks should have been invoked
                    assertThat(bean.callbackCount.get()).isEqualTo(2);
                    assertThat(bean.callbackNames).containsExactly("first", "second");
                })
                .verifyComplete();
    }
}
