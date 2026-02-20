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

import org.fireflyframework.orchestration.saga.composition.SagaComposition;
import org.fireflyframework.orchestration.saga.composition.SagaCompositionBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SagaCompositionTest {

    @Test
    void builder_createsCompositionWithLayers() {
        SagaComposition composition = SagaCompositionBuilder.composition("order-flow")
                .saga("payment")
                    .sagaName("PaymentSaga")
                    .add()
                .saga("inventory")
                    .sagaName("InventorySaga")
                    .add()
                .saga("shipping")
                    .sagaName("ShippingSaga")
                    .dependsOn("payment", "inventory")
                    .add()
                .build();

        assertThat(composition.name()).isEqualTo("order-flow");
        assertThat(composition.sagas()).hasSize(3);

        var layers = composition.getExecutableLayers();
        assertThat(layers).hasSize(2);
        // First layer: payment and inventory (no deps, parallel)
        assertThat(layers.get(0).stream().map(SagaComposition.CompositionSaga::alias))
                .containsExactlyInAnyOrder("payment", "inventory");
        // Second layer: shipping (depends on both)
        assertThat(layers.get(1).stream().map(SagaComposition.CompositionSaga::alias))
                .containsExactly("shipping");
    }

    @Test
    void builder_dataFlowMappings() {
        SagaComposition composition = SagaCompositionBuilder.composition("data-flow")
                .saga("step-a").sagaName("SagaA").add()
                .saga("step-b").sagaName("SagaB").dependsOn("step-a").add()
                .dataFlow("step-a", "orderId", "step-b", "inputOrderId")
                .build();

        assertThat(composition.dataMappings()).hasSize(1);
        assertThat(composition.dataMappings().getFirst().sourceSaga()).isEqualTo("step-a");
        assertThat(composition.dataMappings().getFirst().targetField()).isEqualTo("inputOrderId");
    }

    @Test
    void builder_staticInput() {
        SagaComposition composition = SagaCompositionBuilder.composition("test")
                .saga("s1").sagaName("Saga1").input("key", "value").add()
                .build();

        assertThat(composition.sagaMap().get("s1").staticInput()).containsEntry("key", "value");
    }

    @Test
    void builder_rejectsDuplicateAlias() {
        assertThatThrownBy(() -> SagaCompositionBuilder.composition("test")
                .saga("dup").sagaName("Saga1").add()
                .saga("dup").sagaName("Saga2").add()
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate saga alias");
    }

    @Test
    void builder_rejectsUnknownDependency() {
        assertThatThrownBy(() -> SagaCompositionBuilder.composition("test")
                .saga("a").sagaName("A").dependsOn("nonexistent").add()
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown alias");
    }

    @Test
    void builder_rejectsCircularDependency() {
        assertThatThrownBy(() -> SagaCompositionBuilder.composition("test")
                .saga("a").sagaName("A").dependsOn("b").add()
                .saga("b").sagaName("B").dependsOn("a").add()
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Circular");
    }

    @Test
    void builder_rejectsEmptyComposition() {
        assertThatThrownBy(() -> SagaCompositionBuilder.composition("empty").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no sagas");
    }

    @Test
    void optionalSaga_flagged() {
        SagaComposition composition = SagaCompositionBuilder.composition("test")
                .saga("required").sagaName("R").add()
                .saga("opt").sagaName("O").optional(true).add()
                .build();

        assertThat(composition.sagaMap().get("opt").optional()).isTrue();
        assertThat(composition.sagaMap().get("required").optional()).isFalse();
    }
}
