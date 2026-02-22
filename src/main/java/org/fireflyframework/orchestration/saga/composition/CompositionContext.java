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

package org.fireflyframework.orchestration.saga.composition;

import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.saga.engine.SagaResult;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable execution context for a saga composition. Tracks correlation IDs,
 * results, execution order, and status for each saga in the composition.
 */
public class CompositionContext {

    private final String globalCorrelationId;
    private final Instant startedAt;
    private final Map<String, SagaResult> sagaResults = new ConcurrentHashMap<>();
    private final Map<String, String> correlationIds = new ConcurrentHashMap<>();
    private final Map<String, ExecutionStatus> sagaStatuses = new ConcurrentHashMap<>();
    private final List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

    public CompositionContext(String globalCorrelationId) {
        this.globalCorrelationId = globalCorrelationId;
        this.startedAt = Instant.now();
    }

    public String getGlobalCorrelationId() { return globalCorrelationId; }
    public Instant getStartedAt() { return startedAt; }

    /**
     * Records a saga result and adds the alias to the execution order.
     */
    public void recordResult(String alias, SagaResult result) {
        sagaResults.put(alias, result);
        executionOrder.add(alias);
        if (result.correlationId() != null) {
            correlationIds.put(alias, result.correlationId());
        }
        if (result.isSuccess()) {
            sagaStatuses.put(alias, ExecutionStatus.COMPLETED);
        } else {
            sagaStatuses.put(alias, ExecutionStatus.FAILED);
        }
    }

    /**
     * Retrieves the result for a specific saga alias.
     */
    public SagaResult getResult(String alias) {
        return sagaResults.get(alias);
    }

    /**
     * Returns true if a result has been recorded for the given alias.
     */
    public boolean isCompleted(String alias) {
        return sagaResults.containsKey(alias);
    }

    /**
     * Returns the execution order as an unmodifiable list.
     */
    public List<String> getExecutionOrder() {
        return Collections.unmodifiableList(new ArrayList<>(executionOrder));
    }

    public Map<String, SagaResult> getSagaResults() {
        return Collections.unmodifiableMap(sagaResults);
    }

    public Map<String, String> getCorrelationIds() {
        return Collections.unmodifiableMap(correlationIds);
    }

    public Map<String, ExecutionStatus> getSagaStatuses() {
        return Collections.unmodifiableMap(sagaStatuses);
    }

    public void setSagaStatus(String alias, ExecutionStatus status) {
        sagaStatuses.put(alias, status);
    }

    /**
     * Returns aliases of completed sagas in execution order (for reverse compensation).
     */
    public List<String> getCompletedAliasesInOrder() {
        List<String> completed = new ArrayList<>();
        for (String alias : executionOrder) {
            if (sagaStatuses.get(alias) == ExecutionStatus.COMPLETED) {
                completed.add(alias);
            }
        }
        return Collections.unmodifiableList(completed);
    }
}
