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

import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.tcc.builder.TccBuilder;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.fireflyframework.orchestration.workflow.builder.WorkflowBuilder;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that all three builders (SagaBuilder, TccBuilder, WorkflowBuilder)
 * support the {@code triggerEventType(String)} method for event-driven triggering.
 */
class BuilderTriggerEventTypeTest {

    // --- SagaBuilder ---

    @Test
    void sagaBuilder_triggerEventType_setOnDefinition() {
        SagaDefinition def = SagaBuilder.saga("event-saga")
                .triggerEventType("OrderCreated")
                .step("process")
                    .handler(() -> Mono.just("done"))
                    .add()
                .build();

        assertThat(def.triggerEventType).isEqualTo("OrderCreated");
    }

    @Test
    void sagaBuilder_triggerEventType_defaultsToEmpty() {
        SagaDefinition def = SagaBuilder.saga("plain-saga")
                .step("process")
                    .handler(() -> Mono.just("done"))
                    .add()
                .build();

        assertThat(def.triggerEventType).isEmpty();
    }

    @Test
    void sagaBuilder_triggerEventType_nullTreatedAsEmpty() {
        SagaDefinition def = SagaBuilder.saga("null-trigger")
                .triggerEventType(null)
                .step("process")
                    .handler(() -> Mono.just("done"))
                    .add()
                .build();

        assertThat(def.triggerEventType).isEmpty();
    }

    // --- TccBuilder ---

    @Test
    void tccBuilder_triggerEventType_setOnDefinition() {
        TccDefinition def = TccBuilder.tcc("event-tcc")
                .triggerEventType("PaymentRequested")
                .participant("debit")
                    .tryHandler((input, ctx) -> Mono.just("reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        assertThat(def.triggerEventType).isEqualTo("PaymentRequested");
    }

    @Test
    void tccBuilder_triggerEventType_defaultsToEmpty() {
        TccDefinition def = TccBuilder.tcc("plain-tcc")
                .participant("debit")
                    .tryHandler((input, ctx) -> Mono.just("reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        assertThat(def.triggerEventType).isEmpty();
    }

    @Test
    void tccBuilder_triggerEventType_nullTreatedAsEmpty() {
        TccDefinition def = TccBuilder.tcc("null-trigger-tcc")
                .triggerEventType(null)
                .participant("debit")
                    .tryHandler((input, ctx) -> Mono.just("reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        assertThat(def.triggerEventType).isEmpty();
    }

    // --- WorkflowBuilder ---

    @Test
    void workflowBuilder_triggerEventType_setOnDefinition() {
        WorkflowDefinition def = new WorkflowBuilder("event-wf")
                .triggerEventType("DataIngested")
                .step("process")
                    .add()
                .build();

        assertThat(def.triggerEventType()).isEqualTo("DataIngested");
    }

    @Test
    void workflowBuilder_triggerEventType_defaultsToEmpty() {
        WorkflowDefinition def = new WorkflowBuilder("plain-wf")
                .step("process")
                    .add()
                .build();

        assertThat(def.triggerEventType()).isEmpty();
    }

    @Test
    void workflowBuilder_triggerEventType_nullTreatedAsEmpty() {
        WorkflowDefinition def = new WorkflowBuilder("null-trigger-wf")
                .triggerEventType(null)
                .step("process")
                    .add()
                .build();

        assertThat(def.triggerEventType()).isEmpty();
    }
}
