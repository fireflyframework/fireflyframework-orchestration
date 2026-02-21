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

package org.fireflyframework.orchestration.tcc.registry;

import org.fireflyframework.orchestration.core.exception.DuplicateDefinitionException;
import org.fireflyframework.orchestration.core.exception.ExecutionNotFoundException;
import org.fireflyframework.orchestration.tcc.annotation.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers and indexes TCC transaction coordinators from the Spring context.
 */
public class TccRegistry {

    private final ApplicationContext applicationContext;
    private final Map<String, TccDefinition> tccDefinitions = new ConcurrentHashMap<>();
    private volatile boolean scanned = false;

    public TccRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public TccDefinition getTcc(String name) {
        ensureScanned();
        TccDefinition def = tccDefinitions.get(name);
        if (def == null) throw new ExecutionNotFoundException(name);
        return def;
    }

    public boolean hasTcc(String name) {
        ensureScanned();
        return tccDefinitions.containsKey(name);
    }

    public Collection<TccDefinition> getAll() {
        ensureScanned();
        return Collections.unmodifiableCollection(tccDefinitions.values());
    }

    public void register(TccDefinition definition) {
        if (tccDefinitions.putIfAbsent(definition.name, definition) != null) {
            throw new DuplicateDefinitionException("tcc '" + definition.name + "'");
        }
    }

    private synchronized void ensureScanned() {
        if (scanned) return;
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(Tcc.class);

        for (Object bean : beans.values()) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            Tcc tccAnn = AnnotationUtils.findAnnotation(targetClass, Tcc.class);
            if (tccAnn == null) continue;

            TccDefinition tccDef = new TccDefinition(
                    tccAnn.name(), bean, bean,
                    tccAnn.timeoutMs(), tccAnn.retryEnabled(), tccAnn.maxRetries(), tccAnn.backoffMs());
            tccDef.triggerEventType = tccAnn.triggerEventType();

            scanParticipants(tccDef, targetClass);
            validateDefinition(tccDef);

            // Scan lifecycle callback methods
            tccDef.onTccCompleteMethods = findAnnotatedMethods(targetClass, OnTccComplete.class);
            tccDef.onTccErrorMethods = findAnnotatedMethods(targetClass, OnTccError.class);

            tccDefinitions.putIfAbsent(tccAnn.name(), tccDef);
        }
        scanned = true;
    }

    private void scanParticipants(TccDefinition tccDef, Class<?> coordinatorClass) {
        for (Class<?> nestedClass : coordinatorClass.getDeclaredClasses()) {
            TccParticipant pAnn = AnnotationUtils.findAnnotation(nestedClass, TccParticipant.class);
            if (pAnn == null) continue;
            if (!Modifier.isStatic(nestedClass.getModifiers())) {
                throw new IllegalStateException(
                        "TCC participant class '" + nestedClass.getName() +
                        "' must be declared static. Non-static inner classes require an enclosing instance.");
            }
            try {
                Object instance = nestedClass.getDeclaredConstructor().newInstance();
                registerParticipant(tccDef, pAnn, nestedClass, instance);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to instantiate participant: " + nestedClass.getName(), e);
            }
        }
    }

    private void registerParticipant(TccDefinition tccDef, TccParticipant pAnn,
                                     Class<?> participantClass, Object instance) {
        String id = pAnn.id();
        Method tryM = null, confirmM = null, cancelM = null;
        long tryTimeout = -1, confirmTimeout = -1, cancelTimeout = -1;
        int tryRetry = -1, confirmRetry = -1, cancelRetry = -1;
        long tryBackoff = -1, confirmBackoff = -1, cancelBackoff = -1;

        for (Method m : participantClass.getDeclaredMethods()) {
            TryMethod ta = AnnotationUtils.findAnnotation(m, TryMethod.class);
            if (ta != null) {
                if (tryM != null) throw new IllegalStateException("Participant " + id + " has multiple @TryMethod");
                tryM = m; tryTimeout = ta.timeoutMs(); tryRetry = ta.retry(); tryBackoff = ta.backoffMs();
            }
            ConfirmMethod ca = AnnotationUtils.findAnnotation(m, ConfirmMethod.class);
            if (ca != null) {
                if (confirmM != null) throw new IllegalStateException("Participant " + id + " has multiple @ConfirmMethod");
                confirmM = m; confirmTimeout = ca.timeoutMs(); confirmRetry = ca.retry(); confirmBackoff = ca.backoffMs();
            }
            CancelMethod xa = AnnotationUtils.findAnnotation(m, CancelMethod.class);
            if (xa != null) {
                if (cancelM != null) throw new IllegalStateException("Participant " + id + " has multiple @CancelMethod");
                cancelM = m; cancelTimeout = xa.timeoutMs(); cancelRetry = xa.retry(); cancelBackoff = xa.backoffMs();
            }
        }

        if (tryM == null) throw new IllegalStateException("Participant " + id + " missing @TryMethod");
        if (confirmM == null) throw new IllegalStateException("Participant " + id + " missing @ConfirmMethod");
        if (cancelM == null) throw new IllegalStateException("Participant " + id + " missing @CancelMethod");

        TccParticipantDefinition pd = new TccParticipantDefinition(
                id, pAnn.order(), pAnn.timeoutMs(), pAnn.optional(), instance, instance,
                tryM, tryTimeout, tryRetry, tryBackoff,
                confirmM, confirmTimeout, confirmRetry, confirmBackoff,
                cancelM, cancelTimeout, cancelRetry, cancelBackoff);

        TccEvent te = AnnotationUtils.findAnnotation(participantClass, TccEvent.class);
        if (te != null) pd.tccEvent = new TccEventConfig(te.topic(), te.eventType(), te.key());

        tccDef.participants.put(id, pd);
    }

    private void validateDefinition(TccDefinition tccDef) {
        if (tccDef.participants.isEmpty()) {
            throw new IllegalStateException("TCC '" + tccDef.name + "' has no participants");
        }
    }

    private List<Method> findAnnotatedMethods(Class<?> clazz, Class<? extends java.lang.annotation.Annotation> annotationType) {
        List<Method> methods = new ArrayList<>();
        for (Method m : clazz.getMethods()) {
            if (m.isAnnotationPresent(annotationType)) {
                methods.add(m);
            }
        }
        // Sort by priority (highest first)
        methods.sort((a, b) -> {
            int pa = 0, pb = 0;
            if (annotationType == OnTccComplete.class) {
                pa = a.getAnnotation(OnTccComplete.class).priority();
                pb = b.getAnnotation(OnTccComplete.class).priority();
            } else if (annotationType == OnTccError.class) {
                pa = a.getAnnotation(OnTccError.class).priority();
                pb = b.getAnnotation(OnTccError.class).priority();
            }
            return Integer.compare(pb, pa);
        });
        return List.copyOf(methods);
    }
}
