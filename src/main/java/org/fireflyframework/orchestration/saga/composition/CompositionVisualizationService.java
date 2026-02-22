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

import lombok.extern.slf4j.Slf4j;

/**
 * Generates DOT (Graphviz) and Mermaid diagrams for saga composition topology visualization.
 */
@Slf4j
public class CompositionVisualizationService {

    /**
     * Generates a DOT (Graphviz) representation of the saga composition DAG.
     */
    public String toDot(SagaComposition composition) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph \"").append(escapeLabel(composition.name())).append("\" {\n");
        sb.append("  rankdir=TB;\n");
        sb.append("  node [shape=box, style=rounded];\n\n");

        // Nodes
        for (var saga : composition.sagas()) {
            sb.append("  \"").append(escapeLabel(saga.alias())).append("\"");
            sb.append(" [label=\"").append(escapeLabel(saga.alias()));
            if (!saga.alias().equals(saga.sagaName())) {
                sb.append("\\n(").append(escapeLabel(saga.sagaName())).append(")");
            }
            if (saga.optional()) {
                sb.append("\\n[optional]");
            }
            sb.append("\"");
            if (saga.optional()) {
                sb.append(", style=\"rounded,dashed\"");
            }
            sb.append("];\n");
        }

        sb.append("\n");

        // Dependency edges
        for (var saga : composition.sagas()) {
            for (String dep : saga.dependsOn()) {
                sb.append("  \"").append(escapeLabel(dep)).append("\" -> \"")
                        .append(escapeLabel(saga.alias())).append("\";\n");
            }
        }

        // Data flow edges
        if (!composition.dataMappings().isEmpty()) {
            sb.append("\n  // Data flow mappings\n");
            for (var mapping : composition.dataMappings()) {
                sb.append("  \"").append(escapeLabel(mapping.sourceSaga())).append("\" -> \"")
                        .append(escapeLabel(mapping.targetSaga())).append("\"")
                        .append(" [style=dashed, color=blue, label=\"")
                        .append(escapeLabel(mapping.sourceField())).append(" -> ")
                        .append(escapeLabel(mapping.targetField())).append("\"];\n");
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Generates a Mermaid diagram representation of the saga composition DAG.
     */
    public String toMermaid(SagaComposition composition) {
        StringBuilder sb = new StringBuilder();
        sb.append("graph TD\n");

        // Nodes
        for (var saga : composition.sagas()) {
            String label = saga.alias();
            if (!saga.alias().equals(saga.sagaName())) {
                label += "<br/>" + saga.sagaName();
            }
            if (saga.optional()) {
                label += "<br/>[optional]";
            }

            sb.append("  ").append(sanitizeId(saga.alias()));
            sb.append("[\"").append(label).append("\"]\n");
        }

        sb.append("\n");

        // Dependency edges
        for (var saga : composition.sagas()) {
            for (String dep : saga.dependsOn()) {
                sb.append("  ").append(sanitizeId(dep)).append(" --> ")
                        .append(sanitizeId(saga.alias())).append("\n");
            }
        }

        // Data flow edges
        if (!composition.dataMappings().isEmpty()) {
            sb.append("\n  %% Data flow mappings\n");
            for (var mapping : composition.dataMappings()) {
                sb.append("  ").append(sanitizeId(mapping.sourceSaga()))
                        .append(" -.->|\"").append(mapping.sourceField())
                        .append(" -> ").append(mapping.targetField())
                        .append("\"| ").append(sanitizeId(mapping.targetSaga())).append("\n");
            }
        }

        // Style optional nodes
        for (var saga : composition.sagas()) {
            if (saga.optional()) {
                sb.append("  style ").append(sanitizeId(saga.alias()))
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
