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

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.tcc.annotation.*;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.fireflyframework.orchestration.tcc.registry.TccRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TccRegistryAnnotationScanTest {

    @Test
    void annotationScan_discoversParticipantsMethodsAndCallbacks() {
        var appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(Tcc.class))
                .thenReturn(Map.of("validTcc", new ValidTcc()));

        var registry = new TccRegistry(appCtx);

        assertThat(registry.hasTcc("PaymentTcc")).isTrue();
        TccDefinition def = registry.getTcc("PaymentTcc");
        assertThat(def.name).isEqualTo("PaymentTcc");
        assertThat(def.participants).hasSize(1);
        assertThat(def.participants).containsKey("payment");

        var participant = def.participants.get("payment");
        assertThat(participant.tryMethod).isNotNull();
        assertThat(participant.tryMethod.getName()).isEqualTo("reserveFunds");
        assertThat(participant.confirmMethod).isNotNull();
        assertThat(participant.confirmMethod.getName()).isEqualTo("confirmPayment");
        assertThat(participant.cancelMethod).isNotNull();
        assertThat(participant.cancelMethod.getName()).isEqualTo("cancelPayment");
    }

    @Test
    void annotationScan_wiresTriggerEventType() {
        var appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(Tcc.class))
                .thenReturn(Map.of("eventTcc", new EventTriggeredTcc()));

        var registry = new TccRegistry(appCtx);
        TccDefinition def = registry.getTcc("EventTcc");
        assertThat(def.triggerEventType).isEqualTo("OrderCreated");
    }

    @Test
    void annotationScan_wiresTccEventConfig() {
        var appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(Tcc.class))
                .thenReturn(Map.of("eventConfigTcc", new TccWithEvent()));

        var registry = new TccRegistry(appCtx);
        TccDefinition def = registry.getTcc("TccWithEvent");

        var participant = def.participants.get("evented");
        assertThat(participant.tccEvent).isNotNull();
        assertThat(participant.tccEvent.topic()).isEqualTo("payments");
        assertThat(participant.tccEvent.eventType()).isEqualTo("PaymentConfirmed");
        assertThat(participant.tccEvent.key()).isEqualTo("orderId");
    }

    @Test
    void annotationScan_missingTryMethod_throws() {
        var appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(Tcc.class))
                .thenReturn(Map.of("broken", new MissingTryTcc()));

        var registry = new TccRegistry(appCtx);
        assertThatThrownBy(() -> registry.getTcc("MissingTryTcc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to instantiate participant")
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing @TryMethod");
    }

    @Test
    void annotationScan_nonStaticInnerClass_throws() {
        var appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(Tcc.class))
                .thenReturn(Map.of("nonStatic", new NonStaticParticipantTcc()));

        var registry = new TccRegistry(appCtx);
        assertThatThrownBy(() -> registry.getTcc("NonStaticTcc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be declared static");
    }

    @Test
    void annotationScan_noParticipants_throws() {
        var appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(Tcc.class))
                .thenReturn(Map.of("empty", new NoParticipantsTcc()));

        var registry = new TccRegistry(appCtx);
        assertThatThrownBy(() -> registry.getTcc("EmptyTcc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no participants");
    }

    @Test
    void annotationScan_participantOrdering_respected() {
        var appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(Tcc.class))
                .thenReturn(Map.of("ordered", new OrderedParticipantsTcc()));

        var registry = new TccRegistry(appCtx);
        TccDefinition def = registry.getTcc("OrderedTcc");
        assertThat(def.participants).hasSize(2);

        // Verify order values are captured
        var first = def.participants.get("first");
        var second = def.participants.get("second");
        assertThat(first.order).isEqualTo(1);
        assertThat(second.order).isEqualTo(2);
    }

    @Test
    void annotationScan_lifecycleCallbacks_scannedAndPrioritySorted() {
        var appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansWithAnnotation(Tcc.class))
                .thenReturn(Map.of("lifecycle", new LifecycleCallbackTcc()));

        var registry = new TccRegistry(appCtx);
        TccDefinition def = registry.getTcc("LifecycleTcc");

        assertThat(def.onTccCompleteMethods).hasSize(2);
        // Higher priority first
        assertThat(def.onTccCompleteMethods.get(0).getName()).isEqualTo("highPriorityComplete");
        assertThat(def.onTccCompleteMethods.get(1).getName()).isEqualTo("lowPriorityComplete");

        assertThat(def.onTccErrorMethods).hasSize(1);
        assertThat(def.onTccErrorMethods.get(0).getName()).isEqualTo("onError");
    }

    // ── Test TCC beans ────────────────────────────────────────────

    @Tcc(name = "PaymentTcc")
    public static class ValidTcc {
        @TccParticipant(id = "payment")
        public static class PaymentParticipant {
            @TryMethod
            public Mono<String> reserveFunds(Object input) { return Mono.just("reserved"); }
            @ConfirmMethod
            public Mono<String> confirmPayment(Object input) { return Mono.just("confirmed"); }
            @CancelMethod
            public Mono<Void> cancelPayment(Object input) { return Mono.empty(); }
        }
    }

    @Tcc(name = "EventTcc", triggerEventType = "OrderCreated")
    public static class EventTriggeredTcc {
        @TccParticipant(id = "handler")
        public static class Handler {
            @TryMethod
            public Mono<String> tryIt(Object input) { return Mono.just("tried"); }
            @ConfirmMethod
            public Mono<String> confirmIt(Object input) { return Mono.just("confirmed"); }
            @CancelMethod
            public Mono<Void> cancelIt(Object input) { return Mono.empty(); }
        }
    }

    @Tcc(name = "TccWithEvent")
    public static class TccWithEvent {
        @TccParticipant(id = "evented")
        @TccEvent(topic = "payments", eventType = "PaymentConfirmed", key = "orderId")
        public static class EventedParticipant {
            @TryMethod
            public Mono<String> tryIt(Object input) { return Mono.just("tried"); }
            @ConfirmMethod
            public Mono<String> confirmIt(Object input) { return Mono.just("confirmed"); }
            @CancelMethod
            public Mono<Void> cancelIt(Object input) { return Mono.empty(); }
        }
    }

    @Tcc(name = "MissingTryTcc")
    public static class MissingTryTcc {
        @TccParticipant(id = "broken")
        public static class BrokenParticipant {
            // Missing @TryMethod
            @ConfirmMethod
            public Mono<String> confirmIt(Object input) { return Mono.just("confirmed"); }
            @CancelMethod
            public Mono<Void> cancelIt(Object input) { return Mono.empty(); }
        }
    }

    @Tcc(name = "NonStaticTcc")
    public static class NonStaticParticipantTcc {
        @TccParticipant(id = "nonStatic")
        public class NonStaticParticipant {
            @TryMethod
            public Mono<String> tryIt(Object input) { return Mono.just("tried"); }
            @ConfirmMethod
            public Mono<String> confirmIt(Object input) { return Mono.just("confirmed"); }
            @CancelMethod
            public Mono<Void> cancelIt(Object input) { return Mono.empty(); }
        }
    }

    @Tcc(name = "EmptyTcc")
    public static class NoParticipantsTcc {
        // No participant inner classes
    }

    @Tcc(name = "OrderedTcc")
    public static class OrderedParticipantsTcc {
        @TccParticipant(id = "second", order = 2)
        public static class SecondParticipant {
            @TryMethod
            public Mono<String> tryIt(Object input) { return Mono.just("tried"); }
            @ConfirmMethod
            public Mono<String> confirmIt(Object input) { return Mono.just("confirmed"); }
            @CancelMethod
            public Mono<Void> cancelIt(Object input) { return Mono.empty(); }
        }

        @TccParticipant(id = "first", order = 1)
        public static class FirstParticipant {
            @TryMethod
            public Mono<String> tryIt(Object input) { return Mono.just("tried"); }
            @ConfirmMethod
            public Mono<String> confirmIt(Object input) { return Mono.just("confirmed"); }
            @CancelMethod
            public Mono<Void> cancelIt(Object input) { return Mono.empty(); }
        }
    }

    @Tcc(name = "LifecycleTcc")
    public static class LifecycleCallbackTcc {
        @TccParticipant(id = "p1")
        public static class Participant {
            @TryMethod
            public Mono<String> tryIt(Object input) { return Mono.just("tried"); }
            @ConfirmMethod
            public Mono<String> confirmIt(Object input) { return Mono.just("confirmed"); }
            @CancelMethod
            public Mono<Void> cancelIt(Object input) { return Mono.empty(); }
        }

        @OnTccComplete(priority = 5)
        public void lowPriorityComplete() {}

        @OnTccComplete(priority = 10)
        public void highPriorityComplete() {}

        @OnTccError
        public void onError(Throwable error) {}
    }
}
