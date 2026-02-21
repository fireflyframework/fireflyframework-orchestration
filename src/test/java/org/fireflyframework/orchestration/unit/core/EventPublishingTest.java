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

package org.fireflyframework.orchestration.unit.core;

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.event.OrchestrationEvent;
import org.fireflyframework.orchestration.core.event.OrchestrationEventPublisher;
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.StepTriggerMode;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.step.StepHandler;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class EventPublishingTest {

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

    // --- OrchestrationEvent wither tests ---

    @Test
    void orchestrationEvent_withTopic_setsTopicField() {
        OrchestrationEvent event = OrchestrationEvent.stepCompleted("test", "c1", ExecutionPattern.SAGA, "step1", "result")
                .withTopic("orders-topic");

        assertThat(event.topic()).isEqualTo("orders-topic");
        assertThat(event.type()).isNull();
        assertThat(event.key()).isNull();
        assertThat(event.eventType()).isEqualTo("STEP_COMPLETED");
    }

    @Test
    void orchestrationEvent_withAllRoutingFields_setsAllFields() {
        OrchestrationEvent event = OrchestrationEvent.stepCompleted("test", "c1", ExecutionPattern.SAGA, "step1", "result")
                .withTopic("topic1")
                .withType("OrderCreated")
                .withKey("order-123");

        assertThat(event.topic()).isEqualTo("topic1");
        assertThat(event.type()).isEqualTo("OrderCreated");
        assertThat(event.key()).isEqualTo("order-123");
    }

    @Test
    void orchestrationEvent_factoryMethods_defaultRoutingFieldsToNull() {
        OrchestrationEvent started = OrchestrationEvent.executionStarted("test", "c1", ExecutionPattern.SAGA);
        assertThat(started.topic()).isNull();
        assertThat(started.type()).isNull();
        assertThat(started.key()).isNull();

        OrchestrationEvent completed = OrchestrationEvent.executionCompleted("test", "c1", ExecutionPattern.SAGA, ExecutionStatus.COMPLETED);
        assertThat(completed.topic()).isNull();
        assertThat(completed.type()).isNull();
        assertThat(completed.key()).isNull();
    }

    // --- Saga event publishing tests ---

    @Test
    void saga_executionStartedAndCompleted_publishesLifecycleEvents() {
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, publisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, null, null, compensator, publisher);

        SagaDefinition saga = SagaBuilder.saga("TestSaga")
                .step("step1")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("done"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> assertThat(result.isSuccess()).isTrue())
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(2)).publish(captor.capture());

        List<OrchestrationEvent> events = captor.getAllValues();
        assertThat(events).anyMatch(e -> "EXECUTION_STARTED".equals(e.eventType()) && e.pattern() == ExecutionPattern.SAGA);
        assertThat(events).anyMatch(e -> "EXECUTION_COMPLETED".equals(e.eventType()) && e.status() == ExecutionStatus.COMPLETED);
    }

    @Test
    void saga_stepWithStepEvent_publishesStepEventWithMetadata() {
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, publisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, null, null, compensator, publisher);

        SagaDefinition saga = SagaBuilder.saga("EventSaga")
                .step("reserve")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("reserved"))
                    .add()
                .build();

        // Set step event metadata manually (normally done by annotation scanning)
        saga.steps.get("reserve").stepEvent = new StepEventConfig("orders-topic", "OrderReserved", "order-123");

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> assertThat(result.isSuccess()).isTrue())
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(3)).publish(captor.capture());

        List<OrchestrationEvent> stepEvents = captor.getAllValues().stream()
                .filter(e -> "STEP_COMPLETED".equals(e.eventType()))
                .toList();

        assertThat(stepEvents).hasSize(1);
        OrchestrationEvent stepEvent = stepEvents.get(0);
        assertThat(stepEvent.topic()).isEqualTo("orders-topic");
        assertThat(stepEvent.type()).isEqualTo("OrderReserved");
        assertThat(stepEvent.key()).isEqualTo("order-123");
        assertThat(stepEvent.stepId()).isEqualTo("reserve");
        assertThat(stepEvent.executionName()).isEqualTo("EventSaga");
    }

    @Test
    void saga_stepWithoutStepEvent_doesNotPublishStepEvent() {
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, publisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, null, null, compensator, publisher);

        SagaDefinition saga = SagaBuilder.saga("NoEventSaga")
                .step("step1")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("done"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> assertThat(result.isSuccess()).isTrue())
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(2)).publish(captor.capture());

        // Only lifecycle events, no STEP_COMPLETED
        List<OrchestrationEvent> stepEvents = captor.getAllValues().stream()
                .filter(e -> "STEP_COMPLETED".equals(e.eventType()))
                .toList();
        assertThat(stepEvents).isEmpty();
    }

    @Test
    void saga_failedExecution_publishesCompletedWithFailedStatus() {
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, publisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, null, null, compensator, publisher);

        SagaDefinition saga = SagaBuilder.saga("FailSaga")
                .step("fail")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.error(new RuntimeException("boom")))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> assertThat(result.isSuccess()).isFalse())
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(2)).publish(captor.capture());

        assertThat(captor.getAllValues()).anyMatch(e ->
                "EXECUTION_COMPLETED".equals(e.eventType()) && e.status() == ExecutionStatus.FAILED);
    }

    // --- TCC event publishing tests ---

    @Test
    void tcc_executionStartedAndCompleted_publishesLifecycleEvents() {
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, publisher);
        var engine = new TccEngine(null, events, orchestrator, null, null, publisher);

        TccDefinition tcc = TccBuilder.tcc("TestTcc")
                .participant("p1")
                    .tryHandler((input, ctx) -> Mono.just("tried"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> assertThat(result.isConfirmed()).isTrue())
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(2)).publish(captor.capture());

        assertThat(captor.getAllValues()).anyMatch(e ->
                "EXECUTION_STARTED".equals(e.eventType()) && e.pattern() == ExecutionPattern.TCC);
        assertThat(captor.getAllValues()).anyMatch(e ->
                "EXECUTION_COMPLETED".equals(e.eventType()) && e.status() == ExecutionStatus.CONFIRMED);
    }

    @Test
    void tcc_participantWithTccEvent_publishesEventOnConfirmSuccess() {
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, publisher);
        var engine = new TccEngine(null, events, orchestrator, null, null, publisher);

        TccDefinition tcc = TccBuilder.tcc("EventTcc")
                .participant("debit")
                    .tryHandler((input, ctx) -> Mono.just("debit-reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("debit-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        // Set TCC event metadata manually
        tcc.participants.get("debit").tccEvent = new TccEventConfig("payments-topic", "DebitConfirmed", "acct-456");

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> assertThat(result.isConfirmed()).isTrue())
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(3)).publish(captor.capture());

        List<OrchestrationEvent> stepEvents = captor.getAllValues().stream()
                .filter(e -> "STEP_COMPLETED".equals(e.eventType()))
                .toList();

        assertThat(stepEvents).hasSize(1);
        OrchestrationEvent stepEvent = stepEvents.get(0);
        assertThat(stepEvent.topic()).isEqualTo("payments-topic");
        assertThat(stepEvent.type()).isEqualTo("DebitConfirmed");
        assertThat(stepEvent.key()).isEqualTo("acct-456");
        assertThat(stepEvent.stepId()).isEqualTo("debit");
    }

    @Test
    void tcc_failedTcc_publishesCompletedWithCorrectStatus() {
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, publisher);
        var engine = new TccEngine(null, events, orchestrator, null, null, publisher);

        TccDefinition tcc = TccBuilder.tcc("FailTcc")
                .participant("p1")
                    .tryHandler((input, ctx) -> Mono.error(new RuntimeException("fail")))
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> assertThat(result.isCanceled()).isTrue())
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(2)).publish(captor.capture());

        assertThat(captor.getAllValues()).anyMatch(e ->
                "EXECUTION_COMPLETED".equals(e.eventType()) && e.status() == ExecutionStatus.CANCELED);
    }

    // --- Workflow event publishing tests ---

    @SuppressWarnings("unused")
    public static class TestWorkflowSteps {
        public Mono<String> step1(Map<String, Object> input) {
            return Mono.just("step1-result");
        }

        public Mono<String> step2(Map<String, Object> input) {
            return Mono.just("step2-result");
        }

        public Mono<String> failStep(Map<String, Object> input) {
            return Mono.error(new RuntimeException("workflow step failed"));
        }
    }

    @Test
    void workflow_executionStartedAndCompleted_publishesLifecycleEvents() throws Exception {
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, publisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        var registry = new WorkflowRegistry();
        var engine = new WorkflowEngine(registry, executor, new StepInvoker(new ArgumentResolver()), persistence, events, publisher);

        var testSteps = new TestWorkflowSteps();
        var def = new WorkflowDefinition("wf-test", "Test WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, TestWorkflowSteps.class.getMethod("step1", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("wf-test", Map.of("key", "val")))
                .assertNext(state -> assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED))
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(2)).publish(captor.capture());

        assertThat(captor.getAllValues()).anyMatch(e ->
                "EXECUTION_STARTED".equals(e.eventType()) && e.pattern() == ExecutionPattern.WORKFLOW);
        assertThat(captor.getAllValues()).anyMatch(e ->
                "EXECUTION_COMPLETED".equals(e.eventType()) && e.status() == ExecutionStatus.COMPLETED);
    }

    @Test
    void workflow_stepWithOutputEventType_publishesStepEvent() throws Exception {
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, publisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        var registry = new WorkflowRegistry();
        var engine = new WorkflowEngine(registry, executor, new StepInvoker(new ArgumentResolver()), persistence, events, publisher);

        var testSteps = new TestWorkflowSteps();
        var def = new WorkflowDefinition("wf-event", "Event WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "OrderProcessed", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, TestWorkflowSteps.class.getMethod("step1", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null,
                true, 0);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("wf-event", Map.of("k", "v")))
                .assertNext(state -> assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED))
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(3)).publish(captor.capture());

        List<OrchestrationEvent> stepEvents = captor.getAllValues().stream()
                .filter(e -> "STEP_COMPLETED".equals(e.eventType()))
                .toList();

        assertThat(stepEvents).hasSize(1);
        OrchestrationEvent stepEvent = stepEvents.get(0);
        assertThat(stepEvent.type()).isEqualTo("OrderProcessed");
        assertThat(stepEvent.stepId()).isEqualTo("step1");
        assertThat(stepEvent.pattern()).isEqualTo(ExecutionPattern.WORKFLOW);
    }

    @Test
    void workflow_stepWithBlankOutputEventType_doesNotPublishStepEvent() throws Exception {
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, publisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        var registry = new WorkflowRegistry();
        var engine = new WorkflowEngine(registry, executor, new StepInvoker(new ArgumentResolver()), persistence, events, publisher);

        var testSteps = new TestWorkflowSteps();
        var def = new WorkflowDefinition("wf-blank", "Blank WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, TestWorkflowSteps.class.getMethod("step1", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("wf-blank", Map.of()))
                .assertNext(state -> assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED))
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(2)).publish(captor.capture());

        List<OrchestrationEvent> stepEvents = captor.getAllValues().stream()
                .filter(e -> "STEP_COMPLETED".equals(e.eventType()))
                .toList();
        assertThat(stepEvents).isEmpty();
    }

    @Test
    void workflow_failedExecution_publishesCompletedWithFailedStatus() throws Exception {
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, publisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        var registry = new WorkflowRegistry();
        var engine = new WorkflowEngine(registry, executor, new StepInvoker(new ArgumentResolver()), persistence, events, publisher);

        var testSteps = new TestWorkflowSteps();
        var def = new WorkflowDefinition("wf-fail", "Fail WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("fail", "Fail", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, TestWorkflowSteps.class.getMethod("failStep", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("wf-fail", Map.of()))
                .assertNext(state -> assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED))
                .verifyComplete();

        ArgumentCaptor<OrchestrationEvent> captor = ArgumentCaptor.forClass(OrchestrationEvent.class);
        verify(publisher, atLeast(2)).publish(captor.capture());

        assertThat(captor.getAllValues()).anyMatch(e ->
                "EXECUTION_COMPLETED".equals(e.eventType()) && e.status() == ExecutionStatus.FAILED);
    }
}
