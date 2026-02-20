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

package org.fireflyframework.orchestration.core.dlq;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryDeadLetterStore implements DeadLetterStore {
    private final ConcurrentHashMap<String, DeadLetterEntry> store = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> save(DeadLetterEntry entry) {
        return Mono.fromRunnable(() -> store.put(entry.id(), entry));
    }

    @Override
    public Mono<Optional<DeadLetterEntry>> findById(String id) {
        return Mono.fromCallable(() -> Optional.ofNullable(store.get(id)));
    }

    @Override
    public Flux<DeadLetterEntry> findAll() {
        return Flux.defer(() -> Flux.fromIterable(List.copyOf(store.values())));
    }

    @Override
    public Flux<DeadLetterEntry> findByExecutionName(String executionName) {
        return Flux.defer(() -> Flux.fromIterable(List.copyOf(store.values()))
                .filter(e -> e.executionName().equals(executionName)));
    }

    @Override
    public Flux<DeadLetterEntry> findByCorrelationId(String correlationId) {
        return Flux.defer(() -> Flux.fromIterable(List.copyOf(store.values()))
                .filter(e -> e.correlationId().equals(correlationId)));
    }

    @Override
    public Mono<Void> delete(String id) {
        return Mono.fromRunnable(() -> store.remove(id));
    }

    @Override
    public Mono<Long> count() {
        return Mono.fromCallable(() -> (long) store.size());
    }

    public void clear() { store.clear(); }
}
