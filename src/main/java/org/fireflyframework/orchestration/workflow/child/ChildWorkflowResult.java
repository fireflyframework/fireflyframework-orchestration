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

package org.fireflyframework.orchestration.workflow.child;

import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;

/**
 * Result of a child workflow execution.
 */
public record ChildWorkflowResult(
        String parentCorrelationId,
        String childCorrelationId,
        String childWorkflowId,
        ExecutionStatus status,
        ExecutionState childState
) {
    public boolean isSuccess() {
        return status == ExecutionStatus.COMPLETED;
    }

    public static ChildWorkflowResult from(String parentCorrelationId, ExecutionState childState) {
        return new ChildWorkflowResult(parentCorrelationId, childState.correlationId(),
                childState.executionName(), childState.status(), childState);
    }
}
