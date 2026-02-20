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

import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Service for indexing and searching workflow instances by custom attributes.
 * Uses an in-memory inverted index for efficient querying.
 */
@Slf4j
public class WorkflowSearchService {

    private final SearchAttributeProjection projection;
    private final ExecutionPersistenceProvider persistence;

    public WorkflowSearchService(SearchAttributeProjection projection,
                                   ExecutionPersistenceProvider persistence) {
        this.projection = projection;
        this.persistence = persistence;
    }

    public Mono<Void> updateSearchAttribute(String correlationId, String key, Object value) {
        return Mono.fromRunnable(() -> {
            projection.upsert(correlationId, key, value);
            log.debug("[search] Updated attribute {}={} for instance '{}'", key, value, correlationId);
        });
    }

    public Mono<Void> updateSearchAttributes(String correlationId, Map<String, Object> attributes) {
        return Mono.fromRunnable(() -> attributes.forEach(
                (key, value) -> projection.upsert(correlationId, key, value)));
    }

    public Optional<Object> getAttribute(String correlationId, String key) {
        return projection.get(correlationId, key);
    }

    public Map<String, Object> getAttributes(String correlationId) {
        return projection.getAll(correlationId);
    }

    public Flux<ExecutionState> searchByAttribute(String key, Object value) {
        Set<String> correlationIds = projection.findByAttribute(key, value);
        return Flux.fromIterable(correlationIds)
                .flatMap(id -> persistence.findById(id)
                        .flatMap(opt -> opt.map(Mono::just).orElse(Mono.empty())));
    }

    public Flux<ExecutionState> searchByAttributes(Map<String, Object> criteria) {
        Set<String> correlationIds = projection.findByAttributes(criteria);
        return Flux.fromIterable(correlationIds)
                .flatMap(id -> persistence.findById(id)
                        .flatMap(opt -> opt.map(Mono::just).orElse(Mono.empty())));
    }

    public void removeIndex(String correlationId) {
        projection.remove(correlationId);
    }
}
