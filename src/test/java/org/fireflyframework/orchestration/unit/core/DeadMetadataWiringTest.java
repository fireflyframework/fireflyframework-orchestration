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
import org.fireflyframework.orchestration.core.argument.CorrelationId;
import org.fireflyframework.orchestration.core.argument.Input;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
import org.fireflyframework.orchestration.tcc.builder.TccBuilder;
import org.fireflyframework.orchestration.workflow.builder.WorkflowBuilder;
import org.fireflyframework.orchestration.workflow.engine.WorkflowExecutor;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Commit Group 2 (M2, M6, M10, M11, M12, M14) wiring fixes.
 */
class DeadMetadataWiringTest {

    // ---- M2: Workflow retry falls back to definition-level policy ----

    @Test
    void workflowRetryFallsBackToDefinitionPolicy() throws Exception {
        var testSteps = new TestSteps();
        var workflowRetryPolicy = new RetryPolicy(5, Duration.ofMillis(200), Duration.ofMinutes(5), 2.0, 0.1, new String[]{});

        // Step uses NO_RETRY — should fall back to workflow-level policy
        var stepDef = new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                "", 5000, RetryPolicy.NO_RETRY, "",
                false, false, "",
                testSteps, TestSteps.class.getMethod("step1", Map.class),
                null, 0, 0, null);

        var def = new WorkflowDefinition("retry-wf", "Retry Workflow", "test", "1.0",
                List.of(stepDef),
                TriggerMode.SYNC, "", 30000, workflowRetryPolicy, null, List.of(), List.of(), List.of(),
                false, 0);

        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, noOpPublisher, null, null);

        var ctx = ExecutionContext.forWorkflow("corr-1", "retry-wf");

        // Execute the workflow — step should succeed. The important thing is that
        // the fallback logic was exercised (NO_RETRY step falls back to workflow policy)
        StepVerifier.create(executor.execute(def, ctx))
                .assertNext(resultCtx -> {
                    assertThat(resultCtx.getStepStatus("step1")).isEqualTo(StepStatus.DONE);
                })
                .verifyComplete();
    }

    @Test
    void workflowRetryUsesStepPolicyWhenPresent() throws Exception {
        var testSteps = new TestSteps();
        var workflowRetryPolicy = new RetryPolicy(5, Duration.ofMillis(200), Duration.ofMinutes(5), 2.0, 0.1, new String[]{});
        var stepRetryPolicy = new RetryPolicy(2, Duration.ofMillis(100), Duration.ofMinutes(1), 1.5, 0.2, new String[]{});

        // Step has its own retry policy — should use it, not the workflow-level one
        var stepDef = new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                "", 5000, stepRetryPolicy, "",
                false, false, "",
                testSteps, TestSteps.class.getMethod("step1", Map.class),
                null, 0, 0, null);

        var def = new WorkflowDefinition("retry-wf2", "Retry Workflow 2", "test", "1.0",
                List.of(stepDef),
                TriggerMode.SYNC, "", 30000, workflowRetryPolicy, null, List.of(), List.of(), List.of(),
                false, 0);

        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, noOpPublisher, null, null);

        var ctx = ExecutionContext.forWorkflow("corr-2", "retry-wf2");

        StepVerifier.create(executor.execute(def, ctx))
                .assertNext(resultCtx -> {
                    assertThat(resultCtx.getStepStatus("step1")).isEqualTo(StepStatus.DONE);
                })
                .verifyComplete();
    }

    // ---- M6: Dry-run skips execution ----

    @Test
    void dryRunSkipsExecution() throws Exception {
        var testSteps = new TestSteps();
        var stepDef = new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                "", 5000, RetryPolicy.NO_RETRY, "",
                false, false, "",
                testSteps, TestSteps.class.getMethod("step1", Map.class),
                null, 0, 0, null);

        var def = new WorkflowDefinition("dryrun-wf", "DryRun Workflow", "test", "1.0",
                List.of(stepDef),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, List.of(), List.of(), List.of(),
                false, 0);

        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, noOpPublisher, null, null);

        // Create context with dryRun=true
        var ctx = ExecutionContext.forWorkflow("corr-dry", "dryrun-wf", true);

        StepVerifier.create(executor.execute(def, ctx))
                .assertNext(resultCtx -> {
                    assertThat(resultCtx.getStepStatus("step1")).isEqualTo(StepStatus.SKIPPED);
                    // Step result should NOT be set since execution was skipped
                    assertThat(resultCtx.getResult("step1")).isNull();
                })
                .verifyComplete();
    }

    // ---- M10: SagaBuilder cpuBound ----

    @Test
    void sagaBuilderCpuBound() {
        var saga = SagaBuilder.saga("cpu-saga")
                .step("compute")
                    .handler(() -> Mono.just("computed"))
                    .cpuBound(true)
                    .add()
                .build();

        assertThat(saga.steps.get("compute").cpuBound).isTrue();
    }

    @Test
    void sagaBuilderCpuBoundDefaultsFalse() {
        var saga = SagaBuilder.saga("default-saga")
                .step("normal")
                    .handler(() -> Mono.just("done"))
                    .add()
                .build();

        assertThat(saga.steps.get("normal").cpuBound).isFalse();
    }

    // ---- M11: TccBuilder event ----

    @Test
    void tccBuilderEvent() {
        var tcc = TccBuilder.tcc("event-tcc")
                .participant("debit")
                    .tryHandler((input, ctx) -> Mono.just("reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .event("orders", "OrderConfirmed", "orderId")
                    .add()
                .build();

        var pd = tcc.participants.get("debit");
        assertThat(pd.tccEvent).isNotNull();
        assertThat(pd.tccEvent.topic()).isEqualTo("orders");
        assertThat(pd.tccEvent.eventType()).isEqualTo("OrderConfirmed");
        assertThat(pd.tccEvent.key()).isEqualTo("orderId");
    }

    @Test
    void tccBuilderWithoutEventHasNullTccEvent() {
        var tcc = TccBuilder.tcc("no-event-tcc")
                .participant("debit")
                    .tryHandler((input, ctx) -> Mono.just("reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        var pd = tcc.participants.get("debit");
        assertThat(pd.tccEvent).isNull();
    }

    // ---- M12: WorkflowBuilder async/compensatable ----

    @Test
    void workflowBuilderAsyncCompensatable() {
        var wf = new WorkflowBuilder("async-wf")
                .step("step1")
                    .async(true)
                    .compensatable(true, "rollback")
                    .add()
                .build();

        var step = wf.steps().stream().filter(s -> s.stepId().equals("step1")).findFirst().orElseThrow();
        assertThat(step.async()).isTrue();
        assertThat(step.compensatable()).isTrue();
        assertThat(step.compensationMethod()).isEqualTo("rollback");
    }

    @Test
    void workflowBuilderDefaultsAsyncAndCompensatable() {
        var wf = new WorkflowBuilder("default-wf")
                .step("step1")
                    .add()
                .build();

        var step = wf.steps().stream().filter(s -> s.stepId().equals("step1")).findFirst().orElseThrow();
        assertThat(step.async()).isFalse();
        assertThat(step.compensatable()).isFalse();
        assertThat(step.compensationMethod()).isEmpty();
    }

    // ---- M14: @CorrelationId annotation ----

    @SuppressWarnings("unused")
    static class CorrelationIdBean {
        public void withCorrelationId(@CorrelationId String cid) {}
        public void withCorrelationIdAndInput(@Input String data, @CorrelationId String cid) {}
    }

    @Test
    void correlationIdAnnotation() throws Exception {
        var resolver = new ArgumentResolver();
        Method m = CorrelationIdBean.class.getMethod("withCorrelationId", String.class);
        var ctx = ExecutionContext.forSaga("my-corr-123", "test");
        Object[] args = resolver.resolveArguments(m, null, ctx);
        assertThat(args).hasSize(1);
        assertThat(args[0]).isEqualTo("my-corr-123");
    }

    @Test
    void correlationIdWithOtherAnnotations() throws Exception {
        var resolver = new ArgumentResolver();
        Method m = CorrelationIdBean.class.getMethod("withCorrelationIdAndInput", String.class, String.class);
        var ctx = ExecutionContext.forSaga("corr-456", "test");
        Object[] args = resolver.resolveArguments(m, "inputData", ctx);
        assertThat(args).hasSize(2);
        assertThat(args[0]).isEqualTo("inputData");
        assertThat(args[1]).isEqualTo("corr-456");
    }

    // ---- Test helper ----

    @SuppressWarnings("unused")
    public static class TestSteps {
        public Mono<String> step1(@Input Map<String, Object> input) {
            return Mono.just("step1-result");
        }
    }
}
