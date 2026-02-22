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
 * Tests for {@code @WaitForAll} semantics in the WorkflowExecutor.
 */
class WaitForAllTest {

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
    public static class WaitForAllBean {
        public Mono<String> gatedStep(@Input Map<String, Object> input) {
            return Mono.just("all-signals-received");
        }
    }

    @Test
    void waitForAll_allSignalsReceived_stepProceeds() throws Exception {
        var bean = new WaitForAllBean();

        // Create WaitForSignal annotations programmatically
        WaitForSignal sig1 = createWaitForSignal("approval", 5000);
        WaitForSignal sig2 = createWaitForSignal("validation", 5000);

        var stepDef = new WorkflowStepDefinition(
                "gated-step", "Gated Step", "", List.of(), 0,
                "", 10000, RetryPolicy.NO_RETRY, "",
                false, false, "",
                bean, WaitForAllBean.class.getMethod("gatedStep", Map.class),
                null, 0, 0, null,
                List.of(sig1, sig2), List.of(),
                List.of(), List.of(),
                null, false, 0);

        var def = new WorkflowDefinition("waitforall-wf", "WaitForAll WF", "test", "1.0",
                List.of(stepDef),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        // Start the workflow (blocks waiting for both signals)
        Mono<org.fireflyframework.orchestration.core.persistence.ExecutionState> workflowResult =
                engine.startWorkflow("waitforall-wf", Map.of())
                        .subscribeOn(Schedulers.boundedElastic());

        // Deliver both signals after a short delay
        Mono<Void> signalDelivery = Mono.delay(Duration.ofMillis(50))
                .flatMap(tick -> engine.findByStatus(ExecutionStatus.RUNNING)
                        .next()
                        .flatMap(state -> signalService.signal(state.correlationId(), "approval", "ok")
                                .then(signalService.signal(state.correlationId(), "validation", "valid"))))
                .subscribeOn(Schedulers.boundedElastic())
                .then();

        StepVerifier.create(Flux.merge(signalDelivery.then(Mono.empty()), workflowResult).next())
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(state.stepResults()).containsKey("gated-step");
                    assertThat(state.stepResults().get("gated-step")).isEqualTo("all-signals-received");
                })
                .verifyComplete();
    }

    @Test
    void waitForAll_partialSignals_stepBlocked() throws Exception {
        var bean = new WaitForAllBean();

        // Require two signals but only deliver one; use a short timeout to prevent blocking forever
        WaitForSignal sig1 = createWaitForSignal("signal-a", 200);
        WaitForSignal sig2 = createWaitForSignal("signal-b", 200);

        var stepDef = new WorkflowStepDefinition(
                "partial-step", "Partial Step", "", List.of(), 0,
                "", 5000, RetryPolicy.NO_RETRY, "",
                false, false, "",
                bean, WaitForAllBean.class.getMethod("gatedStep", Map.class),
                null, 0, 0, null,
                List.of(sig1, sig2), List.of(),
                List.of(), List.of(),
                null, false, 0);

        var def = new WorkflowDefinition("partial-wf", "Partial WF", "test", "1.0",
                List.of(stepDef),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        // Start workflow, then deliver only one of the two required signals
        Mono<org.fireflyframework.orchestration.core.persistence.ExecutionState> workflowResult =
                engine.startWorkflow("partial-wf", Map.of())
                        .subscribeOn(Schedulers.boundedElastic());

        Mono<Void> partialDelivery = Mono.delay(Duration.ofMillis(30))
                .flatMap(tick -> engine.findByStatus(ExecutionStatus.RUNNING)
                        .next()
                        .flatMap(state -> signalService.signal(state.correlationId(), "signal-a", "ok")))
                .subscribeOn(Schedulers.boundedElastic())
                .then();

        // The workflow should fail due to timeout on signal-b (only signal-a was delivered)
        StepVerifier.create(Flux.merge(partialDelivery.then(Mono.empty()), workflowResult).next())
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED);
                })
                .verifyComplete();
    }

    // ── Helper to create WaitForSignal annotation instances ──────────

    private static WaitForSignal createWaitForSignal(String value, long timeoutMs) {
        return new WaitForSignal() {
            @Override public String value() { return value; }
            @Override public long timeoutMs() { return timeoutMs; }
            @Override public Class<? extends Annotation> annotationType() { return WaitForSignal.class; }
        };
    }
}
