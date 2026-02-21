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

package org.fireflyframework.orchestration.unit.tcc;

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.tcc.builder.TccBuilder;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccExecutionOrchestrator;
import org.fireflyframework.orchestration.tcc.engine.TccInputs;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TccCheckpointTest {

    private OrchestrationEvents events;
    private StepInvoker stepInvoker;
    private org.fireflyframework.orchestration.core.event.NoOpEventPublisher noOpPublisher;

    @BeforeEach
    void setUp() {
        events = new OrchestrationEvents() {};
        stepInvoker = new StepInvoker(new ArgumentResolver());
        noOpPublisher = new org.fireflyframework.orchestration.core.event.NoOpEventPublisher();
    }

    private ExecutionPersistenceProvider capturingPersistence(List<ExecutionState> captured,
                                                              InMemoryPersistenceProvider delegate) {
        return new ExecutionPersistenceProvider() {
            @Override
            public Mono<Void> save(ExecutionState state) {
                captured.add(state);
                return delegate.save(state);
            }
            @Override
            public Mono<java.util.Optional<ExecutionState>> findById(String correlationId) {
                return delegate.findById(correlationId);
            }
            @Override
            public Mono<Void> updateStatus(String correlationId, ExecutionStatus status) {
                return delegate.updateStatus(correlationId, status);
            }
            @Override
            public reactor.core.publisher.Flux<ExecutionState> findByPattern(org.fireflyframework.orchestration.core.model.ExecutionPattern pattern) {
                return delegate.findByPattern(pattern);
            }
            @Override
            public reactor.core.publisher.Flux<ExecutionState> findByStatus(ExecutionStatus status) {
                return delegate.findByStatus(status);
            }
            @Override
            public reactor.core.publisher.Flux<ExecutionState> findInFlight() {
                return delegate.findInFlight();
            }
            @Override
            public reactor.core.publisher.Flux<ExecutionState> findStale(java.time.Instant before) {
                return delegate.findStale(before);
            }
            @Override
            public Mono<Long> cleanup(java.time.Duration olderThan) {
                return delegate.cleanup(olderThan);
            }
            @Override
            public Mono<Boolean> isHealthy() {
                return delegate.isHealthy();
            }
        };
    }

    @Test
    void checkpoint_savedAfterTryPhase() {
        List<ExecutionState> savedStates = new ArrayList<>();
        InMemoryPersistenceProvider delegate = new InMemoryPersistenceProvider();
        ExecutionPersistenceProvider persistence = capturingPersistence(savedStates, delegate);

        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher, persistence);
        var engine = new TccEngine(null, events, orchestrator, persistence, null, noOpPublisher);

        TccDefinition tcc = TccBuilder.tcc("TryCheckpointTcc")
                .participant("debit")
                    .tryHandler((input, ctx) -> Mono.just("debit-reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("debit-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("debit-cancelled"))
                    .add()
                .participant("credit")
                    .tryHandler((input, ctx) -> Mono.just("credit-reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("credit-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("credit-cancelled"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();

                    // savedStates: [0]=initial (from engine), [1]=after TRY (CONFIRMING),
                    //              [2]=after CONFIRM (CONFIRMED), [3]=final (from engine)
                    assertThat(savedStates).hasSizeGreaterThanOrEqualTo(3);

                    // Find the CONFIRMING checkpoint (after TRY phase completes)
                    boolean hasConfirmingCheckpoint = savedStates.stream()
                            .anyMatch(s -> s.status() == ExecutionStatus.CONFIRMING);
                    assertThat(hasConfirmingCheckpoint)
                            .as("Should have a CONFIRMING checkpoint after TRY phase")
                            .isTrue();

                    // The CONFIRMING checkpoint should contain all try results
                    ExecutionState tryCheckpoint = savedStates.stream()
                            .filter(s -> s.status() == ExecutionStatus.CONFIRMING)
                            .findFirst().orElseThrow();
                    assertThat(tryCheckpoint.stepResults()).containsKeys("debit", "credit");
                })
                .verifyComplete();
    }

    @Test
    void checkpoint_savedAfterConfirmPhase() {
        List<ExecutionState> savedStates = new ArrayList<>();
        InMemoryPersistenceProvider delegate = new InMemoryPersistenceProvider();
        ExecutionPersistenceProvider persistence = capturingPersistence(savedStates, delegate);

        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher, persistence);
        var engine = new TccEngine(null, events, orchestrator, persistence, null, noOpPublisher);

        TccDefinition tcc = TccBuilder.tcc("ConfirmCheckpointTcc")
                .participant("debit")
                    .tryHandler((input, ctx) -> Mono.just("debit-reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("debit-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("debit-cancelled"))
                    .add()
                .participant("credit")
                    .tryHandler((input, ctx) -> Mono.just("credit-reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("credit-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("credit-cancelled"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();

                    // Find the CONFIRMED checkpoint (after CONFIRM phase completes)
                    boolean hasConfirmedCheckpoint = savedStates.stream()
                            .anyMatch(s -> s.status() == ExecutionStatus.CONFIRMED);
                    assertThat(hasConfirmedCheckpoint)
                            .as("Should have a CONFIRMED checkpoint after CONFIRM phase")
                            .isTrue();

                    // Verify all statuses are present
                    ExecutionState confirmCheckpoint = savedStates.stream()
                            .filter(s -> s.status() == ExecutionStatus.CONFIRMED)
                            .findFirst().orElseThrow();
                    assertThat(confirmCheckpoint.stepResults()).containsKeys("debit", "credit");
                })
                .verifyComplete();
    }
}
