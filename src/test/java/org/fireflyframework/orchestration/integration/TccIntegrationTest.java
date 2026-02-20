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

package org.fireflyframework.orchestration.integration;

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.builder.OrchestrationBuilder;
import org.fireflyframework.orchestration.core.dlq.DeadLetterService;
import org.fireflyframework.orchestration.core.dlq.InMemoryDeadLetterStore;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.step.StepInvoker;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class TccIntegrationTest {

    private TccEngine engine;
    private DeadLetterService dlqService;

    @BeforeEach
    void setUp() {
        var events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noOpPublisher = new org.fireflyframework.orchestration.core.event.NoOpEventPublisher();
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var persistence = new InMemoryPersistenceProvider();
        var dlqStore = new InMemoryDeadLetterStore();
        dlqService = new DeadLetterService(dlqStore, events);
        engine = new TccEngine(null, events, orchestrator, persistence, dlqService, noOpPublisher);
    }

    @Test
    void tcc_fullSuccess_triesAndConfirmsAll() {
        List<String> phases = new ArrayList<>();

        TccDefinition tcc = OrchestrationBuilder.tcc("transfer")
                .participant("debit")
                    .tryHandler((input, ctx) -> {
                        synchronized (phases) { phases.add("try:debit"); }
                        return Mono.just("reserved-100");
                    })
                    .confirmHandler((input, ctx) -> {
                        synchronized (phases) { phases.add("confirm:debit"); }
                        return Mono.just("committed");
                    })
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .participant("credit")
                    .tryHandler((input, ctx) -> {
                        synchronized (phases) { phases.add("try:credit"); }
                        return Mono.just("reserved+100");
                    })
                    .confirmHandler((input, ctx) -> {
                        synchronized (phases) { phases.add("confirm:credit"); }
                        return Mono.just("committed");
                    })
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();
                    assertThat(result.isCanceled()).isFalse();
                    // Try phase runs first for all, then confirm
                    assertThat(phases).containsExactly(
                            "try:debit", "try:credit", "confirm:debit", "confirm:credit");
                })
                .verifyComplete();
    }

    @Test
    void tcc_tryFails_cancelsSuccessful() {
        AtomicBoolean debitCancelled = new AtomicBoolean(false);

        TccDefinition tcc = OrchestrationBuilder.tcc("fail-transfer")
                .participant("debit")
                    .order(1)
                    .tryHandler((input, ctx) -> Mono.just("reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> {
                        debitCancelled.set(true);
                        return Mono.just("cancelled");
                    })
                    .add()
                .participant("credit")
                    .order(2)
                    .tryHandler((input, ctx) -> Mono.error(new RuntimeException("insufficient")))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isCanceled()).isTrue();
                    assertThat(result.isConfirmed()).isFalse();
                    assertThat(result.error()).isPresent();
                    assertThat(debitCancelled.get()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void tcc_withInputs_propagatesPerParticipant() {
        TccDefinition tcc = OrchestrationBuilder.tcc("input-tcc")
                .participant("reserve")
                    .tryHandler((input, ctx) -> Mono.just("reserved:" + input))
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        TccInputs inputs = TccInputs.of("reserve", "item-42");

        StepVerifier.create(engine.execute(tcc, inputs))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();
                    assertThat(result.tryResultOf("reserve", String.class))
                            .hasValue("reserved:item-42");
                })
                .verifyComplete();
    }

    @Test
    void tcc_multipleParticipantsWithOrder_executesSequentially() {
        List<String> order = new ArrayList<>();

        TccDefinition tcc = OrchestrationBuilder.tcc("ordered-tcc")
                .participant("third").order(3)
                    .tryHandler((input, ctx) -> {
                        synchronized (order) { order.add("third"); }
                        return Mono.just("3");
                    })
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .participant("first").order(1)
                    .tryHandler((input, ctx) -> {
                        synchronized (order) { order.add("first"); }
                        return Mono.just("1");
                    })
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .participant("second").order(2)
                    .tryHandler((input, ctx) -> {
                        synchronized (order) { order.add("second"); }
                        return Mono.just("2");
                    })
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();
                    assertThat(order).containsExactly("first", "second", "third");
                })
                .verifyComplete();
    }
}
