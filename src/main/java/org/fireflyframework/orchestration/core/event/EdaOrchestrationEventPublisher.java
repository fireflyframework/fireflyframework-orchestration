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

package org.fireflyframework.orchestration.core.event;

import org.fireflyframework.eda.publisher.EventPublisher;
import org.fireflyframework.eda.publisher.EventPublisherFactory;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Bridges {@link OrchestrationEvent}s emitted by sagas, workflows and TCC executions
 * (e.g. {@code @StepEvent}-annotated steps) onto the unified
 * {@code fireflyframework-eda} {@link EventPublisher} infrastructure.
 *
 * <p>The bridge takes the broker-agnostic {@link OrchestrationEvent} record produced by
 * the orchestration engine and translates it into a publish call against EDA's default
 * publisher (Kafka, RabbitMQ, etc.), enriching the message with all orchestration
 * metadata as headers. This unlocks all EDA features for orchestration events:
 * <ul>
 *   <li>Multi-platform support (Kafka, RabbitMQ, Application Events)</li>
 *   <li>Resilience (circuit breaker, retry, rate limiting)</li>
 *   <li>Metrics and health checks</li>
 *   <li>Custom serializers</li>
 * </ul>
 *
 * <p><b>Activation</b>: gated by {@code firefly.orchestration.events.enabled=true}.
 * When disabled (default), the framework falls back to {@link NoOpEventPublisher}
 * and orchestration events are silently dropped — preserving backward compatibility
 * with consumers that have not yet configured EDA.
 *
 * <p><b>Destination resolution</b>: per-publish, the bridge uses
 * {@link OrchestrationEvent#topic()} when set; otherwise it falls back to the
 * configured {@code firefly.orchestration.events.default-topic}. If neither is
 * set the event is dropped with a WARN log (it would otherwise NPE the broker).
 *
 * <p><b>Routing key</b>: {@link OrchestrationEvent#key()} when set, otherwise
 * {@code executionName:correlationId} — enabling broker-side partitioning by
 * saga instance.
 */
@Slf4j
public class EdaOrchestrationEventPublisher implements OrchestrationEventPublisher {

    private final EventPublisherFactory publisherFactory;
    private final String defaultTopic;

    /**
     * Creates a new EDA-backed orchestration event publisher.
     *
     * @param publisherFactory the EDA publisher factory used to obtain the default
     *                         publisher (Kafka/RabbitMQ/etc.) at publish time
     * @param defaultTopic     the topic used when an {@link OrchestrationEvent} does
     *                         not specify its own; may be null/blank in which case
     *                         only events that carry an explicit topic will be published
     */
    public EdaOrchestrationEventPublisher(EventPublisherFactory publisherFactory, String defaultTopic) {
        this.publisherFactory = publisherFactory;
        this.defaultTopic = defaultTopic;
    }

    @Override
    public Mono<Void> publish(OrchestrationEvent event) {
        if (event == null) {
            return Mono.empty();
        }

        // Resolve destination: event.topic() wins, default fallback otherwise.
        String destination = (event.topic() != null && !event.topic().isBlank())
                ? event.topic()
                : defaultTopic;
        if (destination == null || destination.isBlank()) {
            log.warn("[orchestration-eda-bridge] dropping event {} (executionName={} correlationId={}) — no topic and no default-topic configured",
                    event.eventType(), event.executionName(), event.correlationId());
            return Mono.empty();
        }

        // Obtain the EDA default publisher lazily; if EDA is misconfigured, surface as Mono error.
        EventPublisher publisher = publisherFactory.getDefaultPublisher();
        if (publisher == null) {
            log.warn("[orchestration-eda-bridge] no EDA default publisher available — dropping event {} on topic {}",
                    event.eventType(), destination);
            return Mono.empty();
        }

        Map<String, Object> headers = buildHeaders(event);

        return publisher.publish(event, destination, headers)
                .doOnSuccess(v -> log.debug("[orchestration-eda-bridge] published {} to {}", event.eventType(), destination))
                .doOnError(err -> log.error("[orchestration-eda-bridge] failed to publish {} to {}: {}",
                        event.eventType(), destination, err.getMessage()));
    }

    /**
     * Builds the headers map for a given orchestration event. All non-null fields of
     * the event become headers so that downstream consumers can filter / route without
     * deserializing the payload.
     */
    private Map<String, Object> buildHeaders(OrchestrationEvent event) {
        Map<String, Object> headers = new HashMap<>();
        if (event.eventType() != null) {
            headers.put("eventType", event.eventType());
        }
        if (event.executionName() != null) {
            headers.put("executionName", event.executionName());
        }
        if (event.correlationId() != null) {
            headers.put("correlationId", event.correlationId());
        }
        if (event.pattern() != null) {
            headers.put("pattern", event.pattern().name());
        }
        if (event.stepId() != null) {
            headers.put("stepId", event.stepId());
        }
        if (event.status() != null) {
            headers.put("status", event.status().name());
        }
        if (event.type() != null) {
            headers.put("type", event.type());
        }
        if (event.timestamp() != null) {
            headers.put("timestamp", event.timestamp().toString());
        }

        // Routing key: explicit key, otherwise executionName:correlationId fallback
        // (matches the legacy starter-domain bridge contract).
        String routingKey = event.key();
        if (routingKey == null || routingKey.isBlank()) {
            String name = event.executionName() != null ? event.executionName() : "";
            String corr = event.correlationId() != null ? event.correlationId() : "";
            routingKey = name + ":" + corr;
        }
        headers.put("routing_key", routingKey);

        return headers;
    }
}
