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

package org.fireflyframework.orchestration.core.observability;

import org.fireflyframework.orchestration.core.context.TccPhase;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;

import java.util.Map;

public interface OrchestrationEvents {
    // Lifecycle (all patterns)
    default void onStart(String name, String correlationId, ExecutionPattern pattern) {}
    default void onStepStarted(String name, String correlationId, String stepId) {}
    default void onStepSuccess(String name, String correlationId, String stepId, int attempts, long latencyMs) {}
    default void onStepFailed(String name, String correlationId, String stepId, Throwable error, int attempts) {}
    default void onStepSkipped(String name, String correlationId, String stepId) {}
    default void onCompleted(String name, String correlationId, ExecutionPattern pattern, boolean success, long durationMs) {}

    // Saga compensation
    default void onCompensationStarted(String name, String correlationId) {}
    default void onStepCompensated(String name, String correlationId, String stepId) {}
    default void onStepCompensationFailed(String name, String correlationId, String stepId, Throwable error) {}

    // TCC phases
    default void onPhaseStarted(String name, String correlationId, TccPhase phase) {}
    default void onPhaseCompleted(String name, String correlationId, TccPhase phase, long durationMs) {}
    default void onPhaseFailed(String name, String correlationId, TccPhase phase, Throwable error) {}
    default void onParticipantStarted(String name, String correlationId, String participantId, TccPhase phase) {}
    default void onParticipantSuccess(String name, String correlationId, String participantId, TccPhase phase) {}
    default void onParticipantFailed(String name, String correlationId, String participantId, TccPhase phase, Throwable error) {}

    // Workflow-specific
    default void onWorkflowSuspended(String name, String correlationId, String reason) {}
    default void onWorkflowResumed(String name, String correlationId) {}

    // Composition
    default void onCompositionStarted(String compositionName, String correlationId) {}
    default void onCompositionCompleted(String compositionName, String correlationId, boolean success) {}

    // Signals and Timers
    default void onSignalDelivered(String name, String correlationId, String signalName) {}
    default void onTimerFired(String name, String correlationId, String timerId) {}
    default void onChildWorkflowStarted(String parentName, String parentCorrelationId, String childWorkflowId, String childCorrelationId) {}
    default void onChildWorkflowCompleted(String parentName, String parentCorrelationId, String childCorrelationId, boolean success) {}
    default void onContinueAsNew(String name, String oldCorrelationId, String newCorrelationId) {}

    // Idempotency
    default void onStepSkippedIdempotent(String name, String correlationId, String stepId) {}

    // DLQ
    default void onDeadLettered(String name, String correlationId, String stepId, Throwable error) {}
}
