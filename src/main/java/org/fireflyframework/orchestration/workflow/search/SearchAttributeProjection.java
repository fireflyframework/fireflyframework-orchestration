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

package org.fireflyframework.orchestration.workflow.search;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory read model for workflow search attributes.
 * Indexes workflow instances by custom attributes for efficient querying.
 */
public class SearchAttributeProjection {

    // correlationId -> attribute key -> value
    private final Map<String, Map<String, Object>> instanceAttributes = new ConcurrentHashMap<>();
    // attribute key -> (value -> set of correlationIds) — inverted index
    private final Map<String, Map<Object, Set<String>>> invertedIndex = new ConcurrentHashMap<>();

    public void upsert(String correlationId, String key, Object value) {
        // Remove old value from inverted index
        var current = instanceAttributes.getOrDefault(correlationId, Map.of()).get(key);
        if (current != null) {
            var valueMap = invertedIndex.get(key);
            if (valueMap != null) {
                var ids = valueMap.get(current);
                if (ids != null) {
                    ids.remove(correlationId);
                    if (ids.isEmpty()) valueMap.remove(current);
                }
            }
        }

        // Set new value
        instanceAttributes.computeIfAbsent(correlationId, k -> new ConcurrentHashMap<>()).put(key, value);
        invertedIndex.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(value, v -> ConcurrentHashMap.newKeySet())
                .add(correlationId);
    }

    public Optional<Object> get(String correlationId, String key) {
        var attrs = instanceAttributes.get(correlationId);
        return attrs != null ? Optional.ofNullable(attrs.get(key)) : Optional.empty();
    }

    public Map<String, Object> getAll(String correlationId) {
        var attrs = instanceAttributes.get(correlationId);
        return attrs != null ? Collections.unmodifiableMap(attrs) : Map.of();
    }

    public Set<String> findByAttribute(String key, Object value) {
        var valueMap = invertedIndex.get(key);
        if (valueMap == null) return Set.of();
        var ids = valueMap.get(value);
        return ids != null ? Set.copyOf(ids) : Set.of();
    }

    public Set<String> findByAttributes(Map<String, Object> criteria) {
        Set<String> result = null;
        for (var entry : criteria.entrySet()) {
            Set<String> matches = findByAttribute(entry.getKey(), entry.getValue());
            if (result == null) {
                result = new HashSet<>(matches);
            } else {
                result.retainAll(matches);
            }
            if (result.isEmpty()) return Set.of();
        }
        return result != null ? Set.copyOf(result) : Set.of();
    }

    public void remove(String correlationId) {
        var attrs = instanceAttributes.remove(correlationId);
        if (attrs != null) {
            for (var entry : attrs.entrySet()) {
                var valueMap = invertedIndex.get(entry.getKey());
                if (valueMap != null) {
                    var ids = valueMap.get(entry.getValue());
                    if (ids != null) {
                        ids.remove(correlationId);
                        if (ids.isEmpty()) valueMap.remove(entry.getValue());
                    }
                }
            }
        }
    }
}
