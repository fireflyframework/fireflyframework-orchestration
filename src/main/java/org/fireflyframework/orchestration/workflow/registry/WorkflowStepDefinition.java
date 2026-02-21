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

import java.lang.reflect.Method;
import java.util.List;

public record WorkflowStepDefinition(
        String stepId,
        String name,
        String description,
        List<String> dependsOn,
        int order,
        String outputEventType,
        long timeoutMs,
        RetryPolicy retryPolicy,
        String condition,
        boolean async,
        boolean compensatable,
        String compensationMethod,
        Object bean,
        Method method,
        String waitForSignal,
        long signalTimeoutMs,
        long waitForTimerDelayMs,
        String waitForTimerId
) {
    public WorkflowStepDefinition(String stepId, String name, String description,
                                   List<String> dependsOn, int order,
                                   String outputEventType, long timeoutMs,
                                   RetryPolicy retryPolicy, String condition, boolean async,
                                   boolean compensatable, String compensationMethod,
                                   Object bean, Method method) {
        this(stepId, name, description, dependsOn, order,
                outputEventType, timeoutMs, retryPolicy, condition, async, compensatable,
                compensationMethod, bean, method, null, 0, 0, null);
    }

    public boolean hasDependencies() {
        return dependsOn != null && !dependsOn.isEmpty();
    }

    public boolean isRootStep() {
        return !hasDependencies();
    }
}
