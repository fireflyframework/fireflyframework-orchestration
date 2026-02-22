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

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.validation.ValidationIssue;
import org.fireflyframework.orchestration.saga.composition.*;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaResult;
import org.fireflyframework.orchestration.saga.registry.SagaRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositionEnrichmentTest {

    @Mock
    private SagaEngine sagaEngine;

    @Mock
    private SagaRegistry sagaRegistry;

    @Test
    void compositionContext_tracksResultsAndOrder() {
        CompositionContext context = new CompositionContext("corr-123");

        SagaResult resultA = successResult("SagaA");
        SagaResult resultB = successResult("SagaB");
        SagaResult resultC = failedResult("SagaC", new RuntimeException("fail"));

        context.recordResult("a", resultA);
        context.recordResult("b", resultB);
        context.recordResult("c", resultC);

        assertThat(context.getGlobalCorrelationId()).isEqualTo("corr-123");
        assertThat(context.getStartedAt()).isNotNull();
        assertThat(context.getExecutionOrder()).containsExactly("a", "b", "c");
        assertThat(context.getResult("a")).isSameAs(resultA);
        assertThat(context.getResult("b")).isSameAs(resultB);
        assertThat(context.isCompleted("a")).isTrue();
        assertThat(context.isCompleted("nonexistent")).isFalse();
        assertThat(context.getSagaStatuses().get("a")).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(context.getSagaStatuses().get("c")).isEqualTo(ExecutionStatus.FAILED);
        assertThat(context.getCompletedAliasesInOrder()).containsExactly("a", "b");
    }

    @Test
    void compensationManager_compensatesInReverse() {
        CompositionCompensationManager manager =
                new CompositionCompensationManager(sagaEngine, CompensationPolicy.STRICT_SEQUENTIAL);

        SagaComposition composition = SagaCompositionBuilder.composition("comp-test")
                .saga("a").sagaName("SagaA").add()
                .saga("b").sagaName("SagaB").dependsOn("a").add()
                .saga("c").sagaName("SagaC").dependsOn("b").add()
                .build();

        CompositionContext context = new CompositionContext("corr-456");
        context.recordResult("a", successResult("SagaA"));
        context.recordResult("b", successResult("SagaB"));
        // c failed, so only a and b should be compensated

        StepVerifier.create(manager.compensateComposition(composition, context))
                .verifyComplete();

        // Verify the statuses were updated to COMPENSATING
        assertThat(context.getSagaStatuses().get("b")).isEqualTo(ExecutionStatus.COMPENSATING);
        assertThat(context.getSagaStatuses().get("a")).isEqualTo(ExecutionStatus.COMPENSATING);
    }

    @Test
    void dataFlowManager_resolvesInputsFromUpstream() {
        CompositionDataFlowManager dataFlowManager = new CompositionDataFlowManager();

        SagaComposition composition = SagaCompositionBuilder.composition("data-flow-test")
                .saga("producer").sagaName("ProducerSaga").add()
                .saga("consumer").sagaName("ConsumerSaga").dependsOn("producer").add()
                .dataFlow("producer", "orderId", "consumer", "inputOrderId")
                .build();

        CompositionContext context = new CompositionContext("corr-789");
        // Create a result where stepResults returns a map with orderId
        SagaResult producerResult = successResultWithStepData("ProducerSaga", "step1",
                Map.of("orderId", "ORD-999"));
        context.recordResult("producer", producerResult);

        SagaComposition.CompositionSaga consumer = composition.sagaMap().get("consumer");

        Map<String, Object> resolved = dataFlowManager.resolveInputs(
                composition, consumer, context, Map.of("globalKey", "globalValue"));

        assertThat(resolved).containsEntry("globalKey", "globalValue");
        assertThat(resolved).containsEntry("inputOrderId", "ORD-999");
    }

    @Test
    void templateRegistry_registersAndRetrieves() {
        CompositionTemplateRegistry registry = new CompositionTemplateRegistry();

        SagaComposition composition = SagaCompositionBuilder.composition("order-flow")
                .saga("payment").sagaName("PaymentSaga").add()
                .saga("shipping").sagaName("ShippingSaga").dependsOn("payment").add()
                .build();

        CompositionTemplateRegistry.CompositionTemplate template =
                new CompositionTemplateRegistry.CompositionTemplate(
                        "order-template", composition, "Standard order processing flow");

        registry.register(template);

        assertThat(registry.has("order-template")).isTrue();
        assertThat(registry.has("nonexistent")).isFalse();
        assertThat(registry.get("order-template")).isSameAs(template);
        assertThat(registry.get("order-template").description()).isEqualTo("Standard order processing flow");
        assertThat(registry.getAll()).hasSize(1);

        // Duplicate registration should throw
        assertThatThrownBy(() -> registry.register(template))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void validator_detectsCycles() {
        CompositionValidator validator = new CompositionValidator(null);

        // Build a composition with a cycle — we must bypass the builder's own validation
        SagaComposition.CompositionSaga a = new SagaComposition.CompositionSaga(
                "a", "SagaA", List.of("b"), Map.of(), false, "");
        SagaComposition.CompositionSaga b = new SagaComposition.CompositionSaga(
                "b", "SagaB", List.of("a"), Map.of(), false, "");
        SagaComposition composition = new SagaComposition("cyclic", List.of(a, b), List.of());

        List<ValidationIssue> issues = validator.validate(composition);

        assertThat(issues).isNotEmpty();
        assertThat(issues).anyMatch(i ->
                i.severity() == ValidationIssue.Severity.ERROR
                        && i.message().contains("Circular"));
    }

    @Test
    void validator_detectsMissingDependencies() {
        CompositionValidator validator = new CompositionValidator(null);

        // Build a composition with a missing dependency — bypass builder validation
        SagaComposition.CompositionSaga a = new SagaComposition.CompositionSaga(
                "a", "SagaA", List.of("nonexistent"), Map.of(), false, "");
        SagaComposition composition = new SagaComposition("missing-dep", List.of(a), List.of());

        List<ValidationIssue> issues = validator.validate(composition);

        assertThat(issues).isNotEmpty();
        assertThat(issues).anyMatch(i ->
                i.severity() == ValidationIssue.Severity.ERROR
                        && i.message().contains("unknown alias")
                        && i.message().contains("nonexistent"));
    }

    @Test
    void visualization_generatesDotGraph() {
        CompositionVisualizationService vizService = new CompositionVisualizationService();

        SagaComposition composition = SagaCompositionBuilder.composition("viz-test")
                .saga("payment").sagaName("PaymentSaga").add()
                .saga("inventory").sagaName("InventorySaga").add()
                .saga("shipping").sagaName("ShippingSaga").dependsOn("payment", "inventory").add()
                .dataFlow("payment", "orderId", "shipping", "paymentOrderId")
                .build();

        String dot = vizService.toDot(composition);

        assertThat(dot).contains("digraph");
        assertThat(dot).contains("viz-test");
        assertThat(dot).contains("payment");
        assertThat(dot).contains("inventory");
        assertThat(dot).contains("shipping");
        assertThat(dot).contains("PaymentSaga");
        assertThat(dot).contains("\"payment\" -> \"shipping\"");
        assertThat(dot).contains("\"inventory\" -> \"shipping\"");
        assertThat(dot).contains("orderId -> paymentOrderId");
    }

    // --- Helper methods ---

    private SagaResult successResult(String sagaName) {
        ExecutionContext ctx = ExecutionContext.forSaga(null, sagaName);
        return SagaResult.from(sagaName, ctx, Map.of(), Map.of(), List.of());
    }

    @SuppressWarnings("unchecked")
    private SagaResult successResultWithStepData(String sagaName, String stepId,
                                                  Map<String, Object> data) {
        ExecutionContext ctx = ExecutionContext.forSaga(null, sagaName);
        ctx.putResult(stepId, data);
        return SagaResult.from(sagaName, ctx, Map.of(), Map.of(), List.of(stepId));
    }

    private SagaResult failedResult(String sagaName, Throwable error) {
        return SagaResult.failed(sagaName, null, null, error, Map.of());
    }
}
