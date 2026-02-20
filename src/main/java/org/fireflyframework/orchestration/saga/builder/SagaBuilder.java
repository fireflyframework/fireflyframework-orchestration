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

package org.fireflyframework.orchestration.saga.builder;

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.step.StepHandler;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.saga.registry.SagaStepDefinition;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Fluent builder for constructing saga definitions programmatically.
 *
 * <p>Usage:
 * <pre>{@code
 * SagaDefinition def = SagaBuilder.saga("OrderSaga")
 *     .step("reserve").handler(reserveHandler).add()
 *     .step("charge").dependsOn("reserve").handler(chargeHandler)
 *         .compensation((result, ctx) -> refund(result)).add()
 *     .build();
 * }</pre>
 */
public class SagaBuilder {

    private final SagaDefinition saga;

    private SagaBuilder(String name, int layerConcurrency) {
        this.saga = new SagaDefinition(name, null, null, layerConcurrency);
    }

    public static SagaBuilder saga(String name) {
        return new SagaBuilder(name, 0);
    }

    public static SagaBuilder named(String name) {
        return saga(name);
    }

    /**
     * Creates a saga builder with a specific layer concurrency limit.
     * When {@code layerConcurrency > 0}, at most that many steps within a single
     * layer will execute concurrently. Zero or negative means unbounded.
     */
    public static SagaBuilder saga(String name, int layerConcurrency) {
        return new SagaBuilder(name, layerConcurrency);
    }

    public Step step(String id) {
        return new Step(id);
    }

    public SagaDefinition build() {
        return saga;
    }

    public class Step {
        private final String id;
        private String compensateName = "";
        private final List<String> dependsOn = new ArrayList<>();
        private int retry = 0;
        private Duration backoff = null;
        private Duration timeout = null;
        private String idempotencyKey = "";
        private boolean jitter = false;
        private double jitterFactor = 0.5d;
        private StepHandler<?, ?> handler;
        private BiFunction<Object, ExecutionContext, Mono<Void>> compensationFn;
        private Integer compensationRetry;
        private Duration compensationBackoff;
        private Duration compensationTimeout;
        private boolean compensationCritical = false;

        private Step(String id) {
            this.id = id;
        }

        public Step dependsOn(String... ids) {
            if (ids != null && ids.length > 0) this.dependsOn.addAll(Arrays.asList(ids));
            return this;
        }

        public Step retry(int retry) { this.retry = retry; return this; }
        public Step backoff(Duration backoff) { this.backoff = backoff; return this; }
        public Step backoffMs(long ms) { this.backoff = ms >= 0 ? Duration.ofMillis(ms) : null; return this; }
        public Step timeout(Duration timeout) { this.timeout = timeout; return this; }
        public Step timeoutMs(long ms) { this.timeout = ms >= 0 ? Duration.ofMillis(ms) : null; return this; }
        public Step idempotencyKey(String key) { this.idempotencyKey = key != null ? key : ""; return this; }
        public Step jitter() { this.jitter = true; return this; }
        public Step jitter(boolean enabled) { this.jitter = enabled; return this; }
        public Step jitterFactor(double factor) { this.jitterFactor = factor; return this; }

        public Step compensationRetry(int retry) { this.compensationRetry = retry; return this; }
        public Step compensationBackoff(Duration d) { this.compensationBackoff = d; return this; }
        public Step compensationTimeout(Duration d) { this.compensationTimeout = d; return this; }
        public Step compensationCritical(boolean critical) { this.compensationCritical = critical; return this; }

        public Step handler(StepHandler<?, ?> handler) { this.handler = handler; return this; }

        public <I, O> Step handler(BiFunction<I, ExecutionContext, Mono<O>> fn) {
            this.handler = (StepHandler<I, O>) (input, ctx) -> fn.apply(input, ctx);
            return this;
        }

        public <O> Step handlerCtx(Function<ExecutionContext, Mono<O>> fn) {
            this.handler = (StepHandler<Void, O>) (input, ctx) -> fn.apply(ctx);
            return this;
        }

        public <I, O> Step handlerInput(Function<I, Mono<O>> fn) {
            this.handler = (StepHandler<I, O>) (input, ctx) -> fn.apply(input);
            return this;
        }

        public <O> Step handler(Supplier<Mono<O>> fn) {
            this.handler = (StepHandler<Void, O>) (input, ctx) -> fn.get();
            return this;
        }

        public Step compensation(BiFunction<Object, ExecutionContext, Mono<Void>> fn) {
            this.compensationFn = fn;
            return this;
        }

        public Step compensationCtx(Function<ExecutionContext, Mono<Void>> fn) {
            this.compensationFn = (arg, ctx) -> fn.apply(ctx);
            return this;
        }

        public Step compensation(Supplier<Mono<Void>> fn) {
            this.compensationFn = (arg, ctx) -> fn.get();
            return this;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        public SagaBuilder add() {
            if (this.handler == null) {
                throw new IllegalStateException("Missing handler for step '" + id + "' in saga '" + saga.name + "'");
            }
            SagaStepDefinition sd = new SagaStepDefinition(
                    id, compensateName, dependsOn, retry, backoff, timeout,
                    idempotencyKey, jitter, jitterFactor, false, null);

            if (this.handler != null && this.compensationFn != null) {
                StepHandler base = this.handler;
                final BiFunction<Object, ExecutionContext, Mono<Void>> compFn = this.compensationFn;
                this.handler = new StepHandler<Object, Object>() {
                    @Override public Mono<Object> execute(Object input, ExecutionContext ctx) {
                        return ((StepHandler<Object, Object>) base).execute(input, ctx);
                    }
                    @Override public Mono<Void> compensate(Object result, ExecutionContext ctx) {
                        return compFn.apply(result, ctx);
                    }
                };
            }
            sd.handler = this.handler;
            if (this.compensationRetry != null) sd.compensationRetry = this.compensationRetry;
            if (this.compensationBackoff != null) sd.compensationBackoff = this.compensationBackoff;
            if (this.compensationTimeout != null) sd.compensationTimeout = this.compensationTimeout;
            sd.compensationCritical = this.compensationCritical;

            if (saga.steps.putIfAbsent(id, sd) != null) {
                throw new IllegalStateException("Duplicate step id '" + id + "' in saga '" + saga.name + "'");
            }
            return SagaBuilder.this;
        }
    }
}
