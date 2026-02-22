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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.observability.OrchestrationMetrics;
import org.fireflyframework.orchestration.core.observability.OrchestrationMetricsEndpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsEndpointTest {

    private SimpleMeterRegistry registry;
    private OrchestrationMetrics metrics;
    private OrchestrationMetricsEndpoint endpoint;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new OrchestrationMetrics(registry);
        endpoint = new OrchestrationMetricsEndpoint(registry);
    }

    @Test
    @SuppressWarnings("unchecked")
    void metrics_returnsAllCounters() {
        // Simulate orchestration activity across multiple patterns
        metrics.onStart("order-flow", "c1", ExecutionPattern.WORKFLOW);
        metrics.onStart("payment-saga", "c2", ExecutionPattern.SAGA);
        metrics.onStart("reserve-tcc", "c3", ExecutionPattern.TCC);
        metrics.onStart("order-flow", "c4", ExecutionPattern.WORKFLOW);

        // Complete some executions
        metrics.onCompleted("order-flow", "c1", ExecutionPattern.WORKFLOW, true, 100);
        metrics.onCompleted("payment-saga", "c2", ExecutionPattern.SAGA, false, 200);

        // Step activity
        metrics.onStepSuccess("order-flow", "c1", "validate", 1, 50);
        metrics.onStepSuccess("order-flow", "c1", "process", 2, 80);
        metrics.onStepFailed("payment-saga", "c2", "charge", new RuntimeException("insufficient funds"), 3);

        // Compensation
        metrics.onCompensationStarted("payment-saga", "c2");

        // DLQ
        metrics.onDeadLettered("payment-saga", "c2", "charge", new RuntimeException("retry exhausted"));

        Map<String, Object> result = endpoint.metrics();

        // Executions
        Map<String, Object> executions = (Map<String, Object>) result.get("executions");
        assertThat(executions).isNotNull();
        assertThat((double) executions.get("total")).isEqualTo(4.0);
        assertThat((double) executions.get("completed")).isEqualTo(1.0);
        assertThat((double) executions.get("failed")).isEqualTo(1.0);
        assertThat((double) executions.get("active")).isEqualTo(2.0);

        // Pattern breakdown
        Map<String, Object> patterns = (Map<String, Object>) result.get("patterns");
        assertThat(patterns).isNotNull();
        assertThat((double) patterns.get("workflow")).isEqualTo(2.0);
        assertThat((double) patterns.get("saga")).isEqualTo(1.0);
        assertThat((double) patterns.get("tcc")).isEqualTo(1.0);

        // Steps
        Map<String, Object> steps = (Map<String, Object>) result.get("steps");
        assertThat(steps).isNotNull();
        assertThat((double) steps.get("total")).isEqualTo(3.0); // 2 success + 1 failed
        assertThat((double) steps.get("failed")).isEqualTo(1.0);
        assertThat((double) steps.get("retried")).isEqualTo(2.0); // charge had 3 attempts -> 2 retries

        // Compensation
        Map<String, Object> compensation = (Map<String, Object>) result.get("compensation");
        assertThat(compensation).isNotNull();
        assertThat((double) compensation.get("total")).isEqualTo(1.0);

        // DLQ
        Map<String, Object> dlq = (Map<String, Object>) result.get("dlq");
        assertThat(dlq).isNotNull();
        assertThat((double) dlq.get("entries")).isEqualTo(1.0);

        // Uptime
        assertThat(result.get("uptime")).isNotNull();
        assertThat(result.get("uptime").toString()).matches("\\d+h \\d+m \\d+s");
    }

    @Test
    @SuppressWarnings("unchecked")
    void metrics_filteredByPattern() {
        // Create activity for multiple patterns
        metrics.onStart("order-flow", "c1", ExecutionPattern.WORKFLOW);
        metrics.onStart("payment-saga", "c2", ExecutionPattern.SAGA);
        metrics.onStart("order-flow-2", "c3", ExecutionPattern.WORKFLOW);

        metrics.onCompleted("order-flow", "c1", ExecutionPattern.WORKFLOW, true, 100);
        metrics.onCompleted("payment-saga", "c2", ExecutionPattern.SAGA, true, 200);

        metrics.onStepSuccess("order-flow", "c1", "validate", 1, 50);
        metrics.onStepFailed("payment-saga", "c2", "charge", new RuntimeException("fail"), 2);

        // Filter by WORKFLOW
        Map<String, Object> result = endpoint.metrics("WORKFLOW");

        assertThat(result.get("pattern")).isEqualTo("WORKFLOW");

        Map<String, Object> executions = (Map<String, Object>) result.get("executions");
        assertThat((double) executions.get("total")).isEqualTo(2.0);
        assertThat((double) executions.get("completed")).isEqualTo(1.0);
        assertThat((double) executions.get("active")).isEqualTo(1.0);

        // SAGA metrics should not appear in WORKFLOW filter
        // Filter by SAGA
        Map<String, Object> sagaResult = endpoint.metrics("saga"); // test case-insensitivity
        assertThat(sagaResult.get("pattern")).isEqualTo("SAGA");

        Map<String, Object> sagaExecutions = (Map<String, Object>) sagaResult.get("executions");
        assertThat((double) sagaExecutions.get("total")).isEqualTo(1.0);
        assertThat((double) sagaExecutions.get("completed")).isEqualTo(1.0);

        // Unknown pattern returns error
        Map<String, Object> unknown = endpoint.metrics("UNKNOWN");
        assertThat(unknown).containsKey("error");
    }
}
