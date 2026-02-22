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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import lombok.extern.slf4j.Slf4j;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Boot Actuator endpoint exposing orchestration metrics.
 *
 * <p>Reads counters from the {@link MeterRegistry} registered by
 * {@link OrchestrationMetrics} and returns a structured JSON summary
 * at {@code /actuator/orchestration-metrics}.
 */
@Slf4j
@Endpoint(id = "orchestration-metrics")
public class OrchestrationMetricsEndpoint {

    private static final String PREFIX = "firefly.orchestration";

    private final MeterRegistry registry;

    public OrchestrationMetricsEndpoint(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Returns all orchestration metrics as a structured map.
     */
    @ReadOperation
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executions", buildExecutionMetrics(null));
        result.put("patterns", buildPatternBreakdown(null));
        result.put("steps", buildStepMetrics(null));
        result.put("compensation", buildCompensationMetrics(null));
        result.put("dlq", buildDlqMetrics(null));
        result.put("uptime", formatUptime());
        return result;
    }

    /**
     * Returns orchestration metrics filtered by execution pattern.
     *
     * @param pattern the execution pattern name (WORKFLOW, SAGA, or TCC)
     */
    @ReadOperation
    public Map<String, Object> metrics(@Selector String pattern) {
        String normalized = pattern.toUpperCase();
        // Validate pattern
        try {
            ExecutionPattern.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            log.warn("[orchestration] Unknown pattern requested for metrics: {}", pattern);
            return Map.of("error", "Unknown pattern: " + pattern);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pattern", normalized);
        result.put("executions", buildExecutionMetrics(normalized));
        result.put("steps", buildStepMetrics(normalized));
        result.put("compensation", buildCompensationMetrics(normalized));
        result.put("dlq", buildDlqMetrics(normalized));
        result.put("uptime", formatUptime());
        return result;
    }

    private Map<String, Object> buildExecutionMetrics(String patternFilter) {
        double started = sumCounters(PREFIX + ".executions.started", "pattern", patternFilter);
        double completedSuccess = sumCountersWithTag(PREFIX + ".executions.completed", "success", "true", "pattern", patternFilter);
        double completedFailed = sumCountersWithTag(PREFIX + ".executions.completed", "success", "false", "pattern", patternFilter);
        double total = started;
        double completed = completedSuccess;
        double failed = completedFailed;
        double active = total - completed - failed;

        Map<String, Object> executions = new LinkedHashMap<>();
        executions.put("total", total);
        executions.put("active", Math.max(0, active));
        executions.put("completed", completed);
        executions.put("failed", failed);
        return executions;
    }

    private Map<String, Object> buildPatternBreakdown(String patternFilter) {
        Map<String, Object> patterns = new LinkedHashMap<>();
        for (ExecutionPattern ep : ExecutionPattern.values()) {
            if (patternFilter != null && !patternFilter.equals(ep.name())) {
                continue;
            }
            double count = sumCounters(PREFIX + ".executions.started", "pattern", ep.name());
            patterns.put(ep.name().toLowerCase(), count);
        }
        return patterns;
    }

    private Map<String, Object> buildStepMetrics(String patternFilter) {
        double stepsSucceeded = sumCountersWithTag(PREFIX + ".steps.completed", "success", "true", "pattern", patternFilter);
        double stepsFailed = sumCountersWithTag(PREFIX + ".steps.completed", "success", "false", "pattern", patternFilter);
        double stepsRetried = sumCounters(PREFIX + ".steps.retries", "pattern", patternFilter);

        Map<String, Object> steps = new LinkedHashMap<>();
        steps.put("total", stepsSucceeded + stepsFailed);
        steps.put("failed", stepsFailed);
        steps.put("retried", stepsRetried);
        return steps;
    }

    private Map<String, Object> buildCompensationMetrics(String patternFilter) {
        double compensationStarted = sumCounters(PREFIX + ".compensations.started", "pattern", patternFilter);
        // Compensation failures are tracked via OrchestrationEvents.onStepCompensationFailed
        // but OrchestrationMetrics does not currently record them; report 0 for now
        Map<String, Object> compensation = new LinkedHashMap<>();
        compensation.put("total", compensationStarted);
        compensation.put("failed", 0.0);
        return compensation;
    }

    private Map<String, Object> buildDlqMetrics(String patternFilter) {
        double dlqEntries = sumCounters(PREFIX + ".dlq.entries", "pattern", patternFilter);
        Map<String, Object> dlq = new LinkedHashMap<>();
        dlq.put("entries", dlqEntries);
        return dlq;
    }

    /**
     * Sum all counters matching the given metric name, optionally filtered by a pattern tag.
     */
    private double sumCounters(String metricName, String tagKey, String tagValue) {
        Search search = registry.find(metricName);
        if (tagValue != null) {
            search = search.tag(tagKey, tagValue);
        }
        return search.counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    /**
     * Sum counters matching a specific tag pair, with an optional secondary pattern filter.
     */
    private double sumCountersWithTag(String metricName, String tagKey, String tagValue,
                                      String filterTagKey, String filterTagValue) {
        Search search = registry.find(metricName).tag(tagKey, tagValue);
        if (filterTagValue != null) {
            search = search.tag(filterTagKey, filterTagValue);
        }
        return search.counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private String formatUptime() {
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        Duration duration = Duration.ofMillis(uptimeMs);
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }
}
