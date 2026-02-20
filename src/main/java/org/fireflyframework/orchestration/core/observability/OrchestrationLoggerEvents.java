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

@Slf4j
public class OrchestrationLoggerEvents implements OrchestrationEvents {
    @Override
    public void onStart(String name, String correlationId, ExecutionPattern pattern) {
        log.info("[orchestration] started name={} correlationId={} pattern={}", name, correlationId, pattern);
    }
    @Override
    public void onStepStarted(String name, String correlationId, String stepId) {
        log.info("[orchestration] step.started name={} correlationId={} stepId={}", name, correlationId, stepId);
    }
    @Override
    public void onStepSuccess(String name, String correlationId, String stepId, int attempts, long latencyMs) {
        log.info("[orchestration] step.success name={} correlationId={} stepId={} attempts={} latencyMs={}", name, correlationId, stepId, attempts, latencyMs);
    }
    @Override
    public void onStepFailed(String name, String correlationId, String stepId, Throwable error, int attempts) {
        log.warn("[orchestration] step.failed name={} correlationId={} stepId={} attempts={} error={}", name, correlationId, stepId, attempts, error.getMessage());
    }
    @Override
    public void onCompleted(String name, String correlationId, ExecutionPattern pattern, boolean success, long durationMs) {
        log.info("[orchestration] completed name={} correlationId={} pattern={} success={} durationMs={}", name, correlationId, pattern, success, durationMs);
    }
    @Override
    public void onCompensationStarted(String name, String correlationId) {
        log.warn("[orchestration] compensation.started name={} correlationId={}", name, correlationId);
    }
    @Override
    public void onStepCompensated(String name, String correlationId, String stepId) {
        log.info("[orchestration] step.compensated name={} correlationId={} stepId={}", name, correlationId, stepId);
    }
    @Override
    public void onStepCompensationFailed(String name, String correlationId, String stepId, Throwable error) {
        log.error("[orchestration] step.compensation.failed name={} correlationId={} stepId={} error={}", name, correlationId, stepId, error.getMessage());
    }
    @Override
    public void onPhaseStarted(String name, String correlationId, TccPhase phase) {
        log.info("[orchestration] tcc.phase.started name={} correlationId={} phase={}", name, correlationId, phase);
    }
    @Override
    public void onPhaseCompleted(String name, String correlationId, TccPhase phase, long durationMs) {
        log.info("[orchestration] tcc.phase.completed name={} correlationId={} phase={} durationMs={}", name, correlationId, phase, durationMs);
    }
    @Override
    public void onPhaseFailed(String name, String correlationId, TccPhase phase, Throwable error) {
        log.error("[orchestration] tcc.phase.failed name={} correlationId={} phase={} error={}", name, correlationId, phase, error.getMessage());
    }
    @Override
    public void onDeadLettered(String name, String correlationId, String stepId, Throwable error) {
        log.error("[orchestration] dead-lettered name={} correlationId={} stepId={} error={}", name, correlationId, stepId, error.getMessage());
    }
}
