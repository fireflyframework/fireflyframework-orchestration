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
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepHandler;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.saga.annotation.OnSagaError;
import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
import org.fireflyframework.orchestration.saga.compensation.SagaCompensator;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaExecutionOrchestrator;
import org.fireflyframework.orchestration.saga.engine.SagaResult;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.saga.registry.SagaStepDefinition;
import org.fireflyframework.orchestration.tcc.annotation.OnTccError;
import org.fireflyframework.orchestration.tcc.builder.TccBuilder;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccExecutionOrchestrator;
import org.fireflyframework.orchestration.tcc.engine.TccInputs;
import org.fireflyframework.orchestration.tcc.engine.TccResult;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code suppressError} on {@link OnSagaError} and {@link OnTccError}
 * is honoured by SagaEngine and TccEngine, matching the WorkflowEngine pattern.
 */
class SuppressErrorAlignmentTest {

    // ---- Saga helpers ----

    private SagaEngine createSagaEngine() {
        var events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noOpPublisher = new NoOpEventPublisher();
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        return new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher);
    }

    // ---- TCC helpers ----

    private TccEngine createTccEngine() {
        var events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noOpPublisher = new NoOpEventPublisher();
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        return new TccEngine(null, events, orchestrator, null, null, noOpPublisher);
    }

    // ---- Test beans ----

    @SuppressWarnings("unused")
    public static class SagaSuppressBean {
        @OnSagaError(suppressError = true)
        public void handleError(Throwable error, ExecutionContext ctx) {
            // no-op — the suppressError flag is what matters
        }
    }

    @SuppressWarnings("unused")
    public static class SagaSuppressFilteredBean {
        @OnSagaError(errorTypes = IOException.class, suppressError = true)
        public void handleIoError(Throwable error, ExecutionContext ctx) {
            // should only suppress IOException
        }
    }

    @SuppressWarnings("unused")
    public static class TccSuppressBean {
        @OnTccError(suppressError = true)
        public void handleError(Throwable error, ExecutionContext ctx) {
            // no-op — the suppressError flag is what matters
        }
    }

    @SuppressWarnings("unused")
    public static class TccSuppressFilteredBean {
        @OnTccError(errorTypes = IOException.class, suppressError = true)
        public void handleIoError(Throwable error, ExecutionContext ctx) {
            // should only suppress IOException
        }
    }

    // ---- Saga Tests ----

    @Test
    void sagaSuppressErrorMatchingHandler() throws Exception {
        var bean = new SagaSuppressBean();
        SagaDefinition saga = new SagaDefinition("SuppressSaga", bean, bean, 0);

        SagaStepDefinition stepDef = new SagaStepDefinition(
                "s1", "", List.of(), 0, null, null, "", false, 0.5, false, null);
        stepDef.handler = (StepHandler<Object, String>) (input, ctx) ->
                Mono.error(new RuntimeException("saga failure"));
        saga.steps.put("s1", stepDef);

        saga.onSagaErrorMethods = List.of(
                SagaSuppressBean.class.getMethod("handleError", Throwable.class, ExecutionContext.class));

        SagaEngine engine = createSagaEngine();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    // suppressError=true with no errorTypes filter -> should suppress
                    assertThat(result.isSuccess()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void sagaSuppressErrorNonMatchingHandler() throws Exception {
        var bean = new SagaSuppressFilteredBean();
        SagaDefinition saga = new SagaDefinition("NonMatchSuppressSaga", bean, bean, 0);

        SagaStepDefinition stepDef = new SagaStepDefinition(
                "s1", "", List.of(), 0, null, null, "", false, 0.5, false, null);
        stepDef.handler = (StepHandler<Object, String>) (input, ctx) ->
                Mono.error(new RuntimeException("not an IOException"));
        saga.steps.put("s1", stepDef);

        saga.onSagaErrorMethods = List.of(
                SagaSuppressFilteredBean.class.getMethod("handleIoError", Throwable.class, ExecutionContext.class));

        SagaEngine engine = createSagaEngine();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    // IOException filter does not match RuntimeException -> should NOT suppress
                    assertThat(result.isSuccess()).isFalse();
                })
                .verifyComplete();
    }

    // ---- TCC Tests ----

    @Test
    void tccSuppressErrorMatchingHandler() throws Exception {
        var bean = new TccSuppressBean();

        // Build a TCC that will FAIL: try on p2 fails, then cancel on p1 also fails
        TccDefinition tcc = TccBuilder.tcc("SuppressTcc")
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
                TccSuppressBean.class.getMethod("handleError", Throwable.class, ExecutionContext.class));
        var beanField = TccDefinition.class.getDeclaredField("bean");
        beanField.setAccessible(true);
        beanField.set(tcc, bean);

        TccEngine engine = createTccEngine();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    // suppressError=true with no errorTypes filter -> should suppress, returning CONFIRMED
                    assertThat(result.isConfirmed()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void tccSuppressErrorNonMatchingHandler() throws Exception {
        var bean = new TccSuppressFilteredBean();

        // Build a TCC that will FAIL: try on p2 fails, then cancel on p1 also fails
        TccDefinition tcc = TccBuilder.tcc("NonMatchSuppressTcc")
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
                TccSuppressFilteredBean.class.getMethod("handleIoError", Throwable.class, ExecutionContext.class));
        var beanField = TccDefinition.class.getDeclaredField("bean");
        beanField.setAccessible(true);
        beanField.set(tcc, bean);

        TccEngine engine = createTccEngine();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    // IOException filter does not match RuntimeException -> should NOT suppress
                    assertThat(result.isFailed()).isTrue();
                })
                .verifyComplete();
    }
}
