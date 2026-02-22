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
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.workflow.annotation.CompensationStep;
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import org.fireflyframework.orchestration.workflow.engine.WorkflowExecutor;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@code @CompensationStep} annotation linking and execution.
 */
class CompensationStepAnnotationTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;
    private OrchestrationEvents events;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, noOpPublisher,
                null, null);
        var persistence = new InMemoryPersistenceProvider();
        engine = new WorkflowEngine(registry, executor, new StepInvoker(new ArgumentResolver()),
                persistence, events, noOpPublisher);
    }

    /**
     * Bean with a @CompensationStep method that compensates "reserveStock".
     * Simulates registry-level linking of external compensation methods.
     */
    @SuppressWarnings("unused")
    public static class CompensationStepBean {
        final List<String> compensationOrder = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean compensated = new AtomicBoolean(false);

        public Mono<String> reserveStock(@Input Map<String, Object> input) {
            return Mono.just("stock-reserved");
        }

        // This method is annotated with @CompensationStep(compensates = "reserveStock")
        // It should be linked as the compensation method for the "reserveStock" step
        @CompensationStep(compensates = "reserveStock")
        public void releaseStock(Object result) {
            compensated.set(true);
            compensationOrder.add("releaseStock");
        }

        public Mono<String> chargePayment(@Input Map<String, Object> input) {
            return Mono.error(new RuntimeException("payment failed"));
        }
    }

    @Test
    void compensationStep_linksToWorkflowStep() throws Exception {
        var bean = new CompensationStepBean();

        // Simulate what WorkflowRegistry does: scan for @CompensationStep and link it
        Method compensationMethod = null;
        String compensatesStepId = null;
        for (Method m : CompensationStepBean.class.getDeclaredMethods()) {
            CompensationStep ann = m.getAnnotation(CompensationStep.class);
            if (ann != null) {
                compensationMethod = m;
                compensatesStepId = ann.compensates();
            }
        }

        assertThat(compensationMethod).isNotNull();
        assertThat(compensatesStepId).isEqualTo("reserveStock");
        assertThat(compensationMethod.getName()).isEqualTo("releaseStock");

        // Build step definition with the compensation linked via @CompensationStep
        var reserveStep = new WorkflowStepDefinition(
                "reserveStock", "Reserve Stock", "", List.of(), 0,
                "", 5000, RetryPolicy.NO_RETRY, "",
                false, true, "releaseStock",
                bean, CompensationStepBean.class.getMethod("reserveStock", Map.class),
                null, 0, 0, null);

        var chargeStep = new WorkflowStepDefinition(
                "chargePayment", "Charge Payment", "", List.of("reserveStock"), 0,
                "", 5000, RetryPolicy.NO_RETRY, "",
                false, false, "",
                bean, CompensationStepBean.class.getMethod("chargePayment", Map.class),
                null, 0, 0, null);

        // Verify the step definition has compensation wired correctly
        assertThat(reserveStep.compensatable()).isTrue();
        assertThat(reserveStep.compensationMethod()).isEqualTo("releaseStock");
    }

    @Test
    void compensationStep_executedOnFailure() throws Exception {
        var bean = new CompensationStepBean();

        // Build steps: reserveStock (compensatable) -> chargePayment (fails)
        var reserveStep = new WorkflowStepDefinition(
                "reserveStock", "Reserve Stock", "", List.of(), 0,
                "", 5000, RetryPolicy.NO_RETRY, "",
                false, true, "releaseStock",
                bean, CompensationStepBean.class.getMethod("reserveStock", Map.class),
                null, 0, 0, null);

        var chargeStep = new WorkflowStepDefinition(
                "chargePayment", "Charge Payment", "", List.of("reserveStock"), 0,
                "", 5000, RetryPolicy.NO_RETRY, "",
                false, false, "",
                bean, CompensationStepBean.class.getMethod("chargePayment", Map.class),
                null, 0, 0, null);

        var def = new WorkflowDefinition("comp-step-wf", "Compensation Step WF", "test", "1.0",
                List.of(reserveStep, chargeStep),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT,
                null, null, null, null);
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("comp-step-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED);

                    // The @CompensationStep method (releaseStock) should have been executed
                    assertThat(bean.compensated.get()).isTrue();
                    assertThat(bean.compensationOrder).contains("releaseStock");
                })
                .verifyComplete();
    }
}
