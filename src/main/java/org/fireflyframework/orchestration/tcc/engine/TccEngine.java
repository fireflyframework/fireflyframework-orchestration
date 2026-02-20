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

package org.fireflyframework.orchestration.tcc.engine;

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.dlq.DeadLetterEntry;
import org.fireflyframework.orchestration.core.dlq.DeadLetterService;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.fireflyframework.orchestration.tcc.registry.TccRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Core TCC engine that orchestrates TCC transaction execution with
 * persistence, DLQ integration, and lifecycle events.
 */
@Slf4j
public class TccEngine {

    private final TccRegistry registry;
    private final OrchestrationEvents events;
    private final TccExecutionOrchestrator orchestrator;
    private final ExecutionPersistenceProvider persistence;
    private final DeadLetterService dlqService;

    public TccEngine(TccRegistry registry, OrchestrationEvents events,
                     TccExecutionOrchestrator orchestrator,
                     ExecutionPersistenceProvider persistence, DeadLetterService dlqService) {
        this.registry = registry;
        this.events = events;
        this.orchestrator = orchestrator;
        this.persistence = persistence;
        this.dlqService = dlqService;
    }

    public Mono<TccResult> execute(String tccName, TccInputs inputs) {
        Objects.requireNonNull(tccName, "tccName");
        TccDefinition tcc = registry.getTcc(tccName);
        return execute(tcc, inputs, null);
    }

    public Mono<TccResult> execute(String tccName, TccInputs inputs, ExecutionContext ctx) {
        Objects.requireNonNull(tccName, "tccName");
        TccDefinition tcc = registry.getTcc(tccName);
        return execute(tcc, inputs, ctx);
    }

    public Mono<TccResult> execute(String tccName, Map<String, Object> participantInputs) {
        TccInputs inputs = participantInputs != null ? TccInputs.of(participantInputs) : TccInputs.empty();
        return execute(tccName, inputs);
    }

    public Mono<TccResult> execute(TccDefinition tcc, TccInputs inputs) {
        return execute(tcc, inputs, null);
    }

    public Mono<TccResult> execute(TccDefinition tcc, TccInputs inputs, ExecutionContext ctx) {
        Objects.requireNonNull(tcc, "tcc");
        final ExecutionContext finalCtx = ctx != null ? ctx : ExecutionContext.forTcc(null, tcc.name);

        return persistInitialState(tcc, finalCtx)
                .then(orchestrator.orchestrate(tcc, inputs, finalCtx))
                .flatMap(result -> handleResult(result, tcc))
                .onErrorResume(err -> {
                    log.error("[tcc] Unexpected error executing TCC '{}': {}", tcc.name, err.getMessage(), err);
                    return persistFinalState(finalCtx, ExecutionStatus.FAILED)
                            .then(Mono.just(TccResult.failed(tcc.name, finalCtx,
                                    null, null, err, Map.of())));
                });
    }

    private Mono<TccResult> handleResult(TccExecutionOrchestrator.OrchestratorResult result,
                                          TccDefinition tcc) {
        ExecutionContext ctx = result.getContext();
        long durationMs = Duration.between(ctx.getStartedAt(), Instant.now()).toMillis();
        boolean success = result.getStatus() == TccResult.Status.CONFIRMED;
        events.onCompleted(tcc.name, ctx.getCorrelationId(), ExecutionPattern.TCC, success, durationMs);

        TccResult tccResult;
        ExecutionStatus finalStatus;

        switch (result.getStatus()) {
            case CONFIRMED -> {
                finalStatus = ExecutionStatus.CONFIRMED;
                tccResult = TccResult.confirmed(tcc.name, ctx, result.getParticipantOutcomes());
            }
            case CANCELED -> {
                finalStatus = ExecutionStatus.CANCELED;
                tccResult = TccResult.canceled(tcc.name, ctx, result.getFailedParticipantId(),
                        result.getFailedPhase(), result.getFailureError(), result.getParticipantOutcomes());
            }
            default -> {
                finalStatus = ExecutionStatus.FAILED;
                tccResult = TccResult.failed(tcc.name, ctx, result.getFailedParticipantId(),
                        result.getFailedPhase(), result.getFailureError(), result.getParticipantOutcomes());
            }
        }

        Mono<Void> persist = persistFinalState(ctx, finalStatus);
        // Only DLQ truly failed transactions — CANCELED is controlled rollback, not a failure
        Mono<Void> dlq = (finalStatus == ExecutionStatus.FAILED) ? saveToDlq(tcc.name, ctx, result, finalStatus) : Mono.empty();

        return persist.then(dlq).thenReturn(tccResult);
    }

    private Mono<Void> saveToDlq(String tccName, ExecutionContext ctx,
                                  TccExecutionOrchestrator.OrchestratorResult result,
                                  ExecutionStatus status) {
        if (dlqService == null || result.getFailureError() == null) return Mono.empty();
        String failedId = result.getFailedParticipantId() != null ? result.getFailedParticipantId() : "unknown";
        DeadLetterEntry entry = DeadLetterEntry.create(
                tccName, ctx.getCorrelationId(), ExecutionPattern.TCC, failedId,
                status, result.getFailureError(), Map.of());
        return dlqService.deadLetter(entry)
                .onErrorResume(err -> {
                    log.warn("[tcc] Failed to save to DLQ: {}", ctx.getCorrelationId(), err);
                    return Mono.empty();
                });
    }

    private Mono<Void> persistInitialState(TccDefinition tcc, ExecutionContext ctx) {
        if (persistence == null) return Mono.empty();
        ExecutionState state = new ExecutionState(
                ctx.getCorrelationId(), tcc.name, ExecutionPattern.TCC,
                ExecutionStatus.TRYING, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Set.of(), List.of(), null, ctx.getStartedAt(), Instant.now());
        return persistence.save(state)
                .onErrorResume(err -> {
                    log.warn("[tcc] Failed to persist initial state: {}", ctx.getCorrelationId(), err);
                    return Mono.empty();
                });
    }

    private Mono<Void> persistFinalState(ExecutionContext ctx, ExecutionStatus status) {
        if (persistence == null) return Mono.empty();
        ExecutionState state = new ExecutionState(
                ctx.getCorrelationId(), ctx.getExecutionName(), ExecutionPattern.TCC, status,
                new HashMap<>(ctx.getStepResults()), new HashMap<>(ctx.getStepStatuses()),
                Map.of(), Map.of(), new HashMap<>(ctx.getVariables()),
                new HashMap<>(ctx.getHeaders()), Set.copyOf(ctx.getIdempotencyKeys()),
                List.of(), null, ctx.getStartedAt(), Instant.now());
        return persistence.save(state)
                .onErrorResume(err -> {
                    log.warn("[tcc] Failed to persist final state: {}", ctx.getCorrelationId(), err);
                    return Mono.empty();
                });
    }
}
