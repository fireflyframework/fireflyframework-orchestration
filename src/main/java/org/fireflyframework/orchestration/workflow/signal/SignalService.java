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

package org.fireflyframework.orchestration.workflow.signal;

import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Delivers external signals to running or suspended workflow instances.
 * Signals are stored per-instance and can resume waiting steps.
 */
@Slf4j
public class SignalService {

    private final ExecutionPersistenceProvider persistence;
    private final OrchestrationEvents events;

    // In-memory signal mailbox: correlationId -> list of pending signals
    private final Map<String, CopyOnWriteArrayList<PendingSignal>> mailbox = new ConcurrentHashMap<>();
    // Waiters: correlationId:signalName -> Sinks
    private final Map<String, reactor.core.publisher.Sinks.One<Object>> waiters = new ConcurrentHashMap<>();

    public record PendingSignal(String signalName, Object payload) {}

    public SignalService(ExecutionPersistenceProvider persistence, OrchestrationEvents events) {
        this.persistence = persistence;
        this.events = events;
    }

    /**
     * Sends a signal to a workflow instance. If a step is waiting for this signal,
     * it will be resumed immediately.
     */
    public Mono<SignalResult> signal(String correlationId, String signalName, Object payload) {
        return persistence.findById(correlationId)
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        log.warn("[signal] No workflow instance found: {}", correlationId);
                        return Mono.just(SignalResult.notDelivered(correlationId, signalName));
                    }

                    var state = opt.get();
                    if (state.status().isTerminal()) {
                        log.warn("[signal] Cannot signal terminal instance: {} (status={})", correlationId, state.status());
                        return Mono.just(SignalResult.notDelivered(correlationId, signalName));
                    }

                    // Store the signal in the mailbox
                    mailbox.computeIfAbsent(correlationId, k -> new CopyOnWriteArrayList<>())
                            .add(new PendingSignal(signalName, payload));

                    // Check if anyone is waiting for this signal
                    String waiterKey = correlationId + ":" + signalName;
                    var sink = waiters.remove(waiterKey);
                    String resumedStepId = null;

                    if (sink != null) {
                        Object effectivePayload = payload != null ? payload : signalName;
                        sink.tryEmitValue(effectivePayload);
                        resumedStepId = signalName;
                        log.info("[signal] Signal '{}' delivered and resumed waiter for instance '{}'",
                                signalName, correlationId);
                    } else {
                        log.info("[signal] Signal '{}' buffered for instance '{}' (no active waiter)",
                                signalName, correlationId);
                    }

                    events.onSignalDelivered(state.executionName(), correlationId, signalName);
                    return Mono.just(SignalResult.delivered(correlationId, signalName, resumedStepId));
                });
    }

    /**
     * Wait for a signal. Returns a Mono that completes when the signal is received,
     * or when a buffered signal is found.
     */
    public Mono<Object> waitForSignal(String correlationId, String signalName) {
        // Check if signal is already buffered
        var signals = mailbox.get(correlationId);
        if (signals != null) {
            for (var it = signals.iterator(); it.hasNext(); ) {
                PendingSignal ps = it.next();
                if (ps.signalName().equals(signalName)) {
                    signals.remove(ps);
                    return Mono.just(ps.payload() != null ? ps.payload() : signalName);
                }
            }
        }

        // Register a waiter
        String waiterKey = correlationId + ":" + signalName;
        var sink = reactor.core.publisher.Sinks.one();
        waiters.put(waiterKey, sink);
        return sink.asMono();
    }

    /**
     * Returns pending (undelivered) signals for a workflow instance.
     */
    public java.util.List<PendingSignal> getPendingSignals(String correlationId) {
        var signals = mailbox.get(correlationId);
        return signals != null ? java.util.List.copyOf(signals) : java.util.List.of();
    }

    /**
     * Cleans up all signal state for a completed workflow instance.
     */
    public void cleanup(String correlationId) {
        mailbox.remove(correlationId);
        waiters.entrySet().removeIf(e -> e.getKey().startsWith(correlationId + ":"));
    }
}
