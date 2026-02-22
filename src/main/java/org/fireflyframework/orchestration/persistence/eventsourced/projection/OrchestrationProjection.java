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

package org.fireflyframework.orchestration.persistence.eventsourced.projection;

import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.persistence.eventsourced.event.*;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Read-side projection that processes domain events to build queryable views
 * of orchestration execution state. Maintains in-memory projected state using
 * thread-safe concurrent data structures.
 */
@Slf4j
public class OrchestrationProjection {

    private final Map<String, ExecutionSummary> summaries = new ConcurrentHashMap<>();

    /**
     * Processes a domain event and updates the projected read-side views.
     */
    public void processEvent(OrchestrationDomainEvent event) {
        String correlationId = event.correlationId();

        switch (event) {
            case ExecutionStartedEvent e -> {
                summaries.put(correlationId, new ExecutionSummary(
                        correlationId, e.executionName(), e.pattern(),
                        ExecutionStatus.RUNNING, new ConcurrentHashMap<>(),
                        e.timestamp(), e.timestamp(), null, 0
                ));
            }
            case ExecutionCompletedEvent e -> {
                updateSummary(correlationId, s -> new ExecutionSummary(
                        s.correlationId, s.executionName, s.pattern,
                        ExecutionStatus.COMPLETED, s.stepStatuses,
                        s.startedAt, e.timestamp(), null, s.eventCount + 1
                ));
            }
            case ExecutionFailedEvent e -> {
                updateSummary(correlationId, s -> new ExecutionSummary(
                        s.correlationId, s.executionName, s.pattern,
                        ExecutionStatus.FAILED, s.stepStatuses,
                        s.startedAt, e.timestamp(), e.error(), s.eventCount + 1
                ));
            }
            case ExecutionCancelledEvent e -> {
                updateSummary(correlationId, s -> new ExecutionSummary(
                        s.correlationId, s.executionName, s.pattern,
                        ExecutionStatus.CANCELLED, s.stepStatuses,
                        s.startedAt, e.timestamp(), e.reason(), s.eventCount + 1
                ));
            }
            case ExecutionSuspendedEvent e -> {
                updateStatus(correlationId, ExecutionStatus.SUSPENDED, e.timestamp());
            }
            case ExecutionResumedEvent e -> {
                updateStatus(correlationId, ExecutionStatus.RUNNING, e.timestamp());
            }
            case StepStartedEvent e -> {
                updateStepStatus(correlationId, e.stepId(), StepStatus.RUNNING, e.timestamp());
            }
            case StepCompletedEvent e -> {
                updateStepStatus(correlationId, e.stepId(), StepStatus.DONE, e.timestamp());
            }
            case StepFailedEvent e -> {
                updateStepStatus(correlationId, e.stepId(), StepStatus.FAILED, e.timestamp());
            }
            case StepSkippedEvent e -> {
                updateStepStatus(correlationId, e.stepId(), StepStatus.SKIPPED, e.timestamp());
            }
            case StepRetriedEvent e -> {
                updateStepStatus(correlationId, e.stepId(), StepStatus.RETRYING, e.timestamp());
            }
            case CompensationStartedEvent e -> {
                updateStatus(correlationId, ExecutionStatus.COMPENSATING, e.timestamp());
            }
            case CompensationStepCompletedEvent e -> {
                updateStepStatus(correlationId, e.stepId(), StepStatus.COMPENSATED, e.timestamp());
            }
            case CompensationStepFailedEvent e -> {
                updateStepStatus(correlationId, e.stepId(), StepStatus.COMPENSATION_FAILED, e.timestamp());
            }
            case PhaseStartedEvent e -> {
                ExecutionStatus phaseStatus = switch (e.phase()) {
                    case TRY -> ExecutionStatus.TRYING;
                    case CONFIRM -> ExecutionStatus.CONFIRMING;
                    case CANCEL -> ExecutionStatus.CANCELING;
                };
                updateStatus(correlationId, phaseStatus, e.timestamp());
            }
            case PhaseCompletedEvent e -> {
                if (e.phase() == org.fireflyframework.orchestration.core.context.TccPhase.CONFIRM) {
                    updateStatus(correlationId, ExecutionStatus.CONFIRMED, e.timestamp());
                } else if (e.phase() == org.fireflyframework.orchestration.core.context.TccPhase.CANCEL) {
                    updateStatus(correlationId, ExecutionStatus.CANCELED, e.timestamp());
                }
            }
            case PhaseFailedEvent e -> {
                updateSummary(correlationId, s -> new ExecutionSummary(
                        s.correlationId, s.executionName, s.pattern,
                        ExecutionStatus.FAILED, s.stepStatuses,
                        s.startedAt, e.timestamp(), e.error(), s.eventCount + 1
                ));
            }
            default -> {
                // Signal, timer, child workflow, search attribute, continue-as-new, checkpoint events
                // simply increment the event count
                incrementEventCount(correlationId, event.timestamp());
            }
        }
    }

    /**
     * Returns the execution summary for a given correlation ID.
     */
    public Optional<ExecutionSummary> getExecutionSummary(String correlationId) {
        return Optional.ofNullable(summaries.get(correlationId));
    }

    /**
     * Finds all execution summaries matching the given status.
     */
    public List<ExecutionSummary> findByStatus(ExecutionStatus status) {
        return summaries.values().stream()
                .filter(s -> s.status == status)
                .collect(Collectors.toList());
    }

    /**
     * Finds all execution summaries matching the given pattern.
     */
    public List<ExecutionSummary> findByPattern(ExecutionPattern pattern) {
        return summaries.values().stream()
                .filter(s -> s.pattern == pattern)
                .collect(Collectors.toList());
    }

    private void updateSummary(String correlationId, java.util.function.Function<ExecutionSummary, ExecutionSummary> updater) {
        summaries.computeIfPresent(correlationId, (k, existing) -> updater.apply(existing));
    }

    private void updateStatus(String correlationId, ExecutionStatus newStatus, Instant timestamp) {
        updateSummary(correlationId, s -> new ExecutionSummary(
                s.correlationId, s.executionName, s.pattern,
                newStatus, s.stepStatuses,
                s.startedAt, timestamp, s.failureReason, s.eventCount + 1
        ));
    }

    private void updateStepStatus(String correlationId, String stepId, StepStatus stepStatus, Instant timestamp) {
        updateSummary(correlationId, s -> {
            s.stepStatuses.put(stepId, stepStatus);
            return new ExecutionSummary(
                    s.correlationId, s.executionName, s.pattern,
                    s.status, s.stepStatuses,
                    s.startedAt, timestamp, s.failureReason, s.eventCount + 1
            );
        });
    }

    private void incrementEventCount(String correlationId, Instant timestamp) {
        updateSummary(correlationId, s -> new ExecutionSummary(
                s.correlationId, s.executionName, s.pattern,
                s.status, s.stepStatuses,
                s.startedAt, timestamp, s.failureReason, s.eventCount + 1
        ));
    }

    /**
     * Read-side view summarizing the current state of an orchestration execution.
     */
    public record ExecutionSummary(
            String correlationId,
            String executionName,
            ExecutionPattern pattern,
            ExecutionStatus status,
            Map<String, StepStatus> stepStatuses,
            Instant startedAt,
            Instant lastUpdated,
            String failureReason,
            int eventCount
    ) {}
}
