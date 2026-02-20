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
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class TccEngineTest {

    private TccEngine engine;
    private OrchestrationEvents events;

    @BeforeEach
    void setUp() {
        events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events);
        // Use a null registry — we pass TccDefinition directly to engine
        engine = new TccEngine(null, events, orchestrator, null, null);
    }

    @Test
    void execute_successfulTcc_confirmsAllParticipants() {
        TccDefinition tcc = TccBuilder.tcc("TransferFunds")
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
                    assertThat(result.isCanceled()).isFalse();
                    assertThat(result.tccName()).isEqualTo("TransferFunds");
                    assertThat(result.participants()).containsKeys("debit", "credit");
                    assertThat(result.participants().get("debit").trySucceeded()).isTrue();
                    assertThat(result.participants().get("debit").confirmSucceeded()).isTrue();
                    assertThat(result.participants().get("credit").trySucceeded()).isTrue();
                    assertThat(result.participants().get("credit").confirmSucceeded()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void execute_tryFails_cancelsSuccessfulParticipants() {
        AtomicBoolean debitCancelled = new AtomicBoolean(false);

        TccDefinition tcc = TccBuilder.tcc("FailTransfer")
                .participant("debit")
                    .tryHandler((input, ctx) -> Mono.just("debit-reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("debit-confirmed"))
                    .cancelHandler((input, ctx) -> {
                        debitCancelled.set(true);
                        return Mono.just("debit-cancelled");
                    })
                    .add()
                .participant("credit")
                    .tryHandler((input, ctx) -> Mono.error(new RuntimeException("insufficient funds")))
                    .confirmHandler((input, ctx) -> Mono.just("credit-confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("credit-cancelled"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isCanceled()).isTrue();
                    assertThat(result.isConfirmed()).isFalse();
                    assertThat(result.failedParticipantId()).hasValue("credit");
                    assertThat(result.error()).isPresent();
                    assertThat(result.error().get().getMessage()).isEqualTo("insufficient funds");
                    assertThat(debitCancelled.get()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void execute_withInputs_passesInputsToTryMethods() {
        TccDefinition tcc = TccBuilder.tcc("InputTcc")
                .participant("step1")
                    .tryHandler((input, ctx) -> Mono.just("processed:" + input))
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        TccInputs inputs = TccInputs.of("step1", "my-data");

        StepVerifier.create(engine.execute(tcc, inputs))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();
                    assertThat(result.tryResultOf("step1", String.class))
                            .hasValue("processed:my-data");
                })
                .verifyComplete();
    }

    @Test
    void execute_participantOrder_executesSequentially() {
        List<String> executionOrder = new ArrayList<>();

        TccDefinition tcc = TccBuilder.tcc("OrderedTcc")
                .participant("second")
                    .order(2)
                    .tryHandler((input, ctx) -> {
                        synchronized (executionOrder) { executionOrder.add("second"); }
                        return Mono.just("second-done");
                    })
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .participant("first")
                    .order(1)
                    .tryHandler((input, ctx) -> {
                        synchronized (executionOrder) { executionOrder.add("first"); }
                        return Mono.just("first-done");
                    })
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .participant("third")
                    .order(3)
                    .tryHandler((input, ctx) -> {
                        synchronized (executionOrder) { executionOrder.add("third"); }
                        return Mono.just("third-done");
                    })
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();
                    assertThat(executionOrder).containsExactly("first", "second", "third");
                })
                .verifyComplete();
    }

    @Test
    void execute_cancelPhase_runsInReverseOrder() {
        List<String> cancelOrder = new ArrayList<>();

        TccDefinition tcc = TccBuilder.tcc("ReverseCancelTcc")
                .participant("p1")
                    .order(1)
                    .tryHandler((input, ctx) -> Mono.just("p1"))
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> {
                        synchronized (cancelOrder) { cancelOrder.add("p1"); }
                        return Mono.empty();
                    })
                    .add()
                .participant("p2")
                    .order(2)
                    .tryHandler((input, ctx) -> Mono.just("p2"))
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> {
                        synchronized (cancelOrder) { cancelOrder.add("p2"); }
                        return Mono.empty();
                    })
                    .add()
                .participant("p3")
                    .order(3)
                    .tryHandler((input, ctx) -> Mono.error(new RuntimeException("fail")))
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> {
                        synchronized (cancelOrder) { cancelOrder.add("p3"); }
                        return Mono.empty();
                    })
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isCanceled()).isTrue();
                    // p1 and p2 succeeded their try, so they get canceled in reverse order
                    // p3 failed its try, so it was not added to triedParticipants
                    assertThat(cancelOrder).containsExactly("p2", "p1");
                })
                .verifyComplete();
    }

    @Test
    void execute_optionalParticipantFails_continuesTrying() {
        TccDefinition tcc = TccBuilder.tcc("OptionalTcc")
                .participant("required")
                    .order(1)
                    .tryHandler((input, ctx) -> Mono.just("required-done"))
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .participant("optional")
                    .order(2)
                    .optional(true)
                    .tryHandler((input, ctx) -> Mono.error(new RuntimeException("optional fail")))
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .participant("afterOptional")
                    .order(3)
                    .tryHandler((input, ctx) -> Mono.just("after-done"))
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();
                    assertThat(result.participants().get("required").trySucceeded()).isTrue();
                    assertThat(result.participants().get("afterOptional").trySucceeded()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void execute_noParticipants_throwsOnBuild() {
        assertThatThrownBy(() ->
                TccBuilder.tcc("EmptyTcc").build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("at least one participant");
    }

    @Test
    void execute_duplicateParticipantId_throwsOnAdd() {
        assertThatThrownBy(() ->
                TccBuilder.tcc("DupTcc")
                        .participant("same")
                            .tryHandler((input, ctx) -> Mono.empty())
                            .confirmHandler((input, ctx) -> Mono.empty())
                            .cancelHandler((input, ctx) -> Mono.empty())
                            .add()
                        .participant("same")
                            .tryHandler((input, ctx) -> Mono.empty())
                            .confirmHandler((input, ctx) -> Mono.empty())
                            .cancelHandler((input, ctx) -> Mono.empty())
                            .add()
                        .build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("Duplicate participant id");
    }

    @Test
    void execute_missingHandler_throwsOnAdd() {
        assertThatThrownBy(() ->
                TccBuilder.tcc("NoHandler")
                        .participant("broken")
                            .add()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("must have a handler");
    }

    @Test
    void execute_missingTryHandler_throwsOnAdd() {
        assertThatThrownBy(() ->
                TccBuilder.tcc("MissingTry")
                        .participant("broken")
                            .confirmHandler((input, ctx) -> Mono.empty())
                            .cancelHandler((input, ctx) -> Mono.empty())
                            .add()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("missing tryHandler");
    }
}
