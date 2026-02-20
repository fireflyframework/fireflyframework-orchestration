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

import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.rest.dto.ExecutionSummaryDto;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/orchestration")
public class OrchestrationController {

    private final ExecutionPersistenceProvider persistence;

    public OrchestrationController(ExecutionPersistenceProvider persistence) {
        this.persistence = persistence;
    }

    @GetMapping("/executions")
    public Flux<ExecutionSummaryDto> listExecutions(@RequestParam(required = false) ExecutionStatus status) {
        var source = status != null ? persistence.findByStatus(status) : persistence.findInFlight();
        return source.map(s -> new ExecutionSummaryDto(
                s.correlationId(), s.executionName(), s.pattern(), s.status(), s.startedAt(), s.updatedAt()));
    }

    @GetMapping("/executions/{id}")
    public Mono<ExecutionSummaryDto> getExecution(@PathVariable String id) {
        return persistence.findById(id)
                .flatMap(opt -> opt.map(s -> Mono.just(new ExecutionSummaryDto(
                        s.correlationId(), s.executionName(), s.pattern(), s.status(), s.startedAt(), s.updatedAt())))
                        .orElse(Mono.empty()));
    }
}
