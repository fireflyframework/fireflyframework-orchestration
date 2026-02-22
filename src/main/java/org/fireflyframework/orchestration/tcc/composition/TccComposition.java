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

import org.fireflyframework.orchestration.core.topology.TopologyBuilder;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Immutable definition of a TCC composition — a DAG of multiple TCC transactions
 * that can execute in layers with data flow between them.
 */
public final class TccComposition {

    private final String name;
    private final List<CompositionTcc> tccs;
    private final Map<String, CompositionTcc> tccMap;
    private final List<DataMapping> dataMappings;

    public TccComposition(String name, List<CompositionTcc> tccs, List<DataMapping> dataMappings) {
        this.name = name;
        this.tccs = List.copyOf(tccs);
        this.tccMap = tccs.stream().collect(Collectors.toUnmodifiableMap(CompositionTcc::alias, t -> t));
        this.dataMappings = dataMappings != null ? List.copyOf(dataMappings) : List.of();
    }

    public String name() { return name; }
    public List<CompositionTcc> tccs() { return tccs; }
    public Map<String, CompositionTcc> tccMap() { return tccMap; }
    public List<DataMapping> dataMappings() { return dataMappings; }

    /**
     * Returns TCCs grouped into layers based on their dependency order.
     * TCCs in the same layer can execute in parallel.
     * Uses {@link TopologyBuilder} for Kahn's algorithm-based layer computation.
     */
    public List<List<CompositionTcc>> getExecutableLayers() {
        List<List<String>> layerIds = TopologyBuilder.buildLayers(
                tccs,
                CompositionTcc::alias,
                CompositionTcc::dependsOn);

        List<List<CompositionTcc>> layers = new ArrayList<>();
        for (List<String> layerAliases : layerIds) {
            layers.add(layerAliases.stream().map(tccMap::get).toList());
        }

        return Collections.unmodifiableList(layers);
    }

    public record CompositionTcc(
            String alias,
            String tccName,
            List<String> dependsOn,
            Map<String, Object> staticInput,
            boolean optional,
            String condition
    ) {}

    public record DataMapping(
            String sourceTcc,
            String sourceField,
            String targetTcc,
            String targetField
    ) {}
}
