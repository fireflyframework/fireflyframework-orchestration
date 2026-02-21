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

import org.fireflyframework.orchestration.core.exception.DuplicateDefinitionException;
import org.fireflyframework.orchestration.core.exception.ExecutionNotFoundException;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.StepTriggerMode;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.topology.TopologyBuilder;
import org.fireflyframework.orchestration.workflow.annotation.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers and indexes workflows from the Spring application context.
 * Scans for beans annotated with {@link Workflow}, resolves steps, lifecycle
 * callbacks, and validates the DAG topology.
 */
@Slf4j
public class WorkflowRegistry {

    private final ApplicationContext applicationContext;
    private final ConcurrentHashMap<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();
    private volatile boolean scanned = false;

    public WorkflowRegistry() {
        this(null);
    }

    public WorkflowRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public void register(WorkflowDefinition definition) {
        TopologyBuilder.validate(definition.steps(),
                WorkflowStepDefinition::stepId,
                WorkflowStepDefinition::dependsOn);

        if (definitions.putIfAbsent(definition.workflowId(), definition) != null) {
            throw new DuplicateDefinitionException(definition.workflowId());
        }
        log.info("[workflow-registry] Registered workflow '{}'", definition.workflowId());
    }

    public WorkflowDefinition getWorkflow(String workflowId) {
        ensureScanned();
        WorkflowDefinition def = definitions.get(workflowId);
        if (def == null) {
            throw new ExecutionNotFoundException(workflowId);
        }
        return def;
    }

    public Optional<WorkflowDefinition> get(String workflowId) {
        ensureScanned();
        return Optional.ofNullable(definitions.get(workflowId));
    }

    public boolean hasWorkflow(String workflowId) {
        ensureScanned();
        return definitions.containsKey(workflowId);
    }

    public Collection<WorkflowDefinition> getAll() {
        ensureScanned();
        return Collections.unmodifiableCollection(definitions.values());
    }

    public void unregister(String workflowId) {
        definitions.remove(workflowId);
    }

    private synchronized void ensureScanned() {
        if (scanned) return;
        if (applicationContext == null) {
            scanned = true;
            return;
        }

        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(Workflow.class);

        for (Object bean : beans.values()) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            Workflow wfAnn = targetClass.getAnnotation(Workflow.class);
            if (wfAnn == null) continue;

            String workflowId = StringUtils.hasText(wfAnn.id()) ? wfAnn.id() : targetClass.getSimpleName();
            String name = StringUtils.hasText(wfAnn.name()) ? wfAnn.name() : workflowId;

            // Scan @WorkflowStep methods
            List<WorkflowStepDefinition> steps = new ArrayList<>();
            for (Method m : targetClass.getMethods()) {
                WorkflowStep stepAnn = m.getAnnotation(WorkflowStep.class);
                if (stepAnn == null) continue;

                String stepId = StringUtils.hasText(stepAnn.id()) ? stepAnn.id() : m.getName();
                String stepName = StringUtils.hasText(stepAnn.name()) ? stepAnn.name() : stepId;

                RetryPolicy retryPolicy = stepAnn.maxRetries() > 0
                        ? new RetryPolicy(stepAnn.maxRetries(), Duration.ofMillis(stepAnn.retryDelayMs()),
                                Duration.ofMinutes(5), 2.0, 0.0, new String[]{})
                        : RetryPolicy.NO_RETRY;

                Method invokeMethod = resolveInvocationMethod(bean.getClass(), m);

                // Scan for @WaitForSignal and @WaitForTimer on the same method
                WaitForSignal signalAnn = m.getAnnotation(WaitForSignal.class);
                WaitForTimer timerAnn = m.getAnnotation(WaitForTimer.class);

                String waitForSignal = signalAnn != null ? signalAnn.value() : null;
                long signalTimeoutMs = signalAnn != null ? signalAnn.timeoutMs() : 0;
                long waitForTimerDelayMs = timerAnn != null ? timerAnn.delayMs() : 0;
                String waitForTimerId = timerAnn != null && !timerAnn.timerId().isBlank() ? timerAnn.timerId() : null;

                WorkflowStepDefinition stepDef = new WorkflowStepDefinition(
                        stepId, stepName, stepAnn.description(),
                        List.of(stepAnn.dependsOn()), stepAnn.order(),
                        stepAnn.triggerMode(), stepAnn.inputEventType(), stepAnn.outputEventType(),
                        stepAnn.timeoutMs(), retryPolicy, stepAnn.condition(),
                        stepAnn.async(), stepAnn.compensatable(), stepAnn.compensationMethod(),
                        bean, invokeMethod,
                        waitForSignal, signalTimeoutMs, waitForTimerDelayMs, waitForTimerId);

                steps.add(stepDef);
            }

            if (steps.isEmpty()) {
                log.warn("[workflow-registry] @Workflow '{}' has no @WorkflowStep methods, skipping", workflowId);
                continue;
            }

            // Scan lifecycle callbacks (all matching methods, sorted by priority)
            List<Method> onStepCompleteMethods = findAnnotatedMethods(targetClass, OnStepComplete.class);
            List<Method> onWorkflowCompleteMethods = findAnnotatedMethods(targetClass, OnWorkflowComplete.class);
            List<Method> onWorkflowErrorMethods = findAnnotatedMethods(targetClass, OnWorkflowError.class);

            RetryPolicy wfRetryPolicy = wfAnn.maxRetries() > 0
                    ? new RetryPolicy(wfAnn.maxRetries(), Duration.ofMillis(wfAnn.retryDelayMs()),
                            Duration.ofMinutes(5), 2.0, 0.0, new String[]{})
                    : RetryPolicy.NO_RETRY;

            WorkflowDefinition wfDef = new WorkflowDefinition(
                    workflowId, name, wfAnn.description(), wfAnn.version(),
                    List.copyOf(steps), wfAnn.triggerMode(), wfAnn.triggerEventType(),
                    wfAnn.timeoutMs(), wfRetryPolicy, bean,
                    onStepCompleteMethods, onWorkflowCompleteMethods, onWorkflowErrorMethods,
                    wfAnn.publishEvents());

            TopologyBuilder.validate(wfDef.steps(),
                    WorkflowStepDefinition::stepId,
                    WorkflowStepDefinition::dependsOn);

            definitions.putIfAbsent(workflowId, wfDef);
            log.info("[workflow-registry] Discovered workflow '{}' with {} steps", workflowId, steps.size());
        }

        scanned = true;
    }

    private List<Method> findAnnotatedMethods(Class<?> clazz, Class<? extends Annotation> ann) {
        return Arrays.stream(clazz.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(ann))
                .sorted(Comparator.comparingInt(m -> {
                    if (ann == OnStepComplete.class) return m.getAnnotation(OnStepComplete.class).priority();
                    if (ann == OnWorkflowComplete.class) return m.getAnnotation(OnWorkflowComplete.class).priority();
                    if (ann == OnWorkflowError.class) return m.getAnnotation(OnWorkflowError.class).priority();
                    return 0;
                }))
                .toList();
    }

    private Method resolveInvocationMethod(Class<?> beanClass, Method targetMethod) {
        try {
            return beanClass.getMethod(targetMethod.getName(), targetMethod.getParameterTypes());
        } catch (NoSuchMethodException e) {
            for (Method m : beanClass.getMethods()) {
                if (m.getName().equals(targetMethod.getName()) && m.getParameterCount() == targetMethod.getParameterCount()) {
                    return m;
                }
            }
            return targetMethod;
        }
    }
}
