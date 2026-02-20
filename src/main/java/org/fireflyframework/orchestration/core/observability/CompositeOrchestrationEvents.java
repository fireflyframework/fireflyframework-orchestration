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
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Consumer;

@Slf4j
public class CompositeOrchestrationEvents implements OrchestrationEvents {
    private final List<OrchestrationEvents> delegates;

    public CompositeOrchestrationEvents(List<OrchestrationEvents> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    private void safeForEach(Consumer<OrchestrationEvents> action) {
        for (var d : delegates) {
            try { action.accept(d); }
            catch (Exception e) { log.warn("[composite-events] Delegate {} failed: {}", d.getClass().getSimpleName(), e.getMessage()); }
        }
    }

    @Override public void onStart(String name, String correlationId, ExecutionPattern pattern) { safeForEach(d -> d.onStart(name, correlationId, pattern)); }
    @Override public void onStepStarted(String name, String correlationId, String stepId) { safeForEach(d -> d.onStepStarted(name, correlationId, stepId)); }
    @Override public void onStepSuccess(String name, String correlationId, String stepId, int attempts, long latencyMs) { safeForEach(d -> d.onStepSuccess(name, correlationId, stepId, attempts, latencyMs)); }
    @Override public void onStepFailed(String name, String correlationId, String stepId, Throwable error, int attempts) { safeForEach(d -> d.onStepFailed(name, correlationId, stepId, error, attempts)); }
    @Override public void onStepSkipped(String name, String correlationId, String stepId) { safeForEach(d -> d.onStepSkipped(name, correlationId, stepId)); }
    @Override public void onCompleted(String name, String correlationId, ExecutionPattern pattern, boolean success, long durationMs) { safeForEach(d -> d.onCompleted(name, correlationId, pattern, success, durationMs)); }
    @Override public void onCompensationStarted(String name, String correlationId) { safeForEach(d -> d.onCompensationStarted(name, correlationId)); }
    @Override public void onStepCompensated(String name, String correlationId, String stepId) { safeForEach(d -> d.onStepCompensated(name, correlationId, stepId)); }
    @Override public void onStepCompensationFailed(String name, String correlationId, String stepId, Throwable error) { safeForEach(d -> d.onStepCompensationFailed(name, correlationId, stepId, error)); }
    @Override public void onPhaseStarted(String name, String correlationId, TccPhase phase) { safeForEach(d -> d.onPhaseStarted(name, correlationId, phase)); }
    @Override public void onPhaseCompleted(String name, String correlationId, TccPhase phase, long durationMs) { safeForEach(d -> d.onPhaseCompleted(name, correlationId, phase, durationMs)); }
    @Override public void onPhaseFailed(String name, String correlationId, TccPhase phase, Throwable error) { safeForEach(d -> d.onPhaseFailed(name, correlationId, phase, error)); }
    @Override public void onParticipantStarted(String name, String correlationId, String participantId, TccPhase phase) { safeForEach(d -> d.onParticipantStarted(name, correlationId, participantId, phase)); }
    @Override public void onParticipantSuccess(String name, String correlationId, String participantId, TccPhase phase) { safeForEach(d -> d.onParticipantSuccess(name, correlationId, participantId, phase)); }
    @Override public void onParticipantFailed(String name, String correlationId, String participantId, TccPhase phase, Throwable error) { safeForEach(d -> d.onParticipantFailed(name, correlationId, participantId, phase, error)); }
    @Override public void onWorkflowSuspended(String name, String correlationId, String reason) { safeForEach(d -> d.onWorkflowSuspended(name, correlationId, reason)); }
    @Override public void onWorkflowResumed(String name, String correlationId) { safeForEach(d -> d.onWorkflowResumed(name, correlationId)); }
    @Override public void onCompositionStarted(String compositionName, String correlationId) { safeForEach(d -> d.onCompositionStarted(compositionName, correlationId)); }
    @Override public void onCompositionCompleted(String compositionName, String correlationId, boolean success) { safeForEach(d -> d.onCompositionCompleted(compositionName, correlationId, success)); }
    @Override public void onStepSkippedIdempotent(String name, String correlationId, String stepId) { safeForEach(d -> d.onStepSkippedIdempotent(name, correlationId, stepId)); }
    @Override public void onDeadLettered(String name, String correlationId, String stepId, Throwable error) { safeForEach(d -> d.onDeadLettered(name, correlationId, stepId, error)); }
}
