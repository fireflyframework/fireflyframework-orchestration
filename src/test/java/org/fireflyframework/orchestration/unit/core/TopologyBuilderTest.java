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

package org.fireflyframework.orchestration.unit.core;

import org.fireflyframework.orchestration.core.exception.TopologyValidationException;
import org.fireflyframework.orchestration.core.topology.TopologyBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TopologyBuilderTest {

    record SimpleStep(String id, List<String> dependsOn) {}

    @Test
    void buildLayers_linearChain_producesCorrectLayers() {
        var steps = List.of(
                new SimpleStep("a", List.of()),
                new SimpleStep("b", List.of("a")),
                new SimpleStep("c", List.of("b"))
        );
        var layers = TopologyBuilder.buildLayers(steps, SimpleStep::id, SimpleStep::dependsOn);
        assertThat(layers).hasSize(3);
        assertThat(layers.get(0)).containsExactly("a");
        assertThat(layers.get(1)).containsExactly("b");
        assertThat(layers.get(2)).containsExactly("c");
    }

    @Test
    void buildLayers_parallelSteps_groupedInSameLayer() {
        var steps = List.of(
                new SimpleStep("a", List.of()),
                new SimpleStep("b", List.of()),
                new SimpleStep("c", List.of())
        );
        var layers = TopologyBuilder.buildLayers(steps, SimpleStep::id, SimpleStep::dependsOn);
        assertThat(layers).hasSize(1);
        assertThat(layers.get(0)).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void buildLayers_diamondDependency_correctLayering() {
        var steps = List.of(
                new SimpleStep("a", List.of()),
                new SimpleStep("b", List.of("a")),
                new SimpleStep("c", List.of("a")),
                new SimpleStep("d", List.of("b", "c"))
        );
        var layers = TopologyBuilder.buildLayers(steps, SimpleStep::id, SimpleStep::dependsOn);
        assertThat(layers).hasSize(3);
        assertThat(layers.get(0)).containsExactly("a");
        assertThat(layers.get(1)).containsExactlyInAnyOrder("b", "c");
        assertThat(layers.get(2)).containsExactly("d");
    }

    @Test
    void validate_cyclicDependency_throwsException() {
        var steps = List.of(
                new SimpleStep("a", List.of("c")),
                new SimpleStep("b", List.of("a")),
                new SimpleStep("c", List.of("b"))
        );
        assertThatThrownBy(() -> TopologyBuilder.buildLayers(steps, SimpleStep::id, SimpleStep::dependsOn))
                .isInstanceOf(TopologyValidationException.class)
                .hasMessageContaining("Circular dependency");
    }

    @Test
    void validate_missingDependency_throwsException() {
        var steps = List.of(
                new SimpleStep("a", List.of("nonexistent"))
        );
        assertThatThrownBy(() -> TopologyBuilder.buildLayers(steps, SimpleStep::id, SimpleStep::dependsOn))
                .isInstanceOf(TopologyValidationException.class)
                .hasMessageContaining("non-existent step");
    }

    @Test
    void validate_selfDependency_throwsException() {
        var steps = List.of(
                new SimpleStep("a", List.of("a"))
        );
        assertThatThrownBy(() -> TopologyBuilder.buildLayers(steps, SimpleStep::id, SimpleStep::dependsOn))
                .isInstanceOf(TopologyValidationException.class)
                .hasMessageContaining("Self-dependency");
    }

    @Test
    void validate_emptySteps_throwsException() {
        assertThatThrownBy(() -> TopologyBuilder.buildLayers(List.of(), s -> "", s -> List.of()))
                .isInstanceOf(TopologyValidationException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void validate_duplicateStepIds_throwsException() {
        var steps = List.of(
                new SimpleStep("a", List.of()),
                new SimpleStep("a", List.of())
        );
        assertThatThrownBy(() -> TopologyBuilder.buildLayers(steps, SimpleStep::id, SimpleStep::dependsOn))
                .isInstanceOf(TopologyValidationException.class)
                .hasMessageContaining("Duplicate step ID");
    }

    @Test
    void buildLayers_complexGraph_correctOrdering() {
        // a -> b -> d
        // a -> c -> d -> e
        var steps = List.of(
                new SimpleStep("a", List.of()),
                new SimpleStep("b", List.of("a")),
                new SimpleStep("c", List.of("a")),
                new SimpleStep("d", List.of("b", "c")),
                new SimpleStep("e", List.of("d"))
        );
        var layers = TopologyBuilder.buildLayers(steps, SimpleStep::id, SimpleStep::dependsOn);
        assertThat(layers).hasSize(4);
        assertThat(layers.get(0)).containsExactly("a");
        assertThat(layers.get(1)).containsExactlyInAnyOrder("b", "c");
        assertThat(layers.get(2)).containsExactly("d");
        assertThat(layers.get(3)).containsExactly("e");
    }

    @Test
    void buildLayers_nullDependencies_treatedAsNone() {
        var steps = List.of(
                new SimpleStep("a", null),
                new SimpleStep("b", null)
        );
        var layers = TopologyBuilder.buildLayers(steps, SimpleStep::id, SimpleStep::dependsOn);
        assertThat(layers).hasSize(1);
        assertThat(layers.get(0)).containsExactlyInAnyOrder("a", "b");
    }
}
