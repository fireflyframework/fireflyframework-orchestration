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

package org.fireflyframework.orchestration.unit.tcc;

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.tcc.annotation.OnTccComplete;
import org.fireflyframework.orchestration.tcc.builder.TccBuilder;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccExecutionOrchestrator;
import org.fireflyframework.orchestration.tcc.engine.TccInputs;
import org.fireflyframework.orchestration.tcc.engine.TccResult;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@code @OnTccComplete} callback methods receive a non-null
 * {@link TccResult} when they declare it as a parameter (Task 4 fix).
 */
class TccLifecycleResultInjectionTest {

    private TccEngine createEngine() {
        var events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noOpPublisher = new NoOpEventPublisher();
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        return new TccEngine(null, events, orchestrator, null, null, noOpPublisher);
    }

    // --- Test bean that captures the TccResult in the callback ---

    @SuppressWarnings("unused")
    public static class ResultCapturingBean {
        static final AtomicReference<TccResult> capturedResult = new AtomicReference<>();
        static final AtomicReference<ExecutionContext> capturedCtx = new AtomicReference<>();

        @OnTccComplete
        public void onComplete(ExecutionContext ctx, TccResult result) {
            capturedCtx.set(ctx);
            capturedResult.set(result);
        }
    }

    @Test
    void onTccComplete_receivesTccResult() throws Exception {
        ResultCapturingBean.capturedResult.set(null);
        ResultCapturingBean.capturedCtx.set(null);

        var testBean = new ResultCapturingBean();

        TccDefinition tcc = TccBuilder.tcc("ResultInjectionTcc")
                .participant("debit")
                    .tryHandler((input, ctx) -> Mono.just("reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        // Wire lifecycle callback
        tcc.onTccCompleteMethods = List.of(
                ResultCapturingBean.class.getMethod("onComplete", ExecutionContext.class, TccResult.class));

        // Set the bean via reflection (final field)
        var beanField = TccDefinition.class.getDeclaredField("bean");
        beanField.setAccessible(true);
        beanField.set(tcc, testBean);

        TccEngine engine = createEngine();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();

                    // Verify the callback received a non-null TccResult
                    TccResult captured = ResultCapturingBean.capturedResult.get();
                    assertThat(captured).isNotNull();
                    assertThat(captured.tccName()).isEqualTo("ResultInjectionTcc");
                    assertThat(captured.isConfirmed()).isTrue();
                    assertThat(captured.participants()).containsKey("debit");

                    // Verify the context was also injected
                    assertThat(ResultCapturingBean.capturedCtx.get()).isNotNull();
                })
                .verifyComplete();
    }

    // --- Test bean with only ExecutionContext (no TccResult) still works ---

    @SuppressWarnings("unused")
    public static class CtxOnlyBean {
        static final AtomicReference<ExecutionContext> capturedCtx = new AtomicReference<>();

        @OnTccComplete
        public void onComplete(ExecutionContext ctx) {
            capturedCtx.set(ctx);
        }
    }

    @Test
    void onTccComplete_withoutTccResultParam_stillWorks() throws Exception {
        CtxOnlyBean.capturedCtx.set(null);

        var testBean = new CtxOnlyBean();

        TccDefinition tcc = TccBuilder.tcc("CtxOnlyTcc")
                .participant("p1")
                    .tryHandler((input, ctx) -> Mono.just("tried"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        tcc.onTccCompleteMethods = List.of(
                CtxOnlyBean.class.getMethod("onComplete", ExecutionContext.class));

        var beanField = TccDefinition.class.getDeclaredField("bean");
        beanField.setAccessible(true);
        beanField.set(tcc, testBean);

        TccEngine engine = createEngine();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();
                    assertThat(CtxOnlyBean.capturedCtx.get()).isNotNull();
                })
                .verifyComplete();
    }

    // --- Test bean with TccResult only (no ExecutionContext) ---

    @SuppressWarnings("unused")
    public static class ResultOnlyBean {
        static final AtomicReference<TccResult> capturedResult = new AtomicReference<>();

        @OnTccComplete
        public void onComplete(TccResult result) {
            capturedResult.set(result);
        }
    }

    @Test
    void onTccComplete_withTccResultOnly_receivesResult() throws Exception {
        ResultOnlyBean.capturedResult.set(null);

        var testBean = new ResultOnlyBean();

        TccDefinition tcc = TccBuilder.tcc("ResultOnlyTcc")
                .participant("p1")
                    .tryHandler((input, ctx) -> Mono.just("tried"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        tcc.onTccCompleteMethods = List.of(
                ResultOnlyBean.class.getMethod("onComplete", TccResult.class));

        var beanField = TccDefinition.class.getDeclaredField("bean");
        beanField.setAccessible(true);
        beanField.set(tcc, testBean);

        TccEngine engine = createEngine();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();
                    TccResult captured = ResultOnlyBean.capturedResult.get();
                    assertThat(captured).isNotNull();
                    assertThat(captured.tccName()).isEqualTo("ResultOnlyTcc");
                })
                .verifyComplete();
    }
}
