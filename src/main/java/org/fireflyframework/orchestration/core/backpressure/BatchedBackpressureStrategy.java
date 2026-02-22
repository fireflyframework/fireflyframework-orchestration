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

package org.fireflyframework.orchestration.core.backpressure;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Backpressure strategy that processes items in fixed-size batches.
 * Each batch is processed sequentially while items within a batch
 * are processed concurrently.
 */
@Slf4j
public class BatchedBackpressureStrategy implements BackpressureStrategy {

    private final int batchSize;

    public BatchedBackpressureStrategy(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Batch size must be at least 1");
        }
        this.batchSize = batchSize;
    }

    public BatchedBackpressureStrategy() {
        this(10);
    }

    @Override
    public <T, R> Flux<R> applyBackpressure(List<T> items, ItemProcessor<T, R> processor) {
        log.debug("[backpressure] Processing {} items in batches of {}", items.size(), batchSize);
        return Flux.fromIterable(items)
                .buffer(batchSize)
                .concatMap(batch -> {
                    log.debug("[backpressure] Processing batch of {} items", batch.size());
                    return Flux.fromIterable(batch)
                            .flatMap(processor::process);
                });
    }

    public int getBatchSize() {
        return batchSize;
    }
}
