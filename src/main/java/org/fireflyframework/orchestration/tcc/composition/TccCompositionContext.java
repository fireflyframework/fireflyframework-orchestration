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

package org.fireflyframework.orchestration.tcc.composition;

import org.fireflyframework.orchestration.tcc.engine.TccResult;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable execution context for a TCC composition. Tracks correlation IDs,
 * results, execution order, and status for each TCC in the composition.
 */
public class TccCompositionContext {

    public enum TccStatus { PENDING, RUNNING, CONFIRMED, CANCELED, FAILED, SKIPPED }

    private final String compositionCorrelationId;
    private final Instant startTime;
    private final Map<String, String> tccCorrelationIds = new ConcurrentHashMap<>();
    private final Map<String, TccResult> tccResults = new ConcurrentHashMap<>();
    private final Map<String, TccStatus> tccStatuses = new ConcurrentHashMap<>();
    private final List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

    public TccCompositionContext(String compositionCorrelationId) {
        this.compositionCorrelationId = compositionCorrelationId;
        this.startTime = Instant.now();
    }

    public String getCompositionCorrelationId() { return compositionCorrelationId; }
    public Instant getStartTime() { return startTime; }

    public void setTccCorrelationId(String alias, String correlationId) {
        tccCorrelationIds.put(alias, correlationId);
    }

    public String getTccCorrelationId(String alias) {
        return tccCorrelationIds.get(alias);
    }

    public Map<String, String> getTccCorrelationIds() {
        return Collections.unmodifiableMap(tccCorrelationIds);
    }

    public void setTccResult(String alias, TccResult result) {
        tccResults.put(alias, result);
    }

    public TccResult getTccResult(String alias) {
        return tccResults.get(alias);
    }

    public Map<String, TccResult> getTccResults() {
        return Collections.unmodifiableMap(tccResults);
    }

    public void setTccStatus(String alias, TccStatus status) {
        tccStatuses.put(alias, status);
    }

    public TccStatus getTccStatus(String alias) {
        return tccStatuses.getOrDefault(alias, TccStatus.PENDING);
    }

    public Map<String, TccStatus> getTccStatuses() {
        return Collections.unmodifiableMap(tccStatuses);
    }

    public void addToExecutionOrder(String alias) {
        executionOrder.add(alias);
    }

    public List<String> getExecutionOrder() {
        return Collections.unmodifiableList(new ArrayList<>(executionOrder));
    }

    /**
     * Returns aliases of confirmed TCCs in execution order (for reverse compensation).
     */
    public List<String> getConfirmedAliasesInOrder() {
        List<String> confirmed = new ArrayList<>();
        for (String alias : executionOrder) {
            if (getTccStatus(alias) == TccStatus.CONFIRMED) {
                confirmed.add(alias);
            }
        }
        return Collections.unmodifiableList(confirmed);
    }
}
