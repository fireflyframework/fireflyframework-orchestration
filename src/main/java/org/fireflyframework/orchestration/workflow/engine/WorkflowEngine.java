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
        return startWorkflow(workflowId, input, null, "api", false);
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

    public Mono<ExecutionState> cancelWorkflow(String correlationId) {
        return persistence.findById(correlationId)
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return Mono.error(new ExecutionNotFoundException(correlationId));
                    }
                    ExecutionState state = opt.get();
                    if (state.status().isTerminal()) {
                        return Mono.error(new IllegalStateException(
                                "Cannot cancel workflow in terminal status: " + state.status()));
                    }
                    ExecutionState cancelled = state.withStatus(ExecutionStatus.CANCELLED);
                    return persistence.save(cancelled).thenReturn(cancelled);
                });
    }

    public Mono<ExecutionState> suspendWorkflow(String correlationId, String reason) {
        return persistence.findById(correlationId)
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return Mono.error(new ExecutionNotFoundException(correlationId));
                    }
                    if (!opt.get().status().canSuspend()) {
                        return Mono.error(new IllegalStateException(
                                "Cannot suspend workflow in status: " + opt.get().status()));
                    }
                    String effectiveReason = reason != null ? reason : "User requested";
                    events.onWorkflowSuspended(opt.get().executionName(), correlationId, effectiveReason);
                    ExecutionState suspended = opt.get().withStatus(ExecutionStatus.SUSPENDED);
                    return persistence.save(suspended).thenReturn(suspended);
                });
    }

    public Mono<ExecutionState> suspendWorkflow(String correlationId) {
        return suspendWorkflow(correlationId, null);
    }

    public Mono<ExecutionState> resumeWorkflow(String correlationId) {
        return persistence.findById(correlationId)
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return Mono.error(new ExecutionNotFoundException(correlationId));
                    }
                    ExecutionState state = opt.get();
                    if (!state.status().canResume()) {
                        return Mono.error(new IllegalStateException(
                                "Cannot resume workflow in status: " + state.status()));
                    }
                    events.onWorkflowResumed(state.executionName(), correlationId);

                    WorkflowDefinition def = registry.get(state.executionName())
                            .orElseThrow(() -> new ExecutionNotFoundException(state.executionName()));

                    ExecutionContext ctx = ExecutionContext.forWorkflow(correlationId, state.executionName());
                    state.variables().forEach(ctx::putVariable);
                    state.headers().forEach(ctx::putHeader);
                    state.stepResults().forEach(ctx::putResult);
                    state.stepStatuses().forEach(ctx::setStepStatus);

                    ExecutionState runningState = state.withStatus(ExecutionStatus.RUNNING);
                    return persistence.save(runningState)
                            .then(executor.execute(def, ctx))
                            .flatMap(resultCtx -> {
                                ExecutionState completed = buildStateFromContext(
                                        state.executionName(), resultCtx, ExecutionStatus.COMPLETED);
                                return persistence.save(completed).thenReturn(completed);
                            })
                            .onErrorResume(error -> {
                                ExecutionState failed = runningState.withFailure(error.getMessage());
                                return persistence.save(failed).thenReturn(failed);
                            });
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
