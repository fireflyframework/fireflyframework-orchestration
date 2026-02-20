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

package org.fireflyframework.orchestration.integration;

import org.fireflyframework.orchestration.core.builder.OrchestrationBuilder;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests that the OrchestrationBuilder DSL produces valid definitions
 * for all three execution patterns.
 */
class BuilderDslIntegrationTest {

    @Test
    void workflow_builderDsl_producesCompleteDefinition() throws Exception {
        var bean = new Object() {
            public Mono<String> doWork() { return Mono.just("done"); }
        };

        WorkflowDefinition wf = OrchestrationBuilder.workflow("wf-test")
                .description("Test workflow")
                .step("a")
                    .handler(bean, bean.getClass().getDeclaredMethod("doWork"))
                    .add()
                .step("b")
                    .handler(bean, bean.getClass().getDeclaredMethod("doWork"))
                    .dependsOn("a")
                    .add()
                .build();

        assertThat(wf.workflowId()).isEqualTo("wf-test");
        assertThat(wf.steps()).hasSize(2);
    }

    @Test
    void saga_builderDsl_producesCompleteDefinition() {
        SagaDefinition saga = OrchestrationBuilder.saga("saga-test")
                .step("s1").handler(() -> Mono.just("r1")).add()
                .step("s2").handler(() -> Mono.just("r2")).dependsOn("s1").add()
                .build();

        assertThat(saga.name).isEqualTo("saga-test");
        assertThat(saga.steps).hasSize(2);
        assertThat(saga.steps).containsKeys("s1", "s2");
    }

    @Test
    void tcc_builderDsl_producesCompleteDefinition() {
        TccDefinition tcc = OrchestrationBuilder.tcc("tcc-test")
                .participant("p1")
                    .tryHandler((input, ctx) -> Mono.just("tried"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .participant("p2")
                    .tryHandler((input, ctx) -> Mono.just("tried2"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed2"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled2"))
                    .add()
                .build();

        assertThat(tcc.name).isEqualTo("tcc-test");
        assertThat(tcc.participants).hasSize(2);
        assertThat(tcc.participants).containsKeys("p1", "p2");
    }

    @Test
    void saga_withCompensation_includesCompensationHandlers() {
        SagaDefinition saga = OrchestrationBuilder.saga("comp-saga")
                .step("step1")
                    .handler(() -> Mono.just("result"))
                    .compensation((arg, ctx) -> Mono.empty())
                    .add()
                .build();

        assertThat(saga.name).isEqualTo("comp-saga");
        assertThat(saga.steps).hasSize(1);
        assertThat(saga.steps).containsKey("step1");
    }

    @Test
    void tcc_withOptionalParticipant_setsOptionalFlag() {
        TccDefinition tcc = OrchestrationBuilder.tcc("opt-tcc")
                .participant("required")
                    .tryHandler((input, ctx) -> Mono.just("r"))
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .participant("optional")
                    .optional(true)
                    .tryHandler((input, ctx) -> Mono.just("o"))
                    .confirmHandler((input, ctx) -> Mono.empty())
                    .cancelHandler((input, ctx) -> Mono.empty())
                    .add()
                .build();

        assertThat(tcc.participants.get("optional").optional).isTrue();
        assertThat(tcc.participants.get("required").optional).isFalse();
    }
}
