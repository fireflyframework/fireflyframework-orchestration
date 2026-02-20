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

package org.fireflyframework.orchestration.workflow.engine;

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.exception.ExecutionNotFoundException;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
public class WorkflowEngine {

    private final WorkflowRegistry registry;
    private final WorkflowExecutor executor;
    private final ExecutionPersistenceProvider persistence;
    private final OrchestrationEvents events;

    public WorkflowEngine(WorkflowRegistry registry, WorkflowExecutor executor,
                           ExecutionPersistenceProvider persistence, OrchestrationEvents events) {
        this.registry = registry;
        this.executor = executor;
        this.persistence = persistence;
        this.events = events;
    }

    public Mono<ExecutionState> startWorkflow(String workflowId, Map<String, Object> input) {
        return startWorkflow(workflowId, input, null, null, false);
    }

    public Mono<ExecutionState> startWorkflow(String workflowId, Map<String, Object> input,
                                               String correlationId, String triggeredBy, boolean dryRun) {
        return Mono.defer(() -> {
            WorkflowDefinition def = registry.get(workflowId)
                    .orElseThrow(() -> new ExecutionNotFoundException(workflowId));

            ExecutionContext ctx = ExecutionContext.forWorkflow(correlationId, workflowId, dryRun);
            if (input != null) {
                input.forEach(ctx::putVariable);
            }

            ExecutionState initialState = new ExecutionState(
                    ctx.getCorrelationId(), workflowId, ExecutionPattern.WORKFLOW,
                    ExecutionStatus.RUNNING, Map.of(), Map.of(), Map.of(), Map.of(),
                    input != null ? input : Map.of(), Map.of(), Set.of(), List.of(),
                    null, Instant.now(), Instant.now());

            events.onStart(workflowId, ctx.getCorrelationId(), ExecutionPattern.WORKFLOW);

            return persistence.save(initialState)
                    .then(executor.execute(def, ctx))
                    .flatMap(resultCtx -> {
                        ExecutionState completedState = buildStateFromContext(workflowId, resultCtx, ExecutionStatus.COMPLETED);
                        return persistence.save(completedState).thenReturn(completedState);
                    })
                    .onErrorResume(error -> {
                        ExecutionState failedState = initialState.withFailure(error.getMessage());
                        return persistence.save(failedState).thenReturn(failedState);
                    })
                    .doOnSuccess(state -> events.onCompleted(workflowId, ctx.getCorrelationId(),
                            ExecutionPattern.WORKFLOW, state.status() == ExecutionStatus.COMPLETED,
                            Duration.between(state.startedAt(), Instant.now()).toMillis()));
        });
    }

    public Mono<Void> cancelWorkflow(String correlationId) {
        return persistence.updateStatus(correlationId, ExecutionStatus.CANCELLED);
    }

    public Mono<Void> suspendWorkflow(String correlationId) {
        return persistence.findById(correlationId)
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return Mono.error(new ExecutionNotFoundException(correlationId));
                    }
                    if (!opt.get().status().canSuspend()) {
                        return Mono.error(new IllegalStateException("Cannot suspend"));
                    }
                    events.onWorkflowSuspended(opt.get().executionName(), correlationId, "User requested");
                    return persistence.updateStatus(correlationId, ExecutionStatus.SUSPENDED);
                });
    }

    public Mono<Void> resumeWorkflow(String correlationId) {
        return persistence.findById(correlationId)
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return Mono.error(new ExecutionNotFoundException(correlationId));
                    }
                    if (!opt.get().status().canResume()) {
                        return Mono.error(new IllegalStateException("Cannot resume"));
                    }
                    events.onWorkflowResumed(opt.get().executionName(), correlationId);
                    return persistence.updateStatus(correlationId, ExecutionStatus.RUNNING);
                });
    }

    public Flux<ExecutionState> findByStatus(ExecutionStatus status) {
        return persistence.findByStatus(status);
    }

    public Mono<Optional<ExecutionState>> findByCorrelationId(String correlationId) {
        return persistence.findById(correlationId);
    }

    public void registerWorkflow(WorkflowDefinition definition) {
        registry.register(definition);
    }

    private ExecutionState buildStateFromContext(String workflowId, ExecutionContext ctx, ExecutionStatus status) {
        return new ExecutionState(ctx.getCorrelationId(), workflowId, ExecutionPattern.WORKFLOW, status,
                new HashMap<>(ctx.getStepResults()), new HashMap<>(ctx.getStepStatuses()),
                Map.of(), Map.of(), new HashMap<>(ctx.getVariables()),
                new HashMap<>(ctx.getHeaders()), Set.copyOf(ctx.getIdempotencyKeys()),
                ctx.getTopologyLayers(), null, ctx.getStartedAt(), Instant.now());
    }
}
