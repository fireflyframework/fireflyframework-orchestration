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

package org.fireflyframework.orchestration.saga.engine;

import org.fireflyframework.orchestration.core.context.ExecutionContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Immutable, typed DSL to provide per-step inputs without exposing {@code Map<String,Object>}.
 * Supports concrete values and lazy resolvers evaluated against the {@link ExecutionContext}.
 */
public final class StepInputs {

    private final Map<String, Object> values;
    private final Map<String, Function<ExecutionContext, Object>> resolvers;
    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    private StepInputs(Map<String, Object> values, Map<String, Function<ExecutionContext, Object>> resolvers) {
        this.values = values;
        this.resolvers = resolvers;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static StepInputs of(String stepId, Object input) {
        return builder().forStepId(stepId, input).build();
    }

    public static StepInputs of(Map<String, Object> values) {
        Builder b = builder();
        if (values != null) values.forEach(b::forStepId);
        return b.build();
    }

    public static StepInputs empty() {
        return new Builder().build();
    }

    Object rawValue(String stepId) {
        return values.get(stepId);
    }

    Object resolveFor(String stepId, ExecutionContext ctx) {
        if (values.containsKey(stepId)) return values.get(stepId);
        Object cached = cache.get(stepId);
        if (cached != null) return cached;
        var resolver = resolvers.get(stepId);
        if (resolver == null) return null;
        Object resolved = resolver.apply(ctx);
        if (resolved != null) cache.put(stepId, resolved);
        return resolved;
    }

    Map<String, Object> materializedView(ExecutionContext ctx) {
        Map<String, Object> all = new LinkedHashMap<>(values.size() + cache.size());
        all.putAll(values);
        all.putAll(cache);
        return Collections.unmodifiableMap(all);
    }

    public Map<String, Object> materializeAll(ExecutionContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        for (var e : resolvers.entrySet()) {
            if (!cache.containsKey(e.getKey())) {
                Object resolved = e.getValue().apply(ctx);
                if (resolved != null) cache.put(e.getKey(), resolved);
            }
        }
        Map<String, Object> all = new LinkedHashMap<>(values.size() + cache.size());
        all.putAll(values);
        all.putAll(cache);
        return Collections.unmodifiableMap(all);
    }

    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final Map<String, Function<ExecutionContext, Object>> resolvers = new LinkedHashMap<>();

        public Builder forStepId(String stepId, Object input) {
            Objects.requireNonNull(stepId, "stepId");
            values.put(stepId, input);
            return this;
        }

        public Builder forStepId(String stepId, Function<ExecutionContext, Object> resolver) {
            Objects.requireNonNull(stepId, "stepId");
            Objects.requireNonNull(resolver, "resolver");
            resolvers.put(stepId, resolver);
            return this;
        }

        public Builder forSteps(Map<String, Object> inputs) {
            if (inputs != null) inputs.forEach(this::forStepId);
            return this;
        }

        public StepInputs build() {
            return new StepInputs(
                    Collections.unmodifiableMap(new LinkedHashMap<>(values)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(resolvers)));
        }
    }
}
