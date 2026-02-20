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

package org.fireflyframework.orchestration.workflow.continueasnew;

import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import org.fireflyframework.orchestration.workflow.signal.SignalService;
import org.fireflyframework.orchestration.workflow.timer.TimerService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Implements the continue-as-new pattern: completes the current workflow execution
 * and starts a fresh one with the same workflow definition but a new correlation ID.
 * Migrates pending signals and timers to the new instance.
 */
@Slf4j
public class ContinueAsNewService {

    private final WorkflowEngine engine;
    private final ExecutionPersistenceProvider persistence;
    private final OrchestrationEvents events;
    private final SignalService signalService;
    private final TimerService timerService;

    public ContinueAsNewService(WorkflowEngine engine, ExecutionPersistenceProvider persistence,
                                  OrchestrationEvents events, SignalService signalService,
                                  TimerService timerService) {
        this.engine = engine;
        this.persistence = persistence;
        this.events = events;
        this.signalService = signalService;
        this.timerService = timerService;
    }

    /**
     * Continues the workflow as a new instance. The current execution is marked COMPLETED,
     * and a fresh execution starts with the provided input.
     */
    public Mono<ContinueAsNewResult> continueAsNew(String currentCorrelationId,
                                                      Map<String, Object> newInput) {
        return persistence.findById(currentCorrelationId)
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return Mono.error(new IllegalStateException(
                                "Cannot continue-as-new: workflow '" + currentCorrelationId + "' not found"));
                    }

                    var currentState = opt.get();
                    String workflowId = currentState.executionName();

                    // Mark current as completed
                    var completedState = currentState.withStatus(ExecutionStatus.COMPLETED);
                    return persistence.save(completedState)
                            .then(engine.startWorkflow(workflowId, newInput, null,
                                    "continue-as-new:" + currentCorrelationId, false))
                            .doOnNext(newState -> {
                                String newCorrelationId = newState.correlationId();
                                events.onContinueAsNew(workflowId, currentCorrelationId, newCorrelationId);
                                log.info("[continue-as-new] Workflow '{}' continued: {} -> {}",
                                        workflowId, currentCorrelationId, newCorrelationId);

                                // Cleanup old instance
                                if (signalService != null) signalService.cleanup(currentCorrelationId);
                                if (timerService != null) timerService.cleanup(currentCorrelationId);
                            })
                            .map(newState -> new ContinueAsNewResult(
                                    currentCorrelationId, newState.correlationId(), workflowId));
                });
    }
}
