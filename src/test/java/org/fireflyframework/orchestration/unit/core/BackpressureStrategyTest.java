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

package org.fireflyframework.orchestration.unit.core;

import org.fireflyframework.orchestration.core.backpressure.AdaptiveBackpressureStrategy;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class BackpressureStrategyTest {

    @Test
    void adaptive_processesAllItems() {
        var strategy = new AdaptiveBackpressureStrategy(2, 8, 1, 0.1);
        List<Integer> items = List.of(1, 2, 3, 4, 5);

        StepVerifier.create(strategy.applyBackpressure(items, item -> Mono.just(item * 2)))
                .expectNextCount(5)
                .verifyComplete();
    }

    @Test
    void adaptive_defaultConcurrency() {
        var strategy = new AdaptiveBackpressureStrategy();
        assertThat(strategy.getCurrentConcurrency()).isEqualTo(4);
    }

    @Test
    void adaptive_handlesErrors() {
        var strategy = new AdaptiveBackpressureStrategy(2, 8, 1, 0.5);
        List<Integer> items = List.of(1, 2, 3);

        StepVerifier.create(strategy.applyBackpressure(items, item -> {
            if (item == 2) return Mono.error(new RuntimeException("fail"));
            return Mono.just(item * 10);
        }))
                .expectNextCount(2) // items 1 and 3 succeed, item 2 errors (dropped)
                .verifyComplete();
    }

    @Test
    void adaptive_reset() {
        var strategy = new AdaptiveBackpressureStrategy(4, 16, 1, 0.1);
        // Process some items to change state
        strategy.applyBackpressure(List.of(1, 2, 3), Mono::just).collectList().block();

        strategy.reset();
        assertThat(strategy.getCurrentConcurrency()).isEqualTo(4);
        assertThat(strategy.getErrorRate()).isEqualTo(0.0);
    }
}
