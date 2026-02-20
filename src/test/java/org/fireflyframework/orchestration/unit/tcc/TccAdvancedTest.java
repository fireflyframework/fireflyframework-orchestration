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
import org.fireflyframework.orchestration.core.context.TccPhase;
import org.fireflyframework.orchestration.core.dlq.DeadLetterService;
import org.fireflyframework.orchestration.core.dlq.InMemoryDeadLetterStore;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.tcc.builder.TccBuilder;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccExecutionOrchestrator;
import org.fireflyframework.orchestration.tcc.engine.TccInputs;
import org.fireflyframework.orchestration.tcc.engine.TccResult;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/**
 * Advanced TCC tests covering optional participant confirm/cancel behavior
 * and confirm phase failure scenarios.
 */
class TccAdvancedTest {

    private TccEngine engine;
    private TccEngine engineWithDlq;
    private InMemoryDeadLetterStore dlqStore;
    private OrchestrationEvents events;

    @BeforeEach
    void setUp() {
        events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noOpPublisher = new org.fireflyframework.orchestration.core.event.NoOpEventPublisher();
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        engine = new TccEngine(null, events, orchestrator, null, null, noOpPublisher);

        // Engine with DLQ and persistence
        var persistence = new InMemoryPersistenceProvider();
        dlqStore = new InMemoryDeadLetterStore();
        var dlqService = new DeadLetterService(dlqStore, events);
        engineWithDlq = new TccEngine(null, events, orchestrator, persistence, dlqService, noOpPublisher);
    }

    @Test
    void optionalParticipant_failedTry_confirmNotCalledForOptional() {
        AtomicBoolean optionalConfirmCalled = new AtomicBoolean(false);
        AtomicBoolean optionalCancelCalled = new AtomicBoolean(false);

        TccDefinition tcc = TccBuilder.tcc("OptionalConfirmTest")
                .participant("required")
                    .order(1)
                    .tryHandler((input, ctx) -> Mono.just("required-ok"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .participant("optional")
                    .order(2)
                    .optional(true)
                    .tryHandler((input, ctx) -> Mono.error(new RuntimeException("optional fail")))
                    .confirmHandler((input, ctx) -> {
                        optionalConfirmCalled.set(true);
                        return Mono.empty();
                    })
                    .cancelHandler((input, ctx) -> {
                        optionalCancelCalled.set(true);
                        return Mono.empty();
                    })
                    .add()
                .participant("third")
                    .order(3)
                    .tryHandler((input, ctx) -> Mono.just("third-ok"))
                    .confirmHandler((input, ctx) -> Mono.just("third-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();
                    // Optional participant's confirm should NOT be called since try failed
                    assertThat(optionalConfirmCalled.get()).isFalse();
                    // Optional participant's cancel should NOT be called since try failed
                    // (no successful reservation to cancel)
                    assertThat(optionalCancelCalled.get()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void confirmPhaseFailure_triggersCancelPhase() {
        AtomicBoolean cancelCalled = new AtomicBoolean(false);

        TccDefinition tcc = TccBuilder.tcc("ConfirmFailure")
                .participant("p1")
                    .order(1)
                    .tryHandler((input, ctx) -> Mono.just("tried"))
                    .confirmHandler((input, ctx) -> Mono.error(new RuntimeException("confirm exploded")))
                    .cancelHandler((input, ctx) -> {
                        cancelCalled.set(true);
                        return Mono.empty();
                    })
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    // TCC protocol: confirm failure triggers cancel to restore consistency
                    assertThat(result.isCanceled()).isTrue();
                    assertThat(result.isConfirmed()).isFalse();
                    assertThat(result.isFailed()).isFalse();
                    assertThat(result.failedPhase()).hasValue(TccPhase.CONFIRM);
                    assertThat(result.error()).isPresent();
                    assertThat(result.error().get().getMessage()).contains("confirm exploded");
                    assertThat(cancelCalled.get()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void confirmAndCancelBothFail_routedToDlq() {
        // When both confirm AND cancel fail, the transaction is truly FAILED and routes to DLQ
        TccDefinition tcc = TccBuilder.tcc("ConfirmCancelDlq")
                .participant("p1")
                    .order(1)
                    .tryHandler((input, ctx) -> Mono.just("tried"))
                    .confirmHandler((input, ctx) -> Mono.error(new RuntimeException("confirm DLQ")))
                    .cancelHandler((input, ctx) -> Mono.error(new RuntimeException("cancel also failed")))
                    .add()
                .build();

        StepVerifier.create(engineWithDlq.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isFailed()).isTrue();
                })
                .verifyComplete();

        // Verify DLQ received the entry
        StepVerifier.create(dlqStore.count())
                .assertNext(count -> assertThat(count).isGreaterThanOrEqualTo(1L))
                .verifyComplete();
    }

    @Test
    void allParticipantsConfirmSuccessfully_statusIsConfirmed() {
        TccDefinition tcc = TccBuilder.tcc("AllConfirm")
                .participant("p1")
                    .order(1)
                    .tryHandler((input, ctx) -> Mono.just("p1-tried"))
                    .confirmHandler((input, ctx) -> Mono.just("p1-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .participant("p2")
                    .order(2)
                    .tryHandler((input, ctx) -> Mono.just("p2-tried"))
                    .confirmHandler((input, ctx) -> Mono.just("p2-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        StepVerifier.create(engineWithDlq.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();
                    assertThat(result.participants().get("p1").confirmSucceeded()).isTrue();
                    assertThat(result.participants().get("p2").confirmSucceeded()).isTrue();
                })
                .verifyComplete();

        // DLQ should be empty for successful execution
        StepVerifier.create(dlqStore.count())
                .assertNext(count -> assertThat(count).isEqualTo(0L))
                .verifyComplete();
    }
}
