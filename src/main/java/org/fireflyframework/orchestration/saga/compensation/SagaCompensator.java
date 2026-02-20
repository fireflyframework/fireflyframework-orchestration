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

package org.fireflyframework.orchestration.saga.compensation;

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.exception.CompensationException;
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepHandler;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.core.topology.TopologyBuilder;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.saga.registry.SagaStepDefinition;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;

/**
 * Compensation coordinator implementing five strategies for saga rollback:
 * {@link CompensationPolicy#STRICT_SEQUENTIAL},
 * {@link CompensationPolicy#GROUPED_PARALLEL},
 * {@link CompensationPolicy#RETRY_WITH_BACKOFF},
 * {@link CompensationPolicy#CIRCUIT_BREAKER},
 * {@link CompensationPolicy#BEST_EFFORT_PARALLEL}.
 */
@Slf4j
public class SagaCompensator {

    private final OrchestrationEvents events;
    private final CompensationPolicy policy;
    private final StepInvoker invoker;

    public SagaCompensator(OrchestrationEvents events, CompensationPolicy policy, StepInvoker invoker) {
        this.events = events;
        this.policy = policy != null ? policy : CompensationPolicy.STRICT_SEQUENTIAL;
        this.invoker = invoker;
    }

    public Mono<Void> compensate(String sagaName, SagaDefinition saga,
                                  List<String> completionOrder, Map<String, Object> stepInputs,
                                  ExecutionContext ctx) {
        events.onCompensationStarted(sagaName, ctx.getCorrelationId());
        return switch (policy) {
            case STRICT_SEQUENTIAL -> compensateSequential(sagaName, saga, completionOrder, stepInputs, ctx);
            case GROUPED_PARALLEL -> compensateGroupedByLayer(sagaName, saga, completionOrder, stepInputs, ctx);
            case RETRY_WITH_BACKOFF -> compensateWithRetries(sagaName, saga, completionOrder, stepInputs, ctx);
            case CIRCUIT_BREAKER -> compensateWithCircuitBreaker(sagaName, saga, completionOrder, stepInputs, ctx);
            case BEST_EFFORT_PARALLEL -> compensateBestEffort(sagaName, saga, completionOrder, stepInputs, ctx);
        };
    }

    // --- STRICT_SEQUENTIAL ---
    private Mono<Void> compensateSequential(String sagaName, SagaDefinition saga,
                                             List<String> completionOrder, Map<String, Object> stepInputs,
                                             ExecutionContext ctx) {
        List<String> reversed = new ArrayList<>(completionOrder);
        Collections.reverse(reversed);
        return Flux.fromIterable(reversed)
                .concatMap(stepId -> compensateOne(sagaName, saga, stepId, stepInputs, ctx))
                .then();
    }

    // --- GROUPED_PARALLEL ---
    private Mono<Void> compensateGroupedByLayer(String sagaName, SagaDefinition saga,
                                                 List<String> completionOrder, Map<String, Object> stepInputs,
                                                 ExecutionContext ctx) {
        List<List<String>> layers = TopologyBuilder.buildLayers(
                new ArrayList<>(saga.steps.values()), s -> s.id, s -> s.dependsOn);
        Set<String> completed = new LinkedHashSet<>(completionOrder);
        List<List<String>> filtered = new ArrayList<>();
        for (List<String> layer : layers) {
            List<String> lf = layer.stream().filter(completed::contains).toList();
            if (!lf.isEmpty()) filtered.add(lf);
        }
        Collections.reverse(filtered);
        return Flux.fromIterable(filtered)
                .concatMap(layer -> Mono.when(layer.stream()
                        .map(stepId -> compensateOne(sagaName, saga, stepId, stepInputs, ctx))
                        .toList()))
                .then();
    }

    // --- RETRY_WITH_BACKOFF ---
    private Mono<Void> compensateWithRetries(String sagaName, SagaDefinition saga,
                                              List<String> completionOrder, Map<String, Object> stepInputs,
                                              ExecutionContext ctx) {
        List<String> reversed = new ArrayList<>(completionOrder);
        Collections.reverse(reversed);
        return Flux.fromIterable(reversed)
                .concatMap(stepId -> compensateOneWithRetry(sagaName, saga, stepId, stepInputs, ctx, true))
                .then();
    }

    // --- CIRCUIT_BREAKER ---
    private Mono<Void> compensateWithCircuitBreaker(String sagaName, SagaDefinition saga,
                                                     List<String> completionOrder, Map<String, Object> stepInputs,
                                                     ExecutionContext ctx) {
        List<String> reversed = new ArrayList<>(completionOrder);
        Collections.reverse(reversed);
        final boolean[] circuitOpen = {false};
        return Flux.fromIterable(reversed)
                .concatMap(stepId -> {
                    if (circuitOpen[0]) return Mono.empty();
                    return compensateOneWithRetry(sagaName, saga, stepId, stepInputs, ctx, false)
                            .onErrorResume(err -> {
                                SagaStepDefinition sd = saga.steps.get(stepId);
                                if (sd != null && sd.compensationCritical) {
                                    circuitOpen[0] = true;
                                    log.warn("[orchestration] Circuit opened after critical compensation failure: step={}", stepId);
                                }
                                return Mono.empty();
                            });
                })
                .then();
    }

    // --- BEST_EFFORT_PARALLEL ---
    private Mono<Void> compensateBestEffort(String sagaName, SagaDefinition saga,
                                             List<String> completionOrder, Map<String, Object> stepInputs,
                                             ExecutionContext ctx) {
        return Flux.fromIterable(completionOrder)
                .flatMap(stepId -> compensateOne(sagaName, saga, stepId, stepInputs, ctx))
                .then();
    }

    // --- Single-step compensation (basic) ---
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Void> compensateOne(String sagaName, SagaDefinition saga, String stepId,
                                      Map<String, Object> stepInputs, ExecutionContext ctx) {
        SagaStepDefinition sd = saga.steps.get(stepId);
        if (sd == null) return Mono.empty();

        // Handler-based compensation
        if (sd.handler != null) {
            Object arg = resolveCompensationArg(sd, stepInputs, ctx);
            return ((Mono<Void>) ((StepHandler) sd.handler).compensate(arg, ctx))
                    .doOnSuccess(v -> markCompensated(sagaName, stepId, ctx))
                    .doOnError(err -> markCompensationFailed(sagaName, stepId, err, ctx))
                    .onErrorResume(err -> Mono.empty())
                    .then();
        }

        // Method-based compensation
        Method comp = sd.compensateInvocationMethod != null ? sd.compensateInvocationMethod : sd.compensateMethod;
        if (comp == null) return Mono.empty();
        Object arg = resolveMethodCompensationArg(comp, stepInputs.get(stepId), ctx.getResult(stepId));
        Object targetBean = sd.compensateBean != null ? sd.compensateBean : saga.bean;
        return invoker.attemptCall(targetBean, comp, arg, ctx, 0, 0, 0, false, 0, stepId + "_comp", false)
                .doOnNext(obj -> ctx.putCompensationResult(stepId, obj))
                .doOnSuccess(v -> markCompensated(sagaName, stepId, ctx))
                .doOnError(err -> markCompensationFailed(sagaName, stepId, err, ctx))
                .onErrorResume(err -> Mono.empty())
                .then();
    }

    // --- Single-step compensation with retry ---
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Mono<Void> compensateOneWithRetry(String sagaName, SagaDefinition saga, String stepId,
                                               Map<String, Object> stepInputs, ExecutionContext ctx,
                                               boolean swallowErrors) {
        SagaStepDefinition sd = saga.steps.get(stepId);
        if (sd == null) return Mono.empty();

        int retry = sd.compensationRetry != null ? sd.compensationRetry : sd.retry;
        long backoffMs = (sd.compensationBackoff != null ? sd.compensationBackoff : sd.backoff).toMillis();
        long timeoutMs = (sd.compensationTimeout != null ? sd.compensationTimeout : sd.timeout).toMillis();

        if (sd.handler != null) {
            Object arg = resolveCompensationArg(sd, stepInputs, ctx);
            Mono<Void> compensation = Mono.defer(() -> ((Mono<Void>) ((StepHandler) sd.handler).compensate(arg, ctx)));
            if (timeoutMs > 0) compensation = compensation.timeout(Duration.ofMillis(timeoutMs));
            if (retry > 0 && backoffMs > 0) {
                compensation = compensation.retryWhen(reactor.util.retry.Retry.fixedDelay(retry, Duration.ofMillis(backoffMs)));
            } else if (retry > 0) {
                compensation = compensation.retry(retry);
            }
            Mono<Void> mono = compensation
                    .doOnSuccess(v -> markCompensated(sagaName, stepId, ctx))
                    .doOnError(err -> markCompensationFailed(sagaName, stepId, err, ctx));
            if (swallowErrors) mono = mono.onErrorResume(err -> Mono.empty());
            return mono;
        }

        Method comp = sd.compensateInvocationMethod != null ? sd.compensateInvocationMethod : sd.compensateMethod;
        if (comp == null) return Mono.empty();
        Object arg = resolveMethodCompensationArg(comp, stepInputs.get(stepId), ctx.getResult(stepId));
        Object targetBean = sd.compensateBean != null ? sd.compensateBean : saga.bean;
        Mono<Object> mono = invoker.attemptCall(targetBean, comp, arg, ctx,
                        Math.max(0, timeoutMs), Math.max(0, retry), Math.max(0, backoffMs),
                        sd.jitter, sd.jitterFactor, stepId + "_comp", false)
                .doOnNext(obj -> ctx.putCompensationResult(stepId, obj))
                .doOnSuccess(v -> markCompensated(sagaName, stepId, ctx))
                .doOnError(err -> markCompensationFailed(sagaName, stepId, err, ctx));
        if (swallowErrors) mono = mono.onErrorResume(err -> Mono.empty());
        return mono.then();
    }

    @SuppressWarnings("unchecked")
    private Object resolveCompensationArg(SagaStepDefinition sd, Map<String, Object> stepInputs, ExecutionContext ctx) {
        Object input = stepInputs.get(sd.id);
        Object result = ctx.getResult(sd.id);
        return input != null ? input : result;
    }

    private Object resolveMethodCompensationArg(Method comp, Object input, Object result) {
        Class<?>[] params = comp.getParameterTypes();
        if (params.length == 0) return null;
        Class<?> t = params[0];
        if (input != null && t.isAssignableFrom(input.getClass())) return input;
        if (result != null && t.isAssignableFrom(result.getClass())) return result;
        return input != null ? input : result;
    }

    private void markCompensated(String sagaName, String stepId, ExecutionContext ctx) {
        ctx.setStepStatus(stepId, StepStatus.COMPENSATED);
        events.onStepCompensated(sagaName, ctx.getCorrelationId(), stepId);
    }

    private void markCompensationFailed(String sagaName, String stepId, Throwable err, ExecutionContext ctx) {
        ctx.putCompensationError(stepId, err);
        events.onStepCompensationFailed(sagaName, ctx.getCorrelationId(), stepId, err);
    }
}
