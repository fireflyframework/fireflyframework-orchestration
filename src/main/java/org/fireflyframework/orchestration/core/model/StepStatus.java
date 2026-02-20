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

package org.fireflyframework.orchestration.core.model;

public enum StepStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    SKIPPED,
    TIMED_OUT,
    RETRYING,
    // TCC participant states
    TRIED,
    TRY_FAILED,
    CONFIRMING,
    CONFIRMED,
    CONFIRM_FAILED,
    CANCELING,
    CANCELED,
    CANCEL_FAILED,
    // Saga compensation states
    COMPENSATED,
    COMPENSATION_FAILED;

    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == SKIPPED || this == TIMED_OUT
                || this == CONFIRMED || this == CONFIRM_FAILED
                || this == CANCELED || this == CANCEL_FAILED
                || this == COMPENSATED || this == COMPENSATION_FAILED;
    }

    public boolean isActive() {
        return this == PENDING || this == RUNNING || this == RETRYING
                || this == CONFIRMING || this == CANCELING;
    }

    public boolean isSuccessful() {
        return this == DONE || this == SKIPPED || this == CONFIRMED || this == COMPENSATED;
    }
}
