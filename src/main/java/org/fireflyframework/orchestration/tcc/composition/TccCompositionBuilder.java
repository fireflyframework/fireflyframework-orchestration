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

package org.fireflyframework.orchestration.tcc.composition;

import java.util.*;

/**
 * Fluent builder for {@link TccComposition} with validation for circular dependencies,
 * naming, and data mapping consistency.
 */
public class TccCompositionBuilder {

    private final String name;
    private final List<TccComposition.CompositionTcc> tccs = new ArrayList<>();
    private final List<TccComposition.DataMapping> dataMappings = new ArrayList<>();
    private final Set<String> aliases = new HashSet<>();

    private TccCompositionBuilder(String name) {
        this.name = Objects.requireNonNull(name, "composition name");
    }

    public static TccCompositionBuilder composition(String name) {
        return new TccCompositionBuilder(name);
    }

    public TccEntryBuilder tcc(String alias) {
        return new TccEntryBuilder(alias);
    }

    public TccCompositionBuilder dataFlow(String sourceTcc, String sourceField,
                                           String targetTcc, String targetField) {
        dataMappings.add(new TccComposition.DataMapping(sourceTcc, sourceField, targetTcc, targetField));
        return this;
    }

    public TccComposition build() {
        validate();
        return new TccComposition(name, tccs, dataMappings);
    }

    private void validate() {
        if (tccs.isEmpty()) {
            throw new IllegalStateException("Composition '" + name + "' has no TCCs");
        }

        // Validate dependencies reference known aliases
        for (var tcc : tccs) {
            for (String dep : tcc.dependsOn()) {
                if (!aliases.contains(dep)) {
                    throw new IllegalStateException("TCC '" + tcc.alias()
                            + "' depends on unknown alias '" + dep + "'");
                }
            }
        }

        // Validate data mappings reference known aliases
        for (var mapping : dataMappings) {
            if (!aliases.contains(mapping.sourceTcc())) {
                throw new IllegalStateException("Data mapping source '" + mapping.sourceTcc()
                        + "' is not a known TCC alias");
            }
            if (!aliases.contains(mapping.targetTcc())) {
                throw new IllegalStateException("Data mapping target '" + mapping.targetTcc()
                        + "' is not a known TCC alias");
            }
        }

        // Validate no circular dependencies via TopologyBuilder
        try {
            new TccComposition(name, tccs, dataMappings).getExecutableLayers();
        } catch (Exception e) {
            throw new IllegalStateException("Composition '" + name + "' validation failed: " + e.getMessage());
        }
    }

    public class TccEntryBuilder {
        private final String alias;
        private String tccName;
        private final List<String> dependsOn = new ArrayList<>();
        private final Map<String, Object> staticInput = new LinkedHashMap<>();
        private boolean optional;
        private String condition = "";

        TccEntryBuilder(String alias) {
            this.alias = Objects.requireNonNull(alias, "alias");
            this.tccName = alias; // default to alias
        }

        public TccEntryBuilder tccName(String tccName) {
            this.tccName = tccName;
            return this;
        }

        public TccEntryBuilder dependsOn(String... deps) {
            dependsOn.addAll(List.of(deps));
            return this;
        }

        public TccEntryBuilder input(String key, Object value) {
            staticInput.put(key, value);
            return this;
        }

        public TccEntryBuilder optional(boolean optional) {
            this.optional = optional;
            return this;
        }

        public TccEntryBuilder condition(String condition) {
            this.condition = condition;
            return this;
        }

        public TccCompositionBuilder add() {
            if (!aliases.add(alias)) {
                throw new IllegalStateException("Duplicate TCC alias '" + alias + "' in composition '" + name + "'");
            }
            tccs.add(new TccComposition.CompositionTcc(
                    alias, tccName, List.copyOf(dependsOn),
                    Map.copyOf(staticInput), optional, condition));
            return TccCompositionBuilder.this;
        }
    }
}
