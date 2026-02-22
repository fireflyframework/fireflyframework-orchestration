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
 * Compensation error handler that always returns {@link CompensationErrorResult#FAIL_SAGA}.
 * Use this when any compensation failure should immediately abort the entire saga.
 */
@Slf4j
public class FailFastErrorHandler implements CompensationErrorHandler {

    @Override
    public CompensationErrorResult handle(String sagaName, String stepId, Throwable error, int attempt) {
        log.error("[compensation-handler] Failing saga '{}' immediately due to compensation error on step '{}': {}",
                sagaName, stepId, error.getMessage());
        return CompensationErrorResult.FAIL_SAGA;
    }
}
