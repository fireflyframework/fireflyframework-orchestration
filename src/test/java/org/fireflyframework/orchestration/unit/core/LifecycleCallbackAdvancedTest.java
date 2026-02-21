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
import org.fireflyframework.orchestration.saga.annotation.OnSagaComplete;
import org.fireflyframework.orchestration.saga.compensation.SagaCompensator;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaExecutionOrchestrator;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.saga.registry.SagaStepDefinition;
import org.fireflyframework.orchestration.tcc.annotation.OnTccComplete;
import org.fireflyframework.orchestration.tcc.builder.TccBuilder;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccExecutionOrchestrator;
import org.fireflyframework.orchestration.tcc.engine.TccInputs;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleCallbackAdvancedTest {

    // ── Saga Async ────────────────────────────────────────────

    @SuppressWarnings("unused")
    public static class AsyncSagaBean {
        static final CountDownLatch latch = new CountDownLatch(1);
        static final AtomicReference<String> threadName = new AtomicReference<>();

        @OnSagaComplete(async = true)
        public void asyncComplete() {
            threadName.set(Thread.currentThread().getName());
            latch.countDown();
        }
    }

    @Test
    void sagaAsyncCallback_invokesOnDifferentThread() throws Exception {
        AsyncSagaBean.latch.countDown(); // reset
        var latch = new CountDownLatch(1);
        var threadName = new AtomicReference<String>();

        @SuppressWarnings("unused")
        class AsyncBean {
            @OnSagaComplete(async = true)
            public void asyncComplete() {
                threadName.set(Thread.currentThread().getName());
                latch.countDown();
            }
        }

        var testBean = new AsyncBean();
        SagaDefinition saga = new SagaDefinition("AsyncSagaTest", testBean, testBean, 0);
        SagaStepDefinition stepDef = new SagaStepDefinition(
                "s1", "", List.of(), 0, null, null, "", false, 0.5, false, null);
        stepDef.handler = (StepHandler<Object, String>) (input, ctx) -> Mono.just("done");
        saga.steps.put("s1", stepDef);

        saga.onSagaCompleteMethods = List.of(
                AsyncBean.class.getMethod("asyncComplete"));

        SagaEngine engine = createSagaEngine();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> assertThat(result.isSuccess()).isTrue())
                .verifyComplete();

        // Wait for async callback
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).contains("boundedElastic");
    }

    // ── Saga Priority ────────────────────────────────────────────

    @Test
    void sagaPriorityOrdering_highPriorityFirst() throws Exception {
        var executionOrder = Collections.synchronizedList(new ArrayList<String>());

        @SuppressWarnings("unused")
        class PrioritySagaBean {
            @OnSagaComplete(priority = 5)
            public void lowPriority() { executionOrder.add("low"); }

            @OnSagaComplete(priority = 10)
            public void highPriority() { executionOrder.add("high"); }
        }

        var testBean = new PrioritySagaBean();
        SagaDefinition saga = new SagaDefinition("PrioritySagaTest", testBean, testBean, 0);
        SagaStepDefinition stepDef = new SagaStepDefinition(
                "s1", "", List.of(), 0, null, null, "", false, 0.5, false, null);
        stepDef.handler = (StepHandler<Object, String>) (input, ctx) -> Mono.just("done");
        saga.steps.put("s1", stepDef);

        // Methods are sorted by SagaRegistry.findAnnotatedMethods() (highest priority first).
        // Here we replicate the sorted order: high(10) before low(5).
        saga.onSagaCompleteMethods = List.of(
                PrioritySagaBean.class.getMethod("highPriority"),
                PrioritySagaBean.class.getMethod("lowPriority"));

        SagaEngine engine = createSagaEngine();

        StepVerifier.create(engine.execute(saga, StepInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(executionOrder).containsExactly("high", "low");
                })
                .verifyComplete();
    }

    // ── TCC Async ────────────────────────────────────────────

    @Test
    void tccAsyncCallback_invokesOnDifferentThread() throws Exception {
        var latch = new CountDownLatch(1);
        var threadName = new AtomicReference<String>();

        @SuppressWarnings("unused")
        class AsyncTccBean {
            @OnTccComplete(async = true)
            public void asyncComplete() {
                threadName.set(Thread.currentThread().getName());
                latch.countDown();
            }
        }

        var testBean = new AsyncTccBean();

        TccDefinition tcc = TccBuilder.tcc("AsyncTccTest")
                .participant("p1")
                    .tryHandler((input, ctx) -> Mono.just("reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        tcc.onTccCompleteMethods = List.of(
                AsyncTccBean.class.getMethod("asyncComplete"));
        var beanField = TccDefinition.class.getDeclaredField("bean");
        beanField.setAccessible(true);
        beanField.set(tcc, testBean);

        TccEngine engine = createTccEngine();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> assertThat(result.isConfirmed()).isTrue())
                .verifyComplete();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).contains("boundedElastic");
    }

    // ── TCC Priority ────────────────────────────────────────────

    @Test
    void tccPriorityOrdering_highPriorityFirst() throws Exception {
        var executionOrder = Collections.synchronizedList(new ArrayList<String>());

        @SuppressWarnings("unused")
        class PriorityTccBean {
            @OnTccComplete(priority = 5)
            public void lowPriority() { executionOrder.add("low"); }

            @OnTccComplete(priority = 10)
            public void highPriority() { executionOrder.add("high"); }
        }

        var testBean = new PriorityTccBean();

        TccDefinition tcc = TccBuilder.tcc("PriorityTccTest")
                .participant("p1")
                    .tryHandler((input, ctx) -> Mono.just("reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        // Priority-sorted order: high(10) before low(5)
        tcc.onTccCompleteMethods = List.of(
                PriorityTccBean.class.getMethod("highPriority"),
                PriorityTccBean.class.getMethod("lowPriority"));
        var beanField = TccDefinition.class.getDeclaredField("bean");
        beanField.setAccessible(true);
        beanField.set(tcc, testBean);

        TccEngine engine = createTccEngine();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();
                    assertThat(executionOrder).containsExactly("high", "low");
                })
                .verifyComplete();
    }

    // ── Engine factories ────────────────────────────────────────────

    private SagaEngine createSagaEngine() {
        var events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noOpPublisher = new NoOpEventPublisher();
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        return new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher);
    }

    private TccEngine createTccEngine() {
        var events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noOpPublisher = new NoOpEventPublisher();
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        return new TccEngine(null, events, orchestrator, null, null, noOpPublisher);
    }
}
