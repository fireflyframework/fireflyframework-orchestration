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

package org.fireflyframework.orchestration.core.topology;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Logs a pretty-printed topology for any orchestration pattern.
 *
 * <pre>{@code
 * TopologyReporter.logTopology("OrderSaga", steps,
 *     StepDef::stepId, StepDef::dependsOn);
 * }</pre>
 *
 * Output:
 * <pre>
 * Topology for OrderSaga
 * L1 [validate] (sequential, size=1)
 * -> L2 [charge, notify] (parallel, size=2)
 * -> L3 [complete] (sequential, size=1)
 * </pre>
 */
public final class TopologyReporter {

    private static final Logger log = LoggerFactory.getLogger(TopologyReporter.class);

    private TopologyReporter() {}

    /**
     * Compute layers and log the topology at INFO level.
     *
     * @return the computed layers
     */
    public static <T> List<List<String>> logTopology(String name,
                                                      Collection<T> steps,
                                                      Function<T, String> idExtractor,
                                                      Function<T, List<String>> depsExtractor) {
        List<List<String>> layers = TopologyBuilder.buildLayers(
                new java.util.ArrayList<>(steps), idExtractor, depsExtractor);

        StringBuilder pretty = new StringBuilder();
        pretty.append("Topology for ").append(name).append('\n');
        for (int i = 0; i < layers.size(); i++) {
            List<String> layer = layers.get(i);
            String mode = layer.size() > 1 ? "parallel" : "sequential";
            if (i == 0) {
                pretty.append("L").append(i + 1);
            } else {
                pretty.append("-> L").append(i + 1);
            }
            pretty.append(" [").append(String.join(", ", layer))
                    .append("] (").append(mode).append(", size=").append(layer.size()).append(")\n");
        }

        log.info("[orchestration] {}", pretty);
        return layers;
    }
}
