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

package org.fireflyframework.orchestration.saga.engine;

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.dlq.DeadLetterEntry;
import org.fireflyframework.orchestration.core.dlq.DeadLetterService;
import org.fireflyframework.orchestration.core.event.OrchestrationEvent;
import org.fireflyframework.orchestration.core.event.OrchestrationEventPublisher;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.saga.compensation.SagaCompensator;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.saga.registry.SagaRegistry;
import org.fireflyframework.orchestration.saga.registry.SagaStepDefinition;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Core saga engine that orchestrates saga execution with compensation,
 * persistence, DLQ integration, and ExpandEach fan-out support.
 */
@Slf4j
public class SagaEngine {

    private final SagaRegistry registry;
    private final OrchestrationEvents events;
    private final SagaCompensator compensator;
    private final SagaExecutionOrchestrator orchestrator;
    private final ExecutionPersistenceProvider persistence;
    private final DeadLetterService dlqService;
    private final OrchestrationEventPublisher eventPublisher;

    public SagaEngine(SagaRegistry registry, OrchestrationEvents events,
                       SagaExecutionOrchestrator orchestrator,
                       ExecutionPersistenceProvider persistence, DeadLetterService dlqService,
                       SagaCompensator compensator, OrchestrationEventPublisher eventPublisher) {
        this.registry = registry;
        this.events = events;
        this.orchestrator = orchestrator;
        this.persistence = persistence;
        this.dlqService = dlqService;
        this.compensator = compensator;
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    public Mono<SagaResult> execute(String sagaName, StepInputs inputs) {
        Objects.requireNonNull(sagaName, "sagaName");
        SagaDefinition saga = registry.getSaga(sagaName);
        return execute(saga, inputs, null);
    }

    public Mono<SagaResult> execute(String sagaName, StepInputs inputs, ExecutionContext ctx) {
        Objects.requireNonNull(sagaName, "sagaName");
        SagaDefinition saga = registry.getSaga(sagaName);
        return execute(saga, inputs, ctx);
    }

    public Mono<SagaResult> execute(String sagaName, Map<String, Object> stepInputs) {
        StepInputs.Builder b = StepInputs.builder();
        if (stepInputs != null) stepInputs.forEach(b::forStepId);
        return execute(sagaName, b.build());
    }

    public Mono<SagaResult> execute(SagaDefinition saga, StepInputs inputs) {
        return execute(saga, inputs, null);
    }

    public Mono<SagaResult> execute(SagaDefinition saga, StepInputs inputs, ExecutionContext ctx) {
        Objects.requireNonNull(saga, "saga");
        final ExecutionContext finalCtx = ctx != null ? ctx : ExecutionContext.forSaga(null, saga.name);

        // Perform optional ExpandEach expansion
        Map<String, Object> overrideInputs = new LinkedHashMap<>();
        SagaDefinition workSaga = maybeExpandSaga(saga, inputs, overrideInputs);

        // Persist initial state
        Mono<Void> persistSetup = persistInitialState(workSaga, finalCtx);

        return persistSetup
                .then(eventPublisher.publish(OrchestrationEvent.executionStarted(
                        workSaga.name, finalCtx.getCorrelationId(), ExecutionPattern.SAGA)))
                .then(orchestrator.orchestrate(workSaga, inputs, finalCtx, overrideInputs))
                .flatMap(result -> handleResult(result, workSaga, inputs, overrideInputs));
    }

    private Mono<SagaResult> handleResult(SagaExecutionOrchestrator.ExecutionResult result,
                                           SagaDefinition workSaga, StepInputs inputs,
                                           Map<String, Object> overrideInputs) {
        String sagaName = workSaga.name;
        ExecutionContext ctx = result.getContext();
        boolean success = !result.isFailed();
        long durationMs = Duration.between(ctx.getStartedAt(), Instant.now()).toMillis();
        events.onCompleted(sagaName, ctx.getCorrelationId(), ExecutionPattern.SAGA, success, durationMs);

        if (success) {
            return persistFinalState(ctx, ExecutionStatus.COMPLETED)
                    .then(eventPublisher.publish(OrchestrationEvent.executionCompleted(
                            sagaName, ctx.getCorrelationId(), ExecutionPattern.SAGA, ExecutionStatus.COMPLETED)))
                    .then(Mono.just(SagaResult.from(sagaName, ctx, Map.of(), result.getStepErrors(), workSaga.steps.keySet())));
        }

        // Failure path: compensate, then DLQ
        Map<String, Object> materializedInputs = materializeInputs(inputs, overrideInputs, ctx);
        return compensator.compensate(sagaName, workSaga, result.getCompletionOrder(), materializedInputs, ctx)
                .then(persistFinalState(ctx, ExecutionStatus.FAILED))
                .then(saveToDlq(sagaName, ctx, result, materializedInputs))
                .then(eventPublisher.publish(OrchestrationEvent.executionCompleted(
                        sagaName, ctx.getCorrelationId(), ExecutionPattern.SAGA, ExecutionStatus.FAILED)))
                .then(Mono.defer(() -> {
                    Map<String, Boolean> compensated = extractCompensationFlags(result.getCompletionOrder(), ctx);
                    return Mono.just(SagaResult.from(sagaName, ctx, compensated, result.getStepErrors(), workSaga.steps.keySet()));
                }));
    }

    private Mono<Void> saveToDlq(String sagaName, ExecutionContext ctx,
                                  SagaExecutionOrchestrator.ExecutionResult result,
                                  Map<String, Object> inputs) {
        if (dlqService == null) return Mono.empty();
        var firstEntry = result.getStepErrors().entrySet().stream().findFirst().orElse(null);
        if (firstEntry == null) return Mono.empty();
        String failedStep = firstEntry.getKey();
        Throwable firstError = firstEntry.getValue();
        DeadLetterEntry entry = DeadLetterEntry.create(
                sagaName, ctx.getCorrelationId(), ExecutionPattern.SAGA, failedStep,
                ExecutionStatus.FAILED, firstError, inputs != null ? inputs : Map.of());
        return dlqService.deadLetter(entry)
                .onErrorResume(err -> {
                    log.warn("[orchestration] Failed to save to DLQ: {}", ctx.getCorrelationId(), err);
                    return Mono.empty();
                });
    }

    private Mono<Void> persistInitialState(SagaDefinition saga, ExecutionContext ctx) {
        if (persistence == null) return Mono.empty();
        ExecutionState state = new ExecutionState(
                ctx.getCorrelationId(), saga.name, ExecutionPattern.SAGA,
                ExecutionStatus.RUNNING, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Set.of(), List.of(), null, ctx.getStartedAt(), Instant.now());
        return persistence.save(state)
                .onErrorResume(err -> {
                    log.warn("[orchestration] Failed to persist initial saga state: {}", ctx.getCorrelationId(), err);
                    return Mono.empty();
                });
    }

    private Mono<Void> persistFinalState(ExecutionContext ctx, ExecutionStatus status) {
        if (persistence == null) return Mono.empty();
        ExecutionState state = new ExecutionState(
                ctx.getCorrelationId(), ctx.getExecutionName(), ExecutionPattern.SAGA, status,
                new HashMap<>(ctx.getStepResults()), new HashMap<>(ctx.getStepStatuses()),
                Map.of(), Map.of(), new HashMap<>(ctx.getVariables()),
                new HashMap<>(ctx.getHeaders()), Set.copyOf(ctx.getIdempotencyKeys()),
                ctx.getTopologyLayers(), null, ctx.getStartedAt(), Instant.now());
        return persistence.save(state)
                .onErrorResume(err -> {
                    log.warn("[orchestration] Failed to persist final saga state: {}", ctx.getCorrelationId(), err);
                    return Mono.empty();
                });
    }

    private Map<String, Object> materializeInputs(StepInputs inputs, Map<String, Object> overrideInputs,
                                                    ExecutionContext ctx) {
        Map<String, Object> materialized = inputs != null ? inputs.materializedView(ctx) : Map.of();
        if (!overrideInputs.isEmpty()) {
            Map<String, Object> combined = new LinkedHashMap<>(materialized);
            combined.putAll(overrideInputs);
            return combined;
        }
        return materialized;
    }

    private Map<String, Boolean> extractCompensationFlags(List<String> completionOrder, ExecutionContext ctx) {
        Map<String, Boolean> compensated = new HashMap<>();
        for (String stepId : completionOrder) {
            if (StepStatus.COMPENSATED.equals(ctx.getStepStatus(stepId))) {
                compensated.put(stepId, true);
            }
        }
        return compensated;
    }

    private SagaDefinition maybeExpandSaga(SagaDefinition saga, StepInputs inputs, Map<String, Object> overrideInputs) {
        if (inputs == null) return saga;
        Map<String, ExpandEach> toExpand = new LinkedHashMap<>();
        for (String stepId : saga.steps.keySet()) {
            Object raw = inputs.rawValue(stepId);
            if (raw instanceof ExpandEach ee) {
                toExpand.put(stepId, ee);
            }
        }
        if (toExpand.isEmpty()) return saga;

        SagaDefinition ns = new SagaDefinition(saga.name, saga.bean, saga.target, saga.layerConcurrency);
        Map<String, List<String>> expandedIds = new LinkedHashMap<>();

        // First pass: create cloned steps for expanded items
        for (SagaStepDefinition sd : saga.steps.values()) {
            ExpandEach ee = toExpand.get(sd.id);
            if (ee == null) continue;
            List<?> items = ee.items();
            List<String> clones = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                String suffix = ee.idSuffixFn().map(fn -> ":" + fn.apply(item).replaceAll("\\s+", "_")).orElse("#" + i);
                String cloneId = sd.id + suffix;
                clones.add(cloneId);
                SagaStepDefinition csd = cloneStepDef(sd, cloneId, new ArrayList<>(sd.dependsOn));
                ns.steps.put(cloneId, csd);
                overrideInputs.put(cloneId, item);
            }
            expandedIds.put(sd.id, clones);
        }

        // Second pass: add non-expanded steps with rewritten dependencies
        for (SagaStepDefinition sd : saga.steps.values()) {
            if (toExpand.containsKey(sd.id)) {
                // Rewrite clone deps
                List<String> clones = expandedIds.getOrDefault(sd.id, List.of());
                for (String cloneId : clones) {
                    SagaStepDefinition csd = ns.steps.get(cloneId);
                    csd.dependsOn = rewriteDeps(sd.dependsOn, expandedIds);
                }
                continue;
            }
            List<String> newDeps = rewriteDeps(sd.dependsOn, expandedIds);
            SagaStepDefinition copy = cloneStepDef(sd, sd.id, newDeps);
            ns.steps.put(sd.id, copy);
        }
        return ns;
    }

    private List<String> rewriteDeps(List<String> deps, Map<String, List<String>> expandedIds) {
        List<String> result = new ArrayList<>();
        for (String dep : deps) {
            List<String> repl = expandedIds.get(dep);
            if (repl != null) result.addAll(repl);
            else result.add(dep);
        }
        return result;
    }

    private SagaStepDefinition cloneStepDef(SagaStepDefinition sd, String newId, List<String> newDeps) {
        SagaStepDefinition csd = new SagaStepDefinition(
                newId, sd.compensateName, newDeps, sd.retry, sd.backoff, sd.timeout,
                sd.idempotencyKey, sd.jitter, sd.jitterFactor, sd.cpuBound, sd.stepMethod);
        csd.stepInvocationMethod = sd.stepInvocationMethod;
        csd.stepBean = sd.stepBean;
        csd.compensateMethod = sd.compensateMethod;
        csd.compensateInvocationMethod = sd.compensateInvocationMethod;
        csd.compensateBean = sd.compensateBean;
        csd.handler = sd.handler;
        csd.compensationRetry = sd.compensationRetry;
        csd.compensationBackoff = sd.compensationBackoff;
        csd.compensationTimeout = sd.compensationTimeout;
        csd.compensationCritical = sd.compensationCritical;
        csd.stepEvent = sd.stepEvent;
        return csd;
    }
}
