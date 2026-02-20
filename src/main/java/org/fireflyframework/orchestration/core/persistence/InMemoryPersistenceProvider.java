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

package org.fireflyframework.orchestration.core.persistence;

import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryPersistenceProvider implements ExecutionPersistenceProvider {

    private final ConcurrentHashMap<String, ExecutionState> store = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> save(ExecutionState state) {
        return Mono.fromRunnable(() -> store.put(state.correlationId(), state));
    }

    @Override
    public Mono<Optional<ExecutionState>> findById(String correlationId) {
        return Mono.fromCallable(() -> Optional.ofNullable(store.get(correlationId)));
    }

    @Override
    public Mono<Void> updateStatus(String correlationId, ExecutionStatus status) {
        return Mono.fromRunnable(() -> store.computeIfPresent(correlationId, (k, v) -> v.withStatus(status)));
    }

    @Override
    public Flux<ExecutionState> findByPattern(ExecutionPattern pattern) {
        return Flux.defer(() -> Flux.fromIterable(List.copyOf(store.values()))
                .filter(s -> s.pattern() == pattern));
    }

    @Override
    public Flux<ExecutionState> findByStatus(ExecutionStatus status) {
        return Flux.defer(() -> Flux.fromIterable(List.copyOf(store.values()))
                .filter(s -> s.status() == status));
    }

    @Override
    public Flux<ExecutionState> findInFlight() {
        return Flux.defer(() -> Flux.fromIterable(List.copyOf(store.values()))
                .filter(s -> s.status() != null && s.status().isActive()));
    }

    @Override
    public Flux<ExecutionState> findStale(Instant before) {
        return Flux.defer(() -> Flux.fromIterable(List.copyOf(store.values()))
                .filter(s -> s.status() != null && s.status().isActive())
                .filter(s -> s.updatedAt() != null && s.updatedAt().isBefore(before)));
    }

    @Override
    public Mono<Long> cleanup(Duration olderThan) {
        Instant threshold = Instant.now().minus(olderThan);
        return Mono.fromCallable(() -> {
            long count = 0;
            var it = store.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                if (entry.getValue().isTerminal() && entry.getValue().updatedAt() != null
                        && entry.getValue().updatedAt().isBefore(threshold)) {
                    it.remove();
                    count++;
                }
            }
            return count;
        });
    }

    @Override
    public Mono<Boolean> isHealthy() {
        return Mono.just(true);
    }

    // Test helpers
    public int size() { return store.size(); }
    public void clear() { store.clear(); }
}
