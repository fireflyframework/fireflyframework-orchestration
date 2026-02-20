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

public enum ExecutionStatus {
    PENDING,
    RUNNING,
    WAITING,
    SUSPENDED,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    // TCC-specific phases
    TRYING,
    CONFIRMING,
    CONFIRMED,
    CANCELING,
    CANCELED,
    // Saga-specific
    COMPENSATING;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT
                || this == CONFIRMED || this == CANCELED;
    }

    public boolean isActive() {
        return this == PENDING || this == RUNNING || this == WAITING
                || this == TRYING || this == CONFIRMING || this == CANCELING
                || this == COMPENSATING;
    }

    public boolean canSuspend() {
        return this == RUNNING || this == WAITING || this == PENDING;
    }

    public boolean canResume() {
        return this == SUSPENDED;
    }
}
