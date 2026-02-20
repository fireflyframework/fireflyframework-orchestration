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
import java.util.stream.Collectors;

/**
 * Immutable definition of a saga composition — a DAG of multiple sagas
 * that can execute in layers with data flow between them.
 */
public final class SagaComposition {

    private final String name;
    private final List<CompositionSaga> sagas;
    private final Map<String, CompositionSaga> sagaMap;
    private final List<DataMapping> dataMappings;

    public SagaComposition(String name, List<CompositionSaga> sagas, List<DataMapping> dataMappings) {
        this.name = name;
        this.sagas = List.copyOf(sagas);
        this.sagaMap = sagas.stream().collect(Collectors.toUnmodifiableMap(CompositionSaga::alias, s -> s));
        this.dataMappings = dataMappings != null ? List.copyOf(dataMappings) : List.of();
    }

    public String name() { return name; }
    public List<CompositionSaga> sagas() { return sagas; }
    public Map<String, CompositionSaga> sagaMap() { return sagaMap; }
    public List<DataMapping> dataMappings() { return dataMappings; }

    /**
     * Returns sagas grouped into layers based on their dependency order.
     * Sagas in the same layer can execute in parallel.
     */
    public List<List<CompositionSaga>> getExecutableLayers() {
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (CompositionSaga saga : sagas) {
            deps.put(saga.alias(), new LinkedHashSet<>(saga.dependsOn()));
        }

        List<List<CompositionSaga>> layers = new ArrayList<>();
        Set<String> resolved = new HashSet<>();

        while (!deps.isEmpty()) {
            List<String> ready = deps.entrySet().stream()
                    .filter(e -> resolved.containsAll(e.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();

            if (ready.isEmpty()) {
                throw new IllegalStateException("Circular dependency in composition '" + name + "'");
            }

            layers.add(ready.stream().map(sagaMap::get).toList());
            ready.forEach(deps::remove);
            resolved.addAll(ready);
        }

        return Collections.unmodifiableList(layers);
    }

    public record CompositionSaga(
            String alias,
            String sagaName,
            List<String> dependsOn,
            Map<String, Object> staticInput,
            boolean optional,
            String condition
    ) {}

    public record DataMapping(
            String sourceSaga,
            String sourceField,
            String targetSaga,
            String targetField
    ) {}
}
