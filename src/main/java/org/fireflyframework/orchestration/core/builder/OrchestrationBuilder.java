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

package org.fireflyframework.orchestration.core.builder;

import org.fireflyframework.orchestration.saga.builder.SagaBuilder;
import org.fireflyframework.orchestration.tcc.builder.TccBuilder;
import org.fireflyframework.orchestration.workflow.builder.WorkflowBuilder;

/**
 * Unified entry point for building orchestration definitions programmatically.
 *
 * <p>Provides static factory methods for all three execution patterns:
 * <pre>{@code
 * // Workflow
 * WorkflowDefinition wf = OrchestrationBuilder.workflow("processOrder")
 *     .step("validate").handler(bean, method).add()
 *     .step("charge").handler(bean, method).dependsOn("validate").add()
 *     .build();
 *
 * // Saga
 * SagaDefinition saga = OrchestrationBuilder.saga("transferFunds")
 *     .step("debit").handler(bean, method).compensatedBy(bean, compensate).add()
 *     .step("credit").handler(bean, method).dependsOn("debit").add()
 *     .build();
 *
 * // TCC
 * TccDefinition tcc = OrchestrationBuilder.tcc("reserveInventory")
 *     .participant("reserve")
 *         .tryHandler((input, ctx) -> reserve(input))
 *         .confirmHandler((result, ctx) -> commit(result))
 *         .cancelHandler((result, ctx) -> rollback(result))
 *         .add()
 *     .build();
 * }</pre>
 */
public final class OrchestrationBuilder {

    private OrchestrationBuilder() {
        // static factory only
    }

    /**
     * Begin building a Workflow definition.
     *
     * @param name workflow name
     * @return a new {@link WorkflowBuilder}
     */
    public static WorkflowBuilder workflow(String name) {
        return new WorkflowBuilder(name);
    }

    /**
     * Begin building a Saga definition.
     *
     * @param name saga name
     * @return a new {@link SagaBuilder}
     */
    public static SagaBuilder saga(String name) {
        return SagaBuilder.saga(name);
    }

    /**
     * Begin building a TCC definition.
     *
     * @param name TCC name
     * @return a new {@link TccBuilder}
     */
    public static TccBuilder tcc(String name) {
        return TccBuilder.tcc(name);
    }
}
