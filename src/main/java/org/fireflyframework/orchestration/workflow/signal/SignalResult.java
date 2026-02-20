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

package org.fireflyframework.orchestration.workflow.signal;

/**
 * Result of delivering a signal to a workflow instance.
 */
public record SignalResult(
        boolean delivered,
        boolean resumed,
        String resumedStepId,
        String correlationId,
        String signalName
) {
    public static SignalResult delivered(String correlationId, String signalName,
                                         String resumedStepId) {
        return new SignalResult(true, resumedStepId != null, resumedStepId,
                correlationId, signalName);
    }

    public static SignalResult notDelivered(String correlationId, String signalName) {
        return new SignalResult(false, false, null, correlationId, signalName);
    }
}
