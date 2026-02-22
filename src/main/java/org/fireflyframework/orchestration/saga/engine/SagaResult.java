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

package org.fireflyframework.orchestration.saga.engine;

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.report.ExecutionReport;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Immutable snapshot of a saga execution result.
 * Captures top-level metadata and per-step outcomes.
 */
public final class SagaResult {

    private final String sagaName;
    private final String correlationId;
    private final Instant startedAt;
    private final Instant completedAt;
    private final boolean success;
    private final Throwable error;
    private final Map<String, String> headers;
    private final Map<String, StepOutcome> steps;
    private final ExecutionReport report;

    public record StepOutcome(
            StepStatus status, int attempts, long latencyMs,
            Object result, Throwable error, boolean compensated,
            Instant startedAt, Object compensationResult, Throwable compensationError) {}

    private SagaResult(String sagaName, String correlationId, Instant startedAt, Instant completedAt,
                       boolean success, Throwable error, Map<String, String> headers,
                       Map<String, StepOutcome> steps, ExecutionReport report) {
        this.sagaName = sagaName;
        this.correlationId = correlationId;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.success = success;
        this.error = error;
        this.headers = headers;
        this.steps = steps;
        this.report = report;
    }

    public String sagaName() { return sagaName; }
    public String correlationId() { return correlationId; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public Duration duration() { return Duration.between(startedAt, completedAt); }
    public boolean isSuccess() { return success; }
    public Optional<Throwable> error() { return Optional.ofNullable(error); }
    public Map<String, String> headers() { return headers; }
    public Map<String, StepOutcome> steps() { return steps; }
    public Optional<ExecutionReport> report() { return Optional.ofNullable(report); }

    public Optional<String> firstErrorStepId() {
        return steps.entrySet().stream()
                .filter(e -> e.getValue().error() != null || StepStatus.FAILED.equals(e.getValue().status()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    public List<String> failedSteps() {
        return steps.entrySet().stream()
                .filter(e -> e.getValue().error() != null || StepStatus.FAILED.equals(e.getValue().status()))
                .map(Map.Entry::getKey)
                .toList();
    }

    public List<String> compensatedSteps() {
        return steps.entrySet().stream()
                .filter(e -> e.getValue().compensated())
                .map(Map.Entry::getKey)
                .toList();
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> resultOf(String stepId, Class<T> type) {
        Objects.requireNonNull(stepId, "stepId");
        Objects.requireNonNull(type, "type");
        StepOutcome out = steps.get(stepId);
        if (out == null || out.result() == null) return Optional.empty();
        return type.isInstance(out.result()) ? Optional.of((T) out.result()) : Optional.empty();
    }

    public Map<String, Object> stepResults() {
        Map<String, Object> results = new LinkedHashMap<>();
        steps.forEach((k, v) -> { if (v.result() != null) results.put(k, v.result()); });
        return Collections.unmodifiableMap(results);
    }

    public static SagaResult failed(String sagaName, String correlationId,
                                      String failedStepId, Throwable error,
                                      Map<String, StepOutcome> steps) {
        return new SagaResult(sagaName, correlationId, Instant.now(), Instant.now(),
                false, error, Map.of(), steps != null ? Map.copyOf(steps) : Map.of(), null);
    }

    public static SagaResult from(String sagaName, ExecutionContext ctx,
                                   Map<String, Boolean> compensatedFlags,
                                   Map<String, Throwable> stepErrors,
                                   Collection<String> allStepIds) {
        Instant started = ctx.getStartedAt();
        Instant completed = Instant.now();
        LinkedHashMap<String, StepOutcome> stepMap = new LinkedHashMap<>();
        for (String k : allStepIds) {
            stepMap.put(k, new StepOutcome(
                    ctx.getStepStatus(k), ctx.getAttempts(k), ctx.getStepLatency(k),
                    ctx.getResult(k), stepErrors.get(k),
                    Boolean.TRUE.equals(compensatedFlags.get(k)),
                    ctx.getStepStartedAt(k), ctx.getCompensationResult(k),
                    ctx.getCompensationError(k)));
        }
        boolean success = stepErrors.isEmpty();
        Throwable primary = success ? null : stepErrors.values().stream().findFirst().orElse(null);
        return new SagaResult(sagaName, ctx.getCorrelationId(), started, completed,
                success, primary,
                Collections.unmodifiableMap(new LinkedHashMap<>(ctx.getHeaders())),
                Collections.unmodifiableMap(stepMap), null);
    }

    /**
     * Returns a new SagaResult with the given execution report attached.
     */
    public SagaResult withReport(ExecutionReport executionReport) {
        return new SagaResult(sagaName, correlationId, startedAt, completedAt,
                success, error, headers, steps, executionReport);
    }
}
