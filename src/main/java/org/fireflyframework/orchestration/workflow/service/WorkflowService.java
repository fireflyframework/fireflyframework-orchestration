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

package org.fireflyframework.orchestration.workflow.service;

import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.workflow.child.ChildWorkflowResult;
import org.fireflyframework.orchestration.workflow.child.ChildWorkflowService;
import org.fireflyframework.orchestration.workflow.continueasnew.ContinueAsNewResult;
import org.fireflyframework.orchestration.workflow.continueasnew.ContinueAsNewService;
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import org.fireflyframework.orchestration.workflow.query.WorkflowQueryService;
import org.fireflyframework.orchestration.workflow.search.WorkflowSearchService;
import org.fireflyframework.orchestration.workflow.signal.SignalResult;
import org.fireflyframework.orchestration.workflow.signal.SignalService;
import org.fireflyframework.orchestration.workflow.timer.TimerEntry;
import org.fireflyframework.orchestration.workflow.timer.TimerService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * High-level facade for all workflow operations: execution, lifecycle, signals,
 * timers, child workflows, continue-as-new, queries, and search attributes.
 */
public class WorkflowService {

    private final WorkflowEngine engine;
    private final SignalService signalService;
    private final TimerService timerService;
    private final ChildWorkflowService childWorkflowService;
    private final ContinueAsNewService continueAsNewService;
    private final WorkflowQueryService queryService;
    private final WorkflowSearchService searchService;

    public WorkflowService(WorkflowEngine engine, SignalService signalService,
                            TimerService timerService, ChildWorkflowService childWorkflowService,
                            ContinueAsNewService continueAsNewService,
                            WorkflowQueryService queryService, WorkflowSearchService searchService) {
        this.engine = engine;
        this.signalService = signalService;
        this.timerService = timerService;
        this.childWorkflowService = childWorkflowService;
        this.continueAsNewService = continueAsNewService;
        this.queryService = queryService;
        this.searchService = searchService;
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    public Mono<ExecutionState> startWorkflow(String workflowId, Map<String, Object> input) {
        return engine.startWorkflow(workflowId, input);
    }

    public Mono<ExecutionState> cancelWorkflow(String correlationId) {
        return engine.cancelWorkflow(correlationId);
    }

    public Mono<ExecutionState> suspendWorkflow(String correlationId, String reason) {
        return engine.suspendWorkflow(correlationId, reason);
    }

    public Mono<ExecutionState> resumeWorkflow(String correlationId) {
        return engine.resumeWorkflow(correlationId);
    }

    public Mono<Optional<ExecutionState>> getStatus(String correlationId) {
        return engine.findByCorrelationId(correlationId);
    }

    public Flux<ExecutionState> findByStatus(ExecutionStatus status) {
        return engine.findByStatus(status);
    }

    // ── Signals ───────────────────────────────────────────────────

    public Mono<SignalResult> signal(String correlationId, String signalName, Object payload) {
        return signalService.signal(correlationId, signalName, payload);
    }

    public Mono<Object> waitForSignal(String correlationId, String signalName) {
        return signalService.waitForSignal(correlationId, signalName);
    }

    // ── Timers ────────────────────────────────────────────────────

    public Mono<TimerEntry> scheduleTimer(String correlationId, String timerId, Duration delay, Object data) {
        return timerService.schedule(correlationId, timerId, delay, data);
    }

    public Mono<Boolean> cancelTimer(String correlationId, String timerId) {
        return timerService.cancel(correlationId, timerId);
    }

    public Flux<TimerEntry> getReadyTimers(String correlationId) {
        return timerService.getReadyTimers(correlationId);
    }

    // ── Child Workflows ───────────────────────────────────────────

    public Mono<ChildWorkflowResult> spawnChild(String parentCorrelationId, String childWorkflowId,
                                                  Map<String, Object> input) {
        return childWorkflowService.spawn(parentCorrelationId, childWorkflowId, input);
    }

    public List<String> getChildWorkflows(String parentCorrelationId) {
        return childWorkflowService.getChildren(parentCorrelationId);
    }

    // ── Continue-as-New ───────────────────────────────────────────

    public Mono<ContinueAsNewResult> continueAsNew(String correlationId, Map<String, Object> newInput) {
        return continueAsNewService.continueAsNew(correlationId, newInput);
    }

    // ── Queries ───────────────────────────────────────────────────

    public Mono<Optional<ExecutionStatus>> queryStatus(String correlationId) {
        return queryService.getStatus(correlationId);
    }

    public Mono<Optional<List<String>>> queryCurrentSteps(String correlationId) {
        return queryService.getCurrentSteps(correlationId);
    }

    public Mono<Optional<Map<String, Object>>> queryStepResults(String correlationId) {
        return queryService.getStepResults(correlationId);
    }

    public Mono<Optional<Map<String, Object>>> queryVariables(String correlationId) {
        return queryService.getVariables(correlationId);
    }

    // ── Search Attributes ─────────────────────────────────────────

    public Mono<Void> setSearchAttribute(String correlationId, String key, Object value) {
        return searchService.updateSearchAttribute(correlationId, key, value);
    }

    public Flux<ExecutionState> searchByAttribute(String key, Object value) {
        return searchService.searchByAttribute(key, value);
    }

    public Flux<ExecutionState> searchByAttributes(Map<String, Object> criteria) {
        return searchService.searchByAttributes(criteria);
    }
}
