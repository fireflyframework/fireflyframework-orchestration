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

package org.fireflyframework.orchestration.integration;

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.argument.Input;
import org.fireflyframework.orchestration.core.builder.OrchestrationBuilder;
import org.fireflyframework.orchestration.core.event.OrchestrationEvent;
import org.fireflyframework.orchestration.core.event.OrchestrationEventPublisher;
import org.fireflyframework.orchestration.core.model.*;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.step.StepHandler;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
import org.fireflyframework.orchestration.saga.compensation.CompensationErrorHandler;
import org.fireflyframework.orchestration.saga.compensation.CompensationErrorHandler.CompensationErrorResult;
import org.fireflyframework.orchestration.saga.compensation.SagaCompensator;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaExecutionOrchestrator;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.saga.registry.StepEventConfig;
import org.fireflyframework.orchestration.tcc.builder.TccBuilder;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccExecutionOrchestrator;
import org.fireflyframework.orchestration.tcc.engine.TccInputs;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.fireflyframework.orchestration.tcc.registry.TccEventConfig;
import org.fireflyframework.orchestration.workflow.annotation.OnStepComplete;
import org.fireflyframework.orchestration.workflow.annotation.OnWorkflowComplete;
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import org.fireflyframework.orchestration.workflow.engine.WorkflowExecutor;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * End-to-end integration tests verifying all event paths across the three
 * execution engines: Saga, TCC, and Workflow. Tests cover event publishing,
 * lifecycle callbacks, condition evaluation, compensation error handling,
 * and the {@code publishEvents} flag gating.
 */
@ExtendWith(MockitoExtension.class)
class EventWiringIntegrationTest {

    @Mock
    private OrchestrationEventPublisher publisher;

    private OrchestrationEvents events;
    private StepInvoker stepInvoker;

    @BeforeEach
    void setUp() {
        events = new OrchestrationEvents() {};
        stepInvoker = new StepInvoker(new ArgumentResolver());
        lenient().when(publisher.publish(any())).thenReturn(Mono.empty());
    }

    // ── Test beans ────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    public static class WorkflowSteps {
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

    @SuppressWarnings("unused")
    public static class CallbackBean {
        final AtomicBoolean stepCompleteCalled = new AtomicBoolean(false);
        final AtomicBoolean workflowCompleteCalled = new AtomicBoolean(false);
        final List<String> completedStepIds = Collections.synchronizedList(new ArrayList<>());

        public Mono<String> step1(@Input Map<String, Object> input) {
            return Mono.just("step1-result");
        }

        public Mono<String> step2(@Input Map<String, Object> input) {
            return Mono.just("step2-result");
        }

        @OnStepComplete
        public void onStepDone(String stepId, Object result) {
            stepCompleteCalled.set(true);
            completedStepIds.add(stepId);
        }

        @OnWorkflowComplete
        public void onComplete() {
            workflowCompleteCalled.set(true);
        }
    }

    // ── 1. Saga end-to-end: publishes step events ────────────────────

    @Test
    void saga_endToEnd_publishesStepEvents() {
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, publisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, null, null, compensator, publisher);

        SagaDefinition saga = SagaBuilder.saga("EventSaga")
                .step("reserve")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("reserved"))
                    .add()
                .build();

        // Attach @StepEvent metadata manually (as the annotation scanner would)
        saga.steps.get("reserve").stepEvent = new StepEventConfig("orders-topic", "OrderReserved", "order-123");

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> assertThat(result.isSuccess()).isTrue())
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(3)).publish(captor.capture());

        List<OrchestrationEvent> allEvents = captor.getAllValues();

        // executionStarted event published
        assertThat(allEvents).anyMatch(e ->
                "EXECUTION_STARTED".equals(e.eventType()) && e.pattern() == ExecutionPattern.SAGA);

        // stepCompleted event with correct topic/type/key from @StepEvent
        List<OrchestrationEvent> stepEvents = allEvents.stream()
                .filter(e -> "STEP_COMPLETED".equals(e.eventType()))
                .toList();
        assertThat(stepEvents).hasSize(1);
        OrchestrationEvent stepEvent = stepEvents.get(0);
        assertThat(stepEvent.topic()).isEqualTo("orders-topic");
        assertThat(stepEvent.type()).isEqualTo("OrderReserved");
        assertThat(stepEvent.key()).isEqualTo("order-123");
        assertThat(stepEvent.stepId()).isEqualTo("reserve");

        // executionCompleted event published
        assertThat(allEvents).anyMatch(e ->
                "EXECUTION_COMPLETED".equals(e.eventType()) && e.status() == ExecutionStatus.COMPLETED);
    }

    // ── 2. TCC end-to-end: publishes TCC events ─────────────────────

    @Test
    void tcc_endToEnd_publishesTccEvents() {
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, publisher);
        var engine = new TccEngine(null, events, orchestrator, null, null, publisher);

        TccDefinition tcc = TccBuilder.tcc("EventTcc")
                .participant("debit")
                    .tryHandler((input, ctx) -> Mono.just("debit-reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("debit-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        // Set TCC event metadata manually (as the annotation scanner would)
        tcc.participants.get("debit").tccEvent = new TccEventConfig("payments-topic", "DebitConfirmed", "acct-456");

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> assertThat(result.isConfirmed()).isTrue())
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(3)).publish(captor.capture());

        List<OrchestrationEvent> allEvents = captor.getAllValues();

        // executionStarted published
        assertThat(allEvents).anyMatch(e ->
                "EXECUTION_STARTED".equals(e.eventType()) && e.pattern() == ExecutionPattern.TCC);

        // stepCompleted published with correct topic/eventType/key from @TccEvent (after confirm)
        List<OrchestrationEvent> stepEvents = allEvents.stream()
                .filter(e -> "STEP_COMPLETED".equals(e.eventType()))
                .toList();
        assertThat(stepEvents).hasSize(1);
        OrchestrationEvent stepEvent = stepEvents.get(0);
        assertThat(stepEvent.topic()).isEqualTo("payments-topic");
        assertThat(stepEvent.type()).isEqualTo("DebitConfirmed");
        assertThat(stepEvent.key()).isEqualTo("acct-456");
        assertThat(stepEvent.stepId()).isEqualTo("debit");

        // executionCompleted published
        assertThat(allEvents).anyMatch(e ->
                "EXECUTION_COMPLETED".equals(e.eventType()) && e.status() == ExecutionStatus.CONFIRMED);
    }

    // ── 3. Workflow end-to-end: lifecycle callbacks fire ─────────────

    @Test
    void workflow_endToEnd_lifecycleCallbacksFire() throws Exception {
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, publisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        var registry = new WorkflowRegistry();
        var engine = new WorkflowEngine(registry, executor, persistence, events, publisher);

        var bean = new CallbackBean();
        List<Method> onStepCompleteMethods = List.of(
                CallbackBean.class.getMethod("onStepDone", String.class, Object.class));
        List<Method> onWorkflowCompleteMethods = List.of(
                CallbackBean.class.getMethod("onComplete"));

        var def = new WorkflowDefinition("callback-wf", "Callback WF", "test", "1.0",
                List.of(
                        new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                bean, CallbackBean.class.getMethod("step1", Map.class),
                                null, 0, 0, null),
                        new WorkflowStepDefinition("step2", "Step 2", "", List.of("step1"), 1,
                                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                bean, CallbackBean.class.getMethod("step2", Map.class),
                                null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, bean,
                onStepCompleteMethods, onWorkflowCompleteMethods, List.of());
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("callback-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    // Both @OnStepComplete and @OnWorkflowComplete callbacks should have fired
                    assertThat(bean.stepCompleteCalled.get()).isTrue();
                    assertThat(bean.completedStepIds).containsExactly("step1", "step2");
                    assertThat(bean.workflowCompleteCalled.get()).isTrue();
                })
                .verifyComplete();
    }

    // ── 4. Workflow end-to-end: condition skips step ─────────────────

    @Test
    void workflow_endToEnd_conditionSkipsStep() throws Exception {
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, publisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        var registry = new WorkflowRegistry();
        var engine = new WorkflowEngine(registry, executor, persistence, events, publisher);

        var bean = new WorkflowSteps();

        var def = new WorkflowDefinition("condition-wf", "Condition WF", "test", "1.0",
                List.of(
                        new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                bean, WorkflowSteps.class.getMethod("step1", Map.class),
                                null, 0, 0, null),
                        new WorkflowStepDefinition("guarded", "Guarded Step", "", List.of("step1"), 1,
                                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY,
                                "false",  // condition always false — step should be skipped
                                false, false, "",
                                bean, WorkflowSteps.class.getMethod("step2", Map.class),
                                null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("condition-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    // The guarded step was skipped
                    assertThat(state.stepStatuses().get("guarded")).isEqualTo(StepStatus.SKIPPED);
                    // step1 still completed
                    assertThat(state.stepStatuses().get("step1")).isEqualTo(StepStatus.DONE);
                    assertThat(state.stepResults()).containsKey("step1");
                    assertThat(state.stepResults()).doesNotContainKey("guarded");
                })
                .verifyComplete();
    }

    // ── 5. Saga end-to-end: compensation error handler called ────────

    @Test
    void saga_endToEnd_compensationErrorHandlerCalled() {
        AtomicBoolean handlerCalled = new AtomicBoolean(false);
        AtomicInteger handlerCallCount = new AtomicInteger(0);

        CompensationErrorHandler errorHandler = (sagaName, stepId, error, attempt) -> {
            handlerCalled.set(true);
            handlerCallCount.incrementAndGet();
            return CompensationErrorResult.MARK_COMPENSATED;
        };

        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL,
                stepInvoker, errorHandler);
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, publisher);
        var engine = new SagaEngine(null, events, orchestrator, null, null, compensator, publisher);

        // Build a saga: step1 succeeds (compensatable, but compensation throws),
        // step2 fails (triggers compensation of step1)
        SagaDefinition saga = SagaBuilder.saga("comp-error-saga")
                .step("step1")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("done"))
                    .compensation((arg, ctx) -> Mono.error(new RuntimeException("compensation-exploded")))
                    .add()
                .step("step2")
                    .dependsOn("step1")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("step2-failed")))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    // The custom CompensationErrorHandler should have been called
                    assertThat(handlerCalled.get()).isTrue();
                    assertThat(handlerCallCount.get()).isGreaterThanOrEqualTo(1);
                })
                .verifyComplete();
    }

    // ── 6. Workflow publishEvents=false: no step events published ────

    @Test
    void workflow_publishEvents_false_doesNotPublishStepEvents() throws Exception {
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, publisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        var registry = new WorkflowRegistry();
        var engine = new WorkflowEngine(registry, executor, persistence, events, publisher);

        var bean = new WorkflowSteps();

        // publishEvents=false (default via backward-compatible constructor),
        // but step has an outputEventType
        var def = new WorkflowDefinition("no-publish-wf", "No Publish WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "OrderProcessed", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        bean, WorkflowSteps.class.getMethod("step1", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null,
                false);  // publishEvents = false
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("no-publish-wf", Map.of("k", "v")))
                .assertNext(state -> assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED))
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(2)).publish(captor.capture());

        // Lifecycle events (EXECUTION_STARTED, EXECUTION_COMPLETED) should always publish
        assertThat(captor.getAllValues()).anyMatch(e -> "EXECUTION_STARTED".equals(e.eventType()));
        assertThat(captor.getAllValues()).anyMatch(e -> "EXECUTION_COMPLETED".equals(e.eventType()));

        // No step events should have been published since publishEvents=false
        List<OrchestrationEvent> stepEvents = captor.getAllValues().stream()
                .filter(e -> "STEP_COMPLETED".equals(e.eventType()))
                .toList();
        assertThat(stepEvents).isEmpty();
    }

    // ── 7. Workflow publishEvents=true: step events published ────────

    @Test
    void workflow_publishEvents_true_publishesStepEvents() throws Exception {
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, publisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        var registry = new WorkflowRegistry();
        var engine = new WorkflowEngine(registry, executor, persistence, events, publisher);

        var bean = new WorkflowSteps();

        // publishEvents=true AND step has an outputEventType
        var def = new WorkflowDefinition("yes-publish-wf", "Yes Publish WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "OrderProcessed", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        bean, WorkflowSteps.class.getMethod("step1", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null,
                true);  // publishEvents = true
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("yes-publish-wf", Map.of("k", "v")))
                .assertNext(state -> assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED))
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(3)).publish(captor.capture());

        List<OrchestrationEvent> allEvents = captor.getAllValues();

        // Lifecycle events should always publish
        assertThat(allEvents).anyMatch(e -> "EXECUTION_STARTED".equals(e.eventType()));
        assertThat(allEvents).anyMatch(e -> "EXECUTION_COMPLETED".equals(e.eventType()));

        // Step events should be published since publishEvents=true
        List<OrchestrationEvent> stepEvents = allEvents.stream()
                .filter(e -> "STEP_COMPLETED".equals(e.eventType()))
                .toList();
        assertThat(stepEvents).hasSize(1);
        OrchestrationEvent stepEvent = stepEvents.get(0);
        assertThat(stepEvent.type()).isEqualTo("OrderProcessed");
        assertThat(stepEvent.stepId()).isEqualTo("step1");
        assertThat(stepEvent.pattern()).isEqualTo(ExecutionPattern.WORKFLOW);
    }

    // ── 8. Workflow builder DSL: publishEvents wires through ─────────

    @Test
    void workflowBuilder_publishEvents_setsFieldInDefinition() throws Exception {
        var bean = new Object() {
            public Mono<String> doWork() { return Mono.just("done"); }
        };

        WorkflowDefinition wfTrue = OrchestrationBuilder.workflow("pub-true")
                .publishEvents(true)
                .step("a")
                    .handler(bean, bean.getClass().getDeclaredMethod("doWork"))
                    .add()
                .build();

        assertThat(wfTrue.publishEvents()).isTrue();

        WorkflowDefinition wfFalse = OrchestrationBuilder.workflow("pub-false")
                .step("a")
                    .handler(bean, bean.getClass().getDeclaredMethod("doWork"))
                    .add()
                .build();

        assertThat(wfFalse.publishEvents()).isFalse();
    }

    // ── 9. Workflow builder: outputEventType and condition wire through ──

    @Test
    void workflowBuilder_outputEventTypeAndCondition_wireThrough() throws Exception {
        var bean = new Object() {
            public Mono<String> doWork() { return Mono.just("done"); }
        };

        WorkflowDefinition wf = OrchestrationBuilder.workflow("dsl-wf")
                .publishEvents(true)
                .step("a")
                    .handler(bean, bean.getClass().getDeclaredMethod("doWork"))
                    .outputEventType("OrderCreated")
                    .condition("true")
                    .add()
                .build();

        assertThat(wf.publishEvents()).isTrue();
        assertThat(wf.steps()).hasSize(1);
        assertThat(wf.steps().get(0).outputEventType()).isEqualTo("OrderCreated");
        assertThat(wf.steps().get(0).condition()).isEqualTo("true");
    }

    // ── 10. Workflow publishEvents=true via builder: end-to-end ──────

    @Test
    void workflowBuilder_publishEventsTrue_endToEnd_publishesStepEvents() throws Exception {
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, publisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        var registry = new WorkflowRegistry();
        var engine = new WorkflowEngine(registry, executor, persistence, events, publisher);

        var bean = new WorkflowSteps();

        WorkflowDefinition def = OrchestrationBuilder.workflow("builder-pub-wf")
                .publishEvents(true)
                .step("step1")
                    .handler(bean, WorkflowSteps.class.getMethod("step1", Map.class))
                    .outputEventType("StepOneProcessed")
                    .add()
                .build();
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("builder-pub-wf", Map.of()))
                .assertNext(state -> assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED))
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(3)).publish(captor.capture());

        List<OrchestrationEvent> stepEvents = captor.getAllValues().stream()
                .filter(e -> "STEP_COMPLETED".equals(e.eventType()))
                .toList();
        assertThat(stepEvents).hasSize(1);
        assertThat(stepEvents.get(0).type()).isEqualTo("StepOneProcessed");
    }
}
