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

package org.fireflyframework.orchestration.persistence.cache;

import org.fireflyframework.cache.core.CacheAdapter;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Cache-based implementation of {@link ExecutionPersistenceProvider}.
 *
 * <p>Uses fireflyframework-cache CacheAdapter for persisting execution state.
 * Supports both in-memory (Caffeine) and distributed (Redis) backends
 * transparently through the CacheAdapter abstraction.
 *
 * <p>Key patterns:
 * <ul>
 *   <li>{@code orchestration:state:{correlationId}} — full execution state</li>
 *   <li>{@code orchestration:pattern:{pattern}:{correlationId}} — pattern index</li>
 *   <li>{@code orchestration:status:{status}:{correlationId}} — status index</li>
 * </ul>
 */
@Slf4j
public class CachePersistenceProvider implements ExecutionPersistenceProvider {

    private static final String STATE_KEY_PREFIX = "orchestration:state:";
    private static final String PATTERN_INDEX_PREFIX = "orchestration:pattern:";
    private static final String STATUS_INDEX_PREFIX = "orchestration:status:";

    private final CacheAdapter cacheAdapter;
    private final Duration defaultTtl;

    public CachePersistenceProvider(CacheAdapter cacheAdapter, Duration defaultTtl) {
        this.cacheAdapter = cacheAdapter;
        this.defaultTtl = defaultTtl != null ? defaultTtl : Duration.ofHours(24);
        log.info("[orchestration] CachePersistenceProvider initialized with TTL: {}", this.defaultTtl);
    }

    public CachePersistenceProvider(CacheAdapter cacheAdapter) {
        this(cacheAdapter, Duration.ofHours(24));
    }

    @Override
    public Mono<Void> save(ExecutionState state) {
        String stateKey = STATE_KEY_PREFIX + state.correlationId();
        String patternKey = PATTERN_INDEX_PREFIX + state.pattern().name() + ":" + state.correlationId();
        String statusKey = STATUS_INDEX_PREFIX + state.status().name() + ":" + state.correlationId();

        return cacheAdapter.put(stateKey, state, defaultTtl)
                .then(cacheAdapter.put(patternKey, state.correlationId(), defaultTtl))
                .then(cacheAdapter.put(statusKey, state.correlationId(), defaultTtl))
                .doOnSuccess(v -> log.debug("[orchestration] Saved execution state: {}", state.correlationId()))
                .doOnError(e -> log.error("[orchestration] Failed to save execution state: {}", state.correlationId(), e));
    }

    @Override
    public Mono<Optional<ExecutionState>> findById(String correlationId) {
        String stateKey = STATE_KEY_PREFIX + correlationId;
        return cacheAdapter.<String, ExecutionState>get(stateKey)
                .defaultIfEmpty(Optional.empty());
    }

    @Override
    public Mono<Void> updateStatus(String correlationId, ExecutionStatus status) {
        return findById(correlationId)
                .flatMap(opt -> {
                    if (opt.isEmpty()) return Mono.empty();
                    ExecutionState current = opt.get();
                    // Remove old status index
                    String oldStatusKey = STATUS_INDEX_PREFIX + current.status().name() + ":" + correlationId;
                    ExecutionState updated = current.withStatus(status);
                    return cacheAdapter.evict(oldStatusKey)
                            .then(save(updated));
                });
    }

    @Override
    public Flux<ExecutionState> findByPattern(ExecutionPattern pattern) {
        String prefix = PATTERN_INDEX_PREFIX + pattern.name() + ":";
        return findStatesByKeyPrefix(prefix);
    }

    @Override
    public Flux<ExecutionState> findByStatus(ExecutionStatus status) {
        String prefix = STATUS_INDEX_PREFIX + status.name() + ":";
        return findStatesByKeyPrefix(prefix);
    }

    @Override
    public Flux<ExecutionState> findInFlight() {
        return allStates()
                .filter(s -> s.status() != null && s.status().isActive());
    }

    @Override
    public Flux<ExecutionState> findStale(Instant before) {
        return findInFlight()
                .filter(s -> s.updatedAt() != null && s.updatedAt().isBefore(before));
    }

    @Override
    public Mono<Long> cleanup(Duration olderThan) {
        Instant threshold = Instant.now().minus(olderThan);
        return allStates()
                .filter(s -> s.isTerminal() && s.updatedAt() != null && s.updatedAt().isBefore(threshold))
                .flatMap(s -> {
                    String stateKey = STATE_KEY_PREFIX + s.correlationId();
                    String patternKey = PATTERN_INDEX_PREFIX + s.pattern().name() + ":" + s.correlationId();
                    String statusKey = STATUS_INDEX_PREFIX + s.status().name() + ":" + s.correlationId();
                    return Mono.when(
                            cacheAdapter.evict(stateKey),
                            cacheAdapter.evict(patternKey),
                            cacheAdapter.evict(statusKey)
                    ).thenReturn(1L);
                })
                .reduce(0L, Long::sum);
    }

    @Override
    public Mono<Boolean> isHealthy() {
        return Mono.fromSupplier(cacheAdapter::isAvailable);
    }

    private Flux<ExecutionState> findStatesByKeyPrefix(String prefix) {
        return cacheAdapter.<String>keys()
                .flatMapMany(Flux::fromIterable)
                .filter(key -> key.startsWith(prefix))
                .flatMap(key -> cacheAdapter.<String, String>get(key))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .flatMap(correlationId -> findById(correlationId))
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    private Flux<ExecutionState> allStates() {
        return cacheAdapter.<String>keys()
                .flatMapMany(Flux::fromIterable)
                .filter(key -> key.startsWith(STATE_KEY_PREFIX))
                .flatMap(key -> cacheAdapter.<String, ExecutionState>get(key))
                .filter(Optional::isPresent)
                .map(Optional::get);
    }
}
