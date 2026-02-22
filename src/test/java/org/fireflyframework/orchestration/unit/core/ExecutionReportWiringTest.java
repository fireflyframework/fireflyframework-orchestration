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
import org.fireflyframework.orchestration.core.argument.Input;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.report.ExecutionReport;
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
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import org.fireflyframework.orchestration.workflow.engine.WorkflowExecutor;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that ExecutionReport is properly wired into SagaEngine, TccEngine,
 * and WorkflowEngine for both success and failure paths.
 */
class ExecutionReportWiringTest {

    private OrchestrationEvents events;
    private StepInvoker stepInvoker;
    private NoOpEventPublisher noOpPublisher;

    @BeforeEach
    void setUp() {
        events = new OrchestrationEvents() {};
        stepInvoker = new StepInvoker(new ArgumentResolver());
        noOpPublisher = new NoOpEventPublisher();
    }

    // ── Saga ────────────────────────────────────────────────────────────────

    @Test
    void sagaEngine_success_attachesReport() {
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher);

        SagaDefinition saga = SagaBuilder.saga("ReportSuccessSaga")
                .step("step1")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("done"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.report()).isPresent();

                    ExecutionReport report = result.report().get();
                    assertThat(report.isSuccess()).isTrue();
                    assertThat(report.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(report.executionName()).isEqualTo("ReportSuccessSaga");
                    assertThat(report.failureReason()).isNull();
                    assertThat(report.stepReports()).containsKey("step1");
                })
                .verifyComplete();
    }

    @Test
    void sagaEngine_failure_attachesReportWithReason() {
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher);

        SagaDefinition saga = SagaBuilder.saga("ReportFailSaga")
                .step("step1")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("saga step boom")))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    assertThat(result.report()).isPresent();

                    ExecutionReport report = result.report().get();
                    assertThat(report.isSuccess()).isFalse();
                    assertThat(report.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(report.failureReason()).isEqualTo("saga step boom");
                })
                .verifyComplete();
    }

    // ── TCC ─────────────────────────────────────────────────────────────────

    @Test
    void tccEngine_confirmed_attachesReport() {
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var engine = new TccEngine(null, events, orchestrator, null, null, noOpPublisher);

        TccDefinition tcc = TccBuilder.tcc("ReportConfirmTcc")
                .participant("p1")
                    .tryHandler((input, ctx) -> Mono.just("tried"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();
                    assertThat(result.report()).isPresent();

                    ExecutionReport report = result.report().get();
                    assertThat(report.isSuccess()).isTrue();
                    assertThat(report.status()).isEqualTo(ExecutionStatus.CONFIRMED);
                    assertThat(report.executionName()).isEqualTo("ReportConfirmTcc");
                    assertThat(report.failureReason()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void tccEngine_failed_attachesReport() {
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var engine = new TccEngine(null, events, orchestrator, null, null, noOpPublisher);

        TccDefinition tcc = TccBuilder.tcc("ReportFailTcc")
                .participant("p1")
                    .tryHandler((input, ctx) -> Mono.just("tried"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .participant("p2")
                    .tryHandler((input, ctx) -> Mono.error(new RuntimeException("tcc try boom")))
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isFalse();
                    assertThat(result.report()).isPresent();

                    ExecutionReport report = result.report().get();
                    assertThat(report.isSuccess()).isFalse();
                    assertThat(report.failureReason()).isEqualTo("tcc try boom");
                })
                .verifyComplete();
    }

    // ── Workflow ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    public static class WfTestSteps {
        public Mono<String> successStep(@Input Map<String, Object> input) {
            return Mono.just("wf-step-result");
        }

        public Mono<String> failStep(@Input Map<String, Object> input) {
            return Mono.error(new RuntimeException("wf step boom"));
        }
    }

    @Test
    void workflowEngine_completed_attachesReport() throws Exception {
        var executor = new WorkflowExecutor(stepInvoker, events, noOpPublisher, null, null);
        var registry = new WorkflowRegistry();
        var persistence = new InMemoryPersistenceProvider();
        var engine = new WorkflowEngine(registry, executor, stepInvoker, persistence, events, noOpPublisher);

        var testSteps = new WfTestSteps();
        WorkflowDefinition def = new WorkflowDefinition("report-ok-wf", "Report OK WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                        "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, WfTestSteps.class.getMethod("successStep", Map.class))),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("report-ok-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(state.report()).isPresent();

                    ExecutionReport report = state.report().get();
                    assertThat(report.isSuccess()).isTrue();
                    assertThat(report.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(report.executionName()).isEqualTo("report-ok-wf");
                    assertThat(report.failureReason()).isNull();
                    assertThat(report.stepReports()).containsKey("step1");
                })
                .verifyComplete();
    }

    @Test
    void workflowEngine_failed_attachesReport() throws Exception {
        var executor = new WorkflowExecutor(stepInvoker, events, noOpPublisher, null, null);
        var registry = new WorkflowRegistry();
        var persistence = new InMemoryPersistenceProvider();
        var engine = new WorkflowEngine(registry, executor, stepInvoker, persistence, events, noOpPublisher);

        var testSteps = new WfTestSteps();
        WorkflowDefinition def = new WorkflowDefinition("report-fail-wf", "Report Fail WF", "test", "1.0",
                List.of(new WorkflowStepDefinition("fail", "Fail Step", "", List.of(), 0,
                        "", 5000, RetryPolicy.NO_RETRY, "",
                        false, false, "",
                        testSteps, WfTestSteps.class.getMethod("failStep", Map.class))),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("report-fail-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(state.report()).isPresent();

                    ExecutionReport report = state.report().get();
                    assertThat(report.isSuccess()).isFalse();
                    assertThat(report.status()).isEqualTo(ExecutionStatus.FAILED);
                    assertThat(report.failureReason()).isEqualTo("wf step boom");
                })
                .verifyComplete();
    }
}
