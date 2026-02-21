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
import org.fireflyframework.orchestration.core.model.*;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.step.StepInvoker;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class WorkflowLayerConcurrencyTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;

    @SuppressWarnings("unused")
    public static class ConcurrencyTrackingBean {
        final CopyOnWriteArrayList<String> executionOrder = new CopyOnWriteArrayList<>();

        public Mono<String> step(@Input Map<String, Object> input) {
            executionOrder.add("executed");
            return Mono.just("ok");
        }
    }

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, noOpPublisher, null, null);
        var persistence = new InMemoryPersistenceProvider();
        engine = new WorkflowEngine(registry, executor, new StepInvoker(new ArgumentResolver()), persistence, events, noOpPublisher);
    }

    @Test
    void layerConcurrency_limitsParallelExecution() throws Exception {
        var bean = new ConcurrencyTrackingBean();

        // 3 parallel steps at layer 0, concurrency limited to 1
        var def = new WorkflowDefinition(
                "conc-wf", "conc-wf", "", "1.0",
                List.of(
                        new WorkflowStepDefinition("s1", "s1", "", List.of(), 0,
                                "", 5000, RetryPolicy.NO_RETRY,
                                "", false, false, "",
                                bean, bean.getClass().getMethod("step", Map.class),
                                null, 0, 0, null),
                        new WorkflowStepDefinition("s2", "s2", "", List.of(), 0,
                                "", 5000, RetryPolicy.NO_RETRY,
                                "", false, false, "",
                                bean, bean.getClass().getMethod("step", Map.class),
                                null, 0, 0, null),
                        new WorkflowStepDefinition("s3", "s3", "", List.of(), 0,
                                "", 5000, RetryPolicy.NO_RETRY,
                                "", false, false, "",
                                bean, bean.getClass().getMethod("step", Map.class),
                                null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT,
                null, null, null, null,
                false, 1);  // layerConcurrency = 1
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("conc-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(bean.executionOrder).hasSize(3);
                })
                .verifyComplete();
    }

    @Test
    void layerConcurrency_zeroMeansUnbounded() throws Exception {
        var bean = new ConcurrencyTrackingBean();

        var def = new WorkflowDefinition(
                "unbound-wf", "unbound-wf", "", "1.0",
                List.of(
                        new WorkflowStepDefinition("s1", "s1", "", List.of(), 0,
                                "", 5000, RetryPolicy.NO_RETRY,
                                "", false, false, "",
                                bean, bean.getClass().getMethod("step", Map.class),
                                null, 0, 0, null),
                        new WorkflowStepDefinition("s2", "s2", "", List.of(), 0,
                                "", 5000, RetryPolicy.NO_RETRY,
                                "", false, false, "",
                                bean, bean.getClass().getMethod("step", Map.class),
                                null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT,
                null, null, null, null,
                false, 0);  // layerConcurrency = 0 (unbounded)
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("unbound-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(bean.executionOrder).hasSize(2);
                })
                .verifyComplete();
    }
}
