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
import io.micrometer.core.instrument.Timer;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

public class OrchestrationMetrics implements OrchestrationEvents {
    private static final String PREFIX = "firefly.orchestration";
    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public OrchestrationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onStart(String name, String correlationId, ExecutionPattern pattern) {
        counter("executions.started", "name", name, "pattern", pattern.name()).increment();
    }

    @Override
    public void onCompleted(String name, String correlationId, ExecutionPattern pattern, boolean success, long durationMs) {
        counter("executions.completed", "name", name, "pattern", pattern.name(), "success", String.valueOf(success)).increment();
        timer("executions.duration", "name", name, "pattern", pattern.name()).record(Duration.ofMillis(durationMs));
    }

    @Override
    public void onStepSuccess(String name, String correlationId, String stepId, int attempts, long latencyMs) {
        counter("steps.completed", "name", name, "stepId", stepId, "success", "true").increment();
        timer("steps.duration", "name", name, "stepId", stepId).record(Duration.ofMillis(latencyMs));
    }

    @Override
    public void onStepFailed(String name, String correlationId, String stepId, Throwable error, int attempts) {
        counter("steps.completed", "name", name, "stepId", stepId, "success", "false").increment();
        counter("steps.retries", "name", name, "stepId", stepId).increment(attempts - 1);
    }

    @Override
    public void onCompensationStarted(String name, String correlationId) {
        counter("compensations.started", "name", name).increment();
    }

    @Override
    public void onDeadLettered(String name, String correlationId, String stepId, Throwable error) {
        counter("dlq.entries", "name", name).increment();
    }

    private Counter counter(String metricName, String... tags) {
        String key = metricName + String.join(",", tags);
        return counters.computeIfAbsent(key, k -> Counter.builder(PREFIX + "." + metricName).tags(tags).register(registry));
    }

    private Timer timer(String metricName, String... tags) {
        String key = metricName + String.join(",", tags);
        return timers.computeIfAbsent(key, k -> Timer.builder(PREFIX + "." + metricName).tags(tags).register(registry));
    }
}
