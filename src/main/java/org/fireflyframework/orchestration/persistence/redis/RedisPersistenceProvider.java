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

package org.fireflyframework.orchestration.persistence.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Redis-based implementation of {@link ExecutionPersistenceProvider}.
 *
 * <p>Uses Spring Data Redis reactive template for durable persistence
 * across application restarts. Supports configurable key prefix and TTL.
 *
 * <p>Redis key structure:
 * <ul>
 *   <li>{@code {prefix}state:{correlationId}} — serialized execution state</li>
 *   <li>{@code {prefix}meta:{correlationId}} — lightweight metadata (pattern, status, timestamp)</li>
 * </ul>
 */
@Slf4j
public class RedisPersistenceProvider implements ExecutionPersistenceProvider {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final String stateKeyPrefix;
    private final String metaKeyPrefix;
    private final Duration keyTtl;

    public RedisPersistenceProvider(ReactiveRedisTemplate<String, String> redisTemplate,
                                    ObjectMapper objectMapper,
                                    String keyPrefix,
                                    Duration keyTtl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.stateKeyPrefix = (keyPrefix != null ? keyPrefix : "orchestration:") + "state:";
        this.metaKeyPrefix = (keyPrefix != null ? keyPrefix : "orchestration:") + "meta:";
        this.keyTtl = keyTtl;
        log.info("[orchestration] RedisPersistenceProvider initialized with prefix: {}", keyPrefix);
    }

    public RedisPersistenceProvider(ReactiveRedisTemplate<String, String> redisTemplate,
                                    ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, "orchestration:", null);
    }

    @Override
    public Mono<Void> save(ExecutionState state) {
        String stateKey = stateKeyPrefix + state.correlationId();
        String metaKey = metaKeyPrefix + state.correlationId();

        return Mono.fromCallable(() -> objectMapper.writeValueAsString(state))
                .flatMap(json -> {
                    String meta = state.pattern().name() + "|" + state.status().name()
                            + "|" + state.updatedAt();
                    Mono<Boolean> stateOp = redisTemplate.opsForValue().set(stateKey, json);
                    Mono<Boolean> metaOp = redisTemplate.opsForValue().set(metaKey, meta);

                    Mono<Void> ttlOp = Mono.empty();
                    if (keyTtl != null) {
                        ttlOp = redisTemplate.expire(stateKey, keyTtl)
                                .then(redisTemplate.expire(metaKey, keyTtl))
                                .then();
                    }
                    return Mono.when(stateOp, metaOp).then(ttlOp);
                })
                .doOnSuccess(v -> log.debug("[orchestration] Saved state to Redis: {}", state.correlationId()))
                .doOnError(e -> log.error("[orchestration] Failed to save to Redis: {}", state.correlationId(), e));
    }

    @Override
    public Mono<Optional<ExecutionState>> findById(String correlationId) {
        String stateKey = stateKeyPrefix + correlationId;
        return redisTemplate.opsForValue().get(stateKey)
                .map(json -> {
                    try {
                        return Optional.of(objectMapper.readValue(json, ExecutionState.class));
                    } catch (JsonProcessingException e) {
                        log.error("[orchestration] Failed to deserialize state: {}", correlationId, e);
                        return Optional.<ExecutionState>empty();
                    }
                })
                .defaultIfEmpty(Optional.empty());
    }

    @Override
    public Mono<Void> updateStatus(String correlationId, ExecutionStatus status) {
        return findById(correlationId)
                .flatMap(opt -> {
                    if (opt.isEmpty()) return Mono.empty();
                    return save(opt.get().withStatus(status));
                });
    }

    @Override
    public Flux<ExecutionState> findByPattern(ExecutionPattern pattern) {
        return scanMetadata()
                .filter(meta -> meta.pattern == pattern)
                .flatMap(meta -> findById(meta.correlationId))
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    @Override
    public Flux<ExecutionState> findByStatus(ExecutionStatus status) {
        return scanMetadata()
                .filter(meta -> meta.status == status)
                .flatMap(meta -> findById(meta.correlationId))
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    @Override
    public Flux<ExecutionState> findInFlight() {
        return scanMetadata()
                .filter(meta -> meta.status.isActive())
                .flatMap(meta -> findById(meta.correlationId))
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    @Override
    public Flux<ExecutionState> findStale(Instant before) {
        return scanMetadata()
                .filter(meta -> meta.status.isActive() && meta.updatedAt != null && meta.updatedAt.isBefore(before))
                .flatMap(meta -> findById(meta.correlationId))
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    @Override
    public Mono<Long> cleanup(Duration olderThan) {
        Instant threshold = Instant.now().minus(olderThan);
        return scanMetadata()
                .filter(meta -> meta.status.isTerminal()
                        && meta.updatedAt != null && meta.updatedAt.isBefore(threshold))
                .flatMap(meta -> {
                    String stateKey = stateKeyPrefix + meta.correlationId;
                    String metaKey = metaKeyPrefix + meta.correlationId;
                    return Mono.when(
                            redisTemplate.delete(stateKey),
                            redisTemplate.delete(metaKey)
                    ).thenReturn(1L);
                })
                .reduce(0L, Long::sum);
    }

    @Override
    public Mono<Boolean> isHealthy() {
        return redisTemplate.opsForValue()
                .set("orchestration:health", "ok", Duration.ofSeconds(10))
                .onErrorReturn(false);
    }

    private Flux<MetaEntry> scanMetadata() {
        String pattern = metaKeyPrefix + "*";
        return redisTemplate.scan(ScanOptions.scanOptions().match(pattern).build())
                .flatMap(metaKey -> redisTemplate.opsForValue().get(metaKey)
                        .map(value -> parseMetaEntry(metaKey, value)))
                .filter(meta -> meta != null)
                .onErrorContinue((err, obj) ->
                        log.warn("[orchestration] Error scanning Redis metadata", err));
    }

    private MetaEntry parseMetaEntry(String metaKey, String value) {
        try {
            String correlationId = metaKey.substring(metaKeyPrefix.length());
            String[] parts = value.split("\\|");
            ExecutionPattern pattern = ExecutionPattern.valueOf(parts[0]);
            ExecutionStatus status = ExecutionStatus.valueOf(parts[1]);
            Instant updatedAt = parts.length > 2 ? Instant.parse(parts[2]) : null;
            return new MetaEntry(correlationId, pattern, status, updatedAt);
        } catch (Exception e) {
            log.warn("[orchestration] Failed to parse meta entry: {}", metaKey, e);
            return null;
        }
    }

    private record MetaEntry(String correlationId, ExecutionPattern pattern,
                              ExecutionStatus status, Instant updatedAt) {
    }
}
