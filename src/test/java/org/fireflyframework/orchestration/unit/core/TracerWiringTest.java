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

import io.micrometer.observation.ObservationRegistry;
import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.observability.OrchestrationTracer;
import org.fireflyframework.orchestration.core.step.StepHandler;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
import org.fireflyframework.orchestration.saga.compensation.SagaCompensator;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaExecutionOrchestrator;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.tcc.builder.TccBuilder;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccExecutionOrchestrator;
import org.fireflyframework.orchestration.tcc.engine.TccInputs;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.fireflyframework.orchestration.core.argument.Input;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.TriggerMode;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class TracerWiringTest {

    private OrchestrationEvents events;
    private StepInvoker stepInvoker;
    private NoOpEventPublisher noOpPublisher;

    @BeforeEach
    void setUp() {
        events = new OrchestrationEvents() {};
        stepInvoker = new StepInvoker(new ArgumentResolver());
        noOpPublisher = new NoOpEventPublisher();
    }

    @SuppressWarnings("unused")
    public static class TestSteps {
        public Mono<String> step1(@Input Map<String, Object> input) {
            return Mono.just("step1-result");
        }
    }

    @Test
    void sagaEngine_withoutTracer_executesNormally() {
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher);

        SagaDefinition saga = SagaBuilder.saga("NoTracerSaga")
                .step("s1")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("done"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.steps().get("s1").status()).isEqualTo(StepStatus.DONE);
                })
                .verifyComplete();
    }

    @Test
    void sagaEngine_withTracer_executesWithObservation() {
        var tracer = new OrchestrationTracer(ObservationRegistry.create());
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher, tracer);

        SagaDefinition saga = SagaBuilder.saga("TracedSaga")
                .step("s1")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("traced"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.resultOf("s1", String.class)).hasValue("traced");
                })
                .verifyComplete();
    }

    @Test
    void tccEngine_withoutTracer_executesNormally() {
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var engine = new TccEngine(null, events, orchestrator, null, null, noOpPublisher);

        TccDefinition tcc = TccBuilder.tcc("NoTracerTcc")
                .participant("p1")
                    .tryHandler((input, ctx) -> Mono.just("tried"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> assertThat(result.isConfirmed()).isTrue())
                .verifyComplete();
    }

    @Test
    void tccEngine_withTracer_executesWithObservation() {
        var tracer = new OrchestrationTracer(ObservationRegistry.create());
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var engine = new TccEngine(null, events, orchestrator, null, null, noOpPublisher, tracer);

        TccDefinition tcc = TccBuilder.tcc("TracedTcc")
                .participant("p1")
                    .tryHandler((input, ctx) -> Mono.just("tried"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> assertThat(result.isConfirmed()).isTrue())
                .verifyComplete();
    }

    @Test
    void workflowEngine_withoutTracer_executesNormally() throws Exception {
        var executor = new WorkflowExecutor(stepInvoker, events, noOpPublisher, null, null);
        var registry = new WorkflowRegistry();
        var testSteps = new TestSteps();

        WorkflowDefinition def = new WorkflowDefinition("no-tracer-wf", "No Tracer WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                        "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, TestSteps.class.getMethod("step1", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        var engine = new WorkflowEngine(registry, executor, stepInvoker,
                new InMemoryPersistenceProvider(), events, noOpPublisher);

        StepVerifier.create(engine.startWorkflow("no-tracer-wf", Map.of()))
                .assertNext(state -> assertThat(state.status().isTerminal()).isTrue())
                .verifyComplete();
    }

    @Test
    void workflowEngine_withTracer_executesWithObservation() throws Exception {
        var tracer = new OrchestrationTracer(ObservationRegistry.create());
        var executor = new WorkflowExecutor(stepInvoker, events, noOpPublisher, null, null);
        var registry = new WorkflowRegistry();
        var testSteps = new TestSteps();

        WorkflowDefinition def = new WorkflowDefinition("traced-wf", "Traced WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                        "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, TestSteps.class.getMethod("step1", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        var engine = new WorkflowEngine(registry, executor, stepInvoker,
                new InMemoryPersistenceProvider(), events, noOpPublisher, null, tracer);

        StepVerifier.create(engine.startWorkflow("traced-wf", Map.of()))
                .assertNext(state -> assertThat(state.status().isTerminal()).isTrue())
                .verifyComplete();
    }
}
