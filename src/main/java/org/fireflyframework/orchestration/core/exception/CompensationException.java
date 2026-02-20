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

package org.fireflyframework.orchestration.core.exception;

import java.util.Map;

public final class CompensationException extends OrchestrationException {
    public CompensationException(String message) {
        super(message, "ORCHESTRATION_COMPENSATION_ERROR");
    }

    public CompensationException(String message, Throwable cause) {
        super(message, "ORCHESTRATION_COMPENSATION_ERROR", cause);
    }

    public CompensationException(String message, Map<String, Object> context, Throwable cause) {
        super(message, "ORCHESTRATION_COMPENSATION_ERROR", context, cause);
    }
}
