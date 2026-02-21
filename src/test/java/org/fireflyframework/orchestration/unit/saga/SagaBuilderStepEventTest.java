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

package org.fireflyframework.orchestration.unit.saga;

import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.saga.registry.StepEventConfig;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that {@link SagaBuilder} supports per-step event configuration
 * via the {@code stepEvent(topic, type, key)} method on the Step inner class.
 */
class SagaBuilderStepEventTest {

    @Test
    void stepEvent_setsStepEventConfig() {
        SagaDefinition def = SagaBuilder.saga("event-saga")
                .step("reserve")
                    .handler(() -> Mono.just("reserved"))
                    .stepEvent("inventory-events", "InventoryReserved", "orderId")
                    .add()
                .build();

        var stepDef = def.steps.get("reserve");
        assertThat(stepDef).isNotNull();
        assertThat(stepDef.stepEvent).isNotNull();
        assertThat(stepDef.stepEvent.topic()).isEqualTo("inventory-events");
        assertThat(stepDef.stepEvent.type()).isEqualTo("InventoryReserved");
        assertThat(stepDef.stepEvent.key()).isEqualTo("orderId");
    }

    @Test
    void stepEvent_defaultsToNull() {
        SagaDefinition def = SagaBuilder.saga("no-event-saga")
                .step("plain")
                    .handler(() -> Mono.just("done"))
                    .add()
                .build();

        var stepDef = def.steps.get("plain");
        assertThat(stepDef).isNotNull();
        assertThat(stepDef.stepEvent).isNull();
    }

    @Test
    void stepEvent_independentPerStep() {
        SagaDefinition def = SagaBuilder.saga("multi-event-saga")
                .step("step1")
                    .handler(() -> Mono.just("r1"))
                    .stepEvent("topic-a", "TypeA", "keyA")
                    .add()
                .step("step2")
                    .dependsOn("step1")
                    .handler(() -> Mono.just("r2"))
                    .add()
                .step("step3")
                    .dependsOn("step2")
                    .handler(() -> Mono.just("r3"))
                    .stepEvent("topic-b", "TypeB", "keyB")
                    .add()
                .build();

        assertThat(def.steps.get("step1").stepEvent).isNotNull();
        assertThat(def.steps.get("step1").stepEvent.topic()).isEqualTo("topic-a");

        assertThat(def.steps.get("step2").stepEvent).isNull();

        assertThat(def.steps.get("step3").stepEvent).isNotNull();
        assertThat(def.steps.get("step3").stepEvent.topic()).isEqualTo("topic-b");
    }

    @Test
    void stepEvent_combinedWithOtherStepOptions() {
        SagaDefinition def = SagaBuilder.saga("combined-saga")
                .step("charged")
                    .handler(() -> Mono.just("charged"))
                    .retry(3)
                    .backoffMs(500)
                    .timeoutMs(10_000)
                    .jitter()
                    .idempotencyKey("charge-key")
                    .stepEvent("payments", "PaymentCharged", "paymentId")
                    .compensation((r, ctx) -> Mono.empty())
                    .add()
                .build();

        var stepDef = def.steps.get("charged");
        assertThat(stepDef.retry).isEqualTo(3);
        assertThat(stepDef.idempotencyKey).isEqualTo("charge-key");
        assertThat(stepDef.jitter).isTrue();
        assertThat(stepDef.stepEvent).isNotNull();
        assertThat(stepDef.stepEvent.topic()).isEqualTo("payments");
        assertThat(stepDef.stepEvent.type()).isEqualTo("PaymentCharged");
    }
}
