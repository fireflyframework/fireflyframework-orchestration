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

package org.fireflyframework.orchestration.persistence.eventsourced;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fireflyframework.eventsourcing.domain.Event;
import org.fireflyframework.eventsourcing.domain.StoredEventEnvelope;
import org.fireflyframework.eventsourcing.store.EventStore;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.persistence.eventsourced.aggregate.OrchestrationAggregate;
import org.fireflyframework.orchestration.persistence.eventsourced.event.*;
import org.fireflyframework.orchestration.persistence.eventsourced.projection.OrchestrationProjection;
import org.fireflyframework.orchestration.persistence.eventsourced.snapshot.OrchestrationSnapshot;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event-sourced implementation of {@link ExecutionPersistenceProvider}.
 *
 * <p>Persists execution state transitions as domain events in the
 * fireflyframework-eventsourcing EventStore. State is reconstructed by
 * replaying events via an {@link OrchestrationAggregate}.
 *
 * <p>Uses {@link OrchestrationSnapshot} for efficient hydration and
 * an {@link OrchestrationProjection} for read-side queries.
 */
@Slf4j
public class EventSourcedPersistenceProvider implements ExecutionPersistenceProvider {

    private static final String AGGREGATE_TYPE = "orchestration-execution";
    private static final String EVENT_TYPE = "ExecutionStateChanged";

    private final EventStore eventStore;
    private final ObjectMapper objectMapper;
    private final OrchestrationProjection projection;
    private final Map<String, OrchestrationSnapshot> snapshots = new ConcurrentHashMap<>();

    public EventSourcedPersistenceProvider(EventStore eventStore, ObjectMapper objectMapper) {
        this(eventStore, objectMapper, new OrchestrationProjection());
    }

    public EventSourcedPersistenceProvider(EventStore eventStore, ObjectMapper objectMapper,
                                           OrchestrationProjection projection) {
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.projection = Objects.requireNonNull(projection, "projection");
        log.info("[orchestration] EventSourcedPersistenceProvider initialized");
    }

    @Override
    public Mono<Void> save(ExecutionState state) {
        UUID aggregateId = toUUID(state.correlationId());
        return eventStore.getAggregateVersion(aggregateId, AGGREGATE_TYPE)
                .defaultIfEmpty(0L)
                .flatMap(currentVersion -> {
                    // Emit domain events based on the state
                    List<OrchestrationDomainEvent> domainEvents = toDomainEvents(state);

                    // Apply domain events to aggregate for projection updates
                    OrchestrationAggregate aggregate = loadAggregateFromSnapshot(state.correlationId());
                    for (OrchestrationDomainEvent domainEvent : domainEvents) {
                        aggregate.raise(domainEvent);
                        projection.processEvent(domainEvent);
                    }

                    // Serialize state as event payload
                    Map<String, Object> payload = serializeState(state);
                    if (payload == null) return Mono.empty();

                    Event event = new ExecutionStateEvent(aggregateId, payload);

                    return eventStore.appendEvents(
                            aggregateId, AGGREGATE_TYPE,
                            List.of(event), currentVersion, Map.of())
                            .then(Mono.<Void>fromRunnable(() -> {
                                aggregate.markEventsCommitted();
                                // Save snapshot for efficient future hydration
                                snapshots.put(state.correlationId(), OrchestrationSnapshot.from(aggregate));
                            }));
                })
                .then()
                .doOnSuccess(v -> log.debug("[orchestration] Saved state as event: {}", state.correlationId()))
                .doOnError(e -> log.error("[orchestration] Failed to save event-sourced state: {}",
                        state.correlationId(), e));
    }

    @Override
    public Mono<Optional<ExecutionState>> findById(String correlationId) {
        UUID aggregateId = toUUID(correlationId);
        return eventStore.loadEventStream(aggregateId, AGGREGATE_TYPE)
                .filter(stream -> !stream.isEmpty())
                .map(stream -> {
                    // Get the last event envelope (most recent state snapshot)
                    StoredEventEnvelope lastEnvelope = stream.getLastEvent();
                    Event lastEvent = lastEnvelope.getEvent();
                    return Optional.ofNullable(deserializeState(lastEvent.getMetadata()));
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
        // Use projection for efficient cross-aggregate queries
        List<OrchestrationProjection.ExecutionSummary> summaries = projection.findByPattern(pattern);
        if (summaries.isEmpty()) {
            log.debug("[orchestration] findByPattern via projection found no results for pattern={}", pattern);
            return Flux.empty();
        }
        return Flux.fromIterable(summaries)
                .flatMap(summary -> findById(summary.correlationId())
                        .flatMapMany(opt -> opt.map(Flux::just).orElse(Flux.empty())));
    }

    @Override
    public Flux<ExecutionState> findByStatus(ExecutionStatus status) {
        // Use projection for efficient cross-aggregate queries
        List<OrchestrationProjection.ExecutionSummary> summaries = projection.findByStatus(status);
        if (summaries.isEmpty()) {
            log.debug("[orchestration] findByStatus via projection found no results for status={}", status);
            return Flux.empty();
        }
        return Flux.fromIterable(summaries)
                .flatMap(summary -> findById(summary.correlationId())
                        .flatMapMany(opt -> opt.map(Flux::just).orElse(Flux.empty())));
    }

    @Override
    public Flux<ExecutionState> findInFlight() {
        log.debug("[orchestration] findInFlight not efficiently supported in event-sourced mode");
        return Flux.empty();
    }

    @Override
    public Flux<ExecutionState> findStale(Instant before) {
        log.debug("[orchestration] findStale not efficiently supported in event-sourced mode");
        return Flux.empty();
    }

    @Override
    public Mono<Long> cleanup(Duration olderThan) {
        // Events are immutable in event sourcing -- cleanup is a no-op
        log.debug("[orchestration] cleanup is a no-op in event-sourced mode (events are immutable)");
        return Mono.just(0L);
    }

    @Override
    public Mono<Boolean> isHealthy() {
        return eventStore.isHealthy();
    }

    /**
     * Returns the projection used by this provider for read-side queries.
     */
    public OrchestrationProjection getProjection() {
        return projection;
    }

    private OrchestrationAggregate loadAggregateFromSnapshot(String correlationId) {
        OrchestrationSnapshot snapshot = snapshots.get(correlationId);
        if (snapshot != null) {
            return snapshot.restore();
        }
        return new OrchestrationAggregate();
    }

    /**
     * Converts an execution state to domain events representing the state change.
     */
    private List<OrchestrationDomainEvent> toDomainEvents(ExecutionState state) {
        List<OrchestrationDomainEvent> events = new ArrayList<>();
        Instant now = state.updatedAt() != null ? state.updatedAt() : Instant.now();

        // Determine the appropriate domain event based on the current status
        if (state.status() != null) {
            switch (state.status()) {
                case RUNNING -> events.add(new ExecutionStartedEvent(
                        state.correlationId(), state.executionName(), state.pattern(), null, now));
                case COMPLETED -> events.add(new ExecutionCompletedEvent(
                        state.correlationId(), state.executionName(), state.pattern(), null,
                        state.startedAt() != null ? Duration.between(state.startedAt(), now) : Duration.ZERO, now));
                case FAILED -> events.add(new ExecutionFailedEvent(
                        state.correlationId(), state.executionName(), state.pattern(),
                        state.failureReason(), null, now));
                case CANCELLED -> events.add(new ExecutionCancelledEvent(
                        state.correlationId(), state.failureReason(), state.pattern(), now));
                case SUSPENDED -> events.add(new ExecutionSuspendedEvent(
                        state.correlationId(), null, state.pattern(), now));
                case COMPENSATING -> events.add(new CompensationStartedEvent(
                        state.correlationId(), state.pattern(), null, now));
                default -> {
                    // For other statuses, emit a generic started event
                }
            }
        }

        return events;
    }

    private UUID toUUID(String correlationId) {
        try {
            return UUID.fromString(correlationId);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(correlationId.getBytes());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> serializeState(ExecutionState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("[orchestration] Failed to serialize execution state", e);
            return null;
        }
    }

    private ExecutionState deserializeState(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            return objectMapper.readValue(json, ExecutionState.class);
        } catch (Exception e) {
            log.error("[orchestration] Failed to deserialize execution state from event payload", e);
            return null;
        }
    }

    /**
     * Simple event implementation for execution state changes.
     * Stores the serialized execution state in metadata.
     */
    private record ExecutionStateEvent(UUID aggregateId, Map<String, Object> stateData) implements Event {
        @Override public UUID getAggregateId() { return aggregateId; }
        @Override public String getEventType() { return EVENT_TYPE; }
        @Override public Map<String, Object> getMetadata() { return stateData; }
    }
}
