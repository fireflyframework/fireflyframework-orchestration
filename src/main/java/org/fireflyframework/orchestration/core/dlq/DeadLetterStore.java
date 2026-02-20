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

import java.util.Optional;

public interface DeadLetterStore {
    Mono<Void> save(DeadLetterEntry entry);
    Mono<Optional<DeadLetterEntry>> findById(String id);
    Flux<DeadLetterEntry> findAll();
    Flux<DeadLetterEntry> findByExecutionName(String executionName);
    Flux<DeadLetterEntry> findByCorrelationId(String correlationId);
    Mono<Void> delete(String id);
    Mono<Long> count();
}
