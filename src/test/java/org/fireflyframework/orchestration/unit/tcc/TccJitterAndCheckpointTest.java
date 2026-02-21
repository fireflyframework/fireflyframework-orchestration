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
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
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
import org.fireflyframework.orchestration.tcc.registry.TccParticipantDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class TccJitterAndCheckpointTest {

    private OrchestrationEvents events;
    private StepInvoker stepInvoker;
    private NoOpEventPublisher noOpPublisher;

    @BeforeEach
    void setUp() {
        events = new OrchestrationEvents() {};
        stepInvoker = new StepInvoker(new ArgumentResolver());
        noOpPublisher = new NoOpEventPublisher();
    }

    // --- Jitter field tests ---

    @SuppressWarnings("unused")
    public static class SimpleParticipant {
        public String tryOp() { return "tried"; }
        public String confirm() { return "confirmed"; }
        public String cancel() { return "canceled"; }
    }

    private TccParticipantDefinition participantDef(String id, int order, boolean jitter, double jitterFactor)
            throws Exception {
        var instance = new SimpleParticipant();
        Method tryM = SimpleParticipant.class.getMethod("tryOp");
        Method confirmM = SimpleParticipant.class.getMethod("confirm");
        Method cancelM = SimpleParticipant.class.getMethod("cancel");
        return new TccParticipantDefinition(
                id, order, -1, false, instance, instance,
                tryM, -1, -1, -1,
                confirmM, -1, -1, -1,
                cancelM, -1, -1, -1,
                jitter, jitterFactor);
    }

    @Test
    void jitterFieldsAreStoredAndAccessible() throws Exception {
        TccParticipantDefinition pd = participantDef("p1", 0, true, 0.3);
        assertThat(pd.jitter).isTrue();
        assertThat(pd.jitterFactor).isEqualTo(0.3);
    }

    @Test
    void jitterDefaultsToDisabled() throws Exception {
        TccParticipantDefinition pd = participantDef("p2", 0, false, 0.0);
        assertThat(pd.jitter).isFalse();
        assertThat(pd.jitterFactor).isEqualTo(0.0);
    }

    @Test
    void builderSupportsJitterConfiguration() {
        TccDefinition tcc = TccBuilder.tcc("JitterTcc")
                .participant("debit")
                    .jitter(true)
                    .jitterFactor(0.25)
                    .tryHandler((input, ctx) -> Mono.just("reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        TccParticipantDefinition pd = tcc.participants.get("debit");
        assertThat(pd.jitter).isTrue();
        assertThat(pd.jitterFactor).isEqualTo(0.25);
    }

    // --- CANCELING checkpoint test ---

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
            public reactor.core.publisher.Flux<ExecutionState> findByPattern(
                    org.fireflyframework.orchestration.core.model.ExecutionPattern pattern) {
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
    void cancelPhaseWritesCancelingCheckpoint() {
        List<ExecutionState> savedStates = new ArrayList<>();
        InMemoryPersistenceProvider delegate = new InMemoryPersistenceProvider();
        ExecutionPersistenceProvider persistence = capturingPersistence(savedStates, delegate);

        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher, persistence);
        var engine = new TccEngine(null, events, orchestrator, persistence, null, noOpPublisher);

        TccDefinition tcc = TccBuilder.tcc("CancelingCheckpointTcc")
                .participant("p1")
                    .tryHandler((input, ctx) -> Mono.just("p1-reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("p1-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("p1-cancelled"))
                    .add()
                .participant("p2")
                    .order(1)
                    .tryHandler((input, ctx) -> Mono.error(new RuntimeException("try failed")))
                    .confirmHandler((input, ctx) -> Mono.just("p2-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("p2-cancelled"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isCanceled()).isTrue();

                    boolean hasCancelingCheckpoint = savedStates.stream()
                            .anyMatch(s -> s.status() == ExecutionStatus.CANCELING);
                    assertThat(hasCancelingCheckpoint)
                            .as("Should have a CANCELING checkpoint before cancel phase executes")
                            .isTrue();
                })
                .verifyComplete();
    }
}
