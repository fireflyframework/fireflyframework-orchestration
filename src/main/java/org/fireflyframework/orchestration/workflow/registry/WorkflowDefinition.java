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

package org.fireflyframework.orchestration.workflow.registry;

import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.TriggerMode;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

public record WorkflowDefinition(
        String workflowId,
        String name,
        String description,
        String version,
        List<WorkflowStepDefinition> steps,
        TriggerMode triggerMode,
        String triggerEventType,
        long timeoutMs,
        RetryPolicy retryPolicy,
        Object workflowBean,
        List<Method> onStepCompleteMethods,
        List<Method> onWorkflowCompleteMethods,
        List<Method> onWorkflowErrorMethods,
        boolean publishEvents,
        int layerConcurrency
) {
    /**
     * Backward-compatible constructor that defaults {@code publishEvents} to {@code false}.
     */
    public WorkflowDefinition(String workflowId, String name, String description, String version,
                               List<WorkflowStepDefinition> steps, TriggerMode triggerMode,
                               String triggerEventType, long timeoutMs, RetryPolicy retryPolicy,
                               Object workflowBean, List<Method> onStepCompleteMethods,
                               List<Method> onWorkflowCompleteMethods, List<Method> onWorkflowErrorMethods) {
        this(workflowId, name, description, version, steps, triggerMode, triggerEventType, timeoutMs,
                retryPolicy, workflowBean, onStepCompleteMethods, onWorkflowCompleteMethods,
                onWorkflowErrorMethods, false, 0);
    }
    public Optional<WorkflowStepDefinition> findStep(String stepId) {
        return steps.stream().filter(s -> s.stepId().equals(stepId)).findFirst();
    }

    /**
     * Backward-compatible convenience accessor returning the first {@code @OnStepComplete} method, or {@code null}.
     */
    public Method onStepCompleteMethod() {
        return onStepCompleteMethods != null && !onStepCompleteMethods.isEmpty()
                ? onStepCompleteMethods.get(0) : null;
    }

    /**
     * Backward-compatible convenience accessor returning the first {@code @OnWorkflowComplete} method, or {@code null}.
     */
    public Method onWorkflowCompleteMethod() {
        return onWorkflowCompleteMethods != null && !onWorkflowCompleteMethods.isEmpty()
                ? onWorkflowCompleteMethods.get(0) : null;
    }

    /**
     * Backward-compatible convenience accessor returning the first {@code @OnWorkflowError} method, or {@code null}.
     */
    public Method onWorkflowErrorMethod() {
        return onWorkflowErrorMethods != null && !onWorkflowErrorMethods.isEmpty()
                ? onWorkflowErrorMethods.get(0) : null;
    }
}
