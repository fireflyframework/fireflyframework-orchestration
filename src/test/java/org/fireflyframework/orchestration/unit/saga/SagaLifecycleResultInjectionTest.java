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

package org.fireflyframework.orchestration.unit.saga;

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepHandler;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.saga.annotation.OnSagaComplete;
import org.fireflyframework.orchestration.saga.compensation.SagaCompensator;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaExecutionOrchestrator;
import org.fireflyframework.orchestration.saga.engine.SagaResult;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.saga.registry.SagaStepDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@code @OnSagaComplete} callback methods receive a non-null
 * {@link SagaResult} when they declare it as a parameter (Task 3 fix).
 */
class SagaLifecycleResultInjectionTest {

    private SagaEngine createEngine() {
        var events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noOpPublisher = new NoOpEventPublisher();
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        return new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher);
    }

    // --- Test bean that captures the SagaResult in the callback ---

    @SuppressWarnings("unused")
    public static class ResultCapturingBean {
        static final AtomicReference<SagaResult> capturedResult = new AtomicReference<>();
        static final AtomicReference<ExecutionContext> capturedCtx = new AtomicReference<>();

        @OnSagaComplete
        public void onComplete(ExecutionContext ctx, SagaResult result) {
            capturedCtx.set(ctx);
            capturedResult.set(result);
        }
    }

    @Test
    void onSagaComplete_receivesSagaResult() throws Exception {
        ResultCapturingBean.capturedResult.set(null);
        ResultCapturingBean.capturedCtx.set(null);

        var testBean = new ResultCapturingBean();
        SagaDefinition saga = new SagaDefinition("ResultInjectionTest", testBean, testBean, 0);

        SagaStepDefinition stepDef = new SagaStepDefinition(
                "step1", "", List.of(), 0, null, null, "", false, 0.5, false, null);
        stepDef.handler = (StepHandler<Object, String>) (input, ctx) -> Mono.just("step1-result");
        saga.steps.put("step1", stepDef);

        saga.onSagaCompleteMethods = List.of(
                ResultCapturingBean.class.getMethod("onComplete", ExecutionContext.class, SagaResult.class));

        SagaEngine engine = createEngine();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();

                    // Verify the callback received a non-null SagaResult
                    SagaResult captured = ResultCapturingBean.capturedResult.get();
                    assertThat(captured).isNotNull();
                    assertThat(captured.sagaName()).isEqualTo("ResultInjectionTest");
                    assertThat(captured.isSuccess()).isTrue();
                    assertThat(captured.steps()).containsKey("step1");

                    // Verify the context was also injected
                    assertThat(ResultCapturingBean.capturedCtx.get()).isNotNull();
                })
                .verifyComplete();
    }

    // --- Test bean with only ExecutionContext (no SagaResult) still works ---

    @SuppressWarnings("unused")
    public static class CtxOnlyBean {
        static final AtomicReference<ExecutionContext> capturedCtx = new AtomicReference<>();

        @OnSagaComplete
        public void onComplete(ExecutionContext ctx) {
            capturedCtx.set(ctx);
        }
    }

    @Test
    void onSagaComplete_withoutSagaResultParam_stillWorks() throws Exception {
        CtxOnlyBean.capturedCtx.set(null);

        var testBean = new CtxOnlyBean();
        SagaDefinition saga = new SagaDefinition("CtxOnlyTest", testBean, testBean, 0);

        SagaStepDefinition stepDef = new SagaStepDefinition(
                "step1", "", List.of(), 0, null, null, "", false, 0.5, false, null);
        stepDef.handler = (StepHandler<Object, String>) (input, ctx) -> Mono.just("done");
        saga.steps.put("step1", stepDef);

        saga.onSagaCompleteMethods = List.of(
                CtxOnlyBean.class.getMethod("onComplete", ExecutionContext.class));

        SagaEngine engine = createEngine();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(CtxOnlyBean.capturedCtx.get()).isNotNull();
                })
                .verifyComplete();
    }

    // --- Test bean with SagaResult only (no ExecutionContext) ---

    @SuppressWarnings("unused")
    public static class ResultOnlyBean {
        static final AtomicReference<SagaResult> capturedResult = new AtomicReference<>();

        @OnSagaComplete
        public void onComplete(SagaResult result) {
            capturedResult.set(result);
        }
    }

    @Test
    void onSagaComplete_withSagaResultOnly_receivesResult() throws Exception {
        ResultOnlyBean.capturedResult.set(null);

        var testBean = new ResultOnlyBean();
        SagaDefinition saga = new SagaDefinition("ResultOnlyTest", testBean, testBean, 0);

        SagaStepDefinition stepDef = new SagaStepDefinition(
                "step1", "", List.of(), 0, null, null, "", false, 0.5, false, null);
        stepDef.handler = (StepHandler<Object, String>) (input, ctx) -> Mono.just("done");
        saga.steps.put("step1", stepDef);

        saga.onSagaCompleteMethods = List.of(
                ResultOnlyBean.class.getMethod("onComplete", SagaResult.class));

        SagaEngine engine = createEngine();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    SagaResult captured = ResultOnlyBean.capturedResult.get();
                    assertThat(captured).isNotNull();
                    assertThat(captured.sagaName()).isEqualTo("ResultOnlyTest");
                })
                .verifyComplete();
    }
}
