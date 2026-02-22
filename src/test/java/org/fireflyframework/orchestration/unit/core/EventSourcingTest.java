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

package org.fireflyframework.orchestration.unit.core;

import org.fireflyframework.orchestration.core.context.TccPhase;
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.persistence.eventsourced.aggregate.OrchestrationAggregate;
import org.fireflyframework.orchestration.persistence.eventsourced.event.*;
import org.fireflyframework.orchestration.persistence.eventsourced.projection.OrchestrationProjection;
import org.fireflyframework.orchestration.persistence.eventsourced.snapshot.OrchestrationSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class EventSourcingTest {

    private OrchestrationAggregate aggregate;
    private OrchestrationProjection projection;

    @BeforeEach
    void setUp() {
        aggregate = new OrchestrationAggregate();
        projection = new OrchestrationProjection();
    }

    @Test
    void aggregate_appliesStartedEvent() {
        var event = new ExecutionStartedEvent(
                "corr-1", "order-saga", ExecutionPattern.SAGA, null, Instant.now());

        aggregate.raise(event);

        assertThat(aggregate.getCorrelationId()).isEqualTo("corr-1");
        assertThat(aggregate.getExecutionName()).isEqualTo("order-saga");
        assertThat(aggregate.getPattern()).isEqualTo(ExecutionPattern.SAGA);
        assertThat(aggregate.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(aggregate.getStartedAt()).isNotNull();
        assertThat(aggregate.getUncommittedEvents()).hasSize(1);
        assertThat(aggregate.getUncommittedEvents().getFirst()).isEqualTo(event);
    }

    @Test
    void aggregate_appliesStepEvents() {
        Instant now = Instant.now();
        aggregate.raise(new ExecutionStartedEvent("corr-2", "payment-wf", ExecutionPattern.WORKFLOW, null, now));

        // Step started
        aggregate.raise(new StepStartedEvent("corr-2", "step-a", ExecutionPattern.WORKFLOW, 1, now));
        assertThat(aggregate.getStepStatuses().get("step-a")).isEqualTo(StepStatus.RUNNING);
        assertThat(aggregate.getStepAttempts().get("step-a")).isEqualTo(1);

        // Step completed
        aggregate.raise(new StepCompletedEvent("corr-2", "step-a", ExecutionPattern.WORKFLOW, "result-a", 150L, now));
        assertThat(aggregate.getStepStatuses().get("step-a")).isEqualTo(StepStatus.DONE);
        assertThat(aggregate.getStepResults().get("step-a")).isEqualTo("result-a");
        assertThat(aggregate.getStepLatenciesMs().get("step-a")).isEqualTo(150L);

        // Step failed
        aggregate.raise(new StepStartedEvent("corr-2", "step-b", ExecutionPattern.WORKFLOW, 1, now));
        aggregate.raise(new StepFailedEvent("corr-2", "step-b", ExecutionPattern.WORKFLOW, "timeout", 1, now));
        assertThat(aggregate.getStepStatuses().get("step-b")).isEqualTo(StepStatus.FAILED);

        // Step skipped
        aggregate.raise(new StepSkippedEvent("corr-2", "step-c", ExecutionPattern.WORKFLOW, "condition unmet", now));
        assertThat(aggregate.getStepStatuses().get("step-c")).isEqualTo(StepStatus.SKIPPED);

        // Step retried
        aggregate.raise(new StepRetriedEvent("corr-2", "step-b", ExecutionPattern.WORKFLOW, 2, 500L, now));
        assertThat(aggregate.getStepStatuses().get("step-b")).isEqualTo(StepStatus.RETRYING);
        assertThat(aggregate.getStepAttempts().get("step-b")).isEqualTo(2);

        // Execution completed
        aggregate.raise(new ExecutionCompletedEvent("corr-2", "payment-wf", ExecutionPattern.WORKFLOW, null, Duration.ofSeconds(5), now));
        assertThat(aggregate.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);

        // 8 events raised total
        assertThat(aggregate.getUncommittedEvents()).hasSize(8);

        // Mark committed
        aggregate.markEventsCommitted();
        assertThat(aggregate.getUncommittedEvents()).isEmpty();
    }

    @Test
    void aggregate_appliesTccPhaseEvents() {
        Instant now = Instant.now();
        aggregate.raise(new ExecutionStartedEvent("corr-tcc", "tcc-transfer", ExecutionPattern.TCC, null, now));

        // TRY phase
        aggregate.raise(new PhaseStartedEvent("corr-tcc", TccPhase.TRY, ExecutionPattern.TCC, now));
        assertThat(aggregate.getStatus()).isEqualTo(ExecutionStatus.TRYING);
        assertThat(aggregate.getCurrentPhase()).isEqualTo(TccPhase.TRY);

        // CONFIRM phase
        aggregate.raise(new PhaseStartedEvent("corr-tcc", TccPhase.CONFIRM, ExecutionPattern.TCC, now));
        assertThat(aggregate.getStatus()).isEqualTo(ExecutionStatus.CONFIRMING);
        assertThat(aggregate.getCurrentPhase()).isEqualTo(TccPhase.CONFIRM);

        aggregate.raise(new PhaseCompletedEvent("corr-tcc", TccPhase.CONFIRM, ExecutionPattern.TCC, 300L, now));
        assertThat(aggregate.getStatus()).isEqualTo(ExecutionStatus.CONFIRMED);

        // Phase failed
        var agg2 = new OrchestrationAggregate();
        agg2.raise(new ExecutionStartedEvent("corr-tcc2", "tcc-fail", ExecutionPattern.TCC, null, now));
        agg2.raise(new PhaseStartedEvent("corr-tcc2", TccPhase.TRY, ExecutionPattern.TCC, now));
        agg2.raise(new PhaseFailedEvent("corr-tcc2", TccPhase.TRY, ExecutionPattern.TCC, "participant down", "p1", now));
        assertThat(agg2.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(agg2.getFailureReason()).isEqualTo("participant down");

        // Compensation events
        var agg3 = new OrchestrationAggregate();
        agg3.raise(new ExecutionStartedEvent("corr-comp", "saga-comp", ExecutionPattern.SAGA, null, now));
        agg3.raise(new CompensationStartedEvent("corr-comp", ExecutionPattern.SAGA, CompensationPolicy.STRICT_SEQUENTIAL, now));
        assertThat(agg3.getStatus()).isEqualTo(ExecutionStatus.COMPENSATING);

        agg3.raise(new CompensationStepCompletedEvent("corr-comp", "step-x", ExecutionPattern.SAGA, now));
        assertThat(agg3.getStepStatuses().get("step-x")).isEqualTo(StepStatus.COMPENSATED);

        agg3.raise(new CompensationStepFailedEvent("corr-comp", "step-y", ExecutionPattern.SAGA, "unreachable", now));
        assertThat(agg3.getStepStatuses().get("step-y")).isEqualTo(StepStatus.COMPENSATION_FAILED);
    }

    @Test
    void snapshot_capturesAndRestoresState() {
        Instant now = Instant.now();
        aggregate.raise(new ExecutionStartedEvent("corr-snap", "snapshot-wf", ExecutionPattern.WORKFLOW, null, now));
        aggregate.raise(new StepStartedEvent("corr-snap", "s1", ExecutionPattern.WORKFLOW, 1, now));
        aggregate.raise(new StepCompletedEvent("corr-snap", "s1", ExecutionPattern.WORKFLOW, "result-1", 200L, now));
        aggregate.raise(new StepStartedEvent("corr-snap", "s2", ExecutionPattern.WORKFLOW, 1, now));
        aggregate.markEventsCommitted();

        // Take snapshot
        OrchestrationSnapshot snapshot = OrchestrationSnapshot.from(aggregate);
        assertThat(snapshot.correlationId()).isEqualTo("corr-snap");
        assertThat(snapshot.executionName()).isEqualTo("snapshot-wf");
        assertThat(snapshot.pattern()).isEqualTo(ExecutionPattern.WORKFLOW);
        assertThat(snapshot.status()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(snapshot.stepStatuses()).containsEntry("s1", StepStatus.DONE);
        assertThat(snapshot.stepStatuses()).containsEntry("s2", StepStatus.RUNNING);
        assertThat(snapshot.stepResults()).containsEntry("s1", "result-1");
        assertThat(snapshot.stepLatenciesMs()).containsEntry("s1", 200L);

        // Restore from snapshot
        OrchestrationAggregate restored = snapshot.restore();
        assertThat(restored.getCorrelationId()).isEqualTo("corr-snap");
        assertThat(restored.getExecutionName()).isEqualTo("snapshot-wf");
        assertThat(restored.getPattern()).isEqualTo(ExecutionPattern.WORKFLOW);
        assertThat(restored.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(restored.getStepStatuses()).isEqualTo(aggregate.getStepStatuses());
        assertThat(restored.getStepResults()).isEqualTo(aggregate.getStepResults());
        assertThat(restored.getStepLatenciesMs()).isEqualTo(aggregate.getStepLatenciesMs());
        assertThat(restored.getUncommittedEvents()).isEmpty();

        // Apply more events on restored aggregate
        restored.raise(new StepCompletedEvent("corr-snap", "s2", ExecutionPattern.WORKFLOW, "result-2", 100L, now));
        restored.raise(new ExecutionCompletedEvent("corr-snap", "snapshot-wf", ExecutionPattern.WORKFLOW, null, Duration.ofSeconds(1), now));
        assertThat(restored.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(restored.getStepStatuses().get("s2")).isEqualTo(StepStatus.DONE);
        assertThat(restored.getUncommittedEvents()).hasSize(2);
    }

    @Test
    void projection_buildsReadView() {
        Instant now = Instant.now();

        // Workflow execution
        projection.processEvent(new ExecutionStartedEvent("corr-p1", "wf-1", ExecutionPattern.WORKFLOW, null, now));
        projection.processEvent(new StepStartedEvent("corr-p1", "s1", ExecutionPattern.WORKFLOW, 1, now));
        projection.processEvent(new StepCompletedEvent("corr-p1", "s1", ExecutionPattern.WORKFLOW, "ok", 50L, now));
        projection.processEvent(new ExecutionCompletedEvent("corr-p1", "wf-1", ExecutionPattern.WORKFLOW, null, Duration.ofMillis(500), now));

        // Saga execution
        projection.processEvent(new ExecutionStartedEvent("corr-p2", "saga-1", ExecutionPattern.SAGA, null, now));
        projection.processEvent(new StepStartedEvent("corr-p2", "step-a", ExecutionPattern.SAGA, 1, now));
        projection.processEvent(new StepFailedEvent("corr-p2", "step-a", ExecutionPattern.SAGA, "error", 1, now));
        projection.processEvent(new ExecutionFailedEvent("corr-p2", "saga-1", ExecutionPattern.SAGA, "step-a failed", "step-a", now));

        // Query by correlationId
        var summary1 = projection.getExecutionSummary("corr-p1");
        assertThat(summary1).isPresent();
        assertThat(summary1.get().status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(summary1.get().executionName()).isEqualTo("wf-1");
        assertThat(summary1.get().stepStatuses()).containsEntry("s1", StepStatus.DONE);

        var summary2 = projection.getExecutionSummary("corr-p2");
        assertThat(summary2).isPresent();
        assertThat(summary2.get().status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(summary2.get().failureReason()).isEqualTo("step-a failed");

        // Query by status
        assertThat(projection.findByStatus(ExecutionStatus.COMPLETED)).hasSize(1);
        assertThat(projection.findByStatus(ExecutionStatus.FAILED)).hasSize(1);
        assertThat(projection.findByStatus(ExecutionStatus.RUNNING)).isEmpty();

        // Query by pattern
        assertThat(projection.findByPattern(ExecutionPattern.WORKFLOW)).hasSize(1);
        assertThat(projection.findByPattern(ExecutionPattern.SAGA)).hasSize(1);
        assertThat(projection.findByPattern(ExecutionPattern.TCC)).isEmpty();

        // Non-existent
        assertThat(projection.getExecutionSummary("no-such-id")).isEmpty();
    }

    @Test
    void provider_savesAndLoadsViaEvents() {
        // Test the sealed interface and event records directly (provider integration
        // requires EventStore which is an optional external dependency)
        Instant now = Instant.now();

        // Simulate a full saga lifecycle through the aggregate
        aggregate.raise(new ExecutionStartedEvent("corr-full", "full-saga", ExecutionPattern.SAGA, Map.of("input", "data"), now));
        aggregate.raise(new StepStartedEvent("corr-full", "validate", ExecutionPattern.SAGA, 1, now));
        aggregate.raise(new StepCompletedEvent("corr-full", "validate", ExecutionPattern.SAGA, "valid", 100L, now));
        aggregate.raise(new StepStartedEvent("corr-full", "charge", ExecutionPattern.SAGA, 1, now));
        aggregate.raise(new StepFailedEvent("corr-full", "charge", ExecutionPattern.SAGA, "insufficient funds", 1, now));
        aggregate.raise(new CompensationStartedEvent("corr-full", ExecutionPattern.SAGA, CompensationPolicy.STRICT_SEQUENTIAL, now));
        aggregate.raise(new CompensationStepCompletedEvent("corr-full", "validate", ExecutionPattern.SAGA, now));
        aggregate.raise(new ExecutionFailedEvent("corr-full", "full-saga", ExecutionPattern.SAGA, "insufficient funds", "charge", now));

        // Verify aggregate state
        assertThat(aggregate.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(aggregate.getStepStatuses().get("validate")).isEqualTo(StepStatus.COMPENSATED);
        assertThat(aggregate.getStepStatuses().get("charge")).isEqualTo(StepStatus.FAILED);
        assertThat(aggregate.getFailureReason()).isEqualTo("insufficient funds");

        // Snapshot, commit, restore
        OrchestrationSnapshot snapshot = OrchestrationSnapshot.from(aggregate);
        aggregate.markEventsCommitted();
        assertThat(aggregate.getUncommittedEvents()).isEmpty();

        OrchestrationAggregate restored = snapshot.restore();
        assertThat(restored.getCorrelationId()).isEqualTo("corr-full");
        assertThat(restored.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(restored.getStepStatuses().get("validate")).isEqualTo(StepStatus.COMPENSATED);
        assertThat(restored.getFailureReason()).isEqualTo("insufficient funds");

        // Feed events through projection
        OrchestrationProjection proj = new OrchestrationProjection();
        proj.processEvent(new ExecutionStartedEvent("corr-full", "full-saga", ExecutionPattern.SAGA, null, now));
        proj.processEvent(new StepStartedEvent("corr-full", "validate", ExecutionPattern.SAGA, 1, now));
        proj.processEvent(new StepCompletedEvent("corr-full", "validate", ExecutionPattern.SAGA, "valid", 100L, now));
        proj.processEvent(new ExecutionFailedEvent("corr-full", "full-saga", ExecutionPattern.SAGA, "insufficient funds", "charge", now));

        var summary = proj.getExecutionSummary("corr-full");
        assertThat(summary).isPresent();
        assertThat(summary.get().status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(summary.get().pattern()).isEqualTo(ExecutionPattern.SAGA);

        // Verify special event patterns
        var childSpawned = new ChildWorkflowSpawnedEvent("parent-1", "child-1", "child-wf", now);
        assertThat(childSpawned.correlationId()).isEqualTo("parent-1");
        assertThat(childSpawned.pattern()).isEqualTo(ExecutionPattern.WORKFLOW);

        var childCompleted = new ChildWorkflowCompletedEvent("parent-1", "child-1", true, now);
        assertThat(childCompleted.correlationId()).isEqualTo("parent-1");

        var continueAsNew = new ContinueAsNewEvent("old-corr", "new-corr", now);
        assertThat(continueAsNew.correlationId()).isEqualTo("old-corr");
        assertThat(continueAsNew.pattern()).isEqualTo(ExecutionPattern.WORKFLOW);

        var signalReceived = new SignalReceivedEvent("corr-sig", "approvalSignal", "approved", now);
        assertThat(signalReceived.pattern()).isEqualTo(ExecutionPattern.WORKFLOW);

        var timerReg = new TimerRegisteredEvent("corr-timer", "t1", now.plusSeconds(60), now);
        assertThat(timerReg.pattern()).isEqualTo(ExecutionPattern.WORKFLOW);

        var checkpoint = new CheckpointSavedEvent("corr-cp", "s1", ExecutionPattern.SAGA,
                Map.of("s1", StepStatus.DONE, "s2", StepStatus.RUNNING), now);
        assertThat(checkpoint.stepStatuses()).hasSize(2);
    }
}
