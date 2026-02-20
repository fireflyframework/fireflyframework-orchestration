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

package org.fireflyframework.orchestration.saga.composition;

import java.util.*;

/**
 * Fluent builder for {@link SagaComposition} with validation for circular dependencies,
 * naming, and data mapping consistency.
 */
public class SagaCompositionBuilder {

    private final String name;
    private final List<SagaComposition.CompositionSaga> sagas = new ArrayList<>();
    private final List<SagaComposition.DataMapping> dataMappings = new ArrayList<>();
    private final Set<String> aliases = new HashSet<>();

    private SagaCompositionBuilder(String name) {
        this.name = Objects.requireNonNull(name, "composition name");
    }

    public static SagaCompositionBuilder composition(String name) {
        return new SagaCompositionBuilder(name);
    }

    public SagaEntryBuilder saga(String alias) {
        return new SagaEntryBuilder(alias);
    }

    public SagaCompositionBuilder dataFlow(String sourceSaga, String sourceField,
                                             String targetSaga, String targetField) {
        dataMappings.add(new SagaComposition.DataMapping(sourceSaga, sourceField, targetSaga, targetField));
        return this;
    }

    public SagaComposition build() {
        validate();
        return new SagaComposition(name, sagas, dataMappings);
    }

    private void validate() {
        if (sagas.isEmpty()) {
            throw new IllegalStateException("Composition '" + name + "' has no sagas");
        }

        // Validate dependencies reference known aliases
        for (var saga : sagas) {
            for (String dep : saga.dependsOn()) {
                if (!aliases.contains(dep)) {
                    throw new IllegalStateException("Saga '" + saga.alias()
                            + "' depends on unknown alias '" + dep + "'");
                }
            }
        }

        // Validate data mappings reference known aliases
        for (var mapping : dataMappings) {
            if (!aliases.contains(mapping.sourceSaga())) {
                throw new IllegalStateException("Data mapping source '" + mapping.sourceSaga()
                        + "' is not a known saga alias");
            }
            if (!aliases.contains(mapping.targetSaga())) {
                throw new IllegalStateException("Data mapping target '" + mapping.targetSaga()
                        + "' is not a known saga alias");
            }
        }

        // Validate no circular dependencies
        try {
            new SagaComposition(name, sagas, dataMappings).getExecutableLayers();
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Composition '" + name + "' validation failed: " + e.getMessage());
        }
    }

    public class SagaEntryBuilder {
        private final String alias;
        private String sagaName;
        private final List<String> dependsOn = new ArrayList<>();
        private final Map<String, Object> staticInput = new LinkedHashMap<>();
        private boolean optional;
        private String condition = "";

        SagaEntryBuilder(String alias) {
            this.alias = Objects.requireNonNull(alias, "alias");
            this.sagaName = alias; // default to alias
        }

        public SagaEntryBuilder sagaName(String sagaName) {
            this.sagaName = sagaName;
            return this;
        }

        public SagaEntryBuilder dependsOn(String... deps) {
            dependsOn.addAll(List.of(deps));
            return this;
        }

        public SagaEntryBuilder input(String key, Object value) {
            staticInput.put(key, value);
            return this;
        }

        public SagaEntryBuilder optional(boolean optional) {
            this.optional = optional;
            return this;
        }

        public SagaEntryBuilder condition(String condition) {
            this.condition = condition;
            return this;
        }

        public SagaCompositionBuilder add() {
            if (!aliases.add(alias)) {
                throw new IllegalStateException("Duplicate saga alias '" + alias + "' in composition '" + name + "'");
            }
            sagas.add(new SagaComposition.CompositionSaga(
                    alias, sagaName, List.copyOf(dependsOn),
                    Map.copyOf(staticInput), optional, condition));
            return SagaCompositionBuilder.this;
        }
    }
}
