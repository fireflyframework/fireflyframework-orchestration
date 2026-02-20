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

import java.util.*;
import java.util.function.Function;

/**
 * Generates Graphviz DOT and Mermaid diagram text for any orchestration topology.
 *
 * <p>Pattern-agnostic: works with any list of step-like objects via extractor functions.
 *
 * <pre>{@code
 * String dot = TopologyGraphGenerator.toDot("OrderSaga",
 *     sagaDef.steps.values(),
 *     s -> s.stepId,
 *     s -> s.dependsOn,
 *     s -> s.compensateName != null && !s.compensateName.isBlank());
 *
 * String mermaid = TopologyGraphGenerator.toMermaid("OrderSaga",
 *     sagaDef.steps.values(),
 *     s -> s.stepId,
 *     s -> s.dependsOn,
 *     s -> false);
 * }</pre>
 */
public final class TopologyGraphGenerator {

    private TopologyGraphGenerator() {}

    /**
     * Generate a Graphviz DOT diagram for the given topology.
     *
     * @param name execution name (saga, workflow, TCC)
     * @param steps collection of step objects
     * @param idExtractor function to get step ID
     * @param depsExtractor function to get dependency step IDs
     * @param hasCompensation function returning true if the step has compensation
     * @return DOT source text
     */
    public static <T> String toDot(String name,
                                    Collection<T> steps,
                                    Function<T, String> idExtractor,
                                    Function<T, List<String>> depsExtractor,
                                    Function<T, Boolean> hasCompensation) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph \"").append(escape(name)).append("\" {\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  graph [fontname=Helvetica];\n");
        sb.append("  node  [fontname=Helvetica, shape=box, style=rounded];\n");
        sb.append("  edge  [fontname=Helvetica];\n\n");

        // Build layers for rank grouping
        List<List<String>> layers = TopologyBuilder.buildLayers(
                new ArrayList<>(steps), idExtractor, depsExtractor);

        // Compute out-degree to identify terminal steps
        Map<String, Integer> outDegree = new LinkedHashMap<>();
        for (T step : steps) outDegree.put(idExtractor.apply(step), 0);
        for (T step : steps) {
            for (String dep : depsExtractor.apply(step)) {
                outDegree.merge(dep, 1, Integer::sum);
            }
        }

        // Nodes
        for (T step : steps) {
            String id = idExtractor.apply(step);
            String nodeId = nodeId(name, id);
            List<String> deps = depsExtractor.apply(step);
            boolean isStart = deps == null || deps.isEmpty();
            boolean isTerminal = outDegree.getOrDefault(id, 0) == 0;
            boolean comp = hasCompensation.apply(step);

            sb.append("  ").append(nodeId).append(" [label=\"").append(escape(id)).append("\"");
            if (isStart) sb.append(", penwidth=2");
            if (isTerminal) sb.append(", style=\"rounded,filled\", fillcolor=lightblue");
            sb.append("];\n");

            if (comp) {
                String compId = nodeId(name, "comp:" + id);
                sb.append("  ").append(compId)
                        .append(" [shape=hexagon, style=dashed, label=\"compensate\"];\n");
                sb.append("  ").append(nodeId).append(" -> ").append(compId)
                        .append(" [style=dashed, color=grey40];\n");
            }
        }

        sb.append("\n");

        // Layer rank grouping
        for (int i = 0; i < layers.size(); i++) {
            sb.append("  { rank=same; ");
            for (String stepId : layers.get(i)) {
                sb.append(nodeId(name, stepId)).append("; ");
            }
            sb.append("}\n");
        }

        sb.append("\n");

        // Edges
        for (T step : steps) {
            String id = idExtractor.apply(step);
            for (String dep : depsExtractor.apply(step)) {
                sb.append("  ").append(nodeId(name, dep)).append(" -> ")
                        .append(nodeId(name, id)).append(";\n");
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Generate a Mermaid flowchart for the given topology.
     *
     * @param name execution name
     * @param steps collection of step objects
     * @param idExtractor function to get step ID
     * @param depsExtractor function to get dependency step IDs
     * @param hasCompensation function returning true if the step has compensation
     * @return Mermaid source text
     */
    public static <T> String toMermaid(String name,
                                        Collection<T> steps,
                                        Function<T, String> idExtractor,
                                        Function<T, List<String>> depsExtractor,
                                        Function<T, Boolean> hasCompensation) {
        StringBuilder sb = new StringBuilder();
        sb.append("graph LR\n");
        sb.append("  subgraph ").append(mermaidId(name)).append("[\"").append(name).append("\"]\n");

        // Nodes
        for (T step : steps) {
            String id = idExtractor.apply(step);
            sb.append("    ").append(mermaidId(id)).append("[\"").append(id).append("\"]\n");

            if (hasCompensation.apply(step)) {
                String compId = mermaidId("comp_" + id);
                sb.append("    ").append(compId).append("{\"compensate\"}\n");
                sb.append("    ").append(mermaidId(id)).append(" -.-> ").append(compId).append("\n");
            }
        }

        // Edges
        for (T step : steps) {
            String id = idExtractor.apply(step);
            for (String dep : depsExtractor.apply(step)) {
                sb.append("    ").append(mermaidId(dep)).append(" --> ")
                        .append(mermaidId(id)).append("\n");
            }
        }

        sb.append("  end\n");
        return sb.toString();
    }

    private static String nodeId(String name, String stepId) {
        return "\"" + sanitize(name) + ":" + sanitize(stepId) + "\"";
    }

    private static String mermaidId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9') || c == '_' || c == ':' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
