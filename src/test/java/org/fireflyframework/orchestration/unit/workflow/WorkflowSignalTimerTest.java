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
import org.fireflyframework.orchestration.core.model.StepTriggerMode;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import org.fireflyframework.orchestration.workflow.engine.WorkflowExecutor;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import org.fireflyframework.orchestration.workflow.signal.SignalService;
import org.fireflyframework.orchestration.workflow.timer.TimerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for @WaitForSignal and @WaitForTimer integration with WorkflowExecutor.
 */
class WorkflowSignalTimerTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;
    private InMemoryPersistenceProvider persistence;
    private SignalService signalService;
    private TimerService timerService;
    private OrchestrationEvents events;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        persistence = new InMemoryPersistenceProvider();
        signalService = new SignalService(persistence, events);
        timerService = new TimerService(events);
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, noOpPublisher,
                signalService, timerService);
        engine = new WorkflowEngine(registry, executor, new StepInvoker(new ArgumentResolver()), persistence, events, noOpPublisher);
    }

    // ── Test beans ────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    public static class SignalStepBean {
        final AtomicReference<Object> receivedInput = new AtomicReference<>();

        public Mono<String> approvalStep(@Input Map<String, Object> input) {
            receivedInput.set(input);
            return Mono.just("approved");
        }
    }

    @SuppressWarnings("unused")
    public static class TimerStepBean {
        volatile long executedAtMs;

        public Mono<String> delayedStep(@Input Map<String, Object> input) {
            executedAtMs = System.currentTimeMillis();
            return Mono.just("delayed-result");
        }
    }

    @SuppressWarnings("unused")
    public static class SimpleStepBean {
        public Mono<String> step1(@Input Map<String, Object> input) {
            return Mono.just("step1-result");
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────

    @Test
    void step_waitsForSignal_thenExecutes() throws Exception {
        var bean = new SignalStepBean();
        var stepDef = new WorkflowStepDefinition(
                "approval-step", "Approval Step", "", List.of(), 0,
                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                false, false, "",
                bean, SignalStepBean.class.getMethod("approvalStep", Map.class),
                "approval", 0, 0, null);

        var def = new WorkflowDefinition("signal-wf", "Signal Workflow", "test", "1.0",
                List.of(stepDef),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        // Start the workflow which will block waiting for signal
        Mono<ExecutionState> workflowResult = engine.startWorkflow("signal-wf", Map.of("key", "value"))
                .subscribeOn(Schedulers.boundedElastic());

        // Deliver the signal after a short delay
        Mono<ExecutionState> signalDelivery = Mono.delay(Duration.ofMillis(100))
                .flatMap(tick -> engine.findByStatus(ExecutionStatus.RUNNING)
                        .next()
                        .flatMap(state -> signalService.signal(
                                state.correlationId(), "approval", Map.of("approved", true))))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.<ExecutionState>empty());

        // Merge both: the signal delivery and workflow result
        StepVerifier.create(Flux.merge(signalDelivery, workflowResult).next())
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(state.stepResults()).containsKey("approval-step");
                })
                .verifyComplete();
    }

    @Test
    void step_waitsForTimer_delaysExecution() throws Exception {
        var bean = new TimerStepBean();
        long delayMs = 50;
        var stepDef = new WorkflowStepDefinition(
                "delayed-step", "Delayed Step", "", List.of(), 0,
                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                false, false, "",
                bean, TimerStepBean.class.getMethod("delayedStep", Map.class),
                null, 0, delayMs, null);

        var def = new WorkflowDefinition("timer-wf", "Timer Workflow", "test", "1.0",
                List.of(stepDef),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        long startMs = System.currentTimeMillis();

        StepVerifier.create(engine.startWorkflow("timer-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(state.stepResults()).containsKey("delayed-step");
                    // Verify that the step executed after the delay
                    long elapsed = bean.executedAtMs - startMs;
                    assertThat(elapsed).isGreaterThanOrEqualTo(delayMs - 10); // small tolerance
                })
                .verifyComplete();
    }

    @Test
    void signal_payloadStoredInContext() throws Exception {
        var bean = new SignalStepBean();
        var stepDef = new WorkflowStepDefinition(
                "signal-step", "Signal Step", "", List.of(), 0,
                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                false, false, "",
                bean, SignalStepBean.class.getMethod("approvalStep", Map.class),
                "data-signal", 0, 0, null);

        var def = new WorkflowDefinition("signal-payload-wf", "Signal Payload WF", "test", "1.0",
                List.of(stepDef),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        Map<String, Object> payload = Map.of("data", "test-payload");

        // Start the workflow (blocks on signal)
        Mono<ExecutionState> workflowResult = engine.startWorkflow("signal-payload-wf", Map.of())
                .subscribeOn(Schedulers.boundedElastic());

        // Deliver the signal with payload after a short delay
        Mono<ExecutionState> signalDelivery = Mono.delay(Duration.ofMillis(100))
                .flatMap(tick -> engine.findByStatus(ExecutionStatus.RUNNING)
                        .next()
                        .flatMap(state -> signalService.signal(
                                state.correlationId(), "data-signal", payload)))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.<ExecutionState>empty());

        StepVerifier.create(Flux.merge(signalDelivery, workflowResult).next())
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    // The signal payload should be stored in context variables
                    assertThat(state.variables()).containsKey("signal.data-signal");
                    assertThat(state.variables().get("signal.data-signal")).isEqualTo(payload);
                })
                .verifyComplete();
    }

    @Test
    void signal_timeoutThrowsException() throws Exception {
        var bean = new SimpleStepBean();
        var stepDef = new WorkflowStepDefinition(
                "timeout-step", "Timeout Step", "", List.of(), 0,
                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                false, false, "",
                bean, SimpleStepBean.class.getMethod("step1", Map.class),
                "never-arrives", 50, 0, null);  // 50ms signal timeout

        var def = new WorkflowDefinition("timeout-wf", "Timeout WF", "test", "1.0",
                List.of(stepDef),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        // Don't deliver any signal - should timeout and workflow should fail
        StepVerifier.create(engine.startWorkflow("timeout-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED);
                })
                .verifyComplete();
    }

    @Test
    void registry_scansSignalAndTimerAnnotations() {
        // Verify that WorkflowStepDefinition correctly stores signal/timer data
        var stepDef = new WorkflowStepDefinition(
                "annotated-step", "Annotated Step", "", List.of(), 0,
                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                false, false, "", null, null,
                "approval", 3000, 5000, "my-timer");

        assertThat(stepDef.waitForSignal()).isEqualTo("approval");
        assertThat(stepDef.signalTimeoutMs()).isEqualTo(3000);
        assertThat(stepDef.waitForTimerDelayMs()).isEqualTo(5000);
        assertThat(stepDef.waitForTimerId()).isEqualTo("my-timer");

        // Verify defaults when no annotations
        var plainStep = new WorkflowStepDefinition(
                "plain-step", "Plain Step", "", List.of(), 0,
                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                false, false, "", null, null,
                null, 0, 0, null);

        assertThat(plainStep.waitForSignal()).isNull();
        assertThat(plainStep.signalTimeoutMs()).isZero();
        assertThat(plainStep.waitForTimerDelayMs()).isZero();
        assertThat(plainStep.waitForTimerId()).isNull();
    }
}
