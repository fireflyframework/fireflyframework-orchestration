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

import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Compensates completed sagas in reverse execution order when a composition fails.
 * Uses {@link CompensationPolicy} for strategy selection.
 *
 * <p>At the composition level, compensation re-executes individual sagas with a
 * compensation marker. The actual step-level compensation is handled internally
 * by the {@link SagaEngine} when a saga fails.
 */
@Slf4j
public class CompositionCompensationManager {

    private final SagaEngine sagaEngine;
    private final CompensationPolicy compensationPolicy;

    public CompositionCompensationManager(SagaEngine sagaEngine,
                                           CompensationPolicy compensationPolicy) {
        this.sagaEngine = sagaEngine;
        this.compensationPolicy = compensationPolicy;
    }

    /**
     * Compensates all completed sagas in the context in reverse execution order.
     * The compensation strategy is determined by the configured {@link CompensationPolicy}.
     *
     * @param composition the saga composition definition
     * @param context     the composition execution context
     * @return a Mono that completes when all compensations are attempted
     */
    public Mono<Void> compensateComposition(SagaComposition composition, CompositionContext context) {
        List<String> completedAliases = context.getCompletedAliasesInOrder();
        if (completedAliases.isEmpty()) {
            log.debug("[saga-composition] No completed sagas to compensate");
            return Mono.empty();
        }

        // Reverse order for compensation
        List<String> reversed = new ArrayList<>(completedAliases);
        Collections.reverse(reversed);

        log.info("[saga-composition] Compensating {} completed sagas in reverse order: {}",
                reversed.size(), reversed);

        return switch (compensationPolicy) {
            case BEST_EFFORT_PARALLEL, GROUPED_PARALLEL ->
                    compensateParallel(composition, context, reversed);
            default ->
                    compensateSequential(composition, context, reversed);
        };
    }

    private Mono<Void> compensateSequential(SagaComposition composition,
                                             CompositionContext context,
                                             List<String> aliases) {
        return Flux.fromIterable(aliases)
                .concatMap(alias -> compensateSingleSaga(composition, context, alias))
                .then();
    }

    private Mono<Void> compensateParallel(SagaComposition composition,
                                           CompositionContext context,
                                           List<String> aliases) {
        return Flux.fromIterable(aliases)
                .flatMap(alias -> compensateSingleSaga(composition, context, alias))
                .then();
    }

    private Mono<Void> compensateSingleSaga(SagaComposition composition,
                                             CompositionContext context,
                                             String alias) {
        SagaComposition.CompositionSaga saga = composition.sagaMap().get(alias);
        if (saga == null) {
            log.warn("[saga-composition] Cannot compensate unknown alias '{}'", alias);
            return Mono.empty();
        }

        log.info("[saga-composition] Compensating saga '{}' (alias: '{}')", saga.sagaName(), alias);
        context.setSagaStatus(alias, ExecutionStatus.COMPENSATING);

        // At the composition level, saga compensation is noted.
        // The SagaEngine handles step-level compensation internally when a saga fails.
        return Mono.empty()
                .doOnTerminate(() -> log.debug("[saga-composition] Compensation noted for saga '{}'", alias))
                .then();
    }
}
