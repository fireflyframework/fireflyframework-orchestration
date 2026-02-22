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

package org.fireflyframework.orchestration.saga.composition;

import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Orchestrates the execution of a saga composition: layer-by-layer execution with
 * parallel sagas, data flow between sagas, and cascading compensation on failure.
 *
 * <p>Delegates to {@link CompositionExecutionOrchestrator} for layer execution,
 * {@link CompositionDataFlowManager} for data flow resolution, and
 * {@link CompositionCompensationManager} for reverse-order compensation.
 * Uses {@link CompositionContext} to track execution state.
 */
@Slf4j
public class SagaCompositor {

    private final SagaEngine sagaEngine;
    private final OrchestrationEvents events;
    private final CompositionExecutionOrchestrator executionOrchestrator;
    private final CompositionCompensationManager compensationManager;

    public SagaCompositor(SagaEngine sagaEngine,
                           OrchestrationEvents events,
                           CompositionExecutionOrchestrator executionOrchestrator,
                           CompositionCompensationManager compensationManager) {
        this.sagaEngine = sagaEngine;
        this.events = events;
        this.executionOrchestrator = executionOrchestrator;
        this.compensationManager = compensationManager;
    }

    /**
     * Executes a saga composition layer by layer. Sagas in the same layer run in parallel.
     * Data flows between sagas according to defined mappings. If any non-optional saga fails,
     * all completed sagas are compensated in reverse order.
     */
    public Mono<CompositionResult> compose(SagaComposition composition, Map<String, Object> globalInput) {
        String correlationId = UUID.randomUUID().toString();
        CompositionContext context = new CompositionContext(correlationId);
        events.onCompositionStarted(composition.name(), correlationId);

        List<List<SagaComposition.CompositionSaga>> layers = composition.getExecutableLayers();

        return executionOrchestrator.executeLayers(composition, layers, globalInput, context, 0)
                .flatMap(success -> {
                    events.onCompositionCompleted(composition.name(), correlationId, success);
                    if (success) {
                        return Mono.just(CompositionResult.success(
                                composition.name(), correlationId,
                                Map.copyOf(context.getSagaResults())));
                    } else {
                        Throwable error = context.getSagaResults().values().stream()
                                .filter(r -> !r.isSuccess())
                                .findFirst()
                                .flatMap(SagaResult::error)
                                .orElse(new RuntimeException("Composition failed"));

                        return compensationManager.compensateComposition(composition, context)
                                .then(Mono.just(CompositionResult.failure(
                                        composition.name(), correlationId,
                                        Map.copyOf(context.getSagaResults()), error)));
                    }
                });
    }
}
