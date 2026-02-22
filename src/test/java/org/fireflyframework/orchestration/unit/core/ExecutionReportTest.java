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

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.report.ExecutionReport;
import org.fireflyframework.orchestration.core.report.ExecutionReportBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionReportTest {

    @Test
    void fromContext_buildsCompleteReport() {
        ExecutionContext ctx = ExecutionContext.forWorkflow("corr-1", "order-workflow");
        ctx.setTopologyLayers(List.of(List.of("validate"), List.of("process"), List.of("notify")));

        // Simulate step execution
        ctx.markStepStarted("validate");
        ctx.incrementAttempts("validate");
        ctx.setStepStatus("validate", StepStatus.DONE);
        ctx.putResult("validate", "valid");
        ctx.setStepLatency("validate", 50);

        ctx.markStepStarted("process");
        ctx.incrementAttempts("process");
        ctx.incrementAttempts("process"); // retried once
        ctx.setStepStatus("process", StepStatus.DONE);
        ctx.putResult("process", "processed");
        ctx.setStepLatency("process", 120);

        ctx.markStepStarted("notify");
        ctx.incrementAttempts("notify");
        ctx.setStepStatus("notify", StepStatus.DONE);
        ctx.putResult("notify", "notified");
        ctx.setStepLatency("notify", 30);

        ctx.putVariable("orderId", "ORD-123");

        ExecutionReport report = ExecutionReportBuilder.fromContext(ctx, ExecutionStatus.COMPLETED, null);

        assertThat(report.executionName()).isEqualTo("order-workflow");
        assertThat(report.correlationId()).isEqualTo("corr-1");
        assertThat(report.pattern()).isEqualTo(ExecutionPattern.WORKFLOW);
        assertThat(report.status()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(report.isSuccess()).isTrue();
        assertThat(report.failureReason()).isNull();
        assertThat(report.startedAt()).isNotNull();
        assertThat(report.completedAt()).isNotNull();
        assertThat(report.duration()).isNotNull();
        assertThat(report.variables()).containsEntry("orderId", "ORD-123");
        assertThat(report.executionOrder()).containsExactly("validate", "process", "notify");
        assertThat(report.compensationReport()).isNull();

        // Step reports
        assertThat(report.stepCount()).isEqualTo(3);
        assertThat(report.stepReports()).containsKeys("validate", "process", "notify");
        assertThat(report.stepReports().get("validate").status()).isEqualTo(StepStatus.DONE);
        assertThat(report.stepReports().get("validate").attempts()).isEqualTo(1);
        assertThat(report.stepReports().get("validate").result()).isEqualTo("valid");
        assertThat(report.stepReports().get("process").attempts()).isEqualTo(2);
    }

    @Test
    void fromContext_withCompensation_includesCompensationReport() {
        ExecutionContext ctx = ExecutionContext.forSaga("corr-2", "payment-saga");
        ctx.setTopologyLayers(List.of(List.of("reserve"), List.of("charge")));

        // Simulate step execution
        ctx.markStepStarted("reserve");
        ctx.incrementAttempts("reserve");
        ctx.setStepStatus("reserve", StepStatus.DONE);
        ctx.putResult("reserve", "reserved");
        ctx.setStepLatency("reserve", 80);

        ctx.markStepStarted("charge");
        ctx.incrementAttempts("charge");
        ctx.setStepStatus("charge", StepStatus.FAILED);
        ctx.setStepLatency("charge", 200);

        // Simulate compensation
        ctx.setStepStatus("reserve", StepStatus.COMPENSATED);
        ctx.putCompensationResult("reserve", "released");

        ExecutionReport report = ExecutionReportBuilder.fromContext(ctx, ExecutionStatus.FAILED, "charge step failed");

        assertThat(report.isSuccess()).isFalse();
        assertThat(report.failureReason()).isEqualTo("charge step failed");
        assertThat(report.status()).isEqualTo(ExecutionStatus.FAILED);

        // Compensation report
        assertThat(report.compensationReport()).isNotNull();
        assertThat(report.compensationReport().allCompensated()).isTrue();
        assertThat(report.compensationReport().steps()).hasSize(1);
        assertThat(report.compensationReport().steps().get(0).stepId()).isEqualTo("reserve");
        assertThat(report.compensationReport().steps().get(0).success()).isTrue();
    }

    @Test
    void report_calculatesMetrics() {
        ExecutionContext ctx = ExecutionContext.forWorkflow("corr-3", "metrics-test");
        ctx.setTopologyLayers(List.of(List.of("a", "b"), List.of("c")));

        // Step a: done, 1 attempt
        ctx.markStepStarted("a");
        ctx.incrementAttempts("a");
        ctx.setStepStatus("a", StepStatus.DONE);
        ctx.setStepLatency("a", 10);

        // Step b: failed, 3 attempts (2 retries)
        ctx.markStepStarted("b");
        ctx.incrementAttempts("b");
        ctx.incrementAttempts("b");
        ctx.incrementAttempts("b");
        ctx.setStepStatus("b", StepStatus.FAILED);
        ctx.setStepLatency("b", 300);

        // Step c: skipped, 0 attempts
        ctx.setStepStatus("c", StepStatus.SKIPPED);

        ExecutionReport report = ExecutionReportBuilder.fromContext(ctx, ExecutionStatus.FAILED, "step b failed");

        assertThat(report.stepCount()).isEqualTo(3);
        assertThat(report.completedStepCount()).isEqualTo(2); // DONE + SKIPPED are successful
        assertThat(report.failedStepCount()).isEqualTo(1); // only b
        assertThat(report.totalRetries()).isEqualTo(2); // b had 3 attempts = 2 retries, a had 1 = 0 retries
        assertThat(report.isSuccess()).isFalse();
    }
}
