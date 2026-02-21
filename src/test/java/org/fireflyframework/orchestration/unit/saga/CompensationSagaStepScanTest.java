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

import org.fireflyframework.orchestration.saga.annotation.CompensationSagaStep;
import org.fireflyframework.orchestration.saga.annotation.Saga;
import org.fireflyframework.orchestration.saga.annotation.SagaStep;
import org.fireflyframework.orchestration.saga.registry.SagaRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompensationSagaStepScanTest {

    @Test
    void externalCompensation_wiredToCorrectStep() {
        var appCtx = mock(ApplicationContext.class);
        var sagaBean = new TestSaga();
        var compBean = new ExternalCompensationBean();

        when(appCtx.getBeansWithAnnotation(Saga.class))
                .thenReturn(Map.of("testSaga", sagaBean));
        when(appCtx.getBeansOfType(Object.class))
                .thenReturn(Map.of("testSaga", sagaBean, "compBean", compBean));

        var registry = new SagaRegistry(appCtx);
        var sagaDef = registry.getSaga("CompScanSaga");
        var stepDef = sagaDef.steps.get("processOrder");

        assertThat(stepDef.compensateMethod).isNotNull();
        assertThat(stepDef.compensateMethod.getName()).isEqualTo("undoProcessOrder");
        assertThat(stepDef.compensateBean).isSameAs(compBean);
    }

    @Test
    void externalCompensation_unknownSaga_throws() {
        var appCtx = mock(ApplicationContext.class);
        var sagaBean = new TestSaga();
        var badCompBean = new BadSagaRefCompensationBean();

        when(appCtx.getBeansWithAnnotation(Saga.class))
                .thenReturn(Map.of("testSaga", sagaBean));
        when(appCtx.getBeansOfType(Object.class))
                .thenReturn(Map.of("testSaga", sagaBean, "badComp", badCompBean));

        var registry = new SagaRegistry(appCtx);
        assertThatThrownBy(() -> registry.getSaga("CompScanSaga"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown saga")
                .hasMessageContaining("NonExistentSaga");
    }

    @Test
    void externalCompensation_unknownStep_throws() {
        var appCtx = mock(ApplicationContext.class);
        var sagaBean = new TestSaga();
        var badStepComp = new BadStepRefCompensationBean();

        when(appCtx.getBeansWithAnnotation(Saga.class))
                .thenReturn(Map.of("testSaga", sagaBean));
        when(appCtx.getBeansOfType(Object.class))
                .thenReturn(Map.of("testSaga", sagaBean, "badStepComp", badStepComp));

        var registry = new SagaRegistry(appCtx);
        assertThatThrownBy(() -> registry.getSaga("CompScanSaga"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown step")
                .hasMessageContaining("nonExistentStep");
    }

    // ── Test beans ────────────────────────────────────────────

    @Saga(name = "CompScanSaga")
    public static class TestSaga {
        @SagaStep(id = "processOrder")
        public Mono<String> processOrder(Object input) {
            return Mono.just("processed");
        }
    }

    public static class ExternalCompensationBean {
        @CompensationSagaStep(saga = "CompScanSaga", forStepId = "processOrder")
        public Mono<Void> undoProcessOrder(Object result) {
            return Mono.empty();
        }
    }

    public static class BadSagaRefCompensationBean {
        @CompensationSagaStep(saga = "NonExistentSaga", forStepId = "processOrder")
        public Mono<Void> undoIt(Object result) {
            return Mono.empty();
        }
    }

    public static class BadStepRefCompensationBean {
        @CompensationSagaStep(saga = "CompScanSaga", forStepId = "nonExistentStep")
        public Mono<Void> undoIt(Object result) {
            return Mono.empty();
        }
    }
}
