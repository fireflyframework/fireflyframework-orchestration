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
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.workflow.annotation.WaitForSignal;
import org.fireflyframework.orchestration.workflow.annotation.WaitForTimer;
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

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@code @WaitForAny} semantics in the WorkflowExecutor.
 */
class WaitForAnyTest {

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
        engine = new WorkflowEngine(registry, executor, new StepInvoker(new ArgumentResolver()),
                persistence, events, noOpPublisher);
    }

    @SuppressWarnings("unused")
    public static class WaitForAnyBean {
        public Mono<String> anyGatedStep(@Input Map<String, Object> input) {
            return Mono.just("any-signal-received");
        }
    }

    @Test
    void waitForAny_firstSignal_stepProceeds() throws Exception {
        var bean = new WaitForAnyBean();

        // Configure two signals — only one needs to arrive
        WaitForSignal sig1 = createWaitForSignal("fast-signal", 5000);
        WaitForSignal sig2 = createWaitForSignal("slow-signal", 5000);

        var stepDef = new WorkflowStepDefinition(
                "any-step", "Any Step", "", List.of(), 0,
                "", 10000, RetryPolicy.NO_RETRY, "",
                false, false, "",
                bean, WaitForAnyBean.class.getMethod("anyGatedStep", Map.class),
                null, 0, 0, null,
                List.of(), List.of(),
                List.of(sig1, sig2), List.of(),
                null, false, 0);

        var def = new WorkflowDefinition("waitforany-wf", "WaitForAny WF", "test", "1.0",
                List.of(stepDef),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        // Start the workflow (blocks waiting for any signal)
        Mono<org.fireflyframework.orchestration.core.persistence.ExecutionState> workflowResult =
                engine.startWorkflow("waitforany-wf", Map.of())
                        .subscribeOn(Schedulers.boundedElastic());

        // Deliver only the first signal
        Mono<Void> signalDelivery = Mono.delay(Duration.ofMillis(50))
                .flatMap(tick -> engine.findByStatus(ExecutionStatus.RUNNING)
                        .next()
                        .flatMap(state -> signalService.signal(state.correlationId(), "fast-signal", "first")))
                .subscribeOn(Schedulers.boundedElastic())
                .then();

        StepVerifier.create(Flux.merge(signalDelivery.then(Mono.empty()), workflowResult).next())
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(state.stepResults()).containsKey("any-step");
                    assertThat(state.stepResults().get("any-step")).isEqualTo("any-signal-received");
                })
                .verifyComplete();
    }

    @Test
    void waitForAny_timerFires_stepProceeds() throws Exception {
        var bean = new WaitForAnyBean();

        // Configure a signal (that won't arrive) and a short timer (that will fire first)
        WaitForSignal sig1 = createWaitForSignal("never-arrives", 5000);
        WaitForTimer timer1 = createWaitForTimer(50);

        var stepDef = new WorkflowStepDefinition(
                "timer-any-step", "Timer Any Step", "", List.of(), 0,
                "", 10000, RetryPolicy.NO_RETRY, "",
                false, false, "",
                bean, WaitForAnyBean.class.getMethod("anyGatedStep", Map.class),
                null, 0, 0, null,
                List.of(), List.of(),
                List.of(sig1), List.of(timer1),
                null, false, 0);

        var def = new WorkflowDefinition("timer-any-wf", "Timer Any WF", "test", "1.0",
                List.of(stepDef),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        // The timer should fire and allow the step to proceed without a signal
        StepVerifier.create(engine.startWorkflow("timer-any-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(state.stepResults()).containsKey("timer-any-step");
                })
                .verifyComplete();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static WaitForSignal createWaitForSignal(String value, long timeoutMs) {
        return new WaitForSignal() {
            @Override public String value() { return value; }
            @Override public long timeoutMs() { return timeoutMs; }
            @Override public Class<? extends Annotation> annotationType() { return WaitForSignal.class; }
        };
    }

    private static WaitForTimer createWaitForTimer(long delayMs) {
        return new WaitForTimer() {
            @Override public long delayMs() { return delayMs; }
            @Override public String timerId() { return ""; }
            @Override public Class<? extends Annotation> annotationType() { return WaitForTimer.class; }
        };
    }
}
