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

package org.fireflyframework.orchestration.persistence.eventsourced.snapshot;

import org.fireflyframework.orchestration.core.context.TccPhase;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.persistence.eventsourced.aggregate.OrchestrationAggregate;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable snapshot of an {@link OrchestrationAggregate}'s full state,
 * enabling efficient hydration without replaying all events from the beginning.
 */
public record OrchestrationSnapshot(
        String correlationId,
        String executionName,
        ExecutionPattern pattern,
        ExecutionStatus status,
        Map<String, StepStatus> stepStatuses,
        Map<String, Object> stepResults,
        Map<String, Integer> stepAttempts,
        Map<String, Long> stepLatenciesMs,
        Map<String, Object> variables,
        Map<String, String> headers,
        String failureReason,
        Instant startedAt,
        Instant updatedAt,
        TccPhase currentPhase
) {
    public OrchestrationSnapshot {
        stepStatuses = stepStatuses != null ? Map.copyOf(stepStatuses) : Map.of();
        stepResults = stepResults != null ? Map.copyOf(stepResults) : Map.of();
        stepAttempts = stepAttempts != null ? Map.copyOf(stepAttempts) : Map.of();
        stepLatenciesMs = stepLatenciesMs != null ? Map.copyOf(stepLatenciesMs) : Map.of();
        variables = variables != null ? Map.copyOf(variables) : Map.of();
        headers = headers != null ? Map.copyOf(headers) : Map.of();
    }

    /**
     * Creates a snapshot from the current state of an aggregate.
     */
    public static OrchestrationSnapshot from(OrchestrationAggregate aggregate) {
        return new OrchestrationSnapshot(
                aggregate.getCorrelationId(),
                aggregate.getExecutionName(),
                aggregate.getPattern(),
                aggregate.getStatus(),
                aggregate.getStepStatuses(),
                aggregate.getStepResults(),
                aggregate.getStepAttempts(),
                aggregate.getStepLatenciesMs(),
                aggregate.getVariables(),
                aggregate.getHeaders(),
                aggregate.getFailureReason(),
                aggregate.getStartedAt(),
                aggregate.getUpdatedAt(),
                aggregate.getCurrentPhase()
        );
    }

    /**
     * Restores an aggregate from this snapshot.
     */
    public OrchestrationAggregate restore() {
        var aggregate = new OrchestrationAggregate();
        aggregate.setCorrelationId(correlationId);
        aggregate.setExecutionName(executionName);
        aggregate.setPattern(pattern);
        aggregate.setStatus(status);
        aggregate.stepStatusesMutable().putAll(stepStatuses);
        aggregate.stepResultsMutable().putAll(stepResults);
        aggregate.stepAttemptsMutable().putAll(stepAttempts);
        aggregate.stepLatenciesMsMutable().putAll(stepLatenciesMs);
        aggregate.variablesMutable().putAll(variables);
        aggregate.headersMutable().putAll(headers);
        aggregate.setFailureReason(failureReason);
        aggregate.setStartedAt(startedAt);
        aggregate.setUpdatedAt(updatedAt);
        aggregate.setCurrentPhase(currentPhase);
        return aggregate;
    }
}
