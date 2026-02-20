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

package org.fireflyframework.orchestration.saga.compensation;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * Compensation error handler that retries with exponential backoff up to a configurable
 * maximum. Optionally filters which exception types are retryable.
 */
@Slf4j
public class RetryWithBackoffErrorHandler implements CompensationErrorHandler {

    private final int maxRetries;
    private final Set<Class<? extends Throwable>> retryableTypes;

    public RetryWithBackoffErrorHandler(int maxRetries, Set<Class<? extends Throwable>> retryableTypes) {
        this.maxRetries = maxRetries;
        this.retryableTypes = retryableTypes != null ? retryableTypes : Set.of();
    }

    public RetryWithBackoffErrorHandler(int maxRetries) {
        this(maxRetries, Set.of());
    }

    @Override
    public CompensationErrorResult handle(String sagaName, String stepId, Throwable error, int attempt) {
        if (!isRetryable(error)) {
            log.warn("[compensation-handler] Non-retryable error for step '{}' in saga '{}': {}",
                    stepId, sagaName, error.getClass().getSimpleName());
            return CompensationErrorResult.FAIL_SAGA;
        }

        if (attempt < maxRetries) {
            log.info("[compensation-handler] Retrying compensation for step '{}' in saga '{}' (attempt {}/{})",
                    stepId, sagaName, attempt + 1, maxRetries);
            return CompensationErrorResult.RETRY;
        }

        log.warn("[compensation-handler] Max retries ({}) exceeded for step '{}' in saga '{}'",
                maxRetries, stepId, sagaName);
        return CompensationErrorResult.FAIL_SAGA;
    }

    private boolean isRetryable(Throwable error) {
        if (retryableTypes.isEmpty()) return true;
        return retryableTypes.stream().anyMatch(type -> type.isInstance(error));
    }
}
