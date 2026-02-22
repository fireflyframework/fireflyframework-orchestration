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
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.workflow.child.ChildWorkflowResult;
import org.fireflyframework.orchestration.workflow.child.ChildWorkflowService;
import org.fireflyframework.orchestration.workflow.engine.WorkflowExecutor;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@code @ChildWorkflow} annotation wiring in the WorkflowExecutor.
 */
class ChildWorkflowAnnotationTest {

    private OrchestrationEvents events;
    private NoOpEventPublisher noOpPublisher;
    private InMemoryPersistenceProvider persistence;

    @BeforeEach
    void setUp() {
        events = new OrchestrationEvents() {};
        noOpPublisher = new NoOpEventPublisher();
        persistence = new InMemoryPersistenceProvider();
    }

    @SuppressWarnings("unused")
    public static class ParentStepBean {
        public Mono<String> parentStep(@Input Map<String, Object> input) {
            return Mono.just("parent-result");
        }
    }

    @Test
    void childWorkflow_annotation_spawnsAndWaits() throws Exception {
        // Create a mock ChildWorkflowService that returns a completed child state
        ExecutionState childState = new ExecutionState(
                "child-corr-1", "child-wf", ExecutionPattern.WORKFLOW,
                ExecutionStatus.COMPLETED,
                Map.of("childStep", "child-done"),
                Map.of("childStep", StepStatus.DONE),
                Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), List.of(),
                null, Instant.now(), Instant.now(), Optional.empty());

        ChildWorkflowService mockChildService = new ChildWorkflowService(null, events) {
            @Override
            public Mono<ChildWorkflowResult> spawn(String parentCorrelationId, String childWorkflowId,
                                                     Map<String, Object> input) {
                return Mono.just(ChildWorkflowResult.from(parentCorrelationId, childState));
            }
        };

        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, noOpPublisher,
                null, null, null, mockChildService);

        var bean = new ParentStepBean();
        var stepDef = new WorkflowStepDefinition(
                "child-step", "Child Step", "", List.of(), 0,
                "", 10000, RetryPolicy.NO_RETRY, "",
                false, false, "",
                bean, ParentStepBean.class.getMethod("parentStep", Map.class),
                null, 0, 0, null,
                List.of(), List.of(), List.of(), List.of(),
                "child-wf", true, 0);

        var def = new WorkflowDefinition("parent-wf", "Parent WF", "test", "1.0",
                List.of(stepDef),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);

        ExecutionContext ctx = ExecutionContext.forWorkflow(null, "parent-wf");

        StepVerifier.create(executor.execute(def, ctx))
                .assertNext(resultCtx -> {
                    assertThat(resultCtx.getStepStatus("child-step")).isEqualTo(StepStatus.DONE);
                    assertThat(resultCtx.getStepResults()).containsKey("child-step");
                })
                .verifyComplete();
    }

    @Test
    void childWorkflow_annotation_fireAndForget() throws Exception {
        // Create a mock ChildWorkflowService that simulates a longer-running child
        ChildWorkflowService mockChildService = new ChildWorkflowService(null, events) {
            @Override
            public Mono<ChildWorkflowResult> spawn(String parentCorrelationId, String childWorkflowId,
                                                     Map<String, Object> input) {
                ExecutionState childState = new ExecutionState(
                        "child-corr-2", "child-wf", ExecutionPattern.WORKFLOW,
                        ExecutionStatus.COMPLETED,
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), List.of(),
                        null, Instant.now(), Instant.now(), Optional.empty());
                return Mono.delay(java.time.Duration.ofMillis(200))
                        .map(tick -> ChildWorkflowResult.from(parentCorrelationId, childState));
            }
        };

        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, noOpPublisher,
                null, null, null, mockChildService);

        var bean = new ParentStepBean();
        var stepDef = new WorkflowStepDefinition(
                "fire-forget-step", "Fire And Forget Step", "", List.of(), 0,
                "", 10000, RetryPolicy.NO_RETRY, "",
                false, false, "",
                bean, ParentStepBean.class.getMethod("parentStep", Map.class),
                null, 0, 0, null,
                List.of(), List.of(), List.of(), List.of(),
                "child-wf", false, 0);

        var def = new WorkflowDefinition("fire-forget-wf", "Fire Forget WF", "test", "1.0",
                List.of(stepDef),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);

        ExecutionContext ctx = ExecutionContext.forWorkflow(null, "fire-forget-wf");

        // Fire-and-forget: should complete immediately without waiting for child
        StepVerifier.create(executor.execute(def, ctx))
                .assertNext(resultCtx -> {
                    // Step should be marked DONE immediately (fire-and-forget)
                    assertThat(resultCtx.getStepStatus("fire-forget-step")).isEqualTo(StepStatus.DONE);
                })
                .verifyComplete();
    }
}
