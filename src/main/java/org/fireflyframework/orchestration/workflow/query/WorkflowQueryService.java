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

package org.fireflyframework.orchestration.workflow.query;

import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provides read-only queries against running or completed workflow instances.
 * Supports built-in queries: status, current step, step history, variables, etc.
 */
@Slf4j
public class WorkflowQueryService {

    private final ExecutionPersistenceProvider persistence;

    public WorkflowQueryService(ExecutionPersistenceProvider persistence) {
        this.persistence = persistence;
    }

    public Mono<Optional<ExecutionStatus>> getStatus(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(ExecutionState::status));
    }

    public Mono<Optional<Map<String, StepStatus>>> getStepStatuses(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(ExecutionState::stepStatuses));
    }

    public Mono<Optional<List<String>>> getCurrentSteps(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(state -> state.stepStatuses().entrySet().stream()
                        .filter(e -> e.getValue() == StepStatus.RUNNING)
                        .map(Map.Entry::getKey)
                        .toList()));
    }

    public Mono<Optional<Map<String, Object>>> getStepResults(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(ExecutionState::stepResults));
    }

    public Mono<Optional<Object>> getStepResult(String correlationId, String stepId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(state -> state.stepResults().get(stepId)));
    }

    public Mono<Optional<Map<String, Object>>> getVariables(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(ExecutionState::variables));
    }

    public Mono<Optional<Object>> getVariable(String correlationId, String key) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(state -> state.variables().get(key)));
    }

    public Mono<Optional<List<List<String>>>> getTopologyLayers(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(ExecutionState::topologyLayers));
    }

    public Mono<Optional<Map<String, String>>> getHeaders(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(ExecutionState::headers));
    }

    public Mono<Optional<String>> getFailureReason(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(ExecutionState::failureReason));
    }
}
