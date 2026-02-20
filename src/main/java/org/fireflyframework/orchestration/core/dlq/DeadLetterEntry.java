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

package org.fireflyframework.orchestration.core.dlq;

import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DeadLetterEntry(
        String id,
        String executionName,
        String correlationId,
        ExecutionPattern pattern,
        String stepId,
        ExecutionStatus statusAtFailure,
        String errorMessage,
        String errorType,
        Map<String, Object> input,
        int retryCount,
        Instant createdAt,
        Instant lastRetriedAt
) {
    public static DeadLetterEntry create(String executionName, String correlationId, ExecutionPattern pattern,
                                          String stepId, ExecutionStatus statusAtFailure, Throwable error,
                                          Map<String, Object> input) {
        return new DeadLetterEntry(
                UUID.randomUUID().toString(), executionName, correlationId, pattern, stepId, statusAtFailure,
                error != null ? error.getMessage() : null,
                error != null ? error.getClass().getName() : null,
                input != null ? Map.copyOf(input) : Map.of(),
                0, Instant.now(), null);
    }

    public DeadLetterEntry withRetry() {
        return new DeadLetterEntry(id, executionName, correlationId, pattern, stepId, statusAtFailure,
                errorMessage, errorType, input, retryCount + 1, createdAt, Instant.now());
    }
}
