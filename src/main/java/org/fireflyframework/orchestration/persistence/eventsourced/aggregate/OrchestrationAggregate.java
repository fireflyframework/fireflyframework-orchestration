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

package org.fireflyframework.orchestration.persistence.eventsourced.aggregate;

import org.fireflyframework.orchestration.core.context.TccPhase;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.persistence.eventsourced.event.*;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Event-sourced aggregate that accumulates orchestration execution state
 * by applying domain events. The aggregate maintains the full execution state
 * including step statuses, step results, step attempts, variables, and headers.
 *
 * <p>Uncommitted events are tracked for persistence and can be flushed
 * via {@link #markEventsCommitted()}.
 */
@Slf4j
public class OrchestrationAggregate {

    private String correlationId;
    private String executionName;
    private ExecutionPattern pattern;
    private ExecutionStatus status;
    private final Map<String, StepStatus> stepStatuses = new ConcurrentHashMap<>();
    private final Map<String, Object> stepResults = new ConcurrentHashMap<>();
    private final Map<String, Integer> stepAttempts = new ConcurrentHashMap<>();
    private final Map<String, Long> stepLatenciesMs = new ConcurrentHashMap<>();
    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private final Map<String, String> headers = new ConcurrentHashMap<>();
    private String failureReason;
    private Instant startedAt;
    private Instant updatedAt;
    private TccPhase currentPhase;

    private final List<OrchestrationDomainEvent> uncommittedEvents = new CopyOnWriteArrayList<>();

    public OrchestrationAggregate() {
        this.status = ExecutionStatus.PENDING;
    }

    /**
     * Raises a domain event: adds it to the uncommitted list and applies it to the aggregate state.
     */
    public void raise(OrchestrationDomainEvent event) {
        uncommittedEvents.add(event);
        apply(event);
    }

    /**
     * Applies a domain event to the aggregate state using pattern matching on the sealed type.
     */
    public void apply(OrchestrationDomainEvent event) {
        updatedAt = event.timestamp();

        switch (event) {
            case ExecutionStartedEvent e -> {
                this.correlationId = e.correlationId();
                this.executionName = e.executionName();
                this.pattern = e.pattern();
                this.status = ExecutionStatus.RUNNING;
                this.startedAt = e.timestamp();
            }
            case ExecutionCompletedEvent e -> {
                this.status = ExecutionStatus.COMPLETED;
            }
            case ExecutionFailedEvent e -> {
                this.status = ExecutionStatus.FAILED;
                this.failureReason = e.error();
            }
            case ExecutionCancelledEvent e -> {
                this.status = ExecutionStatus.CANCELLED;
                this.failureReason = e.reason();
            }
            case ExecutionSuspendedEvent e -> {
                this.status = ExecutionStatus.SUSPENDED;
            }
            case ExecutionResumedEvent e -> {
                this.status = ExecutionStatus.RUNNING;
            }
            case StepStartedEvent e -> {
                stepStatuses.put(e.stepId(), StepStatus.RUNNING);
                stepAttempts.put(e.stepId(), e.attempt());
            }
            case StepCompletedEvent e -> {
                stepStatuses.put(e.stepId(), StepStatus.DONE);
                stepResults.put(e.stepId(), e.result());
                stepLatenciesMs.put(e.stepId(), e.latencyMs());
            }
            case StepFailedEvent e -> {
                stepStatuses.put(e.stepId(), StepStatus.FAILED);
                stepAttempts.put(e.stepId(), e.attempt());
            }
            case StepSkippedEvent e -> {
                stepStatuses.put(e.stepId(), StepStatus.SKIPPED);
            }
            case StepRetriedEvent e -> {
                stepStatuses.put(e.stepId(), StepStatus.RETRYING);
                stepAttempts.put(e.stepId(), e.attempt());
            }
            case CompensationStartedEvent e -> {
                this.status = ExecutionStatus.COMPENSATING;
            }
            case CompensationStepCompletedEvent e -> {
                stepStatuses.put(e.stepId(), StepStatus.COMPENSATED);
            }
            case CompensationStepFailedEvent e -> {
                stepStatuses.put(e.stepId(), StepStatus.COMPENSATION_FAILED);
            }
            case PhaseStartedEvent e -> {
                this.currentPhase = e.phase();
                this.status = switch (e.phase()) {
                    case TRY -> ExecutionStatus.TRYING;
                    case CONFIRM -> ExecutionStatus.CONFIRMING;
                    case CANCEL -> ExecutionStatus.CANCELING;
                };
            }
            case PhaseCompletedEvent e -> {
                if (e.phase() == TccPhase.CONFIRM) {
                    this.status = ExecutionStatus.CONFIRMED;
                } else if (e.phase() == TccPhase.CANCEL) {
                    this.status = ExecutionStatus.CANCELED;
                }
            }
            case PhaseFailedEvent e -> {
                this.status = ExecutionStatus.FAILED;
                this.failureReason = e.error();
            }
            case SignalReceivedEvent e -> {
                variables.put("signal." + e.signalName(), e.payload());
            }
            case SignalConsumedEvent e -> {
                // Track that a signal was consumed by a step
                variables.put("signal." + e.signalName() + ".consumed", true);
            }
            case TimerRegisteredEvent e -> {
                variables.put("timer." + e.timerId(), e.fireAt());
            }
            case TimerFiredEvent e -> {
                variables.put("timer." + e.timerId() + ".fired", true);
            }
            case ChildWorkflowSpawnedEvent e -> {
                variables.put("child." + e.childCorrelationId(), e.childWorkflowId());
            }
            case ChildWorkflowCompletedEvent e -> {
                variables.put("child." + e.childCorrelationId() + ".completed", e.success());
            }
            case SearchAttributeUpdatedEvent e -> {
                variables.put("search." + e.key(), e.value());
            }
            case ContinueAsNewEvent e -> {
                variables.put("continueAsNew.newCorrelationId", e.newCorrelationId());
            }
            case CheckpointSavedEvent e -> {
                stepStatuses.putAll(e.stepStatuses());
            }
        }
    }

    /**
     * Returns the list of uncommitted events (events raised but not yet persisted).
     */
    public List<OrchestrationDomainEvent> getUncommittedEvents() {
        return List.copyOf(uncommittedEvents);
    }

    /**
     * Clears the uncommitted events list after successful persistence.
     */
    public void markEventsCommitted() {
        uncommittedEvents.clear();
    }

    // --- Accessors ---

    public String getCorrelationId() { return correlationId; }
    public String getExecutionName() { return executionName; }
    public ExecutionPattern getPattern() { return pattern; }
    public ExecutionStatus getStatus() { return status; }
    public Map<String, StepStatus> getStepStatuses() { return Collections.unmodifiableMap(stepStatuses); }
    public Map<String, Object> getStepResults() { return Collections.unmodifiableMap(stepResults); }
    public Map<String, Integer> getStepAttempts() { return Collections.unmodifiableMap(stepAttempts); }
    public Map<String, Long> getStepLatenciesMs() { return Collections.unmodifiableMap(stepLatenciesMs); }
    public Map<String, Object> getVariables() { return Collections.unmodifiableMap(variables); }
    public Map<String, String> getHeaders() { return Collections.unmodifiableMap(headers); }
    public String getFailureReason() { return failureReason; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public TccPhase getCurrentPhase() { return currentPhase; }

    // --- Mutators for snapshot restoration ---

    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public void setExecutionName(String executionName) { this.executionName = executionName; }
    public void setPattern(ExecutionPattern pattern) { this.pattern = pattern; }
    public void setStatus(ExecutionStatus status) { this.status = status; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setCurrentPhase(TccPhase currentPhase) { this.currentPhase = currentPhase; }
    public Map<String, StepStatus> stepStatusesMutable() { return stepStatuses; }
    public Map<String, Object> stepResultsMutable() { return stepResults; }
    public Map<String, Integer> stepAttemptsMutable() { return stepAttempts; }
    public Map<String, Long> stepLatenciesMsMutable() { return stepLatenciesMs; }
    public Map<String, Object> variablesMutable() { return variables; }
    public Map<String, String> headersMutable() { return headers; }
}
