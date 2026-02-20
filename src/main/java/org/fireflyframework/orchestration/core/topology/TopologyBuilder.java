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

import org.fireflyframework.orchestration.core.exception.TopologyValidationException;

import java.util.*;
import java.util.function.Function;

public final class TopologyBuilder {

    private TopologyBuilder() {}

    /**
     * Builds execution layers from a list of steps using Kahn's BFS algorithm.
     * Steps with no dependencies form layer 0; each subsequent layer contains
     * steps whose dependencies are all in previous layers.
     * Steps within the same layer can execute in parallel.
     *
     * @param steps list of step objects
     * @param idExtractor function to get step ID
     * @param dependsOnExtractor function to get list of dependency IDs
     * @return ordered list of layers, each layer is a list of step IDs
     * @throws TopologyValidationException if the topology is invalid
     */
    public static <T> List<List<String>> buildLayers(
            List<T> steps,
            Function<T, String> idExtractor,
            Function<T, List<String>> dependsOnExtractor) {

        if (steps == null || steps.isEmpty()) {
            throw new TopologyValidationException("Steps list cannot be empty");
        }

        // Validate first
        validate(steps, idExtractor, dependsOnExtractor);

        // Build adjacency and in-degree using LinkedHashMap for deterministic order
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> adjacency = new LinkedHashMap<>();

        for (T step : steps) {
            String id = idExtractor.apply(step);
            indegree.putIfAbsent(id, 0);
            adjacency.putIfAbsent(id, new ArrayList<>());
        }

        for (T step : steps) {
            String id = idExtractor.apply(step);
            List<String> deps = dependsOnExtractor.apply(step);
            if (deps != null) {
                for (String dep : deps) {
                    indegree.merge(id, 1, Integer::sum);
                    adjacency.get(dep).add(id);
                }
            }
        }

        // Kahn's BFS
        List<List<String>> layers = new ArrayList<>();
        Queue<String> queue = new ArrayDeque<>();

        for (String id : indegree.keySet()) {
            if (indegree.get(id) == 0) {
                queue.add(id);
            }
        }

        while (!queue.isEmpty()) {
            int size = queue.size();
            List<String> layer = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                String u = queue.poll();
                layer.add(u);
                for (String v : adjacency.getOrDefault(u, List.of())) {
                    indegree.put(v, indegree.get(v) - 1);
                    if (indegree.get(v) == 0) {
                        queue.add(v);
                    }
                }
            }
            layers.add(layer);
        }

        return layers;
    }

    /**
     * Validates step topology: checks for empty steps, missing dependencies,
     * self-dependencies, and cycles.
     */
    public static <T> void validate(
            List<T> steps,
            Function<T, String> idExtractor,
            Function<T, List<String>> dependsOnExtractor) {

        if (steps == null || steps.isEmpty()) {
            throw new TopologyValidationException("Steps list cannot be empty");
        }

        Set<String> knownIds = new LinkedHashSet<>();
        for (T step : steps) {
            String id = idExtractor.apply(step);
            if (!knownIds.add(id)) {
                throw new TopologyValidationException("Duplicate step ID: " + id);
            }
        }

        // Check for missing dependencies and self-dependencies
        for (T step : steps) {
            String id = idExtractor.apply(step);
            List<String> deps = dependsOnExtractor.apply(step);
            if (deps == null) continue;
            for (String dep : deps) {
                if (dep.equals(id)) {
                    throw new TopologyValidationException("Self-dependency detected: step '" + id + "' depends on itself");
                }
                if (!knownIds.contains(dep)) {
                    throw new TopologyValidationException("Step '" + id + "' depends on non-existent step '" + dep + "'");
                }
            }
        }

        // Cycle detection using DFS
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (T step : steps) {
            String id = idExtractor.apply(step);
            List<String> deps = dependsOnExtractor.apply(step);
            graph.put(id, deps != null ? deps : List.of());
        }

        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();

        for (String id : knownIds) {
            if (hasCycle(id, graph, visited, recursionStack)) {
                throw new TopologyValidationException("Circular dependency detected in topology");
            }
        }
    }

    private static boolean hasCycle(String node, Map<String, List<String>> graph,
                                    Set<String> visited, Set<String> recursionStack) {
        if (recursionStack.contains(node)) return true;
        if (visited.contains(node)) return false;

        visited.add(node);
        recursionStack.add(node);

        for (String dep : graph.getOrDefault(node, List.of())) {
            if (hasCycle(dep, graph, visited, recursionStack)) return true;
        }

        recursionStack.remove(node);
        return false;
    }
}
