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
import org.fireflyframework.orchestration.tcc.annotation.OnTccError;
import org.fireflyframework.orchestration.tcc.builder.TccBuilder;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccExecutionOrchestrator;
import org.fireflyframework.orchestration.tcc.engine.TccInputs;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TccLifecycleCallbackTest {

    private TccEngine createEngine() {
        var events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noOpPublisher = new NoOpEventPublisher();
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        return new TccEngine(null, events, orchestrator, null, null, noOpPublisher);
    }

    // Test bean with lifecycle callbacks
    @SuppressWarnings("unused")
    public static class TestTccBean {
        static final AtomicBoolean completeCalled = new AtomicBoolean(false);
        static final AtomicBoolean errorCalled = new AtomicBoolean(false);
        static final AtomicReference<Throwable> capturedError = new AtomicReference<>();

        @OnTccComplete
        public void onComplete(ExecutionContext ctx) {
            completeCalled.set(true);
        }

        @OnTccError
        public void onError(Throwable error, ExecutionContext ctx) {
            errorCalled.set(true);
            capturedError.set(error);
        }
    }

    @Test
    void onTccCompleteInvokedOnConfirmed() throws Exception {
        TestTccBean.completeCalled.set(false);
        TestTccBean.errorCalled.set(false);

        var testBean = new TestTccBean();

        // Build a TCC that will succeed (all try + confirm methods work)
        TccDefinition tcc = TccBuilder.tcc("CompleteCallbackTcc")
                .participant("debit")
                    .tryHandler((input, ctx) -> Mono.just("debit-reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("debit-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("debit-cancelled"))
                    .add()
                .participant("credit")
                    .tryHandler((input, ctx) -> Mono.just("credit-reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("credit-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("credit-cancelled"))
                    .add()
                .build();

        // Set the bean and lifecycle callbacks on the definition
        tcc.onTccCompleteMethods = List.of(
                TestTccBean.class.getMethod("onComplete", ExecutionContext.class));
        // Set the bean field via reflection since it's final and null from builder
        var beanField = TccDefinition.class.getDeclaredField("bean");
        beanField.setAccessible(true);
        beanField.set(tcc, testBean);

        TccEngine engine = createEngine();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();
                    assertThat(TestTccBean.completeCalled.get()).isTrue();
                    assertThat(TestTccBean.errorCalled.get()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void onTccErrorInvokedOnFailed() throws Exception {
        TestTccBean.completeCalled.set(false);
        TestTccBean.errorCalled.set(false);
        TestTccBean.capturedError.set(null);

        var testBean = new TestTccBean();

        // Build a TCC that will FAIL: try on p2 fails, then cancel on p1 also fails
        // This causes FAILED status (not CANCELED), which triggers @OnTccError
        TccDefinition tcc = TccBuilder.tcc("ErrorCallbackTcc")
                .participant("p1")
                    .order(1)
                    .tryHandler((input, ctx) -> Mono.just("p1-reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("p1-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.error(new RuntimeException("cancel also failed")))
                    .add()
                .participant("p2")
                    .order(2)
                    .tryHandler((input, ctx) -> Mono.error(new RuntimeException("try failed")))
                    .confirmHandler((input, ctx) -> Mono.just("p2-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("p2-cancelled"))
                    .add()
                .build();

        tcc.onTccErrorMethods = List.of(
                TestTccBean.class.getMethod("onError", Throwable.class, ExecutionContext.class));
        var beanField = TccDefinition.class.getDeclaredField("bean");
        beanField.setAccessible(true);
        beanField.set(tcc, testBean);

        TccEngine engine = createEngine();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isFailed()).isTrue();
                    assertThat(TestTccBean.errorCalled.get()).isTrue();
                    assertThat(TestTccBean.capturedError.get()).isNotNull();
                    assertThat(TestTccBean.completeCalled.get()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void onTccErrorNotInvokedOnCanceled() throws Exception {
        TestTccBean.completeCalled.set(false);
        TestTccBean.errorCalled.set(false);

        var testBean = new TestTccBean();

        // Build a TCC where try fails but cancel succeeds -> CANCELED status
        // @OnTccError should NOT fire on CANCELED (that's controlled rollback)
        TccDefinition tcc = TccBuilder.tcc("CanceledNoCallbackTcc")
                .participant("p1")
                    .order(1)
                    .tryHandler((input, ctx) -> Mono.just("p1-reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("p1-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("p1-cancelled"))
                    .add()
                .participant("p2")
                    .order(2)
                    .tryHandler((input, ctx) -> Mono.error(new RuntimeException("try failed")))
                    .confirmHandler((input, ctx) -> Mono.just("p2-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("p2-cancelled"))
                    .add()
                .build();

        tcc.onTccCompleteMethods = List.of(
                TestTccBean.class.getMethod("onComplete", ExecutionContext.class));
        tcc.onTccErrorMethods = List.of(
                TestTccBean.class.getMethod("onError", Throwable.class, ExecutionContext.class));
        var beanField = TccDefinition.class.getDeclaredField("bean");
        beanField.setAccessible(true);
        beanField.set(tcc, testBean);

        TccEngine engine = createEngine();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isCanceled()).isTrue();
                    // Neither callback should fire: CANCELED is controlled rollback
                    assertThat(TestTccBean.completeCalled.get()).isFalse();
                    assertThat(TestTccBean.errorCalled.get()).isFalse();
                })
                .verifyComplete();
    }
}
