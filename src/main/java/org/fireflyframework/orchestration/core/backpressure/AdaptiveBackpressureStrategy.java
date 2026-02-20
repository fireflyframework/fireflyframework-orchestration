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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptive backpressure strategy that dynamically adjusts concurrency and batch size
 * based on error rates and processing performance.
 */
@Slf4j
public class AdaptiveBackpressureStrategy implements BackpressureStrategy {

    private final int initialConcurrency;
    private final int maxConcurrency;
    private final int minConcurrency;
    private final double errorRateThreshold;
    private final AtomicInteger currentConcurrency;
    private final AtomicLong totalProcessed;
    private final AtomicLong totalErrors;

    public AdaptiveBackpressureStrategy(int initialConcurrency, int maxConcurrency,
                                          int minConcurrency, double errorRateThreshold) {
        this.initialConcurrency = initialConcurrency;
        this.maxConcurrency = maxConcurrency;
        this.minConcurrency = Math.max(1, minConcurrency);
        this.errorRateThreshold = errorRateThreshold;
        this.currentConcurrency = new AtomicInteger(initialConcurrency);
        this.totalProcessed = new AtomicLong(0);
        this.totalErrors = new AtomicLong(0);
    }

    public AdaptiveBackpressureStrategy() {
        this(4, 16, 1, 0.1);
    }

    @Override
    public <T, R> Flux<R> applyBackpressure(List<T> items, ItemProcessor<T, R> processor) {
        return Flux.fromIterable(items)
                .flatMap(item -> processor.process(item)
                                .doOnNext(r -> {
                                    totalProcessed.incrementAndGet();
                                    adjustConcurrency();
                                })
                                .onErrorResume(err -> {
                                    totalErrors.incrementAndGet();
                                    totalProcessed.incrementAndGet();
                                    adjustConcurrency();
                                    return Mono.empty();
                                }),
                        currentConcurrency.get());
    }

    private void adjustConcurrency() {
        long processed = totalProcessed.get();
        if (processed < 10) return; // Need enough samples

        double errorRate = (double) totalErrors.get() / processed;
        int current = currentConcurrency.get();

        if (errorRate > errorRateThreshold && current > minConcurrency) {
            int newVal = Math.max(minConcurrency, current / 2);
            if (currentConcurrency.compareAndSet(current, newVal)) {
                log.info("[backpressure] Reducing concurrency {} -> {} (error rate: {:.2f}%)",
                        current, newVal, errorRate * 100);
            }
        } else if (errorRate < errorRateThreshold / 2 && current < maxConcurrency) {
            int newVal = Math.min(maxConcurrency, current + 1);
            if (currentConcurrency.compareAndSet(current, newVal)) {
                log.debug("[backpressure] Increasing concurrency {} -> {}", current, newVal);
            }
        }
    }

    public int getCurrentConcurrency() {
        return currentConcurrency.get();
    }

    public double getErrorRate() {
        long processed = totalProcessed.get();
        return processed > 0 ? (double) totalErrors.get() / processed : 0.0;
    }

    public void reset() {
        currentConcurrency.set(initialConcurrency);
        totalProcessed.set(0);
        totalErrors.set(0);
    }
}
