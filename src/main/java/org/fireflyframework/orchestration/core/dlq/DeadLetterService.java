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

import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;

public class DeadLetterService {
    private final DeadLetterStore store;
    private final OrchestrationEvents events;

    public DeadLetterService(DeadLetterStore store, OrchestrationEvents events) {
        this.store = store;
        this.events = events;
    }

    public Mono<Void> deadLetter(DeadLetterEntry entry) {
        return store.save(entry)
                .doOnSuccess(v -> events.onDeadLettered(entry.executionName(), entry.correlationId(), entry.stepId(),
                        new RuntimeException(entry.errorMessage())));
    }

    public Flux<DeadLetterEntry> getAllEntries() { return store.findAll(); }
    public Mono<Optional<DeadLetterEntry>> getEntry(String id) { return store.findById(id); }
    public Flux<DeadLetterEntry> getByExecutionName(String name) { return store.findByExecutionName(name); }
    public Flux<DeadLetterEntry> getByCorrelationId(String correlationId) { return store.findByCorrelationId(correlationId); }
    public Mono<Void> deleteEntry(String id) { return store.delete(id); }
    public Mono<Long> count() { return store.count(); }

    public Mono<DeadLetterEntry> markRetried(String id) {
        return store.findById(id)
                .flatMap(opt -> {
                    if (opt.isEmpty()) return Mono.empty();
                    var updated = opt.get().withRetry();
                    return store.save(updated).thenReturn(updated);
                });
    }
}
