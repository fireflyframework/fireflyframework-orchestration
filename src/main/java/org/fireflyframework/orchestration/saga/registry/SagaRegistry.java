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

package org.fireflyframework.orchestration.saga.registry;

import org.fireflyframework.orchestration.core.exception.DuplicateDefinitionException;
import org.fireflyframework.orchestration.core.exception.ExecutionNotFoundException;
import org.fireflyframework.orchestration.core.topology.TopologyBuilder;
import org.fireflyframework.orchestration.saga.annotation.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers and indexes sagas from the Spring application context.
 * Scans for beans annotated with {@link Saga}, resolves steps, compensations,
 * external steps, and validates the DAG topology.
 */
public class SagaRegistry {

    private final ApplicationContext applicationContext;
    private final Map<String, SagaDefinition> sagas = new ConcurrentHashMap<>();
    private volatile boolean scanned = false;

    public SagaRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public SagaDefinition getSaga(String name) {
        ensureScanned();
        SagaDefinition def = sagas.get(name);
        if (def == null) {
            throw new ExecutionNotFoundException(name);
        }
        return def;
    }

    public boolean hasSaga(String name) {
        ensureScanned();
        return sagas.containsKey(name);
    }

    public Collection<SagaDefinition> getAll() {
        ensureScanned();
        return Collections.unmodifiableCollection(sagas.values());
    }

    public void register(SagaDefinition definition) {
        if (sagas.putIfAbsent(definition.name, definition) != null) {
            throw new DuplicateDefinitionException("saga '" + definition.name + "'");
        }
    }

    private synchronized void ensureScanned() {
        if (scanned) return;
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(Saga.class);

        for (Object bean : beans.values()) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            Saga sagaAnn = targetClass.getAnnotation(Saga.class);
            if (sagaAnn == null) continue;
            String sagaName = sagaAnn.name();
            SagaDefinition sagaDef = new SagaDefinition(sagaName, bean, bean, sagaAnn.layerConcurrency());

            for (Method m : targetClass.getMethods()) {
                SagaStep stepAnn = m.getAnnotation(SagaStep.class);
                if (stepAnn == null) continue;
                Duration backoff = stepAnn.backoffMs() >= 0 ? Duration.ofMillis(stepAnn.backoffMs()) : null;
                Duration timeout = stepAnn.timeoutMs() >= 0 ? Duration.ofMillis(stepAnn.timeoutMs()) : null;
                SagaStepDefinition stepDef = new SagaStepDefinition(
                        stepAnn.id(), stepAnn.compensate(), List.of(stepAnn.dependsOn()),
                        stepAnn.retry(), backoff, timeout, stepAnn.idempotencyKey(),
                        stepAnn.jitter(), stepAnn.jitterFactor(), stepAnn.cpuBound(), m);

                StepEvent se = m.getAnnotation(StepEvent.class);
                if (se != null) {
                    stepDef.stepEvent = new StepEventConfig(se.topic(), se.type(), se.key());
                }
                if (stepAnn.compensationRetry() >= 0) stepDef.compensationRetry = stepAnn.compensationRetry();
                if (stepAnn.compensationBackoffMs() >= 0) stepDef.compensationBackoff = Duration.ofMillis(stepAnn.compensationBackoffMs());
                if (stepAnn.compensationTimeoutMs() >= 0) stepDef.compensationTimeout = Duration.ofMillis(stepAnn.compensationTimeoutMs());
                stepDef.compensationCritical = stepAnn.compensationCritical();
                stepDef.stepInvocationMethod = resolveInvocationMethod(bean.getClass(), m);

                if (sagaDef.steps.putIfAbsent(stepDef.id, stepDef) != null) {
                    throw new DuplicateDefinitionException("step '" + stepDef.id + "' in saga '" + sagaName + "'");
                }
            }

            // Resolve in-class compensations
            for (SagaStepDefinition sd : sagaDef.steps.values()) {
                if (!StringUtils.hasText(sd.compensateName)) continue;
                Method comp = findMethodByName(targetClass, sd.compensateName);
                if (comp == null) {
                    throw new IllegalStateException("Compensation method '" + sd.compensateName + "' not found in saga '" + sagaName + "'");
                }
                sd.compensateMethod = comp;
                sd.compensateInvocationMethod = resolveInvocationMethod(bean.getClass(), comp);
            }

            validateTopology(sagaDef);
            sagas.put(sagaName, sagaDef);
        }

        // Second pass: external steps and compensations
        scanExternalSteps();
        scanExternalCompensations();

        // Re-validate after enrichment
        for (SagaDefinition def : sagas.values()) {
            validateTopology(def);
        }

        scanned = true;
    }

    private void scanExternalSteps() {
        Map<String, Object> allBeans = applicationContext.getBeansOfType(Object.class);
        for (Object bean : allBeans.values()) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            for (Method m : targetClass.getMethods()) {
                ExternalSagaStep es = m.getAnnotation(ExternalSagaStep.class);
                if (es == null) continue;
                String sagaName = es.saga();
                SagaDefinition def = sagas.get(sagaName);
                if (def == null) {
                    throw new IllegalStateException("@ExternalSagaStep references unknown saga '" + sagaName + "'");
                }
                if (def.steps.containsKey(es.id())) {
                    throw new DuplicateDefinitionException("step '" + es.id() + "' in saga '" + sagaName + "' (external)");
                }
                Duration backoff = es.backoffMs() >= 0 ? Duration.ofMillis(es.backoffMs()) : null;
                Duration timeout = es.timeoutMs() >= 0 ? Duration.ofMillis(es.timeoutMs()) : null;
                SagaStepDefinition stepDef = new SagaStepDefinition(
                        es.id(), es.compensate(), List.of(es.dependsOn()),
                        es.retry(), backoff, timeout, es.idempotencyKey(),
                        es.jitter(), es.jitterFactor(), es.cpuBound(), m);

                StepEvent se = m.getAnnotation(StepEvent.class);
                if (se != null) {
                    stepDef.stepEvent = new StepEventConfig(se.topic(), se.type(), se.key());
                }
                if (es.compensationRetry() >= 0) stepDef.compensationRetry = es.compensationRetry();
                if (es.compensationBackoffMs() >= 0) stepDef.compensationBackoff = Duration.ofMillis(es.compensationBackoffMs());
                if (es.compensationTimeoutMs() >= 0) stepDef.compensationTimeout = Duration.ofMillis(es.compensationTimeoutMs());
                stepDef.compensationCritical = es.compensationCritical();
                stepDef.stepInvocationMethod = resolveInvocationMethod(bean.getClass(), m);
                stepDef.stepBean = bean;

                if (StringUtils.hasText(stepDef.compensateName)) {
                    Method comp = findMethodByName(targetClass, stepDef.compensateName);
                    if (comp == null) {
                        throw new IllegalStateException("Compensation method '" + stepDef.compensateName + "' not found on external step bean");
                    }
                    stepDef.compensateMethod = comp;
                    stepDef.compensateInvocationMethod = resolveInvocationMethod(bean.getClass(), comp);
                    stepDef.compensateBean = bean;
                }
                def.steps.put(stepDef.id, stepDef);
            }
        }
    }

    private void scanExternalCompensations() {
        Map<String, Object> allBeans = applicationContext.getBeansOfType(Object.class);
        Set<String> seenKeys = new HashSet<>();
        for (Object bean : allBeans.values()) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            for (Method m : targetClass.getMethods()) {
                CompensationSagaStep cs = m.getAnnotation(CompensationSagaStep.class);
                if (cs == null) continue;
                String key = cs.saga() + "::" + cs.forStepId();
                SagaDefinition def = sagas.get(cs.saga());
                if (def == null) {
                    throw new IllegalStateException("@CompensationSagaStep references unknown saga '" + cs.saga() + "'");
                }
                SagaStepDefinition sd = def.steps.get(cs.forStepId());
                if (sd == null) {
                    throw new IllegalStateException("@CompensationSagaStep references unknown step '" + cs.forStepId() + "' in saga '" + cs.saga() + "'");
                }
                if (!seenKeys.add(key)) {
                    throw new DuplicateDefinitionException("external compensation '" + key + "'");
                }
                sd.compensateMethod = m;
                sd.compensateInvocationMethod = resolveInvocationMethod(bean.getClass(), m);
                sd.compensateBean = bean;
            }
        }
    }

    private void validateTopology(SagaDefinition saga) {
        TopologyBuilder.validate(
                new ArrayList<>(saga.steps.values()),
                s -> s.id,
                s -> s.dependsOn);
    }

    private Method findMethodByName(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
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
