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

import org.fireflyframework.orchestration.core.exception.DuplicateDefinitionException;
import org.fireflyframework.orchestration.core.exception.ExecutionNotFoundException;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.StepTriggerMode;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.workflow.annotation.*;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowRegistryTest {

    @Test
    void annotationScanning_discoversWorkflowBeanWithSteps() {
        var appCtx = mock(ApplicationContext.class);
        var bean = new SampleWorkflow();
        when(appCtx.getBeansWithAnnotation(Workflow.class))
                .thenReturn(Map.of("sampleWorkflow", bean));

        var registry = new WorkflowRegistry(appCtx);

        assertThat(registry.hasWorkflow("order-processing")).isTrue();
        var def = registry.getWorkflow("order-processing");
        assertThat(def.name()).isEqualTo("Order Processing");
        assertThat(def.version()).isEqualTo("2.0");
        assertThat(def.triggerMode()).isEqualTo(TriggerMode.SYNC);
        assertThat(def.steps()).hasSize(2);
        assertThat(def.workflowBean()).isSameAs(bean);

        // Verify step details
        var validate = def.findStep("validate").orElseThrow();
        assertThat(validate.name()).isEqualTo("Validate Order");
        assertThat(validate.dependsOn()).isEmpty();

        var process = def.findStep("process").orElseThrow();
        assertThat(process.dependsOn()).containsExactly("validate");
    }

    @Test
    void annotationScanning_discoversLifecycleCallbacks() {
        var appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(Workflow.class))
                .thenReturn(Map.of("sampleWorkflow", new SampleWorkflow()));

        var registry = new WorkflowRegistry(appCtx);
        var def = registry.getWorkflow("order-processing");

        assertThat(def.onStepCompleteMethod()).isNotNull();
        assertThat(def.onStepCompleteMethod().getName()).isEqualTo("onStepDone");
        assertThat(def.onWorkflowCompleteMethod()).isNotNull();
        assertThat(def.onWorkflowCompleteMethod().getName()).isEqualTo("onComplete");
        assertThat(def.onWorkflowErrorMethod()).isNotNull();
        assertThat(def.onWorkflowErrorMethod().getName()).isEqualTo("onError");
    }

    @Test
    void annotationScanning_workflowIdDefaultsToClassName() {
        var appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(Workflow.class))
                .thenReturn(Map.of("minimalWf", new MinimalWorkflow()));

        var registry = new WorkflowRegistry(appCtx);
        assertThat(registry.hasWorkflow("MinimalWorkflow")).isTrue();
    }

    @Test
    void annotationScanning_skipsWorkflowWithNoSteps() {
        var appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(Workflow.class))
                .thenReturn(Map.of("empty", new EmptyWorkflow()));

        var registry = new WorkflowRegistry(appCtx);
        assertThat(registry.getAll()).isEmpty();
    }

    @Test
    void manualRegister_stillWorks() {
        var registry = new WorkflowRegistry();
        var step = new WorkflowStepDefinition(
                "s1", "Step 1", "", List.of(), 1,
                StepTriggerMode.BOTH, "", "", 0,
                RetryPolicy.NO_RETRY, "", false, false, "", null, null);
        var def = new WorkflowDefinition(
                "test-wf", "Test", "", "1.0", List.of(step),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT,
                null, null, null, null);

        registry.register(def);
        assertThat(registry.get("test-wf")).isPresent();
    }

    @Test
    void manualRegister_duplicateThrows() {
        var registry = new WorkflowRegistry();
        var step = new WorkflowStepDefinition(
                "s1", "Step 1", "", List.of(), 1,
                StepTriggerMode.BOTH, "", "", 0,
                RetryPolicy.NO_RETRY, "", false, false, "", null, null);
        var def = new WorkflowDefinition(
                "dup-wf", "Dup", "", "1.0", List.of(step),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT,
                null, null, null, null);

        registry.register(def);
        assertThatThrownBy(() -> registry.register(def))
                .isInstanceOf(DuplicateDefinitionException.class);
    }

    @Test
    void getWorkflow_throwsForUnknown() {
        var registry = new WorkflowRegistry();
        assertThatThrownBy(() -> registry.getWorkflow("nonexistent"))
                .isInstanceOf(ExecutionNotFoundException.class);
    }

    // ── Test workflow beans ────────────────────────────────────────

    @Workflow(id = "order-processing", name = "Order Processing", version = "2.0")
    static class SampleWorkflow {

        @WorkflowStep(id = "validate", name = "Validate Order")
        public Mono<String> validateOrder(Object input) {
            return Mono.just("validated");
        }

        @WorkflowStep(id = "process", name = "Process Order", dependsOn = "validate")
        public Mono<String> processOrder(Object input) {
            return Mono.just("processed");
        }

        @OnStepComplete
        public void onStepDone(String stepId, Object result) {}

        @OnWorkflowComplete
        public void onComplete(Object result) {}

        @OnWorkflowError
        public void onError(Throwable error) {}
    }

    @Workflow
    static class MinimalWorkflow {
        @WorkflowStep(id = "only-step")
        public Mono<String> doWork(Object input) {
            return Mono.just("done");
        }
    }

    @Workflow(id = "empty")
    static class EmptyWorkflow {
        // No steps — should be skipped
    }
}
