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

import org.fireflyframework.orchestration.core.context.TccPhase;
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

    // ── Composition and additional event methods delegated correctly ──

    @Test
    void compositeEvents_compositionMethodsDelegatedToAllTrackers() {
        contextRunner
                .withUserConfiguration(FullTrackingEventsConfig.class)
                .run(context -> {
                    OrchestrationEvents events = context.getBean("orchestrationEvents", OrchestrationEvents.class);
                    assertThat(events).isInstanceOf(CompositeOrchestrationEvents.class);

                    // Composition events
                    events.onCompositionStarted("order-saga-comp", "corr-10");
                    events.onCompositionCompleted("order-saga-comp", "corr-10", true);

                    // Compensation events
                    events.onCompensationStarted("payment-saga", "corr-11");
                    events.onStepCompensated("payment-saga", "corr-11", "charge-step");
                    events.onStepCompensationFailed("payment-saga", "corr-11", "refund-step",
                            new RuntimeException("refund failed"));

                    // TCC phase events
                    events.onPhaseStarted("reservation-tcc", "corr-12", TccPhase.TRY);
                    events.onPhaseCompleted("reservation-tcc", "corr-12", TccPhase.CONFIRM, 250L);
                    events.onParticipantStarted("reservation-tcc", "corr-12", "hotel", TccPhase.TRY);
                    events.onParticipantSuccess("reservation-tcc", "corr-12", "hotel", TccPhase.CONFIRM);

                    // Workflow-specific events
                    events.onWorkflowSuspended("approval-wf", "corr-13", "awaiting manager approval");
                    events.onWorkflowResumed("approval-wf", "corr-13");

                    // Signal and timer events
                    events.onSignalDelivered("approval-wf", "corr-13", "approve");
                    events.onTimerFired("timeout-wf", "corr-14", "timer-1");

                    // Child workflow events
                    events.onChildWorkflowStarted("parent-wf", "corr-15", "child-wf", "corr-16");
                    events.onChildWorkflowCompleted("parent-wf", "corr-15", "corr-16", true);

                    // Continue-as-new
                    events.onContinueAsNew("long-running-wf", "corr-17", "corr-18");

                    // Idempotency and DLQ
                    events.onStepSkippedIdempotent("saga-1", "corr-19", "step-already-done");
                    events.onDeadLettered("saga-2", "corr-20", "failed-step",
                            new RuntimeException("exhausted retries"));

                    // Verify both trackers received all calls
                    FullTrackingEventsA a = context.getBean(FullTrackingEventsA.class);
                    FullTrackingEventsB b = context.getBean(FullTrackingEventsB.class);

                    // Composition
                    assertThat(a.calls).contains(
                            "compositionStarted:order-saga-comp:corr-10",
                            "compositionCompleted:order-saga-comp:corr-10:true");
                    assertThat(b.calls).contains(
                            "compositionStarted:order-saga-comp:corr-10",
                            "compositionCompleted:order-saga-comp:corr-10:true");

                    // Compensation
                    assertThat(a.calls).contains(
                            "compensationStarted:payment-saga:corr-11",
                            "stepCompensated:payment-saga:corr-11:charge-step",
                            "stepCompensationFailed:payment-saga:corr-11:refund-step");
                    assertThat(b.calls).contains(
                            "compensationStarted:payment-saga:corr-11",
                            "stepCompensated:payment-saga:corr-11:charge-step",
                            "stepCompensationFailed:payment-saga:corr-11:refund-step");

                    // TCC phases
                    assertThat(a.calls).contains(
                            "phaseStarted:reservation-tcc:corr-12:TRY",
                            "phaseCompleted:reservation-tcc:corr-12:CONFIRM",
                            "participantStarted:reservation-tcc:corr-12:hotel:TRY",
                            "participantSuccess:reservation-tcc:corr-12:hotel:CONFIRM");
                    assertThat(b.calls).contains(
                            "phaseStarted:reservation-tcc:corr-12:TRY",
                            "phaseCompleted:reservation-tcc:corr-12:CONFIRM",
                            "participantStarted:reservation-tcc:corr-12:hotel:TRY",
                            "participantSuccess:reservation-tcc:corr-12:hotel:CONFIRM");

                    // Workflow-specific
                    assertThat(a.calls).contains(
                            "workflowSuspended:approval-wf:corr-13:awaiting manager approval",
                            "workflowResumed:approval-wf:corr-13");
                    assertThat(b.calls).contains(
                            "workflowSuspended:approval-wf:corr-13:awaiting manager approval",
                            "workflowResumed:approval-wf:corr-13");

                    // Signal and timer
                    assertThat(a.calls).contains(
                            "signalDelivered:approval-wf:corr-13:approve",
                            "timerFired:timeout-wf:corr-14:timer-1");
                    assertThat(b.calls).contains(
                            "signalDelivered:approval-wf:corr-13:approve",
                            "timerFired:timeout-wf:corr-14:timer-1");

                    // Child workflow
                    assertThat(a.calls).contains(
                            "childWorkflowStarted:parent-wf:corr-15:child-wf:corr-16",
                            "childWorkflowCompleted:parent-wf:corr-15:corr-16:true");
                    assertThat(b.calls).contains(
                            "childWorkflowStarted:parent-wf:corr-15:child-wf:corr-16",
                            "childWorkflowCompleted:parent-wf:corr-15:corr-16:true");

                    // Continue-as-new
                    assertThat(a.calls).contains("continueAsNew:long-running-wf:corr-17:corr-18");
                    assertThat(b.calls).contains("continueAsNew:long-running-wf:corr-17:corr-18");

                    // Idempotency and DLQ
                    assertThat(a.calls).contains(
                            "stepSkippedIdempotent:saga-1:corr-19:step-already-done",
                            "deadLettered:saga-2:corr-20:failed-step");
                    assertThat(b.calls).contains(
                            "stepSkippedIdempotent:saga-1:corr-19:step-already-done",
                            "deadLettered:saga-2:corr-20:failed-step");
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

    /**
     * Full tracking implementation that records all event method calls into a single list.
     */
    static class FullTrackingEventsA implements OrchestrationEvents {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override public void onStart(String n, String c, ExecutionPattern p) { calls.add("start:" + n + ":" + c + ":" + p); }
        @Override public void onStepStarted(String n, String c, String s) { calls.add("stepStarted:" + n + ":" + c + ":" + s); }
        @Override public void onStepSuccess(String n, String c, String s, int a, long l) { calls.add("stepSuccess:" + n + ":" + c + ":" + s); }
        @Override public void onStepFailed(String n, String c, String s, Throwable e, int a) { calls.add("stepFailed:" + n + ":" + c + ":" + s); }
        @Override public void onStepSkipped(String n, String c, String s) { calls.add("stepSkipped:" + n + ":" + c + ":" + s); }
        @Override public void onCompleted(String n, String c, ExecutionPattern p, boolean s, long d) { calls.add("completed:" + n + ":" + c + ":" + p + ":" + s); }
        @Override public void onCompensationStarted(String n, String c) { calls.add("compensationStarted:" + n + ":" + c); }
        @Override public void onStepCompensated(String n, String c, String s) { calls.add("stepCompensated:" + n + ":" + c + ":" + s); }
        @Override public void onStepCompensationFailed(String n, String c, String s, Throwable e) { calls.add("stepCompensationFailed:" + n + ":" + c + ":" + s); }
        @Override public void onPhaseStarted(String n, String c, TccPhase p) { calls.add("phaseStarted:" + n + ":" + c + ":" + p); }
        @Override public void onPhaseCompleted(String n, String c, TccPhase p, long d) { calls.add("phaseCompleted:" + n + ":" + c + ":" + p); }
        @Override public void onPhaseFailed(String n, String c, TccPhase p, Throwable e) { calls.add("phaseFailed:" + n + ":" + c + ":" + p); }
        @Override public void onParticipantStarted(String n, String c, String pid, TccPhase p) { calls.add("participantStarted:" + n + ":" + c + ":" + pid + ":" + p); }
        @Override public void onParticipantSuccess(String n, String c, String pid, TccPhase p) { calls.add("participantSuccess:" + n + ":" + c + ":" + pid + ":" + p); }
        @Override public void onParticipantFailed(String n, String c, String pid, TccPhase p, Throwable e) { calls.add("participantFailed:" + n + ":" + c + ":" + pid + ":" + p); }
        @Override public void onWorkflowSuspended(String n, String c, String r) { calls.add("workflowSuspended:" + n + ":" + c + ":" + r); }
        @Override public void onWorkflowResumed(String n, String c) { calls.add("workflowResumed:" + n + ":" + c); }
        @Override public void onCompositionStarted(String cn, String c) { calls.add("compositionStarted:" + cn + ":" + c); }
        @Override public void onCompositionCompleted(String cn, String c, boolean s) { calls.add("compositionCompleted:" + cn + ":" + c + ":" + s); }
        @Override public void onSignalDelivered(String n, String c, String sn) { calls.add("signalDelivered:" + n + ":" + c + ":" + sn); }
        @Override public void onTimerFired(String n, String c, String t) { calls.add("timerFired:" + n + ":" + c + ":" + t); }
        @Override public void onChildWorkflowStarted(String pn, String pc, String cw, String cc) { calls.add("childWorkflowStarted:" + pn + ":" + pc + ":" + cw + ":" + cc); }
        @Override public void onChildWorkflowCompleted(String pn, String pc, String cc, boolean s) { calls.add("childWorkflowCompleted:" + pn + ":" + pc + ":" + cc + ":" + s); }
        @Override public void onContinueAsNew(String n, String oc, String nc) { calls.add("continueAsNew:" + n + ":" + oc + ":" + nc); }
        @Override public void onStepSkippedIdempotent(String n, String c, String s) { calls.add("stepSkippedIdempotent:" + n + ":" + c + ":" + s); }
        @Override public void onDeadLettered(String n, String c, String s, Throwable e) { calls.add("deadLettered:" + n + ":" + c + ":" + s); }
    }

    /**
     * Second full tracking implementation for verifying multi-delegate dispatch.
     */
    static class FullTrackingEventsB implements OrchestrationEvents {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Override public void onStart(String n, String c, ExecutionPattern p) { calls.add("start:" + n + ":" + c + ":" + p); }
        @Override public void onStepStarted(String n, String c, String s) { calls.add("stepStarted:" + n + ":" + c + ":" + s); }
        @Override public void onStepSuccess(String n, String c, String s, int a, long l) { calls.add("stepSuccess:" + n + ":" + c + ":" + s); }
        @Override public void onStepFailed(String n, String c, String s, Throwable e, int a) { calls.add("stepFailed:" + n + ":" + c + ":" + s); }
        @Override public void onStepSkipped(String n, String c, String s) { calls.add("stepSkipped:" + n + ":" + c + ":" + s); }
        @Override public void onCompleted(String n, String c, ExecutionPattern p, boolean s, long d) { calls.add("completed:" + n + ":" + c + ":" + p + ":" + s); }
        @Override public void onCompensationStarted(String n, String c) { calls.add("compensationStarted:" + n + ":" + c); }
        @Override public void onStepCompensated(String n, String c, String s) { calls.add("stepCompensated:" + n + ":" + c + ":" + s); }
        @Override public void onStepCompensationFailed(String n, String c, String s, Throwable e) { calls.add("stepCompensationFailed:" + n + ":" + c + ":" + s); }
        @Override public void onPhaseStarted(String n, String c, TccPhase p) { calls.add("phaseStarted:" + n + ":" + c + ":" + p); }
        @Override public void onPhaseCompleted(String n, String c, TccPhase p, long d) { calls.add("phaseCompleted:" + n + ":" + c + ":" + p); }
        @Override public void onPhaseFailed(String n, String c, TccPhase p, Throwable e) { calls.add("phaseFailed:" + n + ":" + c + ":" + p); }
        @Override public void onParticipantStarted(String n, String c, String pid, TccPhase p) { calls.add("participantStarted:" + n + ":" + c + ":" + pid + ":" + p); }
        @Override public void onParticipantSuccess(String n, String c, String pid, TccPhase p) { calls.add("participantSuccess:" + n + ":" + c + ":" + pid + ":" + p); }
        @Override public void onParticipantFailed(String n, String c, String pid, TccPhase p, Throwable e) { calls.add("participantFailed:" + n + ":" + c + ":" + pid + ":" + p); }
        @Override public void onWorkflowSuspended(String n, String c, String r) { calls.add("workflowSuspended:" + n + ":" + c + ":" + r); }
        @Override public void onWorkflowResumed(String n, String c) { calls.add("workflowResumed:" + n + ":" + c); }
        @Override public void onCompositionStarted(String cn, String c) { calls.add("compositionStarted:" + cn + ":" + c); }
        @Override public void onCompositionCompleted(String cn, String c, boolean s) { calls.add("compositionCompleted:" + cn + ":" + c + ":" + s); }
        @Override public void onSignalDelivered(String n, String c, String sn) { calls.add("signalDelivered:" + n + ":" + c + ":" + sn); }
        @Override public void onTimerFired(String n, String c, String t) { calls.add("timerFired:" + n + ":" + c + ":" + t); }
        @Override public void onChildWorkflowStarted(String pn, String pc, String cw, String cc) { calls.add("childWorkflowStarted:" + pn + ":" + pc + ":" + cw + ":" + cc); }
        @Override public void onChildWorkflowCompleted(String pn, String pc, String cc, boolean s) { calls.add("childWorkflowCompleted:" + pn + ":" + pc + ":" + cc + ":" + s); }
        @Override public void onContinueAsNew(String n, String oc, String nc) { calls.add("continueAsNew:" + n + ":" + oc + ":" + nc); }
        @Override public void onStepSkippedIdempotent(String n, String c, String s) { calls.add("stepSkippedIdempotent:" + n + ":" + c + ":" + s); }
        @Override public void onDeadLettered(String n, String c, String s, Throwable e) { calls.add("deadLettered:" + n + ":" + c + ":" + s); }
    }

    @Configuration(proxyBeanMethods = false)
    static class FullTrackingEventsConfig {
        @Bean
        FullTrackingEventsA fullTrackingEventsA() {
            return new FullTrackingEventsA();
        }

        @Bean
        FullTrackingEventsB fullTrackingEventsB() {
            return new FullTrackingEventsB();
        }
    }
}
