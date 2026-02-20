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

/**
 * Strategy for handling errors during saga compensation.
 * Determines whether to continue, retry, skip, or fail the saga.
 */
public interface CompensationErrorHandler {

    enum CompensationErrorResult {
        CONTINUE,
        RETRY,
        FAIL_SAGA,
        SKIP_STEP,
        MARK_COMPENSATED
    }

    /**
     * Handles a compensation error and returns the action to take.
     */
    CompensationErrorResult handle(String sagaName, String stepId, Throwable error, int attempt);
}
