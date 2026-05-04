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

package org.fireflyframework.orchestration.unit.core.event;

import org.fireflyframework.eda.publisher.EventPublisher;
import org.fireflyframework.eda.publisher.EventPublisherFactory;
import org.fireflyframework.orchestration.core.event.EdaOrchestrationEventPublisher;
import org.fireflyframework.orchestration.core.event.OrchestrationEvent;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EdaOrchestrationEventPublisher} — verifies that
 * {@code OrchestrationEvent}s are correctly translated into EDA publish
 * calls with the right destination, headers and routing key fallbacks.
 */
class EdaOrchestrationEventPublisherTest {

    private EventPublisherFactory factory;
    private EventPublisher publisher;

    @BeforeEach
    void setUp() {
        factory = mock(EventPublisherFactory.class);
        publisher = mock(EventPublisher.class);
        when(factory.getDefaultPublisher()).thenReturn(publisher);
        when(publisher.publish(any(), anyString(), anyMap())).thenReturn(Mono.empty());
    }

    @Test
    void publish_delegatesToEventPublisher_withEventTopicAndAllHeaders() {
        // Given an event with an explicit topic and full metadata
        Instant now = Instant.parse("2026-04-28T10:00:00Z");
        OrchestrationEvent event = new OrchestrationEvent(
                "STEP_COMPLETED",
                "OnboardTenantSaga",
                "corr-123",
                ExecutionPattern.SAGA,
                "createTenant",
                ExecutionStatus.COMPLETED,
                Map.of("result", "ok"),
                now,
                "explicit.topic",
                "tenant.created",
                "key-abc"
        );
        EdaOrchestrationEventPublisher bridge =
                new EdaOrchestrationEventPublisher(factory, "fallback.topic");

        // When publishing
        StepVerifier.create(bridge.publish(event)).verifyComplete();

        // Then the EDA publisher is called with the explicit topic and full headers
        ArgumentCaptor<String> destCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(publisher, times(1)).publish(eq(event), destCaptor.capture(), headersCaptor.capture());

        assertThat(destCaptor.getValue()).isEqualTo("explicit.topic");
        Map<String, Object> headers = headersCaptor.getValue();
        assertThat(headers)
                .containsEntry("eventType", "STEP_COMPLETED")
                .containsEntry("executionName", "OnboardTenantSaga")
                .containsEntry("correlationId", "corr-123")
                .containsEntry("pattern", "SAGA")
                .containsEntry("stepId", "createTenant")
                .containsEntry("status", "COMPLETED")
                .containsEntry("type", "tenant.created")
                .containsEntry("timestamp", now.toString())
                .containsEntry("routing_key", "key-abc");
    }

    @Test
    void publish_fallsBackToDefaultTopic_whenEventTopicIsBlank() {
        OrchestrationEvent event = OrchestrationEvent.executionStarted(
                "OnboardTenantSaga", "corr-1", ExecutionPattern.SAGA);
        EdaOrchestrationEventPublisher bridge =
                new EdaOrchestrationEventPublisher(factory, "idp.admin-identity.events");

        StepVerifier.create(bridge.publish(event)).verifyComplete();

        verify(publisher, times(1))
                .publish(eq(event), eq("idp.admin-identity.events"), anyMap());
    }

    @Test
    void publish_fallsBackToDefaultTopic_whenEventTopicIsNull() {
        OrchestrationEvent event = new OrchestrationEvent(
                "EXECUTION_COMPLETED", "WF", "corr-2",
                ExecutionPattern.WORKFLOW, null, ExecutionStatus.COMPLETED,
                Map.of(), Instant.now(),
                null,           // topic = null
                null, null
        );
        EdaOrchestrationEventPublisher bridge =
                new EdaOrchestrationEventPublisher(factory, "default.topic");

        StepVerifier.create(bridge.publish(event)).verifyComplete();

        verify(publisher, times(1))
                .publish(eq(event), eq("default.topic"), anyMap());
    }

    @Test
    void publish_returnsEmpty_whenBothTopicsBlankAndDoesNotInvokePublisher() {
        OrchestrationEvent event = new OrchestrationEvent(
                "STEP_COMPLETED", "Sg", "corr-x",
                ExecutionPattern.SAGA, "step1", ExecutionStatus.COMPLETED,
                Map.of(), Instant.now(),
                "",     // topic blank
                null, null
        );
        EdaOrchestrationEventPublisher bridge =
                new EdaOrchestrationEventPublisher(factory, "   ");

        StepVerifier.create(bridge.publish(event)).verifyComplete();

        verify(publisher, never()).publish(any(), anyString(), anyMap());
    }

    @Test
    void publish_returnsEmpty_whenBothTopicsNull() {
        OrchestrationEvent event = new OrchestrationEvent(
                "STEP_COMPLETED", "Sg", "corr-y",
                ExecutionPattern.SAGA, "step1", ExecutionStatus.COMPLETED,
                Map.of(), Instant.now(),
                null,
                null, null
        );
        EdaOrchestrationEventPublisher bridge =
                new EdaOrchestrationEventPublisher(factory, null);

        StepVerifier.create(bridge.publish(event)).verifyComplete();

        verify(publisher, never()).publish(any(), anyString(), anyMap());
    }

    @Test
    void publish_routingKeyFallsBackToExecutionNameAndCorrelationId() {
        OrchestrationEvent event = new OrchestrationEvent(
                "STEP_COMPLETED",
                "DelegateValidationSaga",
                "abc-456",
                ExecutionPattern.SAGA,
                "publishToData",
                ExecutionStatus.COMPLETED,
                Map.of(),
                Instant.now(),
                null,           // topic null → uses default
                "step.event",
                null            // key null → fallback expected
        );
        EdaOrchestrationEventPublisher bridge =
                new EdaOrchestrationEventPublisher(factory, "idp.orchestration.events");

        StepVerifier.create(bridge.publish(event)).verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publish(eq(event), eq("idp.orchestration.events"), headersCaptor.capture());
        assertThat(headersCaptor.getValue())
                .containsEntry("routing_key", "DelegateValidationSaga:abc-456");
    }

    @Test
    void publish_routingKeyFallsBackEvenWhenKeyIsBlank() {
        OrchestrationEvent event = new OrchestrationEvent(
                "STEP_COMPLETED",
                "MySaga",
                "corr-9",
                ExecutionPattern.SAGA,
                "stepA",
                ExecutionStatus.COMPLETED,
                Map.of(),
                Instant.now(),
                "topic-x",
                null,
                "   "           // key blank → fallback expected
        );
        EdaOrchestrationEventPublisher bridge =
                new EdaOrchestrationEventPublisher(factory, null);

        StepVerifier.create(bridge.publish(event)).verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publish(eq(event), eq("topic-x"), headersCaptor.capture());
        assertThat(headersCaptor.getValue())
                .containsEntry("routing_key", "MySaga:corr-9");
    }

    @Test
    void publish_returnsEmpty_whenFactoryReturnsNullPublisher() {
        when(factory.getDefaultPublisher()).thenReturn(null);
        OrchestrationEvent event = OrchestrationEvent.executionStarted(
                "S", "c", ExecutionPattern.SAGA);
        EdaOrchestrationEventPublisher bridge =
                new EdaOrchestrationEventPublisher(factory, "default.topic");

        StepVerifier.create(bridge.publish(event)).verifyComplete();

        verify(publisher, never()).publish(any(), anyString(), anyMap());
    }

    @Test
    void publish_propagatesErrorFromUnderlyingPublisher() {
        when(publisher.publish(any(), anyString(), anyMap()))
                .thenReturn(Mono.error(new RuntimeException("kafka down")));
        OrchestrationEvent event = OrchestrationEvent.stepCompleted(
                "S", "c", ExecutionPattern.SAGA, "step1", "result");
        EdaOrchestrationEventPublisher bridge =
                new EdaOrchestrationEventPublisher(factory, "default.topic");

        StepVerifier.create(bridge.publish(event))
                .expectErrorMatches(t -> t instanceof RuntimeException
                        && "kafka down".equals(t.getMessage()))
                .verify();
    }

    @Test
    void publish_handlesNullEvent_returnsEmpty() {
        EdaOrchestrationEventPublisher bridge =
                new EdaOrchestrationEventPublisher(factory, "default.topic");

        StepVerifier.create(bridge.publish(null)).verifyComplete();

        verify(publisher, never()).publish(any(), anyString(), anyMap());
    }
}
