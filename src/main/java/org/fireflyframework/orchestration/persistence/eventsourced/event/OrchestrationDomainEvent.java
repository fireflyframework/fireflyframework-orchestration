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

package org.fireflyframework.orchestration.persistence.eventsourced.event;

import org.fireflyframework.orchestration.core.model.ExecutionPattern;

import java.time.Instant;

/**
 * Sealed interface representing all domain events in the orchestration subsystem.
 *
 * <p>Each event captures a discrete state change in an orchestration execution,
 * spanning all three execution patterns: WORKFLOW, SAGA, and TCC.
 */
public sealed interface OrchestrationDomainEvent permits
        ExecutionStartedEvent, ExecutionCompletedEvent, ExecutionFailedEvent,
        ExecutionCancelledEvent, ExecutionSuspendedEvent, ExecutionResumedEvent,
        StepStartedEvent, StepCompletedEvent, StepFailedEvent, StepSkippedEvent, StepRetriedEvent,
        CompensationStartedEvent, CompensationStepCompletedEvent, CompensationStepFailedEvent,
        PhaseStartedEvent, PhaseCompletedEvent, PhaseFailedEvent,
        SignalReceivedEvent, SignalConsumedEvent, TimerRegisteredEvent, TimerFiredEvent,
        ChildWorkflowSpawnedEvent, ChildWorkflowCompletedEvent,
        SearchAttributeUpdatedEvent, ContinueAsNewEvent,
        CheckpointSavedEvent {

    String correlationId();

    Instant timestamp();

    ExecutionPattern pattern();
}
