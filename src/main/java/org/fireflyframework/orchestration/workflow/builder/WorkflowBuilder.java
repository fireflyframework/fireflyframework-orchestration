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

package org.fireflyframework.orchestration.workflow.builder;

import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.workflow.annotation.WaitForSignal;
import org.fireflyframework.orchestration.workflow.annotation.WaitForTimer;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class WorkflowBuilder {

    private final String name;
    private String description = "";
    private String version = "1.0";
    private TriggerMode triggerMode = TriggerMode.SYNC;
    private long timeoutMs = 30000;
    private RetryPolicy retryPolicy = RetryPolicy.DEFAULT;
    private boolean publishEvents = false;
    private int layerConcurrency = 0;
    private String triggerEventType = "";
    private final List<WorkflowStepDefinition> steps = new ArrayList<>();

    public WorkflowBuilder(String name) {
        this.name = name;
    }

    public WorkflowBuilder description(String description) {
        this.description = description;
        return this;
    }

    public WorkflowBuilder version(String version) {
        this.version = version;
        return this;
    }

    public WorkflowBuilder triggerMode(TriggerMode mode) {
        this.triggerMode = mode;
        return this;
    }

    public WorkflowBuilder timeout(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        return this;
    }

    public WorkflowBuilder retryPolicy(RetryPolicy policy) {
        this.retryPolicy = policy;
        return this;
    }

    public WorkflowBuilder publishEvents(boolean publishEvents) {
        this.publishEvents = publishEvents;
        return this;
    }

    public WorkflowBuilder layerConcurrency(int layerConcurrency) {
        this.layerConcurrency = layerConcurrency;
        return this;
    }

    public WorkflowBuilder triggerEventType(String eventType) {
        this.triggerEventType = eventType != null ? eventType : "";
        return this;
    }

    public StepBuilder step(String stepId) {
        return new StepBuilder(this, stepId);
    }

    WorkflowBuilder addStep(WorkflowStepDefinition stepDef) {
        steps.add(stepDef);
        return this;
    }

    public WorkflowDefinition build() {
        return new WorkflowDefinition(name, name, description, version, List.copyOf(steps),
                triggerMode, triggerEventType, timeoutMs, retryPolicy, null, List.of(), List.of(), List.of(),
                publishEvents, layerConcurrency, Map.of());
    }

    public static class StepBuilder {

        private final WorkflowBuilder parent;
        private final String stepId;
        private String name = "";
        private String description = "";
        private List<String> dependsOn = List.of();
        private int order = 0;
        private long timeoutMs = 0;
        private RetryPolicy retryPolicy;
        private Object bean;
        private Method method;
        private String waitForSignal;
        private long signalTimeoutMs = 0;
        private long waitForTimerDelayMs = 0;
        private String waitForTimerId;
        private String outputEventType = "";
        private String condition = "";
        private boolean async = false;
        private boolean compensatable = false;
        private String compensationMethod = "";
        private List<WaitForSignal> waitForAllSignals = List.of();
        private List<WaitForTimer> waitForAllTimers = List.of();
        private List<WaitForSignal> waitForAnySignals = List.of();
        private List<WaitForTimer> waitForAnyTimers = List.of();
        private String childWorkflowId;
        private boolean childWaitForCompletion = true;
        private long childTimeoutMs = 0;

        StepBuilder(WorkflowBuilder parent, String stepId) {
            this.parent = parent;
            this.stepId = stepId;
            this.name = stepId;
        }

        public StepBuilder name(String name) {
            this.name = name;
            return this;
        }

        public StepBuilder description(String description) {
            this.description = description;
            return this;
        }

        public StepBuilder dependsOn(String... deps) {
            this.dependsOn = Arrays.asList(deps);
            return this;
        }

        public StepBuilder order(int order) {
            this.order = order;
            return this;
        }

        public StepBuilder timeout(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public StepBuilder retryPolicy(RetryPolicy policy) {
            this.retryPolicy = policy;
            return this;
        }

        public StepBuilder handler(Object bean, Method method) {
            this.bean = bean;
            this.method = method;
            return this;
        }

        public StepBuilder outputEventType(String outputEventType) {
            this.outputEventType = outputEventType;
            return this;
        }

        public StepBuilder condition(String condition) {
            this.condition = condition;
            return this;
        }

        public StepBuilder waitForSignal(String signalName) {
            this.waitForSignal = signalName;
            return this;
        }

        public StepBuilder waitForSignal(String signalName, long timeoutMs) {
            this.waitForSignal = signalName;
            this.signalTimeoutMs = timeoutMs;
            return this;
        }

        public StepBuilder async(boolean async) {
            this.async = async;
            return this;
        }

        public StepBuilder compensatable(boolean compensatable, String compensationMethod) {
            this.compensatable = compensatable;
            this.compensationMethod = compensationMethod != null ? compensationMethod : "";
            return this;
        }

        public StepBuilder waitForTimer(long delayMs) {
            this.waitForTimerDelayMs = delayMs;
            return this;
        }

        public StepBuilder waitForTimer(long delayMs, String timerId) {
            this.waitForTimerDelayMs = delayMs;
            this.waitForTimerId = timerId;
            return this;
        }

        /**
         * Configures the step to wait for ALL specified signals and timers before proceeding.
         */
        public StepBuilder waitForAll(WaitForSignal[] signals, WaitForTimer[] timers) {
            this.waitForAllSignals = signals != null ? List.of(signals) : List.of();
            this.waitForAllTimers = timers != null ? List.of(timers) : List.of();
            return this;
        }

        /**
         * Configures the step to wait for ANY of the specified signals or timers before proceeding.
         */
        public StepBuilder waitForAny(WaitForSignal[] signals, WaitForTimer[] timers) {
            this.waitForAnySignals = signals != null ? List.of(signals) : List.of();
            this.waitForAnyTimers = timers != null ? List.of(timers) : List.of();
            return this;
        }

        /**
         * Configures the step to spawn a child workflow with default settings (wait for completion, no timeout).
         */
        public StepBuilder childWorkflow(String workflowId) {
            this.childWorkflowId = workflowId;
            this.childWaitForCompletion = true;
            this.childTimeoutMs = 0;
            return this;
        }

        /**
         * Configures the step to spawn a child workflow with explicit wait and timeout settings.
         */
        public StepBuilder childWorkflow(String workflowId, boolean wait, long timeoutMs) {
            this.childWorkflowId = workflowId;
            this.childWaitForCompletion = wait;
            this.childTimeoutMs = timeoutMs;
            return this;
        }

        public WorkflowBuilder add() {
            var stepDef = new WorkflowStepDefinition(stepId, name, description, dependsOn, order,
                    outputEventType, timeoutMs, retryPolicy, condition,
                    async, compensatable, compensationMethod, bean, method,
                    waitForSignal, signalTimeoutMs, waitForTimerDelayMs, waitForTimerId,
                    waitForAllSignals, waitForAllTimers, waitForAnySignals, waitForAnyTimers,
                    childWorkflowId, childWaitForCompletion, childTimeoutMs);
            return parent.addStep(stepDef);
        }
    }
}
