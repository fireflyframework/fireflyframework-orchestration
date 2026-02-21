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
import org.fireflyframework.orchestration.core.event.OrchestrationEvent;
import org.fireflyframework.orchestration.core.event.OrchestrationEventPublisher;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.tcc.annotation.OnTccComplete;
import org.fireflyframework.orchestration.tcc.annotation.OnTccError;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.fireflyframework.orchestration.tcc.registry.TccRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
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
    private final OrchestrationEventPublisher eventPublisher;

    public TccEngine(TccRegistry registry, OrchestrationEvents events,
                     TccExecutionOrchestrator orchestrator,
                     ExecutionPersistenceProvider persistence, DeadLetterService dlqService,
                     OrchestrationEventPublisher eventPublisher) {
        this.registry = registry;
        this.events = events;
        this.orchestrator = orchestrator;
        this.persistence = persistence;
        this.dlqService = dlqService;
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
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
        Map<String, Object> inputMap = inputs != null ? inputs.asMap() : Map.of();

        return persistInitialState(tcc, finalCtx)
                .then(eventPublisher.publish(OrchestrationEvent.executionStarted(
                        tcc.name, finalCtx.getCorrelationId(), ExecutionPattern.TCC)))
                .then(orchestrator.orchestrate(tcc, inputs, finalCtx))
                .flatMap(result -> handleResult(result, tcc, inputMap))
                .onErrorResume(err -> {
                    log.error("[tcc] Unexpected error executing TCC '{}': {}", tcc.name, err.getMessage(), err);
                    return persistFinalState(finalCtx, ExecutionStatus.FAILED)
                            .then(eventPublisher.publish(OrchestrationEvent.executionCompleted(
                                    tcc.name, finalCtx.getCorrelationId(), ExecutionPattern.TCC, ExecutionStatus.FAILED)))
                            .then(Mono.just(TccResult.failed(tcc.name, finalCtx,
                                    null, null, err, Map.of())));
                });
    }

    private Mono<TccResult> handleResult(TccExecutionOrchestrator.OrchestratorResult result,
                                          TccDefinition tcc, Map<String, Object> inputMap) {
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
        Mono<Void> dlq = (finalStatus == ExecutionStatus.FAILED) ? saveToDlq(tcc.name, ctx, result, finalStatus, inputMap) : Mono.empty();
        Mono<Void> publishCompleted = eventPublisher.publish(OrchestrationEvent.executionCompleted(
                tcc.name, ctx.getCorrelationId(), ExecutionPattern.TCC, finalStatus));

        Mono<Void> callbacks = Mono.empty();
        if (finalStatus == ExecutionStatus.CONFIRMED) {
            callbacks = invokeTccCompleteCallbacks(tcc, ctx, tccResult);
        } else if (finalStatus == ExecutionStatus.FAILED) {
            callbacks = invokeTccErrorCallbacks(tcc, ctx, result.getFailureError());
        }

        return persist.then(dlq).then(publishCompleted).then(callbacks)
                .then(Mono.defer(() -> {
                    if (finalStatus == ExecutionStatus.FAILED && result.getFailureError() != null
                            && shouldSuppressError(tcc, result.getFailureError())) {
                        return Mono.just(TccResult.confirmed(tcc.name, ctx, result.getParticipantOutcomes()));
                    }
                    return Mono.just(tccResult);
                }));
    }

    private Mono<Void> saveToDlq(String tccName, ExecutionContext ctx,
                                  TccExecutionOrchestrator.OrchestratorResult result,
                                  ExecutionStatus status, Map<String, Object> inputs) {
        if (dlqService == null || result.getFailureError() == null) return Mono.empty();
        String failedId = result.getFailedParticipantId() != null ? result.getFailedParticipantId() : "unknown";
        DeadLetterEntry entry = DeadLetterEntry.create(
                tccName, ctx.getCorrelationId(), ExecutionPattern.TCC, failedId,
                status, result.getFailureError(), inputs != null ? inputs : Map.of());
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
                new HashMap<>(ctx.getStepAttempts()), new HashMap<>(ctx.getStepLatenciesMs()),
                new HashMap<>(ctx.getVariables()),
                new HashMap<>(ctx.getHeaders()), Set.copyOf(ctx.getIdempotencyKeys()),
                List.of(), null, ctx.getStartedAt(), Instant.now());
        return persistence.save(state)
                .onErrorResume(err -> {
                    log.warn("[tcc] Failed to persist final state: {}", ctx.getCorrelationId(), err);
                    return Mono.empty();
                });
    }

    private Mono<Void> invokeTccCompleteCallbacks(TccDefinition tcc, ExecutionContext ctx, TccResult tccResult) {
        List<Method> methods = tcc.onTccCompleteMethods;
        if (methods == null || methods.isEmpty()) return Mono.empty();

        Object bean = tcc.bean;
        if (bean == null) return Mono.empty();

        List<Mono<Void>> syncInvocations = new ArrayList<>();
        for (Method m : methods) {
            OnTccComplete ann = m.getAnnotation(OnTccComplete.class);
            Mono<Void> invoke = Mono.fromRunnable(() -> {
                try {
                    m.setAccessible(true);
                    Object[] args = resolveCallbackArgs(m, ctx, tccResult);
                    m.invoke(bean, args);
                } catch (Exception e) {
                    log.warn("[tcc] @OnTccComplete callback '{}' failed", m.getName(), e);
                }
            });
            if (ann.async()) {
                invoke.subscribeOn(Schedulers.boundedElastic()).subscribe();
            } else {
                syncInvocations.add(invoke);
            }
        }
        return Flux.concat(syncInvocations).then();
    }

    private Mono<Void> invokeTccErrorCallbacks(TccDefinition tcc, ExecutionContext ctx, Throwable error) {
        List<Method> methods = tcc.onTccErrorMethods;
        if (methods == null || methods.isEmpty()) return Mono.empty();

        Object bean = tcc.bean;
        if (bean == null || error == null) return Mono.empty();

        List<Mono<Void>> syncInvocations = new ArrayList<>();
        for (Method m : methods) {
            OnTccError ann = m.getAnnotation(OnTccError.class);

            // Filter by errorTypes
            if (ann.errorTypes().length > 0) {
                boolean matches = Arrays.stream(ann.errorTypes()).anyMatch(t -> t.isInstance(error));
                if (!matches) continue;
            }

            Mono<Void> invoke = Mono.fromRunnable(() -> {
                try {
                    m.setAccessible(true);
                    Object[] args = resolveCallbackArgs(m, error, ctx);
                    m.invoke(bean, args);
                } catch (Exception e) {
                    log.warn("[tcc] @OnTccError callback '{}' failed", m.getName(), e);
                }
            });
            if (ann.async()) {
                invoke.subscribeOn(Schedulers.boundedElastic()).subscribe();
            } else {
                syncInvocations.add(invoke);
            }
        }
        return Flux.concat(syncInvocations).then();
    }

    private boolean shouldSuppressError(TccDefinition tcc, Throwable error) {
        List<Method> methods = tcc.onTccErrorMethods;
        if (methods == null || methods.isEmpty()) return false;

        for (Method m : methods) {
            OnTccError ann = m.getAnnotation(OnTccError.class);
            if (ann == null || !ann.suppressError()) continue;

            if (ann.errorTypes().length == 0) return true;
            if (Arrays.stream(ann.errorTypes()).anyMatch(t -> t.isInstance(error))) return true;
        }
        return false;
    }

    private Object[] resolveCallbackArgs(Method method, Object... candidates) {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] args = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> pt = paramTypes[i];
            if (ExecutionContext.class.isAssignableFrom(pt)) {
                for (Object c : candidates) {
                    if (c instanceof ExecutionContext) { args[i] = c; break; }
                }
            } else if (Throwable.class.isAssignableFrom(pt)) {
                for (Object c : candidates) {
                    if (c instanceof Throwable) { args[i] = c; break; }
                }
            } else {
                for (Object c : candidates) {
                    if (c != null && pt.isAssignableFrom(c.getClass())) {
                        args[i] = c;
                        break;
                    }
                }
            }
        }
        return args;
    }
}
