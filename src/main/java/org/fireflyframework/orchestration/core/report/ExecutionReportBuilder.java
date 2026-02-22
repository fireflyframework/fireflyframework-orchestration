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

package org.fireflyframework.orchestration.core.report;

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.report.CompensationReport.CompensationStepReport;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Builds an {@link ExecutionReport} from an {@link ExecutionContext}.
 * Can be called from SagaEngine, TccEngine, or WorkflowEngine at execution completion.
 */
public final class ExecutionReportBuilder {

    private ExecutionReportBuilder() {}

    /**
     * Creates an {@link ExecutionReport} from the given execution context.
     *
     * @param ctx            the execution context capturing step-level outcomes
     * @param status         the final execution status
     * @param failureReason  optional failure reason (may be {@code null} for successful executions)
     * @return an immutable execution report
     */
    public static ExecutionReport fromContext(ExecutionContext ctx, ExecutionStatus status, String failureReason) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(status, "status");

        Instant completedAt = Instant.now();
        Duration duration = Duration.between(ctx.getStartedAt(), completedAt);

        Map<String, StepReport> stepReports = buildStepReports(ctx, completedAt);
        List<String> executionOrder = buildExecutionOrder(ctx);

        CompensationReport compensationReport = buildCompensationReport(ctx);

        return new ExecutionReport(
                ctx.getExecutionName(),
                ctx.getCorrelationId(),
                ctx.getPattern(),
                status,
                ctx.getStartedAt(),
                completedAt,
                duration,
                Collections.unmodifiableMap(stepReports),
                Collections.unmodifiableList(executionOrder),
                Collections.unmodifiableMap(new LinkedHashMap<>(ctx.getVariables())),
                failureReason,
                compensationReport
        );
    }

    private static Map<String, StepReport> buildStepReports(ExecutionContext ctx, Instant completedAt) {
        Map<String, StepReport> reports = new LinkedHashMap<>();

        for (Map.Entry<String, StepStatus> entry : ctx.getStepStatuses().entrySet()) {
            String stepId = entry.getKey();
            StepStatus stepStatus = entry.getValue();
            int attempts = ctx.getAttempts(stepId);
            long latencyMs = ctx.getStepLatency(stepId);
            Duration latency = Duration.ofMillis(latencyMs);
            Object result = ctx.getResult(stepId);
            Instant stepStartedAt = ctx.getStepStartedAt(stepId);

            // Derive step completedAt from startedAt + latency when available
            Instant stepCompletedAt = null;
            if (stepStartedAt != null && latencyMs > 0) {
                stepCompletedAt = stepStartedAt.plusMillis(latencyMs);
            } else if (stepStatus.isTerminal()) {
                stepCompletedAt = completedAt;
            }

            reports.put(stepId, new StepReport(
                    stepId, stepStatus, attempts, latency, result, null,
                    stepStartedAt, stepCompletedAt));
        }

        return reports;
    }

    private static List<String> buildExecutionOrder(ExecutionContext ctx) {
        // Flatten topology layers to derive execution order
        List<List<String>> layers = ctx.getTopologyLayers();
        if (layers == null || layers.isEmpty()) {
            // Fall back to step statuses key order
            return List.copyOf(ctx.getStepStatuses().keySet());
        }
        List<String> order = new ArrayList<>();
        for (List<String> layer : layers) {
            order.addAll(layer);
        }
        return order;
    }

    private static CompensationReport buildCompensationReport(ExecutionContext ctx) {
        Map<String, Object> compResults = ctx.getCompensationResults();
        Map<String, Throwable> compErrors = ctx.getCompensationErrors();

        // Also check for COMPENSATED / COMPENSATION_FAILED step statuses
        boolean hasCompensation = !compResults.isEmpty() || !compErrors.isEmpty()
                || ctx.getStepStatuses().values().stream()
                        .anyMatch(s -> s == StepStatus.COMPENSATED || s == StepStatus.COMPENSATION_FAILED);

        if (!hasCompensation) {
            return null;
        }

        List<CompensationStepReport> stepReports = new ArrayList<>();
        Set<String> compensatedSteps = new LinkedHashSet<>();

        // Gather all steps involved in compensation
        compensatedSteps.addAll(compResults.keySet());
        compensatedSteps.addAll(compErrors.keySet());
        ctx.getStepStatuses().forEach((stepId, status) -> {
            if (status == StepStatus.COMPENSATED || status == StepStatus.COMPENSATION_FAILED) {
                compensatedSteps.add(stepId);
            }
        });

        boolean allCompensated = true;
        for (String stepId : compensatedSteps) {
            Throwable error = compErrors.get(stepId);
            StepStatus stepStatus = ctx.getStepStatus(stepId);
            boolean success = stepStatus == StepStatus.COMPENSATED || (error == null && compResults.containsKey(stepId));
            if (!success) {
                allCompensated = false;
            }

            // Use step latency as a rough duration for the compensation step
            long latencyMs = ctx.getStepLatency(stepId);
            Duration stepDuration = Duration.ofMillis(latencyMs);

            stepReports.add(new CompensationStepReport(stepId, success, error, stepDuration));
        }

        // Total compensation duration: sum of individual step durations
        Duration totalDuration = stepReports.stream()
                .map(CompensationStepReport::duration)
                .reduce(Duration.ZERO, Duration::plus);

        return new CompensationReport(
                CompensationPolicy.STRICT_SEQUENTIAL, // Default; caller can override if needed
                Collections.unmodifiableList(stepReports),
                totalDuration,
                allCompensated
        );
    }
}
