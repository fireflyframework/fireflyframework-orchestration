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

package org.fireflyframework.orchestration.core.rest;

import org.fireflyframework.orchestration.core.dlq.DeadLetterService;
import org.fireflyframework.orchestration.core.rest.dto.DeadLetterEntryDto;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/orchestration/dlq")
public class DeadLetterController {

    private final DeadLetterService deadLetterService;

    public DeadLetterController(DeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @GetMapping
    public Flux<DeadLetterEntryDto> listEntries() {
        return deadLetterService.getAllEntries().map(e -> new DeadLetterEntryDto(
                e.id(), e.executionName(), e.correlationId(), e.pattern(), e.stepId(),
                e.errorMessage(), e.retryCount(), e.createdAt()));
    }

    @DeleteMapping("/{id}")
    public Mono<Void> deleteEntry(@PathVariable String id) {
        return deadLetterService.deleteEntry(id);
    }

    @GetMapping("/count")
    public Mono<Long> count() {
        return deadLetterService.count();
    }
}
