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

package org.fireflyframework.orchestration.core.persistence;

import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.StepStatus;

import java.time.Instant;
import java.util.*;

public record ExecutionState(
        String correlationId,
        String executionName,
        ExecutionPattern pattern,
        ExecutionStatus status,
        Map<String, Object> stepResults,
        Map<String, StepStatus> stepStatuses,
        Map<String, Integer> stepAttempts,
        Map<String, Long> stepLatenciesMs,
        Map<String, Object> variables,
        Map<String, String> headers,
        Set<String> idempotencyKeys,
        List<List<String>> topologyLayers,
        String failureReason,
        Instant startedAt,
        Instant updatedAt
) {
    public ExecutionState {
        stepResults = stepResults != null ? Map.copyOf(stepResults) : Map.of();
        stepStatuses = stepStatuses != null ? Map.copyOf(stepStatuses) : Map.of();
        stepAttempts = stepAttempts != null ? Map.copyOf(stepAttempts) : Map.of();
        stepLatenciesMs = stepLatenciesMs != null ? Map.copyOf(stepLatenciesMs) : Map.of();
        variables = variables != null ? Map.copyOf(variables) : Map.of();
        headers = headers != null ? Map.copyOf(headers) : Map.of();
        idempotencyKeys = idempotencyKeys != null ? Set.copyOf(idempotencyKeys) : Set.of();
        topologyLayers = topologyLayers != null ? List.copyOf(topologyLayers) : List.of();
    }

    public ExecutionState withStatus(ExecutionStatus newStatus) {
        return new ExecutionState(correlationId, executionName, pattern, newStatus,
                stepResults, stepStatuses, stepAttempts, stepLatenciesMs,
                variables, headers, idempotencyKeys, topologyLayers,
                failureReason, startedAt, Instant.now());
    }

    public ExecutionState withFailure(String reason) {
        return new ExecutionState(correlationId, executionName, pattern, ExecutionStatus.FAILED,
                stepResults, stepStatuses, stepAttempts, stepLatenciesMs,
                variables, headers, idempotencyKeys, topologyLayers,
                reason, startedAt, Instant.now());
    }

    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }
}
