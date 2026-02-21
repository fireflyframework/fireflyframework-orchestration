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

package org.fireflyframework.orchestration.config;

import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.observability.CompositeOrchestrationEvents;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.observability.OrchestrationLoggerEvents;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link OrchestrationAutoConfiguration} correctly composes
 * all {@link OrchestrationEvents} implementations into a
 * {@link CompositeOrchestrationEvents} when multiple exist, and returns
 * a single implementation directly when only one exists.
 */
class CompositeEventsWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OrchestrationAutoConfiguration.class));

    // ── Single implementation: returned directly (no wrapper) ─────────

    @Test
    void singleImplementation_returnedDirectlyWithoutComposite() {
        contextRunner.run(context -> {
            // The "orchestrationEvents" bean should be the logger directly (not wrapped)
            OrchestrationEvents events = context.getBean("orchestrationEvents", OrchestrationEvents.class);
            assertThat(events).isInstanceOf(OrchestrationLoggerEvents.class);
            assertThat(events).isNotInstanceOf(CompositeOrchestrationEvents.class);
        });
    }

    // ── Multiple implementations: all composed into CompositeOrchestrationEvents ──

    @Test
    void multipleImplementations_composedIntoComposite() {
        contextRunner
                .withUserConfiguration(ExtraEventsConfig.class)
                .run(context -> {
                    OrchestrationEvents events = context.getBean("orchestrationEvents", OrchestrationEvents.class);
                    assertThat(events).isInstanceOf(CompositeOrchestrationEvents.class);

                    // Also verify that the individual beans exist
                    assertThat(context.getBean(OrchestrationLoggerEvents.class)).isNotNull();
                    assertThat(context.getBean(StubMetricsEvents.class)).isNotNull();
                });
    }

    // ── Multiple implementations: all delegates receive events ────────

    @Test
    void multipleImplementations_allDelegatesReceiveEvents() {
        contextRunner
                .withUserConfiguration(TrackingEventsConfig.class)
                .run(context -> {
                    OrchestrationEvents events = context.getBean("orchestrationEvents", OrchestrationEvents.class);
                    assertThat(events).isInstanceOf(CompositeOrchestrationEvents.class);

                    // Fire an event through the composite
                    events.onStart("test-saga", "corr-1", ExecutionPattern.SAGA);

                    // Both tracking implementations should have received the event
                    TrackingEventsA trackingA = context.getBean(TrackingEventsA.class);
                    TrackingEventsB trackingB = context.getBean(TrackingEventsB.class);

                    assertThat(trackingA.startCalls).hasSize(1);
                    assertThat(trackingA.startCalls.get(0)).isEqualTo("test-saga:corr-1:SAGA");
                    assertThat(trackingB.startCalls).hasSize(1);
                    assertThat(trackingB.startCalls.get(0)).isEqualTo("test-saga:corr-1:SAGA");
                });
    }

    // ── Three implementations: all three composed ─────────────────────

    @Test
    void threeImplementations_allThreeComposed() {
        contextRunner
                .withUserConfiguration(TrackingEventsConfig.class)
                .run(context -> {
                    OrchestrationEvents events = context.getBean("orchestrationEvents", OrchestrationEvents.class);
                    assertThat(events).isInstanceOf(CompositeOrchestrationEvents.class);

                    // Verify all 3 OrchestrationEvents beans exist (logger + trackingA + trackingB)
                    String[] eventBeanNames = context.getBeanNamesForType(OrchestrationEvents.class);
                    // Exclude the composite itself; remaining beans are the 3 delegates
                    long delegateCount = java.util.Arrays.stream(eventBeanNames)
                            .map(n -> context.getBean(n, OrchestrationEvents.class))
                            .filter(e -> !(e instanceof CompositeOrchestrationEvents))
                            .count();
                    assertThat(delegateCount).as("Expected 3 delegates: logger + trackingA + trackingB").isEqualTo(3);

                    // Fire events
                    events.onStepStarted("wf", "corr-2", "step1");
                    events.onCompleted("wf", "corr-2", ExecutionPattern.WORKFLOW, true, 100L);

                    // The logger (from auto-config) plus both tracking impls = 3 total
                    TrackingEventsA trackingA = context.getBean(TrackingEventsA.class);
                    TrackingEventsB trackingB = context.getBean(TrackingEventsB.class);

                    assertThat(trackingA.stepStartedCalls).containsExactly("wf:corr-2:step1");
                    assertThat(trackingB.stepStartedCalls).containsExactly("wf:corr-2:step1");
                    assertThat(trackingA.completedCalls).containsExactly("wf:corr-2:WORKFLOW:true");
                    assertThat(trackingB.completedCalls).containsExactly("wf:corr-2:WORKFLOW:true");
                });
    }

    // ── Test configurations ──────────────────────────────────────────

    /**
     * Stub metrics events implementation for testing that extra
     * OrchestrationEvents beans get composed.
     */
    static class StubMetricsEvents implements OrchestrationEvents {
        @Override
        public void onStart(String name, String correlationId, ExecutionPattern pattern) {
            // no-op stub
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ExtraEventsConfig {
        @Bean
        StubMetricsEvents stubMetricsEvents() {
            return new StubMetricsEvents();
        }
    }

    /**
     * Tracking implementation A that records calls for verification.
     */
    static class TrackingEventsA implements OrchestrationEvents {
        final List<String> startCalls = Collections.synchronizedList(new ArrayList<>());
        final List<String> stepStartedCalls = Collections.synchronizedList(new ArrayList<>());
        final List<String> completedCalls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onStart(String name, String correlationId, ExecutionPattern pattern) {
            startCalls.add(name + ":" + correlationId + ":" + pattern);
        }

        @Override
        public void onStepStarted(String name, String correlationId, String stepId) {
            stepStartedCalls.add(name + ":" + correlationId + ":" + stepId);
        }

        @Override
        public void onCompleted(String name, String correlationId, ExecutionPattern pattern, boolean success, long durationMs) {
            completedCalls.add(name + ":" + correlationId + ":" + pattern + ":" + success);
        }
    }

    /**
     * Tracking implementation B that records calls for verification.
     */
    static class TrackingEventsB implements OrchestrationEvents {
        final List<String> startCalls = Collections.synchronizedList(new ArrayList<>());
        final List<String> stepStartedCalls = Collections.synchronizedList(new ArrayList<>());
        final List<String> completedCalls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onStart(String name, String correlationId, ExecutionPattern pattern) {
            startCalls.add(name + ":" + correlationId + ":" + pattern);
        }

        @Override
        public void onStepStarted(String name, String correlationId, String stepId) {
            stepStartedCalls.add(name + ":" + correlationId + ":" + stepId);
        }

        @Override
        public void onCompleted(String name, String correlationId, ExecutionPattern pattern, boolean success, long durationMs) {
            completedCalls.add(name + ":" + correlationId + ":" + pattern + ":" + success);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TrackingEventsConfig {
        @Bean
        TrackingEventsA trackingEventsA() {
            return new TrackingEventsA();
        }

        @Bean
        TrackingEventsB trackingEventsB() {
            return new TrackingEventsB();
        }
    }
}
