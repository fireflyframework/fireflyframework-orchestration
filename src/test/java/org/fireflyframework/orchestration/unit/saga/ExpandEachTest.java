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

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepHandler;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
import org.fireflyframework.orchestration.saga.compensation.SagaCompensator;
import org.fireflyframework.orchestration.saga.engine.ExpandEach;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaExecutionOrchestrator;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests ExpandEach fan-out behavior for saga steps.
 */
class ExpandEachTest {

    private SagaEngine engine;

    @BeforeEach
    void setUp() {
        var events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noOpPublisher = new org.fireflyframework.orchestration.core.event.NoOpEventPublisher();
        var orchestrator = new SagaExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, stepInvoker);
        engine = new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher);
    }

    @Test
    void expandEach_clonesStepPerItem() {
        List<String> processedItems = Collections.synchronizedList(new ArrayList<>());

        SagaDefinition saga = SagaBuilder.saga("FanOutSaga")
                .step("process")
                    .handler((StepHandler<String, String>) (input, ctx) -> {
                        processedItems.add(input);
                        return Mono.just("done:" + input);
                    })
                    .add()
                .build();

        StepInputs inputs = StepInputs.builder()
                .forStepId("process", ExpandEach.of(List.of("apple", "banana", "cherry")))
                .build();

        StepVerifier.create(engine.execute(saga, inputs))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    // Should have 3 cloned steps instead of 1
                    assertThat(result.steps()).hasSize(3);
                    assertThat(processedItems).containsExactlyInAnyOrder("apple", "banana", "cherry");
                })
                .verifyComplete();
    }

    @Test
    void expandEach_withIdSuffix_usesCustomIds() {
        SagaDefinition saga = SagaBuilder.saga("CustomIdSaga")
                .step("insert")
                    .handler((StepHandler<String, String>) (input, ctx) ->
                            Mono.just("inserted:" + input))
                    .add()
                .build();

        StepInputs inputs = StepInputs.builder()
                .forStepId("insert", ExpandEach.of(
                        List.of("item-A", "item-B"),
                        obj -> obj.toString()))
                .build();

        StepVerifier.create(engine.execute(saga, inputs))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.steps()).hasSize(2);
                    // Step IDs should include the custom suffix
                    assertThat(result.steps().keySet()).allSatisfy(key ->
                            assertThat(key).startsWith("insert:"));
                })
                .verifyComplete();
    }

    @Test
    void expandEach_withDependentStep_rewritesDeps() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());

        SagaDefinition saga = SagaBuilder.saga("DepFanOut")
                .step("load")
                    .handler((StepHandler<String, String>) (input, ctx) -> {
                        order.add("load:" + input);
                        return Mono.just("loaded:" + input);
                    })
                    .add()
                .step("aggregate")
                    .dependsOn("load")
                    .handler((StepHandler<Object, String>) (input, ctx) -> {
                        order.add("aggregate");
                        return Mono.just("aggregated");
                    })
                    .add()
                .build();

        StepInputs inputs = StepInputs.builder()
                .forStepId("load", ExpandEach.of(List.of("x", "y")))
                .build();

        StepVerifier.create(engine.execute(saga, inputs))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    // "aggregate" depends on all expanded "load" clones
                    int aggIdx = order.indexOf("aggregate");
                    assertThat(aggIdx).isGreaterThan(0);
                    // Both loads must complete before aggregate
                    assertThat(order.subList(0, aggIdx))
                            .containsExactlyInAnyOrder("load:x", "load:y");
                })
                .verifyComplete();
    }

    @Test
    void expandEach_emptyList_producesNoSteps() {
        SagaDefinition saga = SagaBuilder.saga("EmptyFanOut")
                .step("process")
                    .handler((StepHandler<String, String>) (input, ctx) ->
                            Mono.just("done:" + input))
                    .add()
                .step("finish")
                    .dependsOn("process")
                    .handler((StepHandler<Object, String>) (input, ctx) ->
                            Mono.just("finished"))
                    .add()
                .build();

        StepInputs inputs = StepInputs.builder()
                .forStepId("process", ExpandEach.of(List.of()))
                .build();

        StepVerifier.create(engine.execute(saga, inputs))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    // "process" was expanded to 0 clones, "finish" has no unmet deps
                    assertThat(result.steps()).containsKey("finish");
                })
                .verifyComplete();
    }
}
