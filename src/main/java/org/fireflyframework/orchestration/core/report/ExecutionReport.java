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

import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.StepStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Unified execution report for all three orchestration patterns (Workflow, Saga, TCC).
 * Captures top-level metadata, per-step outcomes, execution order, and optional compensation details.
 */
public record ExecutionReport(
        String executionName,
        String correlationId,
        ExecutionPattern pattern,
        ExecutionStatus status,
        Instant startedAt,
        Instant completedAt,
        Duration duration,
        Map<String, StepReport> stepReports,
        List<String> executionOrder,
        Map<String, Object> variables,
        String failureReason,
        CompensationReport compensationReport
) {

    /**
     * Returns {@code true} if the execution completed successfully.
     */
    public boolean isSuccess() {
        return status == ExecutionStatus.COMPLETED || status == ExecutionStatus.CONFIRMED;
    }

    /**
     * Returns the total number of steps in this execution.
     */
    public int stepCount() {
        return stepReports != null ? stepReports.size() : 0;
    }

    /**
     * Returns the number of steps that failed.
     */
    public int failedStepCount() {
        if (stepReports == null) return 0;
        return (int) stepReports.values().stream()
                .filter(r -> r.status() == StepStatus.FAILED
                        || r.status() == StepStatus.TRY_FAILED
                        || r.status() == StepStatus.CONFIRM_FAILED
                        || r.status() == StepStatus.CANCEL_FAILED
                        || r.status() == StepStatus.COMPENSATION_FAILED)
                .count();
    }

    /**
     * Returns the number of steps that completed successfully.
     */
    public int completedStepCount() {
        if (stepReports == null) return 0;
        return (int) stepReports.values().stream()
                .filter(r -> r.status() != null && r.status().isSuccessful())
                .count();
    }

    /**
     * Returns the total number of retry attempts across all steps.
     * Each step's first attempt is not counted as a retry; only additional attempts are.
     */
    public int totalRetries() {
        if (stepReports == null) return 0;
        return stepReports.values().stream()
                .mapToInt(r -> Math.max(0, r.attempts() - 1))
                .sum();
    }
}
