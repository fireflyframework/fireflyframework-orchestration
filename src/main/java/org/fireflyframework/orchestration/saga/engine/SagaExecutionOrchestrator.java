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

import org.fireflyframework.orchestration.core.argument.SetVariable;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.core.topology.TopologyBuilder;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.saga.registry.SagaStepDefinition;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles pure execution orchestration for sagas — builds the DAG topology
 * and executes steps layer by layer with proper concurrency, idempotency,
 * and lifecycle event emission.
 */
@Slf4j
public class SagaExecutionOrchestrator {

    private final StepInvoker stepInvoker;
    private final OrchestrationEvents events;

    public SagaExecutionOrchestrator(StepInvoker stepInvoker, OrchestrationEvents events) {
        this.stepInvoker = Objects.requireNonNull(stepInvoker, "stepInvoker");
        this.events = Objects.requireNonNull(events, "events");
    }

    public Mono<ExecutionResult> orchestrate(SagaDefinition saga, StepInputs inputs,
                                              ExecutionContext ctx, Map<String, Object> overrideInputs) {
        return Mono.fromCallable(() -> {
            List<List<String>> layers = TopologyBuilder.buildLayers(
                    new ArrayList<>(saga.steps.values()),
                    s -> s.id,
                    s -> s.dependsOn);
            ctx.setTopologyLayers(layers);
            var state = new ExecutionState(saga, layers, inputs, ctx, overrideInputs);
            events.onStart(saga.name, ctx.getCorrelationId(), ExecutionPattern.SAGA);
            return state;
        })
        .flatMap(this::executeLayersSequentially)
        .map(state -> new ExecutionResult(
                state.completionOrder, state.failed.get(), state.stepErrors, state.saga, state.ctx));
    }

    private Mono<ExecutionState> executeLayersSequentially(ExecutionState state) {
        return Flux.fromIterable(state.layers)
                .concatMap(layer -> {
                    if (state.failed.get()) return Mono.just(state);
                    return executeLayer(state, layer);
                })
                .last(state);
    }

    private Mono<ExecutionState> executeLayer(ExecutionState state, List<String> layer) {
        if (layer.isEmpty()) return Mono.just(state);
        List<Mono<Void>> stepExecutions = layer.stream()
                .map(stepId -> executeStepWithErrorHandling(state, stepId))
                .toList();
        return Mono.when(stepExecutions).then(Mono.just(state))
                .onErrorResume(err -> {
                    state.failed.set(true);
                    return Mono.just(state);
                });
    }

    private Mono<Void> executeStepWithErrorHandling(ExecutionState state, String stepId) {
        Object input = resolveStepInput(state, stepId);
        return executeStep(state.saga, stepId, input, state.ctx)
                .doOnSuccess(v -> state.completionOrder.add(stepId))
                .onErrorResume(err -> {
                    state.failed.set(true);
                    state.stepErrors.put(stepId, err);
                    return Mono.empty();
                });
    }

    private Mono<Void> executeStep(SagaDefinition saga, String stepId, Object input, ExecutionContext ctx) {
        SagaStepDefinition stepDef = saga.steps.get(stepId);
        if (stepDef == null) {
            return Mono.error(new IllegalArgumentException("Unknown step: " + stepId));
        }

        // Idempotency
        if (stepDef.idempotencyKey != null && !stepDef.idempotencyKey.isEmpty()) {
            if (ctx.hasIdempotencyKey(stepDef.idempotencyKey)) {
                ctx.setStepStatus(stepId, StepStatus.DONE);
                events.onStepSkippedIdempotent(saga.name, ctx.getCorrelationId(), stepId);
                return Mono.empty();
            }
            ctx.addIdempotencyKey(stepDef.idempotencyKey);
        }

        ctx.setStepStatus(stepId, StepStatus.RUNNING);
        ctx.markStepStarted(stepId);
        events.onStepStarted(saga.name, ctx.getCorrelationId(), stepId);
        final long start = System.currentTimeMillis();

        Mono<Object> execution = createStepExecution(saga, stepDef, stepId, input, ctx);

        return execution
                .doOnNext(result -> handleStepSuccess(saga, stepDef, stepId, result, ctx, start))
                .switchIfEmpty(Mono.defer(() -> {
                    handleStepSuccess(saga, stepDef, stepId, null, ctx, start);
                    return Mono.empty();
                }))
                .then()
                .doOnError(err -> handleStepError(saga, stepId, err, ctx, start));
    }

    private Mono<Object> createStepExecution(SagaDefinition saga, SagaStepDefinition stepDef,
                                              String stepId, Object input, ExecutionContext ctx) {
        long timeoutMs = stepDef.timeout != null ? stepDef.timeout.toMillis() : 0L;
        long backoffMs = stepDef.backoff != null ? stepDef.backoff.toMillis() : 0L;

        if (stepDef.handler != null) {
            return stepInvoker.attemptCallHandler(stepDef.handler, input, ctx,
                    timeoutMs, stepDef.retry, backoffMs, stepDef.jitter, stepDef.jitterFactor, stepId);
        } else {
            var invokeMethod = stepDef.stepInvocationMethod != null ? stepDef.stepInvocationMethod : stepDef.stepMethod;
            Object targetBean = stepDef.stepBean != null ? stepDef.stepBean : saga.bean;
            return stepInvoker.attemptCall(targetBean, invokeMethod, input, ctx,
                    timeoutMs, stepDef.retry, backoffMs, stepDef.jitter, stepDef.jitterFactor, stepId, stepDef.cpuBound);
        }
    }

    private void handleStepSuccess(SagaDefinition saga, SagaStepDefinition stepDef,
                                    String stepId, Object result, ExecutionContext ctx, long start) {
        ctx.putResult(stepId, result);
        if (stepDef.stepMethod != null) {
            var setVarAnn = stepDef.stepMethod.getAnnotation(SetVariable.class);
            if (setVarAnn != null && !setVarAnn.value().isBlank()) {
                ctx.putVariable(setVarAnn.value(), result);
            }
        }
        long latency = System.currentTimeMillis() - start;
        ctx.setStepLatency(stepId, latency);
        ctx.setStepStatus(stepId, StepStatus.DONE);
        events.onStepSuccess(saga.name, ctx.getCorrelationId(), stepId, ctx.getAttempts(stepId), latency);
    }

    private void handleStepError(SagaDefinition saga, String stepId, Throwable err,
                                  ExecutionContext ctx, long start) {
        long latency = System.currentTimeMillis() - start;
        ctx.setStepLatency(stepId, latency);
        ctx.setStepStatus(stepId, StepStatus.FAILED);
        events.onStepFailed(saga.name, ctx.getCorrelationId(), stepId, err, ctx.getAttempts(stepId));
    }

    private Object resolveStepInput(ExecutionState state, String stepId) {
        if (state.overrideInputs.containsKey(stepId)) return state.overrideInputs.get(stepId);
        return state.inputs != null ? state.inputs.resolveFor(stepId, state.ctx) : null;
    }

    private static class ExecutionState {
        final SagaDefinition saga;
        final List<List<String>> layers;
        final StepInputs inputs;
        final ExecutionContext ctx;
        final Map<String, Object> overrideInputs;
        final List<String> completionOrder = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean failed = new AtomicBoolean(false);
        final Map<String, Throwable> stepErrors = new ConcurrentHashMap<>();

        ExecutionState(SagaDefinition saga, List<List<String>> layers, StepInputs inputs,
                       ExecutionContext ctx, Map<String, Object> overrideInputs) {
            this.saga = saga;
            this.layers = layers;
            this.inputs = inputs;
            this.ctx = ctx;
            this.overrideInputs = overrideInputs != null ? overrideInputs : Map.of();
        }
    }

    public static class ExecutionResult {
        private final List<String> completionOrder;
        private final boolean failed;
        private final Map<String, Throwable> stepErrors;
        private final SagaDefinition saga;
        private final ExecutionContext context;

        public ExecutionResult(List<String> completionOrder, boolean failed,
                               Map<String, Throwable> stepErrors, SagaDefinition saga, ExecutionContext context) {
            this.completionOrder = completionOrder;
            this.failed = failed;
            this.stepErrors = stepErrors;
            this.saga = saga;
            this.context = context;
        }

        public List<String> getCompletionOrder() { return completionOrder; }
        public boolean isFailed() { return failed; }
        public Map<String, Throwable> getStepErrors() { return stepErrors; }
        public SagaDefinition getSaga() { return saga; }
        public ExecutionContext getContext() { return context; }
    }
}
