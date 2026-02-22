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

package org.fireflyframework.orchestration.tcc.composition;

import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Orchestrates the execution of a TCC composition: layer-by-layer execution with
 * parallel TCCs, data flow between TCCs, and cascading compensation on failure.
 *
 * <p>The compositor delegates individual TCC execution to {@link TccEngine}, which
 * handles Try-Confirm-Cancel phases internally. {@link TccEngine#execute(String, Map)}
 * always returns a {@link TccResult} (never errors) — even failures are represented
 * as {@link TccResult.Status#FAILED} or {@link TccResult.Status#CANCELED}.
 */
@Slf4j
public class TccCompositor {

    private final TccEngine tccEngine;
    private final OrchestrationEvents events;
    private final TccCompositionDataFlowManager dataFlowManager;
    private final TccCompositionCompensationManager compensationManager;

    public TccCompositor(TccEngine tccEngine,
                          OrchestrationEvents events,
                          TccCompositionDataFlowManager dataFlowManager,
                          TccCompositionCompensationManager compensationManager) {
        this.tccEngine = tccEngine;
        this.events = events;
        this.dataFlowManager = dataFlowManager;
        this.compensationManager = compensationManager;
    }

    /**
     * Executes a TCC composition layer by layer. TCCs in the same layer run in parallel.
     * Data flows between TCCs according to defined mappings. If any non-optional TCC fails,
     * all confirmed TCCs are compensated in reverse order.
     */
    public Mono<TccCompositionResult> compose(TccComposition composition, Map<String, Object> globalInput) {
        String correlationId = UUID.randomUUID().toString();
        TccCompositionContext context = new TccCompositionContext(correlationId);
        events.onCompositionStarted(composition.name(), correlationId);

        List<List<TccComposition.CompositionTcc>> layers = composition.getExecutableLayers();

        return executeLayersSequentially(composition, layers, globalInput, context, 0)
                .flatMap(success -> {
                    events.onCompositionCompleted(composition.name(), correlationId, success);
                    if (success) {
                        return Mono.just(TccCompositionResult.success(
                                composition.name(), correlationId, context.getTccResults()));
                    } else {
                        Throwable error = context.getTccResults().values().stream()
                                .filter(r -> !r.isConfirmed())
                                .findFirst()
                                .flatMap(TccResult::error)
                                .orElse(new RuntimeException("TCC composition failed"));

                        return compensationManager.compensate(composition, context)
                                .then(Mono.just(TccCompositionResult.failure(
                                        composition.name(), correlationId,
                                        context.getTccResults(), error)));
                    }
                });
    }

    private Mono<Boolean> executeLayersSequentially(
            TccComposition composition,
            List<List<TccComposition.CompositionTcc>> layers,
            Map<String, Object> globalInput,
            TccCompositionContext context,
            int layerIndex) {

        if (layerIndex >= layers.size()) {
            return Mono.just(true);
        }

        List<TccComposition.CompositionTcc> layer = layers.get(layerIndex);

        return Flux.fromIterable(layer)
                .flatMap(tcc -> executeTcc(composition, tcc, globalInput, context))
                .collectList()
                .flatMap(layerResults -> {
                    boolean layerSuccess = layerResults.stream()
                            .allMatch(r -> r.isConfirmed() || isOptionalTcc(composition, r));

                    if (!layerSuccess) {
                        return Mono.just(false);
                    }

                    return executeLayersSequentially(composition, layers, globalInput,
                            context, layerIndex + 1);
                });
    }

    private Mono<TccResult> executeTcc(TccComposition composition,
                                        TccComposition.CompositionTcc tcc,
                                        Map<String, Object> globalInput,
                                        TccCompositionContext context) {
        context.setTccStatus(tcc.alias(), TccCompositionContext.TccStatus.RUNNING);
        context.addToExecutionOrder(tcc.alias());

        Map<String, Object> input = dataFlowManager.resolveInputs(composition, tcc, globalInput, context);

        log.info("[tcc-composition] Executing TCC '{}' (alias: '{}')", tcc.tccName(), tcc.alias());
        return tccEngine.execute(tcc.tccName(), input)
                .doOnNext(result -> {
                    context.setTccResult(tcc.alias(), result);
                    if (result.correlationId() != null) {
                        context.setTccCorrelationId(tcc.alias(), result.correlationId());
                    }
                    if (result.isConfirmed()) {
                        context.setTccStatus(tcc.alias(), TccCompositionContext.TccStatus.CONFIRMED);
                    } else if (result.isCanceled()) {
                        context.setTccStatus(tcc.alias(), TccCompositionContext.TccStatus.CANCELED);
                    } else {
                        context.setTccStatus(tcc.alias(), TccCompositionContext.TccStatus.FAILED);
                    }
                });
    }

    private boolean isOptionalTcc(TccComposition composition, TccResult result) {
        return composition.tccs().stream()
                .filter(t -> t.tccName().equals(result.tccName()))
                .anyMatch(TccComposition.CompositionTcc::optional);
    }
}
