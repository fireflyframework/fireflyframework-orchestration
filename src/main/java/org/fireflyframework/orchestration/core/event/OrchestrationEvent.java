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

package org.fireflyframework.orchestration.core.event;

import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;

import java.time.Instant;
import java.util.Map;

public record OrchestrationEvent(
        String eventType,
        String executionName,
        String correlationId,
        ExecutionPattern pattern,
        String stepId,
        ExecutionStatus status,
        Map<String, Object> payload,
        Instant timestamp
) {
    public static OrchestrationEvent executionStarted(String name, String correlationId, ExecutionPattern pattern) {
        return new OrchestrationEvent("EXECUTION_STARTED", name, correlationId, pattern, null, ExecutionStatus.RUNNING, Map.of(), Instant.now());
    }

    public static OrchestrationEvent executionCompleted(String name, String correlationId, ExecutionPattern pattern, ExecutionStatus status) {
        return new OrchestrationEvent("EXECUTION_COMPLETED", name, correlationId, pattern, null, status, Map.of(), Instant.now());
    }

    public static OrchestrationEvent stepCompleted(String name, String correlationId, ExecutionPattern pattern, String stepId, Object result) {
        return new OrchestrationEvent("STEP_COMPLETED", name, correlationId, pattern, stepId, null,
                result != null ? Map.of("result", result) : Map.of(), Instant.now());
    }

    public static OrchestrationEvent stepFailed(String name, String correlationId, ExecutionPattern pattern, String stepId, Throwable error) {
        return new OrchestrationEvent("STEP_FAILED", name, correlationId, pattern, stepId, null,
                Map.of("error", error.getMessage(), "errorType", error.getClass().getName()), Instant.now());
    }
}
