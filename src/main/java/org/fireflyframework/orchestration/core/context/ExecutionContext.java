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

package org.fireflyframework.orchestration.core.context;

import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.StepStatus;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ExecutionContext {

    private final String correlationId;
    private final String executionName;
    private final ExecutionPattern pattern;

    // Shared state (thread-safe)
    private final Map<String, String> headers;
    private final Map<String, Object> variables;
    private final Map<String, Object> stepResults;
    private final Map<String, StepStatus> stepStatuses;
    private final Map<String, Integer> stepAttempts;
    private final Map<String, Long> stepLatenciesMs;
    private final Map<String, Instant> stepStartedAt;
    private final Set<String> idempotencyKeys;
    private final Instant startedAt;

    // Saga-specific compensation tracking
    private final Map<String, Object> compensationResults;
    private final Map<String, Throwable> compensationErrors;

    // Workflow-specific
    private volatile boolean dryRun;

    // TCC-specific
    private volatile TccPhase currentPhase;
    private final Map<String, Object> tryResults;

    // Workflow compensation tracking (completed compensatable steps in execution order)
    private final List<String> completedCompensatableSteps;

    // Topology
    private volatile List<List<String>> topologyLayers;

    private ExecutionContext(String correlationId, String executionName, ExecutionPattern pattern) {
        this.correlationId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        this.executionName = executionName;
        this.pattern = pattern;
        this.headers = new ConcurrentHashMap<>();
        this.variables = new ConcurrentHashMap<>();
        this.stepResults = new ConcurrentHashMap<>();
        this.stepStatuses = new ConcurrentHashMap<>();
        this.stepAttempts = new ConcurrentHashMap<>();
        this.stepLatenciesMs = new ConcurrentHashMap<>();
        this.stepStartedAt = new ConcurrentHashMap<>();
        this.idempotencyKeys = ConcurrentHashMap.newKeySet();
        this.compensationResults = new ConcurrentHashMap<>();
        this.compensationErrors = new ConcurrentHashMap<>();
        this.tryResults = new ConcurrentHashMap<>();
        this.completedCompensatableSteps = new CopyOnWriteArrayList<>();
        this.startedAt = Instant.now();
    }

    // Factory methods
    public static ExecutionContext forWorkflow(String correlationId, String name) {
        return new ExecutionContext(correlationId, name, ExecutionPattern.WORKFLOW);
    }

    public static ExecutionContext forWorkflow(String correlationId, String name, boolean dryRun) {
        var ctx = new ExecutionContext(correlationId, name, ExecutionPattern.WORKFLOW);
        ctx.dryRun = dryRun;
        return ctx;
    }

    public static ExecutionContext forSaga(String correlationId, String name) {
        return new ExecutionContext(correlationId, name, ExecutionPattern.SAGA);
    }

    public static ExecutionContext forTcc(String correlationId, String name) {
        return new ExecutionContext(correlationId, name, ExecutionPattern.TCC);
    }

    // Identity
    public String getCorrelationId() { return correlationId; }
    public String getExecutionName() { return executionName; }
    public ExecutionPattern getPattern() { return pattern; }
    public Instant getStartedAt() { return startedAt; }

    // Headers
    public Map<String, String> getHeaders() { return Collections.unmodifiableMap(headers); }
    public void putHeader(String key, String value) { headers.put(key, value); }
    public String getHeader(String key) { return headers.get(key); }

    // Variables
    public Map<String, Object> getVariables() { return Collections.unmodifiableMap(variables); }
    public void putVariable(String name, Object value) { variables.put(name, value); }
    public Object getVariable(String name) { return variables.get(name); }
    @SuppressWarnings("unchecked")
    public <T> T getVariableAs(String name, Class<T> type) { return (T) variables.get(name); }
    public void removeVariable(String name) { variables.remove(name); }

    // Step results
    public void putResult(String stepId, Object result) { stepResults.put(stepId, result); }
    public Object getResult(String stepId) { return stepResults.get(stepId); }
    @SuppressWarnings("unchecked")
    public <T> T getResult(String stepId, Class<T> type) { return (T) stepResults.get(stepId); }
    public Map<String, Object> getStepResults() { return Collections.unmodifiableMap(stepResults); }

    // Step tracking
    public void setStepStatus(String stepId, StepStatus status) { stepStatuses.put(stepId, status); }
    public StepStatus getStepStatus(String stepId) { return stepStatuses.getOrDefault(stepId, StepStatus.PENDING); }
    public Map<String, StepStatus> getStepStatuses() { return Collections.unmodifiableMap(stepStatuses); }

    public int incrementAttempts(String stepId) { return stepAttempts.merge(stepId, 1, Integer::sum); }
    public int getAttempts(String stepId) { return stepAttempts.getOrDefault(stepId, 0); }
    public Map<String, Integer> getStepAttempts() { return Collections.unmodifiableMap(stepAttempts); }

    public void setStepLatency(String stepId, long millis) { stepLatenciesMs.put(stepId, millis); }
    public long getStepLatency(String stepId) { return stepLatenciesMs.getOrDefault(stepId, 0L); }
    public Map<String, Long> getStepLatenciesMs() { return Collections.unmodifiableMap(stepLatenciesMs); }

    public void markStepStarted(String stepId) { stepStartedAt.put(stepId, Instant.now()); }
    public Instant getStepStartedAt(String stepId) { return stepStartedAt.get(stepId); }

    // Idempotency
    public boolean addIdempotencyKey(String key) { return idempotencyKeys.add(key); }
    public boolean hasIdempotencyKey(String key) { return idempotencyKeys.contains(key); }
    public Set<String> getIdempotencyKeys() { return Collections.unmodifiableSet(idempotencyKeys); }

    // Compensation tracking (Saga)
    public void putCompensationResult(String stepId, Object value) { compensationResults.put(stepId, value); }
    public Object getCompensationResult(String stepId) { return compensationResults.get(stepId); }
    public void putCompensationError(String stepId, Throwable error) { compensationErrors.put(stepId, error); }
    public Throwable getCompensationError(String stepId) { return compensationErrors.get(stepId); }
    public Map<String, Object> getCompensationResults() { return Collections.unmodifiableMap(compensationResults); }
    public Map<String, Throwable> getCompensationErrors() { return Collections.unmodifiableMap(compensationErrors); }

    // Workflow-specific
    public boolean isDryRun() { return dryRun; }

    // Workflow compensation tracking
    public void addCompensatableStep(String stepId) { completedCompensatableSteps.add(stepId); }
    public List<String> getCompletedCompensatableSteps() { return List.copyOf(completedCompensatableSteps); }

    // TCC-specific
    public TccPhase getCurrentPhase() { return currentPhase; }
    public void setCurrentPhase(TccPhase phase) { this.currentPhase = phase; }
    public void putTryResult(String participantId, Object result) { tryResults.put(participantId, result); }
    public Object getTryResult(String participantId) { return tryResults.get(participantId); }
    public Map<String, Object> getTryResults() { return Collections.unmodifiableMap(tryResults); }

    // Topology
    public void setTopologyLayers(List<List<String>> layers) {
        this.topologyLayers = layers != null
                ? layers.stream().map(List::copyOf).toList()
                : List.of();
    }
    public List<List<String>> getTopologyLayers() {
        List<List<String>> snapshot = topologyLayers;
        return snapshot != null ? Collections.unmodifiableList(snapshot) : List.of();
    }
}
