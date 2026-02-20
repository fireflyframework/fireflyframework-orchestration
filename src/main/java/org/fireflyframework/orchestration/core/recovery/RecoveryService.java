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

package org.fireflyframework.orchestration.core.recovery;

import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Slf4j
public class RecoveryService {
    private final ExecutionPersistenceProvider persistence;
    private final OrchestrationEvents events;
    private final Duration staleThreshold;

    public RecoveryService(ExecutionPersistenceProvider persistence, OrchestrationEvents events, Duration staleThreshold) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.events = Objects.requireNonNull(events, "events");
        Objects.requireNonNull(staleThreshold, "staleThreshold must not be null");
        if (staleThreshold.isNegative() || staleThreshold.isZero()) {
            throw new IllegalArgumentException("staleThreshold must be positive, got: " + staleThreshold);
        }
        this.staleThreshold = staleThreshold;
    }

    public Flux<ExecutionState> findStaleExecutions() {
        return persistence.findStale(Instant.now().minus(staleThreshold));
    }

    public Mono<Long> cleanupCompletedExecutions(Duration olderThan) {
        return persistence.cleanup(olderThan)
                .doOnNext(count -> log.info("[recovery] cleaned up {} completed executions older than {}", count, olderThan))
                .doOnError(err -> log.error("[recovery] cleanup failed for duration {}", olderThan, err));
    }
}
