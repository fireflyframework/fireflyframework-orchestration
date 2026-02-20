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

package org.fireflyframework.orchestration.workflow.rest;

import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.core.rest.dto.ExecutionSummaryDto;
import org.fireflyframework.orchestration.core.rest.dto.StartExecutionRequest;
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * REST API for workflow lifecycle operations: start, cancel, suspend, resume,
 * list definitions, and query instances.
 */
@RestController
@RequestMapping("/api/orchestration/workflows")
public class WorkflowController {

    private final WorkflowEngine engine;
    private final WorkflowRegistry registry;

    public WorkflowController(WorkflowEngine engine, WorkflowRegistry registry) {
        this.engine = engine;
        this.registry = registry;
    }

    // ── Definition Endpoints ──────────────────────────────────────

    @GetMapping
    public Mono<List<WorkflowSummary>> listWorkflows() {
        return Mono.fromCallable(() ->
                registry.getAll().stream()
                        .map(d -> new WorkflowSummary(d.workflowId(), d.name(), d.version(),
                                d.description(), d.steps().size()))
                        .toList());
    }

    @GetMapping("/{workflowId}")
    public Mono<WorkflowDetail> getWorkflow(@PathVariable String workflowId) {
        return Mono.fromCallable(() -> {
            var def = registry.getWorkflow(workflowId);
            var steps = def.steps().stream()
                    .map(s -> new StepSummary(s.stepId(), s.name(), s.dependsOn(), s.order()))
                    .toList();
            return new WorkflowDetail(def.workflowId(), def.name(), def.version(),
                    def.description(), def.triggerMode().name(), def.timeoutMs(), steps);
        });
    }

    @GetMapping("/{workflowId}/topology")
    public Mono<List<List<String>>> getTopology(@PathVariable String workflowId) {
        return Mono.fromCallable(() -> {
            var def = registry.getWorkflow(workflowId);
            return org.fireflyframework.orchestration.core.topology.TopologyBuilder.buildLayers(
                    def.steps(),
                    org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition::stepId,
                    org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition::dependsOn);
        });
    }

    // ── Lifecycle Endpoints ───────────────────────────────────────

    @PostMapping("/{workflowId}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<ExecutionSummaryDto> startWorkflow(@PathVariable String workflowId,
                                                    @RequestBody(required = false) StartExecutionRequest request) {
        var req = request != null ? request : new StartExecutionRequest(Map.of(), null, "api", false);
        return engine.startWorkflow(workflowId, req.input(), req.correlationId(), req.triggeredBy(), req.dryRun())
                .map(WorkflowController::toDto);
    }

    @PostMapping("/instances/{correlationId}/cancel")
    public Mono<ExecutionSummaryDto> cancelWorkflow(@PathVariable String correlationId) {
        return engine.cancelWorkflow(correlationId).map(WorkflowController::toDto);
    }

    @PostMapping("/instances/{correlationId}/suspend")
    public Mono<ExecutionSummaryDto> suspendWorkflow(@PathVariable String correlationId,
                                                      @RequestParam(required = false) String reason) {
        return engine.suspendWorkflow(correlationId, reason).map(WorkflowController::toDto);
    }

    @PostMapping("/instances/{correlationId}/resume")
    public Mono<ExecutionSummaryDto> resumeWorkflow(@PathVariable String correlationId) {
        return engine.resumeWorkflow(correlationId).map(WorkflowController::toDto);
    }

    // ── Instance Query Endpoints ──────────────────────────────────

    @GetMapping("/instances/{correlationId}")
    public Mono<ExecutionSummaryDto> getInstance(@PathVariable String correlationId) {
        return engine.findByCorrelationId(correlationId)
                .flatMap(opt -> opt.map(s -> Mono.just(toDto(s))).orElse(Mono.empty()));
    }

    @GetMapping("/instances")
    public Flux<ExecutionSummaryDto> listInstances(@RequestParam(required = false) ExecutionStatus status) {
        if (status == null) status = ExecutionStatus.RUNNING;
        return engine.findByStatus(status).map(WorkflowController::toDto);
    }

    // ── DTOs ──────────────────────────────────────────────────────

    public record WorkflowSummary(String workflowId, String name, String version,
                                   String description, int stepCount) {}

    public record WorkflowDetail(String workflowId, String name, String version,
                                  String description, String triggerMode,
                                  long timeoutMs, List<StepSummary> steps) {}

    public record StepSummary(String stepId, String name, List<String> dependsOn, int order) {}

    private static ExecutionSummaryDto toDto(ExecutionState s) {
        return new ExecutionSummaryDto(s.correlationId(), s.executionName(),
                s.pattern(), s.status(), s.startedAt(), s.updatedAt());
    }
}
