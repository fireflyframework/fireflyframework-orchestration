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

import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Handles execution of individual sagas within a composition, replacing the inline
 * logic in {@link SagaCompositor}. Manages SpEL condition evaluation, optional saga
 * failure tolerance, and data flow resolution via {@link CompositionDataFlowManager}.
 */
@Slf4j
public class CompositionExecutionOrchestrator {

    private final SagaEngine sagaEngine;
    private final CompositionDataFlowManager dataFlowManager;

    public CompositionExecutionOrchestrator(SagaEngine sagaEngine,
                                             CompositionDataFlowManager dataFlowManager) {
        this.sagaEngine = sagaEngine;
        this.dataFlowManager = dataFlowManager;
    }

    /**
     * Executes composition layers sequentially. Within each layer, sagas run in parallel.
     *
     * @param composition the saga composition definition
     * @param layers      the pre-computed execution layers
     * @param globalInput the global input provided to the composition
     * @param context     the mutable composition context
     * @param layerIndex  the current layer index
     * @return Mono emitting true if all layers succeed, false otherwise
     */
    public Mono<Boolean> executeLayers(SagaComposition composition,
                                        List<List<SagaComposition.CompositionSaga>> layers,
                                        Map<String, Object> globalInput,
                                        CompositionContext context,
                                        int layerIndex) {
        if (layerIndex >= layers.size()) {
            return Mono.just(true);
        }

        List<SagaComposition.CompositionSaga> layer = layers.get(layerIndex);

        return Flux.fromIterable(layer)
                .flatMap(saga -> executeSaga(composition, saga, globalInput, context))
                .collectList()
                .flatMap(layerResults -> {
                    boolean layerSuccess = layerResults.stream()
                            .allMatch(r -> r.isSuccess() || isOptionalSaga(composition, r));

                    if (!layerSuccess) {
                        return Mono.just(false);
                    }

                    return executeLayers(composition, layers, globalInput, context, layerIndex + 1);
                });
    }

    /**
     * Executes a single saga within the composition, resolving its inputs from
     * data flow mappings and static input.
     */
    private Mono<SagaResult> executeSaga(SagaComposition composition,
                                          SagaComposition.CompositionSaga saga,
                                          Map<String, Object> globalInput,
                                          CompositionContext context) {
        // Check SpEL condition if present
        if (saga.condition() != null && !saga.condition().isBlank()) {
            if (!evaluateCondition(saga.condition(), context)) {
                log.info("[saga-composition] Skipping saga '{}' — condition not met: {}",
                        saga.alias(), saga.condition());
                context.setSagaStatus(saga.alias(), ExecutionStatus.CANCELLED);
                return Mono.just(SagaResult.failed(saga.sagaName(), null, null,
                        new RuntimeException("Condition not met"), Map.of()));
            }
        }

        context.setSagaStatus(saga.alias(), ExecutionStatus.RUNNING);

        Map<String, Object> input = dataFlowManager.resolveInputs(composition, saga, context, globalInput);

        log.info("[saga-composition] Executing saga '{}' (alias: '{}')", saga.sagaName(), saga.alias());
        return sagaEngine.execute(saga.sagaName(), input)
                .doOnNext(result -> context.recordResult(saga.alias(), result))
                .onErrorResume(err -> {
                    if (saga.optional()) {
                        log.warn("[saga-composition] Optional saga '{}' failed, continuing: {}",
                                saga.alias(), err.getMessage());
                        SagaResult failedResult = SagaResult.failed(saga.sagaName(), null,
                                null, err, Map.of());
                        context.recordResult(saga.alias(), failedResult);
                        return Mono.just(failedResult);
                    }
                    return Mono.error(err);
                });
    }

    /**
     * Evaluates a SpEL-like condition string. For now, supports simple boolean expressions
     * based on saga completion status. A full SpEL evaluator can be plugged in later.
     */
    private boolean evaluateCondition(String condition, CompositionContext context) {
        // Simple implementation: if condition references a saga alias like "aliasName.completed",
        // check if it's completed in the context
        if (condition.endsWith(".completed")) {
            String alias = condition.substring(0, condition.length() - ".completed".length());
            return context.isCompleted(alias);
        }
        // Default: condition is met
        return true;
    }

    private boolean isOptionalSaga(SagaComposition composition, SagaResult result) {
        return composition.sagas().stream()
                .filter(s -> s.sagaName().equals(result.sagaName()))
                .anyMatch(SagaComposition.CompositionSaga::optional);
    }
}
