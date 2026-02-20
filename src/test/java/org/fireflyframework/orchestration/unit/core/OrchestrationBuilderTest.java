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

import org.fireflyframework.orchestration.core.builder.OrchestrationBuilder;
import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.tcc.builder.TccBuilder;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.fireflyframework.orchestration.workflow.builder.WorkflowBuilder;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.*;

class OrchestrationBuilderTest {

    @Test
    void workflow_returnsWorkflowBuilder() {
        WorkflowBuilder builder = OrchestrationBuilder.workflow("test-workflow");
        assertThat(builder).isNotNull();
    }

    @Test
    void saga_returnsSagaBuilder() {
        SagaBuilder builder = OrchestrationBuilder.saga("test-saga");
        assertThat(builder).isNotNull();
    }

    @Test
    void tcc_returnsTccBuilder() {
        TccBuilder builder = OrchestrationBuilder.tcc("test-tcc");
        assertThat(builder).isNotNull();
    }

    @Test
    void tcc_buildsValidDefinition() {
        TccDefinition tcc = OrchestrationBuilder.tcc("transfer")
                .participant("debit")
                    .tryHandler((input, ctx) -> Mono.just("reserved"))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        assertThat(tcc.name).isEqualTo("transfer");
        assertThat(tcc.participants).containsKey("debit");
    }

    @Test
    void saga_buildsValidDefinition() {
        SagaDefinition saga = OrchestrationBuilder.saga("order-saga")
                .step("validate")
                    .handler(() -> Mono.just("validated"))
                    .add()
                .build();

        assertThat(saga.name).isEqualTo("order-saga");
        assertThat(saga.steps).hasSize(1);
        assertThat(saga.steps).containsKey("validate");
    }
}
