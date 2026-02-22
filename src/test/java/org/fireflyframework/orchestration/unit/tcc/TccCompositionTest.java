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
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.tcc.composition.*;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TccCompositionTest {

    @Mock
    private TccEngine tccEngine;

    private OrchestrationEvents events;
    private TccCompositor compositor;

    @BeforeEach
    void setUp() {
        events = new OrchestrationEvents() {};
        TccCompositionDataFlowManager dataFlowManager = new TccCompositionDataFlowManager();
        TccCompositionCompensationManager compensationManager =
                new TccCompositionCompensationManager(tccEngine, CompensationPolicy.STRICT_SEQUENTIAL);
        compositor = new TccCompositor(tccEngine, events, dataFlowManager, compensationManager);
    }

    @Test
    void compose_sequentialTccs_executesInOrder() {
        // Build: A -> B -> C (fully sequential)
        TccComposition composition = TccCompositionBuilder.composition("sequential-flow")
                .tcc("step-a").tccName("TccA").add()
                .tcc("step-b").tccName("TccB").dependsOn("step-a").add()
                .tcc("step-c").tccName("TccC").dependsOn("step-b").add()
                .build();

        // Track execution order
        AtomicInteger order = new AtomicInteger(0);
        ConcurrentHashMap<String, Integer> executionOrder = new ConcurrentHashMap<>();

        when(tccEngine.execute(eq("TccA"), anyMap()))
                .thenAnswer(inv -> {
                    executionOrder.put("TccA", order.getAndIncrement());
                    return Mono.just(confirmedResult("TccA"));
                });
        when(tccEngine.execute(eq("TccB"), anyMap()))
                .thenAnswer(inv -> {
                    executionOrder.put("TccB", order.getAndIncrement());
                    return Mono.just(confirmedResult("TccB"));
                });
        when(tccEngine.execute(eq("TccC"), anyMap()))
                .thenAnswer(inv -> {
                    executionOrder.put("TccC", order.getAndIncrement());
                    return Mono.just(confirmedResult("TccC"));
                });

        StepVerifier.create(compositor.compose(composition, Map.of()))
                .assertNext(result -> {
                    assertThat(result.success()).isTrue();
                    assertThat(result.compositionName()).isEqualTo("sequential-flow");
                    assertThat(result.tccResults()).hasSize(3);
                    // Verify sequential ordering
                    assertThat(executionOrder.get("TccA")).isLessThan(executionOrder.get("TccB"));
                    assertThat(executionOrder.get("TccB")).isLessThan(executionOrder.get("TccC"));
                })
                .verifyComplete();

        verify(tccEngine).execute(eq("TccA"), anyMap());
        verify(tccEngine).execute(eq("TccB"), anyMap());
        verify(tccEngine).execute(eq("TccC"), anyMap());
    }

    @Test
    void compose_parallelTccs_executeConcurrently() {
        // Build: A and B in parallel (no deps), then C depends on both
        TccComposition composition = TccCompositionBuilder.composition("parallel-flow")
                .tcc("payment").tccName("PaymentTcc").add()
                .tcc("inventory").tccName("InventoryTcc").add()
                .tcc("shipping").tccName("ShippingTcc").dependsOn("payment", "inventory").add()
                .build();

        // Verify layers
        var layers = composition.getExecutableLayers();
        assertThat(layers).hasSize(2);
        assertThat(layers.get(0).stream().map(TccComposition.CompositionTcc::alias))
                .containsExactlyInAnyOrder("payment", "inventory");
        assertThat(layers.get(1).stream().map(TccComposition.CompositionTcc::alias))
                .containsExactly("shipping");

        when(tccEngine.execute(eq("PaymentTcc"), anyMap()))
                .thenReturn(Mono.just(confirmedResult("PaymentTcc")));
        when(tccEngine.execute(eq("InventoryTcc"), anyMap()))
                .thenReturn(Mono.just(confirmedResult("InventoryTcc")));
        when(tccEngine.execute(eq("ShippingTcc"), anyMap()))
                .thenReturn(Mono.just(confirmedResult("ShippingTcc")));

        StepVerifier.create(compositor.compose(composition, Map.of()))
                .assertNext(result -> {
                    assertThat(result.success()).isTrue();
                    assertThat(result.tccResults()).hasSize(3);
                    assertThat(result.tccResults().get("payment").isConfirmed()).isTrue();
                    assertThat(result.tccResults().get("inventory").isConfirmed()).isTrue();
                    assertThat(result.tccResults().get("shipping").isConfirmed()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void compose_dataFlow_passesResultsBetweenTccs() {
        // Build: A -> B with data flow
        TccComposition composition = TccCompositionBuilder.composition("data-flow")
                .tcc("producer").tccName("ProducerTcc").add()
                .tcc("consumer").tccName("ConsumerTcc").dependsOn("producer").add()
                .dataFlow("producer", "orderId", "consumer", "inputOrderId")
                .build();

        // Producer returns a result with participant outcomes containing orderId
        TccResult producerResult = confirmedResultWithData("ProducerTcc",
                Map.of("orderId", "ORD-123"));

        when(tccEngine.execute(eq("ProducerTcc"), anyMap()))
                .thenReturn(Mono.just(producerResult));
        when(tccEngine.execute(eq("ConsumerTcc"), anyMap()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = (Map<String, Object>) inv.getArgument(1);
                    // Verify the data flow mapped orderId -> inputOrderId
                    assertThat(input).containsEntry("inputOrderId", "ORD-123");
                    return Mono.just(confirmedResult("ConsumerTcc"));
                });

        StepVerifier.create(compositor.compose(composition, Map.of()))
                .assertNext(result -> {
                    assertThat(result.success()).isTrue();
                    assertThat(result.tccResults()).hasSize(2);
                })
                .verifyComplete();

        verify(tccEngine).execute(eq("ConsumerTcc"), anyMap());
    }

    @Test
    void compose_failingTcc_compensatesCompleted() {
        // Build: A -> B, where B fails
        TccComposition composition = TccCompositionBuilder.composition("failing-flow")
                .tcc("step-a").tccName("TccA").add()
                .tcc("step-b").tccName("TccB").dependsOn("step-a").add()
                .build();

        when(tccEngine.execute(eq("TccA"), anyMap()))
                .thenReturn(Mono.just(confirmedResult("TccA")));
        when(tccEngine.execute(eq("TccB"), anyMap()))
                .thenReturn(Mono.just(failedResult("TccB", new RuntimeException("TccB failed"))));

        StepVerifier.create(compositor.compose(composition, Map.of()))
                .assertNext(result -> {
                    assertThat(result.success()).isFalse();
                    assertThat(result.error()).isInstanceOf(RuntimeException.class);
                    assertThat(result.error().getMessage()).isEqualTo("TccB failed");
                    assertThat(result.tccResults()).containsKey("step-a");
                    assertThat(result.tccResults()).containsKey("step-b");
                    assertThat(result.tccResults().get("step-a").isConfirmed()).isTrue();
                    assertThat(result.tccResults().get("step-b").isFailed()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void compose_optionalTcc_continuesOnFailure() {
        // Build: A and B (optional) in parallel, then C depends on both
        TccComposition composition = TccCompositionBuilder.composition("optional-flow")
                .tcc("required").tccName("RequiredTcc").add()
                .tcc("optional").tccName("OptionalTcc").optional(true).add()
                .tcc("final").tccName("FinalTcc").dependsOn("required", "optional").add()
                .build();

        when(tccEngine.execute(eq("RequiredTcc"), anyMap()))
                .thenReturn(Mono.just(confirmedResult("RequiredTcc")));
        when(tccEngine.execute(eq("OptionalTcc"), anyMap()))
                .thenReturn(Mono.just(failedResult("OptionalTcc",
                        new RuntimeException("Optional failed"))));
        when(tccEngine.execute(eq("FinalTcc"), anyMap()))
                .thenReturn(Mono.just(confirmedResult("FinalTcc")));

        StepVerifier.create(compositor.compose(composition, Map.of()))
                .assertNext(result -> {
                    assertThat(result.success()).isTrue();
                    assertThat(result.tccResults()).hasSize(3);
                    assertThat(result.tccResults().get("required").isConfirmed()).isTrue();
                    assertThat(result.tccResults().get("optional").isFailed()).isTrue();
                    assertThat(result.tccResults().get("final").isConfirmed()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void builder_duplicateAlias_throwsOnBuild() {
        assertThatThrownBy(() -> TccCompositionBuilder.composition("test")
                .tcc("dup").tccName("Tcc1").add()
                .tcc("dup").tccName("Tcc2").add()
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate TCC alias");
    }

    @Test
    void builder_cyclicDependency_throwsOnBuild() {
        assertThatThrownBy(() -> TccCompositionBuilder.composition("test")
                .tcc("a").tccName("A").dependsOn("b").add()
                .tcc("b").tccName("B").dependsOn("a").add()
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Circular");
    }

    // --- Helper methods ---

    private TccResult confirmedResult(String tccName) {
        ExecutionContext ctx = ExecutionContext.forTcc(null, tccName);
        return TccResult.confirmed(tccName, ctx, Map.of());
    }

    @SuppressWarnings("unchecked")
    private TccResult confirmedResultWithData(String tccName, Map<String, Object> data) {
        ExecutionContext ctx = ExecutionContext.forTcc(null, tccName);
        // Create a participant outcome with the data as tryResult
        Map<String, TccResult.ParticipantOutcome> participants = Map.of(
                "default", new TccResult.ParticipantOutcome(
                        "default", data, true, true, false, null, 1, 10L));
        return TccResult.confirmed(tccName, ctx, participants);
    }

    private TccResult failedResult(String tccName, Throwable error) {
        ExecutionContext ctx = ExecutionContext.forTcc(null, tccName);
        return TccResult.failed(tccName, ctx, null, null, error, Map.of());
    }
}
