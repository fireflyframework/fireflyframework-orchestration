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

package org.fireflyframework.orchestration.core.backpressure;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backpressure strategy that implements the circuit breaker pattern.
 * <p>
 * The circuit breaker has three states:
 * <ul>
 *     <li><b>CLOSED</b> — normal operation; failures are counted</li>
 *     <li><b>OPEN</b> — requests are rejected immediately; after the recovery timeout,
 *         transitions to HALF_OPEN</li>
 *     <li><b>HALF_OPEN</b> — a limited number of probe calls are allowed; if they
 *         succeed, the breaker closes; if any fail, it reopens</li>
 * </ul>
 */
@Slf4j
public class CircuitBreakerBackpressureStrategy implements BackpressureStrategy {

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final int failureThreshold;
    private final Duration recoveryTimeout;
    private final int halfOpenMaxCalls;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenCallCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccessCount = new AtomicInteger(0);
    private volatile Instant openedAt = Instant.MIN;

    public CircuitBreakerBackpressureStrategy(int failureThreshold, Duration recoveryTimeout,
                                              int halfOpenMaxCalls) {
        this.failureThreshold = failureThreshold;
        this.recoveryTimeout = recoveryTimeout;
        this.halfOpenMaxCalls = halfOpenMaxCalls;
    }

    public CircuitBreakerBackpressureStrategy() {
        this(5, Duration.ofSeconds(30), 3);
    }

    @Override
    public <T, R> Flux<R> applyBackpressure(List<T> items, ItemProcessor<T, R> processor) {
        return Flux.fromIterable(items)
                .concatMap(item -> processItem(item, processor));
    }

    private <T, R> Mono<R> processItem(T item, ItemProcessor<T, R> processor) {
        State current = state.get();

        if (current == State.OPEN) {
            if (Instant.now().isAfter(openedAt.plus(recoveryTimeout))) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.info("[circuit-breaker] Transitioning OPEN -> HALF_OPEN after recovery timeout");
                    halfOpenCallCount.set(0);
                    halfOpenSuccessCount.set(0);
                }
            } else {
                log.debug("[circuit-breaker] Circuit is OPEN, rejecting item");
                return Mono.error(new CircuitBreakerOpenException("Circuit breaker is OPEN"));
            }
        }

        current = state.get();
        if (current == State.HALF_OPEN) {
            int calls = halfOpenCallCount.incrementAndGet();
            if (calls > halfOpenMaxCalls) {
                log.debug("[circuit-breaker] HALF_OPEN call limit reached, rejecting item");
                return Mono.error(new CircuitBreakerOpenException(
                        "Circuit breaker HALF_OPEN call limit exceeded"));
            }
        }

        return processor.process(item)
                .doOnNext(r -> onSuccess())
                .onErrorResume(err -> {
                    if (err instanceof CircuitBreakerOpenException) {
                        return Mono.error(err);
                    }
                    onFailure();
                    return Mono.empty();
                });
    }

    private void onSuccess() {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            int successes = halfOpenSuccessCount.incrementAndGet();
            if (successes >= halfOpenMaxCalls) {
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    log.info("[circuit-breaker] Transitioning HALF_OPEN -> CLOSED after {} successful probes",
                            successes);
                    failureCount.set(0);
                }
            }
        } else if (current == State.CLOSED) {
            failureCount.set(0);
        }
    }

    private void onFailure() {
        State current = state.get();
        if (current == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt = Instant.now();
                log.info("[circuit-breaker] Transitioning HALF_OPEN -> OPEN after probe failure");
            }
        } else if (current == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    openedAt = Instant.now();
                    log.info("[circuit-breaker] Transitioning CLOSED -> OPEN after {} failures", failures);
                }
            }
        }
    }

    public State getState() {
        return state.get();
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        halfOpenCallCount.set(0);
        halfOpenSuccessCount.set(0);
        openedAt = Instant.MIN;
    }

    /**
     * Exception thrown when the circuit breaker is open and rejecting calls.
     */
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }
}
