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

/**
 * Compensation error handler that logs at WARN level and returns {@link CompensationErrorResult#CONTINUE}.
 * Use this when compensation errors should be recorded but not block further compensation.
 */
@Slf4j
public class LogAndContinueErrorHandler implements CompensationErrorHandler {

    @Override
    public CompensationErrorResult handle(String sagaName, String stepId, Throwable error, int attempt) {
        log.warn("[compensation-handler] Compensation error on step '{}' in saga '{}' (attempt {}), continuing: {}",
                stepId, sagaName, attempt, error.getMessage());
        return CompensationErrorResult.CONTINUE;
    }
}
