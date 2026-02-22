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

import lombok.extern.slf4j.Slf4j;

/**
 * Generates DOT (Graphviz) and Mermaid diagrams for TCC composition topology visualization.
 */
@Slf4j
public class TccCompositionVisualizationService {

    /**
     * Generates a DOT (Graphviz) representation of the TCC composition DAG.
     */
    public String toDot(TccComposition composition) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph \"").append(escapeLabel(composition.name())).append("\" {\n");
        sb.append("  rankdir=TB;\n");
        sb.append("  node [shape=box, style=rounded];\n\n");

        // Nodes
        for (var tcc : composition.tccs()) {
            sb.append("  \"").append(escapeLabel(tcc.alias())).append("\"");
            sb.append(" [label=\"").append(escapeLabel(tcc.alias()));
            if (!tcc.alias().equals(tcc.tccName())) {
                sb.append("\\n(").append(escapeLabel(tcc.tccName())).append(")");
            }
            if (tcc.optional()) {
                sb.append("\\n[optional]");
            }
            sb.append("\"");
            if (tcc.optional()) {
                sb.append(", style=\"rounded,dashed\"");
            }
            sb.append("];\n");
        }

        sb.append("\n");

        // Dependency edges
        for (var tcc : composition.tccs()) {
            for (String dep : tcc.dependsOn()) {
                sb.append("  \"").append(escapeLabel(dep)).append("\" -> \"")
                        .append(escapeLabel(tcc.alias())).append("\";\n");
            }
        }

        // Data flow edges
        if (!composition.dataMappings().isEmpty()) {
            sb.append("\n  // Data flow mappings\n");
            for (var mapping : composition.dataMappings()) {
                sb.append("  \"").append(escapeLabel(mapping.sourceTcc())).append("\" -> \"")
                        .append(escapeLabel(mapping.targetTcc())).append("\"")
                        .append(" [style=dashed, color=blue, label=\"")
                        .append(escapeLabel(mapping.sourceField())).append(" -> ")
                        .append(escapeLabel(mapping.targetField())).append("\"];\n");
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Generates a Mermaid diagram representation of the TCC composition DAG.
     */
    public String toMermaid(TccComposition composition) {
        StringBuilder sb = new StringBuilder();
        sb.append("graph TD\n");

        // Nodes
        for (var tcc : composition.tccs()) {
            String label = tcc.alias();
            if (!tcc.alias().equals(tcc.tccName())) {
                label += "<br/>" + tcc.tccName();
            }
            if (tcc.optional()) {
                label += "<br/>[optional]";
            }

            sb.append("  ").append(sanitizeId(tcc.alias()));
            sb.append("[\"").append(label).append("\"]\n");
        }

        sb.append("\n");

        // Dependency edges
        for (var tcc : composition.tccs()) {
            for (String dep : tcc.dependsOn()) {
                sb.append("  ").append(sanitizeId(dep)).append(" --> ")
                        .append(sanitizeId(tcc.alias())).append("\n");
            }
        }

        // Data flow edges
        if (!composition.dataMappings().isEmpty()) {
            sb.append("\n  %% Data flow mappings\n");
            for (var mapping : composition.dataMappings()) {
                sb.append("  ").append(sanitizeId(mapping.sourceTcc()))
                        .append(" -.->|\"").append(mapping.sourceField())
                        .append(" -> ").append(mapping.targetField())
                        .append("\"| ").append(sanitizeId(mapping.targetTcc())).append("\n");
            }
        }

        // Style optional nodes
        for (var tcc : composition.tccs()) {
            if (tcc.optional()) {
                sb.append("  style ").append(sanitizeId(tcc.alias()))
                        .append(" stroke-dasharray: 5 5\n");
            }
        }

        return sb.toString();
    }

    private String escapeLabel(String text) {
        return text.replace("\"", "\\\"");
    }

    private String sanitizeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
