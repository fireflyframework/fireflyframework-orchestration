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
import org.fireflyframework.orchestration.workflow.annotation.WaitForSignal;
import org.fireflyframework.orchestration.workflow.annotation.WaitForTimer;

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
        String waitForTimerId,
        List<WaitForSignal> waitForAllSignals,
        List<WaitForTimer> waitForAllTimers,
        List<WaitForSignal> waitForAnySignals,
        List<WaitForTimer> waitForAnyTimers,
        String childWorkflowId,
        boolean childWaitForCompletion,
        long childTimeoutMs
) {
    /**
     * Backward-compatible constructor (14 args) — no signal/timer/child fields.
     */
    public WorkflowStepDefinition(String stepId, String name, String description,
                                   List<String> dependsOn, int order,
                                   String outputEventType, long timeoutMs,
                                   RetryPolicy retryPolicy, String condition, boolean async,
                                   boolean compensatable, String compensationMethod,
                                   Object bean, Method method) {
        this(stepId, name, description, dependsOn, order,
                outputEventType, timeoutMs, retryPolicy, condition, async, compensatable,
                compensationMethod, bean, method, null, 0, 0, null,
                List.of(), List.of(), List.of(), List.of(), null, false, 0);
    }

    /**
     * Backward-compatible constructor (18 args) — with single signal/timer but no waitForAll/waitForAny/child fields.
     */
    public WorkflowStepDefinition(String stepId, String name, String description,
                                   List<String> dependsOn, int order,
                                   String outputEventType, long timeoutMs,
                                   RetryPolicy retryPolicy, String condition, boolean async,
                                   boolean compensatable, String compensationMethod,
                                   Object bean, Method method,
                                   String waitForSignal, long signalTimeoutMs,
                                   long waitForTimerDelayMs, String waitForTimerId) {
        this(stepId, name, description, dependsOn, order,
                outputEventType, timeoutMs, retryPolicy, condition, async, compensatable,
                compensationMethod, bean, method, waitForSignal, signalTimeoutMs,
                waitForTimerDelayMs, waitForTimerId,
                List.of(), List.of(), List.of(), List.of(), null, false, 0);
    }

    public boolean hasDependencies() {
        return dependsOn != null && !dependsOn.isEmpty();
    }

    public boolean isRootStep() {
        return !hasDependencies();
    }

    public boolean hasWaitForAll() {
        return (waitForAllSignals != null && !waitForAllSignals.isEmpty())
                || (waitForAllTimers != null && !waitForAllTimers.isEmpty());
    }

    public boolean hasWaitForAny() {
        return (waitForAnySignals != null && !waitForAnySignals.isEmpty())
                || (waitForAnyTimers != null && !waitForAnyTimers.isEmpty());
    }

    public boolean hasChildWorkflow() {
        return childWorkflowId != null && !childWorkflowId.isBlank();
    }
}
