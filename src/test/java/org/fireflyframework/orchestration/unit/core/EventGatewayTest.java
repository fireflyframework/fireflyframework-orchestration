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

import org.fireflyframework.orchestration.core.event.EventGateway;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class EventGatewayTest {

    private EventGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new EventGateway();
    }

    @Test
    void routeEvent_triggersMatchingWorkflow() {
        var triggered = new AtomicBoolean(false);
        var receivedPayload = new AtomicReference<Map<String, Object>>();

        gateway.register("order.created", "order-workflow", ExecutionPattern.WORKFLOW,
                payload -> {
                    triggered.set(true);
                    receivedPayload.set(payload);
                    return Mono.just("workflow-started");
                });

        Map<String, Object> payload = Map.of("orderId", "12345");

        StepVerifier.create(gateway.routeEvent("order.created", payload))
                .verifyComplete();

        assertThat(triggered.get()).isTrue();
        assertThat(receivedPayload.get()).containsEntry("orderId", "12345");
    }

    @Test
    void routeEvent_triggersMatchingSaga() {
        var triggered = new AtomicBoolean(false);
        var receivedPayload = new AtomicReference<Map<String, Object>>();

        gateway.register("payment.failed", "payment-saga", ExecutionPattern.SAGA,
                payload -> {
                    triggered.set(true);
                    receivedPayload.set(payload);
                    return Mono.just("saga-started");
                });

        Map<String, Object> payload = Map.of("paymentId", "pay-001", "amount", 99.99);

        StepVerifier.create(gateway.routeEvent("payment.failed", payload))
                .verifyComplete();

        assertThat(triggered.get()).isTrue();
        assertThat(receivedPayload.get()).containsEntry("paymentId", "pay-001");
    }

    @Test
    void routeEvent_ignoresUnmatchedEvent() {
        // No triggers registered for "unknown.event"
        StepVerifier.create(gateway.routeEvent("unknown.event", Map.of("data", "value")))
                .verifyComplete();

        // No error, just completes empty
    }

    @Test
    void registrationCount_returnsCorrectCount() {
        assertThat(gateway.registrationCount()).isZero();

        gateway.register("event.a", "target-a", ExecutionPattern.WORKFLOW, p -> Mono.empty());
        gateway.register("event.b", "target-b", ExecutionPattern.SAGA, p -> Mono.empty());
        gateway.register("event.c", "target-c", ExecutionPattern.TCC, p -> Mono.empty());

        assertThat(gateway.registrationCount()).isEqualTo(3);
    }

    @Test
    void hasRegistration_returnsTrueForRegistered() {
        gateway.register("order.created", "order-wf", ExecutionPattern.WORKFLOW, p -> Mono.empty());

        assertThat(gateway.hasRegistration("order.created")).isTrue();
        assertThat(gateway.hasRegistration("order.deleted")).isFalse();
    }

    @Test
    void registeredEventTypes_returnsAllTypes() {
        gateway.register("event.a", "target-a", ExecutionPattern.WORKFLOW, p -> Mono.empty());
        gateway.register("event.b", "target-b", ExecutionPattern.SAGA, p -> Mono.empty());

        assertThat(gateway.registeredEventTypes()).containsExactlyInAnyOrder("event.a", "event.b");
    }
}
