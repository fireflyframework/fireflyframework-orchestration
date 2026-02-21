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

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metadata for a discovered saga orchestrator.
 * Holds the saga name, the original Spring bean and the unwrapped target,
 * plus an ordered map of its step definitions.
 */
public class SagaDefinition {

    public final String name;
    public final Object bean;
    public final Object target;
    public final int layerConcurrency;
    public final Map<String, SagaStepDefinition> steps = new LinkedHashMap<>();
    public List<Method> onSagaCompleteMethods = List.of();
    public List<Method> onSagaErrorMethods = List.of();

    public SagaDefinition(String name, Object bean, Object target, int layerConcurrency) {
        this.name = name;
        this.bean = bean;
        this.target = target;
        this.layerConcurrency = layerConcurrency;
    }
}
