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

package org.fireflyframework.orchestration.unit.core;

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.dlq.DeadLetterEntry;
import org.fireflyframework.orchestration.core.dlq.DeadLetterService;
import org.fireflyframework.orchestration.core.dlq.InMemoryDeadLetterStore;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepHandler;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
import org.fireflyframework.orchestration.saga.compensation.SagaCompensator;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaExecutionOrchestrator;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.tcc.builder.TccBuilder;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccExecutionOrchestrator;
import org.fireflyframework.orchestration.tcc.engine.TccInputs;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DlqInputCaptureTest {

    private OrchestrationEvents events;
    private InMemoryDeadLetterStore dlqStore;
    private DeadLetterService dlqService;
    private NoOpEventPublisher noOpPublisher;
    private StepInvoker stepInvoker;

    @BeforeEach
    void setUp() {
        events = new OrchestrationEvents() {};
        dlqStore = new InMemoryDeadLetterStore();
        dlqService = new DeadLetterService(dlqStore, events);
        noOpPublisher = new NoOpEventPublisher();
        stepInvoker = new StepInvoker(new ArgumentResolver());
    }

    @Test
    void saga_dlqEntry_capturesOriginalInputs() {
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, null, dlqService, compensator, noOpPublisher);

        SagaDefinition saga = SagaBuilder.saga("DlqSaga")
                .step("reserve")
                    .handler((StepHandler<Object, String>) (input, ctx) -> Mono.just("reserved"))
                    .add()
                .step("charge")
                    .dependsOn("reserve")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("charge failed")))
                    .add()
                .build();

        StepInputs inputs = StepInputs.builder()
                .forStepId("reserve", "booking-123")
                .forStepId("charge", 99.99)
                .build();

        StepVerifier.create(engine.execute(saga, inputs))
                .assertNext(result -> assertThat(result.isSuccess()).isFalse())
                .verifyComplete();

        // Verify DLQ entry captured original inputs
        List<DeadLetterEntry> entries = dlqService.getAllEntries().collectList().block();
        assertThat(entries).hasSize(1);
        DeadLetterEntry entry = entries.get(0);
        assertThat(entry.executionName()).isEqualTo("DlqSaga");
        assertThat(entry.stepId()).isEqualTo("charge");
        assertThat(entry.input()).isNotEmpty();
        assertThat(entry.input()).containsEntry("reserve", "booking-123");
        assertThat(entry.input()).containsEntry("charge", 99.99);
    }

    @Test
    void saga_dlqEntry_handlesNullInputsGracefully() {
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        var engine = new SagaEngine(null, events, orchestrator, null, dlqService, compensator, noOpPublisher);

        SagaDefinition saga = SagaBuilder.saga("NullInputSaga")
                .step("only")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.error(new RuntimeException("fail")))
                    .add()
                .build();

        StepVerifier.create(engine.execute(saga, null))
                .assertNext(result -> assertThat(result.isSuccess()).isFalse())
                .verifyComplete();

        List<DeadLetterEntry> entries = dlqService.getAllEntries().collectList().block();
        assertThat(entries).hasSize(1);
        // With null inputs, the DLQ entry should have an empty map, not throw
        assertThat(entries.get(0).input()).isNotNull();
    }

    @Test
    void tcc_dlqEntry_capturesOriginalInputs() {
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var engine = new TccEngine(null, events, orchestrator, null, dlqService, noOpPublisher);

        // Build a TCC that fails during confirm phase so it goes to FAILED status
        // A try failure leads to CANCELED (not FAILED), and only FAILED goes to DLQ
        // Instead, make the cancel phase fail after a try failure to get FAILED status
        TccDefinition tcc = TccBuilder.tcc("DlqTcc")
                .participant("debit")
                    .order(1)
                    .tryHandler((input, ctx) -> Mono.just("debit-reserved"))
                    .confirmHandler((input, ctx) -> Mono.error(new RuntimeException("confirm failed")))
                    .cancelHandler((input, ctx) -> Mono.error(new RuntimeException("cancel also failed")))
                    .add()
                .build();

        TccInputs inputs = TccInputs.builder()
                .forParticipant("debit", "transfer-500")
                .build();

        StepVerifier.create(engine.execute(tcc, inputs))
                .assertNext(result -> {
                    // Confirm fails, then cancel fails => FAILED
                    assertThat(result.isFailed()).isTrue();
                })
                .verifyComplete();

        List<DeadLetterEntry> entries = dlqService.getAllEntries().collectList().block();
        assertThat(entries).hasSize(1);
        DeadLetterEntry entry = entries.get(0);
        assertThat(entry.executionName()).isEqualTo("DlqTcc");
        assertThat(entry.input()).isNotEmpty();
        assertThat(entry.input()).containsEntry("debit", "transfer-500");
    }

    @Test
    void tcc_dlqEntry_handlesNullInputsGracefully() {
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var engine = new TccEngine(null, events, orchestrator, null, dlqService, noOpPublisher);

        TccDefinition tcc = TccBuilder.tcc("NullInputTcc")
                .participant("p1")
                    .tryHandler((input, ctx) -> Mono.just("tried"))
                    .confirmHandler((input, ctx) -> Mono.error(new RuntimeException("confirm fail")))
                    .cancelHandler((input, ctx) -> Mono.error(new RuntimeException("cancel fail")))
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, null, null))
                .assertNext(result -> assertThat(result.isFailed()).isTrue())
                .verifyComplete();

        List<DeadLetterEntry> entries = dlqService.getAllEntries().collectList().block();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).input()).isNotNull();
    }
}
