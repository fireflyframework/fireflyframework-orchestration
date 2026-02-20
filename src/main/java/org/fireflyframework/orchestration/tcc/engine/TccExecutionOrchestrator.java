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
import org.fireflyframework.orchestration.core.context.TccPhase;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.fireflyframework.orchestration.tcc.registry.TccParticipantDefinition;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates TCC transaction execution following the Try-Confirm-Cancel protocol.
 *
 * <p>Execution flow:
 * <ol>
 *   <li>TRY phase: execute all participants' try methods sequentially by order</li>
 *   <li>If all succeed → CONFIRM phase: confirm all participants</li>
 *   <li>If any try fails → CANCEL phase: cancel all participants that succeeded</li>
 * </ol>
 */
@Slf4j
public class TccExecutionOrchestrator {

    private final StepInvoker stepInvoker;
    private final OrchestrationEvents events;

    public TccExecutionOrchestrator(StepInvoker stepInvoker, OrchestrationEvents events) {
        this.stepInvoker = Objects.requireNonNull(stepInvoker, "stepInvoker");
        this.events = Objects.requireNonNull(events, "events");
    }

    public Mono<OrchestratorResult> orchestrate(TccDefinition tccDef, TccInputs inputs, ExecutionContext ctx) {
        events.onStart(tccDef.name, ctx.getCorrelationId(), ExecutionPattern.TCC);
        List<TccParticipantDefinition> ordered = getOrderedParticipants(tccDef);
        OrchestratorState state = new OrchestratorState(tccDef, ordered, inputs, ctx);

        return executeTryPhase(state)
                .flatMap(trySuccess -> {
                    if (trySuccess) {
                        return executeConfirmPhase(state);
                    } else {
                        return executeCancelPhase(state);
                    }
                });
    }

    private List<TccParticipantDefinition> getOrderedParticipants(TccDefinition tccDef) {
        List<TccParticipantDefinition> sorted = new ArrayList<>(tccDef.participants.values());
        sorted.sort(Comparator.comparingInt(p -> p.order));
        return sorted;
    }

    // --- TRY PHASE ---

    private Mono<Boolean> executeTryPhase(OrchestratorState state) {
        state.ctx.setCurrentPhase(TccPhase.TRY);
        events.onPhaseStarted(state.tccDef.name, state.ctx.getCorrelationId(), TccPhase.TRY);
        long phaseStart = System.currentTimeMillis();

        return Flux.fromIterable(state.ordered)
                .concatMap(pd -> executeTry(state, pd))
                .takeWhile(success -> success)
                .collectList()
                .map(results -> {
                    boolean allSucceeded = results.size() == state.ordered.size()
                            && results.stream().allMatch(Boolean::booleanValue);
                    long phaseDuration = System.currentTimeMillis() - phaseStart;
                    if (allSucceeded) {
                        events.onPhaseCompleted(state.tccDef.name, state.ctx.getCorrelationId(),
                                TccPhase.TRY, phaseDuration);
                    } else {
                        events.onPhaseFailed(state.tccDef.name, state.ctx.getCorrelationId(),
                                TccPhase.TRY, state.failureError);
                    }
                    return allSucceeded;
                });
    }

    private Mono<Boolean> executeTry(OrchestratorState state, TccParticipantDefinition pd) {
        String participantId = pd.id;
        Object input = state.inputs != null ? state.inputs.getInput(participantId) : null;

        state.ctx.setStepStatus(participantId, StepStatus.RUNNING);
        state.ctx.markStepStarted(participantId);
        events.onParticipantStarted(state.tccDef.name, state.ctx.getCorrelationId(), participantId, TccPhase.TRY);
        long start = System.currentTimeMillis();

        long timeoutMs = pd.getEffectiveTryTimeout(state.tccDef.timeoutMs);
        int retry = pd.getEffectiveTryRetry(state.tccDef.retryEnabled ? state.tccDef.maxRetries : 0);
        long backoffMs = pd.getEffectiveTryBackoff(state.tccDef.backoffMs);

        return stepInvoker.attemptCall(pd.bean, pd.tryMethod, input, state.ctx,
                        timeoutMs, retry, backoffMs, false, 0.5, participantId, false)
                .doOnNext(result -> {
                    long latency = System.currentTimeMillis() - start;
                    state.ctx.putTryResult(participantId, result);
                    state.ctx.putResult(participantId, result);
                    state.ctx.setStepLatency(participantId, latency);
                    state.ctx.setStepStatus(participantId, StepStatus.DONE);
                    state.triedParticipants.add(participantId);
                    state.participantOutcomes.put(participantId, new TccResult.ParticipantOutcome(
                            participantId, result, true, false, false, null,
                            state.ctx.getAttempts(participantId), latency));
                    events.onParticipantSuccess(state.tccDef.name, state.ctx.getCorrelationId(),
                            participantId, TccPhase.TRY);
                    events.onStepSuccess(state.tccDef.name, state.ctx.getCorrelationId(),
                            participantId, state.ctx.getAttempts(participantId), latency);
                })
                .thenReturn(true)
                .onErrorResume(err -> {
                    long latency = System.currentTimeMillis() - start;
                    state.ctx.setStepLatency(participantId, latency);
                    state.ctx.setStepStatus(participantId, StepStatus.FAILED);
                    state.failedParticipantId = participantId;
                    state.failureError = err;
                    state.failedPhase = TccPhase.TRY;
                    state.participantOutcomes.put(participantId, new TccResult.ParticipantOutcome(
                            participantId, null, false, false, false, err,
                            state.ctx.getAttempts(participantId), latency));
                    events.onParticipantFailed(state.tccDef.name, state.ctx.getCorrelationId(),
                            participantId, TccPhase.TRY, err);
                    events.onStepFailed(state.tccDef.name, state.ctx.getCorrelationId(),
                            participantId, err, state.ctx.getAttempts(participantId));

                    if (pd.optional) {
                        state.triedParticipants.add(participantId);
                        return Mono.just(true);
                    }
                    return Mono.just(false);
                });
    }

    // --- CONFIRM PHASE ---

    private Mono<OrchestratorResult> executeConfirmPhase(OrchestratorState state) {
        state.ctx.setCurrentPhase(TccPhase.CONFIRM);
        events.onPhaseStarted(state.tccDef.name, state.ctx.getCorrelationId(), TccPhase.CONFIRM);
        long phaseStart = System.currentTimeMillis();

        return Flux.fromIterable(state.ordered)
                .filter(pd -> state.triedParticipants.contains(pd.id))
                .concatMap(pd -> executeConfirm(state, pd))
                .collectList()
                .map(results -> {
                    boolean allConfirmed = results.stream().allMatch(Boolean::booleanValue);
                    long phaseDuration = System.currentTimeMillis() - phaseStart;

                    if (allConfirmed) {
                        events.onPhaseCompleted(state.tccDef.name, state.ctx.getCorrelationId(),
                                TccPhase.CONFIRM, phaseDuration);
                        return OrchestratorResult.confirmed(state);
                    } else {
                        events.onPhaseFailed(state.tccDef.name, state.ctx.getCorrelationId(),
                                TccPhase.CONFIRM, state.failureError);
                        return OrchestratorResult.failed(state);
                    }
                });
    }

    private Mono<Boolean> executeConfirm(OrchestratorState state, TccParticipantDefinition pd) {
        String participantId = pd.id;
        Object tryResult = state.ctx.getTryResult(participantId);
        events.onParticipantStarted(state.tccDef.name, state.ctx.getCorrelationId(), participantId, TccPhase.CONFIRM);
        long start = System.currentTimeMillis();

        long timeoutMs = pd.getEffectiveConfirmTimeout(state.tccDef.timeoutMs);
        int retry = pd.getEffectiveConfirmRetry(state.tccDef.retryEnabled ? state.tccDef.maxRetries : 0);
        long backoffMs = pd.getEffectiveConfirmBackoff(state.tccDef.backoffMs);

        return stepInvoker.attemptCall(pd.bean, pd.confirmMethod, tryResult, state.ctx,
                        timeoutMs, retry, backoffMs, false, 0.5, participantId + ":confirm", false)
                .doOnNext(result -> {
                    long latency = System.currentTimeMillis() - start;
                    TccResult.ParticipantOutcome prev = state.participantOutcomes.get(participantId);
                    state.participantOutcomes.put(participantId, new TccResult.ParticipantOutcome(
                            participantId, prev != null ? prev.tryResult() : null,
                            true, true, false, null,
                            state.ctx.getAttempts(participantId), latency));
                    events.onParticipantSuccess(state.tccDef.name, state.ctx.getCorrelationId(),
                            participantId, TccPhase.CONFIRM);
                })
                .thenReturn(true)
                .onErrorResume(err -> {
                    long latency = System.currentTimeMillis() - start;
                    state.failedParticipantId = participantId;
                    state.failureError = err;
                    state.failedPhase = TccPhase.CONFIRM;
                    TccResult.ParticipantOutcome prev = state.participantOutcomes.get(participantId);
                    state.participantOutcomes.put(participantId, new TccResult.ParticipantOutcome(
                            participantId, prev != null ? prev.tryResult() : null,
                            true, false, false, err,
                            state.ctx.getAttempts(participantId), latency));
                    events.onParticipantFailed(state.tccDef.name, state.ctx.getCorrelationId(),
                            participantId, TccPhase.CONFIRM, err);
                    return Mono.just(false);
                });
    }

    // --- CANCEL PHASE ---

    private Mono<OrchestratorResult> executeCancelPhase(OrchestratorState state) {
        state.ctx.setCurrentPhase(TccPhase.CANCEL);
        events.onPhaseStarted(state.tccDef.name, state.ctx.getCorrelationId(), TccPhase.CANCEL);
        long phaseStart = System.currentTimeMillis();

        // Cancel in reverse order of successful tries
        List<TccParticipantDefinition> toCancel = new ArrayList<>();
        for (int i = state.ordered.size() - 1; i >= 0; i--) {
            TccParticipantDefinition pd = state.ordered.get(i);
            if (state.triedParticipants.contains(pd.id)) {
                toCancel.add(pd);
            }
        }

        return Flux.fromIterable(toCancel)
                .concatMap(pd -> executeCancel(state, pd))
                .collectList()
                .map(results -> {
                    long phaseDuration = System.currentTimeMillis() - phaseStart;
                    boolean anyFailed = results.stream().anyMatch(b -> !b);

                    if (anyFailed) {
                        events.onPhaseFailed(state.tccDef.name, state.ctx.getCorrelationId(),
                                TccPhase.CANCEL, state.failureError);
                        return OrchestratorResult.failed(state);
                    } else {
                        events.onPhaseCompleted(state.tccDef.name, state.ctx.getCorrelationId(),
                                TccPhase.CANCEL, phaseDuration);
                        return OrchestratorResult.canceled(state);
                    }
                });
    }

    private Mono<Boolean> executeCancel(OrchestratorState state, TccParticipantDefinition pd) {
        String participantId = pd.id;
        Object tryResult = state.ctx.getTryResult(participantId);
        events.onParticipantStarted(state.tccDef.name, state.ctx.getCorrelationId(), participantId, TccPhase.CANCEL);
        long start = System.currentTimeMillis();

        long timeoutMs = pd.getEffectiveCancelTimeout(state.tccDef.timeoutMs);
        int retry = pd.getEffectiveCancelRetry(state.tccDef.retryEnabled ? state.tccDef.maxRetries : 0);
        long backoffMs = pd.getEffectiveCancelBackoff(state.tccDef.backoffMs);

        return stepInvoker.attemptCall(pd.bean, pd.cancelMethod, tryResult, state.ctx,
                        timeoutMs, retry, backoffMs, false, 0.5, participantId + ":cancel", false)
                .doOnNext(result -> {
                    TccResult.ParticipantOutcome prev = state.participantOutcomes.get(participantId);
                    state.participantOutcomes.put(participantId, new TccResult.ParticipantOutcome(
                            participantId, prev != null ? prev.tryResult() : null,
                            prev != null && prev.trySucceeded(), false, true, prev != null ? prev.error() : null,
                            state.ctx.getAttempts(participantId),
                            System.currentTimeMillis() - start));
                    events.onParticipantSuccess(state.tccDef.name, state.ctx.getCorrelationId(),
                            participantId, TccPhase.CANCEL);
                })
                .thenReturn(true)
                .onErrorResume(err -> {
                    log.warn("[tcc] Cancel failed for participant {} in TCC {}: {}",
                            participantId, state.tccDef.name, err.getMessage());
                    events.onParticipantFailed(state.tccDef.name, state.ctx.getCorrelationId(),
                            participantId, TccPhase.CANCEL, err);
                    // Cancel failures are best-effort — don't stop canceling other participants
                    return Mono.just(false);
                });
    }

    // --- Internal State ---

    private static class OrchestratorState {
        final TccDefinition tccDef;
        final List<TccParticipantDefinition> ordered;
        final TccInputs inputs;
        final ExecutionContext ctx;
        final List<String> triedParticipants = Collections.synchronizedList(new ArrayList<>());
        final Map<String, TccResult.ParticipantOutcome> participantOutcomes = new ConcurrentHashMap<>();
        volatile String failedParticipantId;
        volatile Throwable failureError;
        volatile TccPhase failedPhase;

        OrchestratorState(TccDefinition tccDef, List<TccParticipantDefinition> ordered,
                          TccInputs inputs, ExecutionContext ctx) {
            this.tccDef = tccDef;
            this.ordered = ordered;
            this.inputs = inputs;
            this.ctx = ctx;
        }
    }

    public static class OrchestratorResult {
        private final TccResult.Status status;
        private final Map<String, TccResult.ParticipantOutcome> participantOutcomes;
        private final String failedParticipantId;
        private final TccPhase failedPhase;
        private final Throwable failureError;
        private final ExecutionContext ctx;

        private OrchestratorResult(TccResult.Status status, OrchestratorState state) {
            this.status = status;
            this.participantOutcomes = new LinkedHashMap<>(state.participantOutcomes);
            this.failedParticipantId = state.failedParticipantId;
            this.failedPhase = state.failedPhase;
            this.failureError = state.failureError;
            this.ctx = state.ctx;
        }

        static OrchestratorResult confirmed(OrchestratorState state) {
            return new OrchestratorResult(TccResult.Status.CONFIRMED, state);
        }

        static OrchestratorResult canceled(OrchestratorState state) {
            return new OrchestratorResult(TccResult.Status.CANCELED, state);
        }

        static OrchestratorResult failed(OrchestratorState state) {
            return new OrchestratorResult(TccResult.Status.FAILED, state);
        }

        public TccResult.Status getStatus() { return status; }
        public Map<String, TccResult.ParticipantOutcome> getParticipantOutcomes() { return participantOutcomes; }
        public String getFailedParticipantId() { return failedParticipantId; }
        public TccPhase getFailedPhase() { return failedPhase; }
        public Throwable getFailureError() { return failureError; }
        public ExecutionContext getContext() { return ctx; }
    }
}
