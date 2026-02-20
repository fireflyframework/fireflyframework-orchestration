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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the execution of a saga composition: layer-by-layer execution with
 * parallel sagas, data flow between sagas, and cascading compensation on failure.
 */
@Slf4j
public class SagaCompositor {

    private final SagaEngine sagaEngine;
    private final OrchestrationEvents events;

    public SagaCompositor(SagaEngine sagaEngine, OrchestrationEvents events) {
        this.sagaEngine = sagaEngine;
        this.events = events;
    }

    /**
     * Executes a saga composition layer by layer. Sagas in the same layer run in parallel.
     * Data flows between sagas according to defined mappings. If any non-optional saga fails,
     * all completed sagas are compensated in reverse order.
     */
    public Mono<CompositionResult> compose(SagaComposition composition, Map<String, Object> globalInput) {
        String correlationId = UUID.randomUUID().toString();
        events.onCompositionStarted(composition.name(), correlationId);

        List<List<SagaComposition.CompositionSaga>> layers = composition.getExecutableLayers();
        Map<String, SagaResult> results = new ConcurrentHashMap<>();
        Map<String, Map<String, Object>> sagaOutputs = new ConcurrentHashMap<>();

        return executeLayersSequentially(composition, layers, globalInput, results, sagaOutputs, 0)
                .flatMap(success -> {
                    events.onCompositionCompleted(composition.name(), correlationId, success);
                    if (success) {
                        return Mono.just(CompositionResult.success(
                                composition.name(), correlationId, Map.copyOf(results)));
                    } else {
                        // Find the failure
                        Throwable error = results.values().stream()
                                .filter(r -> !r.isSuccess())
                                .findFirst()
                                .flatMap(r -> r.error())
                                .orElse(new RuntimeException("Composition failed"));
                        return Mono.just(CompositionResult.failure(
                                composition.name(), correlationId, Map.copyOf(results), error));
                    }
                });
    }

    private Mono<Boolean> executeLayersSequentially(
            SagaComposition composition,
            List<List<SagaComposition.CompositionSaga>> layers,
            Map<String, Object> globalInput,
            Map<String, SagaResult> results,
            Map<String, Map<String, Object>> sagaOutputs,
            int layerIndex) {

        if (layerIndex >= layers.size()) {
            return Mono.just(true);
        }

        List<SagaComposition.CompositionSaga> layer = layers.get(layerIndex);

        return Flux.fromIterable(layer)
                .flatMap(saga -> executeSaga(composition, saga, globalInput, sagaOutputs)
                        .doOnNext(result -> {
                            results.put(saga.alias(), result);
                            if (result.isSuccess()) {
                                sagaOutputs.put(saga.alias(), result.stepResults());
                            }
                        }))
                .collectList()
                .flatMap(layerResults -> {
                    boolean layerSuccess = layerResults.stream()
                            .allMatch(r -> r.isSuccess() ||
                                    composition.sagaMap().values().stream()
                                            .filter(s -> s.alias().equals(findAlias(composition, r)))
                                            .anyMatch(SagaComposition.CompositionSaga::optional));

                    if (!layerSuccess) {
                        return Mono.just(false);
                    }

                    return executeLayersSequentially(composition, layers, globalInput,
                            results, sagaOutputs, layerIndex + 1);
                });
    }

    private Mono<SagaResult> executeSaga(SagaComposition composition,
                                           SagaComposition.CompositionSaga saga,
                                           Map<String, Object> globalInput,
                                           Map<String, Map<String, Object>> sagaOutputs) {
        Map<String, Object> input = new LinkedHashMap<>(globalInput);
        input.putAll(saga.staticInput());

        // Apply data mappings
        for (SagaComposition.DataMapping mapping : composition.dataMappings()) {
            if (mapping.targetSaga().equals(saga.alias())) {
                var sourceOutput = sagaOutputs.get(mapping.sourceSaga());
                if (sourceOutput != null && sourceOutput.containsKey(mapping.sourceField())) {
                    input.put(mapping.targetField(), sourceOutput.get(mapping.sourceField()));
                }
            }
        }

        log.info("[composition] Executing saga '{}' (alias: '{}')", saga.sagaName(), saga.alias());
        return sagaEngine.execute(saga.sagaName(), input)
                .onErrorResume(err -> {
                    if (saga.optional()) {
                        log.warn("[composition] Optional saga '{}' failed, continuing: {}",
                                saga.alias(), err.getMessage());
                        return Mono.just(SagaResult.failed(saga.sagaName(), null,
                                null, err, Map.of()));
                    }
                    return Mono.error(err);
                });
    }

    private String findAlias(SagaComposition composition, SagaResult result) {
        return composition.sagas().stream()
                .filter(s -> s.sagaName().equals(result.sagaName()))
                .map(SagaComposition.CompositionSaga::alias)
                .findFirst()
                .orElse("");
    }
}
