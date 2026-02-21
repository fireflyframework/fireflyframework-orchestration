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
import org.fireflyframework.orchestration.saga.annotation.OnSagaError;
import org.fireflyframework.orchestration.saga.compensation.SagaCompensator;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaExecutionOrchestrator;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.saga.registry.SagaStepDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SagaLifecycleCallbackTest {

    private SagaEngine createEngine() {
        var events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noOpPublisher = new NoOpEventPublisher();
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        return new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher);
    }

    // Test bean with lifecycle callbacks
    @SuppressWarnings("unused")
    public static class TestSagaBean {
        static final AtomicBoolean completeCalled = new AtomicBoolean(false);
        static final AtomicBoolean errorCalled = new AtomicBoolean(false);
        static final AtomicReference<Throwable> capturedError = new AtomicReference<>();

        @OnSagaComplete
        public void onComplete(ExecutionContext ctx) {
            completeCalled.set(true);
        }

        @OnSagaError
        public void onError(Throwable error, ExecutionContext ctx) {
            errorCalled.set(true);
            capturedError.set(error);
        }
    }

    @Test
    void onSagaCompleteInvokedOnSuccess() throws Exception {
        TestSagaBean.completeCalled.set(false);
        TestSagaBean.errorCalled.set(false);

        var testBean = new TestSagaBean();

        // Create SagaDefinition with the test bean so lifecycle callbacks can be invoked
        SagaDefinition saga = new SagaDefinition("CompleteCallbackTest", testBean, testBean, 0);
        SagaStepDefinition stepDef = new SagaStepDefinition(
                "s1", "", List.of(), 0, null, null, "", false, 0.5, false, null);
        stepDef.handler = (StepHandler<Object, String>) (input, ctx) -> Mono.just("done");
        saga.steps.put("s1", stepDef);

        // Set lifecycle callbacks on the definition
        saga.onSagaCompleteMethods = List.of(
                TestSagaBean.class.getMethod("onComplete", ExecutionContext.class));

        SagaEngine engine = createEngine();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(TestSagaBean.completeCalled.get()).isTrue();
                    assertThat(TestSagaBean.errorCalled.get()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void onSagaErrorInvokedOnFailure() throws Exception {
        TestSagaBean.completeCalled.set(false);
        TestSagaBean.errorCalled.set(false);
        TestSagaBean.capturedError.set(null);

        var testBean = new TestSagaBean();

        SagaDefinition saga = new SagaDefinition("ErrorCallbackTest", testBean, testBean, 0);
        SagaStepDefinition stepDef = new SagaStepDefinition(
                "s1", "", List.of(), 0, null, null, "", false, 0.5, false, null);
        stepDef.handler = (StepHandler<Object, String>) (input, ctx) ->
                Mono.error(new RuntimeException("saga failed"));
        saga.steps.put("s1", stepDef);

        saga.onSagaErrorMethods = List.of(
                TestSagaBean.class.getMethod("onError", Throwable.class, ExecutionContext.class));

        SagaEngine engine = createEngine();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    assertThat(TestSagaBean.errorCalled.get()).isTrue();
                    assertThat(TestSagaBean.capturedError.get()).isNotNull();
                    assertThat(TestSagaBean.capturedError.get().getMessage()).contains("saga failed");
                    assertThat(TestSagaBean.completeCalled.get()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void errorCallbackFiltersByErrorType() throws Exception {
        AtomicBoolean ioHandlerCalled = new AtomicBoolean(false);

        @SuppressWarnings("unused")
        class FilteredBean {
            @OnSagaError(errorTypes = java.io.IOException.class)
            public void onIoError(Throwable error) {
                ioHandlerCalled.set(true);
            }
        }

        var filterBean = new FilteredBean();

        SagaDefinition saga = new SagaDefinition("FilteredErrorTest", filterBean, filterBean, 0);
        SagaStepDefinition stepDef = new SagaStepDefinition(
                "s1", "", List.of(), 0, null, null, "", false, 0.5, false, null);
        stepDef.handler = (StepHandler<Object, String>) (input, ctx) ->
                Mono.error(new RuntimeException("not an IOException"));
        saga.steps.put("s1", stepDef);

        saga.onSagaErrorMethods = List.of(
                FilteredBean.class.getMethod("onIoError", Throwable.class));

        SagaEngine engine = createEngine();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    // IOException handler should NOT be called for RuntimeException
                    assertThat(ioHandlerCalled.get()).isFalse();
                })
                .verifyComplete();
    }
}
