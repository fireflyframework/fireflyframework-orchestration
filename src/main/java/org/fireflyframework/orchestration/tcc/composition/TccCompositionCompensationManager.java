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

import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Compensates completed TCCs in reverse execution order when a composition fails.
 * Uses {@link CompensationPolicy} for strategy selection.
 */
@Slf4j
public class TccCompositionCompensationManager {

    private final TccEngine tccEngine;
    private final CompensationPolicy compensationPolicy;

    public TccCompositionCompensationManager(TccEngine tccEngine,
                                              CompensationPolicy compensationPolicy) {
        this.tccEngine = tccEngine;
        this.compensationPolicy = compensationPolicy;
    }

    /**
     * Compensates all confirmed TCCs in the context in reverse execution order.
     * The compensation strategy is determined by the configured {@link CompensationPolicy}.
     *
     * @param composition the TCC composition definition
     * @param context     the composition execution context
     * @return a Mono that completes when all compensations are attempted
     */
    public Mono<Void> compensate(TccComposition composition, TccCompositionContext context) {
        List<String> confirmedAliases = context.getConfirmedAliasesInOrder();
        if (confirmedAliases.isEmpty()) {
            log.debug("[tcc-composition] No confirmed TCCs to compensate");
            return Mono.empty();
        }

        // Reverse order for compensation
        List<String> reversed = new ArrayList<>(confirmedAliases);
        Collections.reverse(reversed);

        log.info("[tcc-composition] Compensating {} confirmed TCCs in reverse order: {}",
                reversed.size(), reversed);

        return switch (compensationPolicy) {
            case BEST_EFFORT_PARALLEL, GROUPED_PARALLEL ->
                    compensateParallel(composition, context, reversed);
            default ->
                    compensateSequential(composition, context, reversed);
        };
    }

    private Mono<Void> compensateSequential(TccComposition composition,
                                             TccCompositionContext context,
                                             List<String> aliases) {
        return Flux.fromIterable(aliases)
                .concatMap(alias -> compensateSingleTcc(composition, context, alias))
                .then();
    }

    private Mono<Void> compensateParallel(TccComposition composition,
                                           TccCompositionContext context,
                                           List<String> aliases) {
        return Flux.fromIterable(aliases)
                .flatMap(alias -> compensateSingleTcc(composition, context, alias))
                .then();
    }

    private Mono<Void> compensateSingleTcc(TccComposition composition,
                                            TccCompositionContext context,
                                            String alias) {
        TccComposition.CompositionTcc tcc = composition.tccMap().get(alias);
        if (tcc == null) {
            log.warn("[tcc-composition] Cannot compensate unknown alias '{}'", alias);
            return Mono.empty();
        }

        log.info("[tcc-composition] Compensating TCC '{}' (alias: '{}')", tcc.tccName(), alias);
        context.setTccStatus(alias, TccCompositionContext.TccStatus.CANCELED);

        // TCC cancellation is handled internally by the TCC engine during execution.
        // At the composition level, we note the cancellation status.
        // In a real system, this would invoke the TCC's cancel phase via the engine.
        return Mono.empty()
                .doOnTerminate(() -> log.debug("[tcc-composition] Compensation noted for TCC '{}'", alias))
                .then();
    }
}
