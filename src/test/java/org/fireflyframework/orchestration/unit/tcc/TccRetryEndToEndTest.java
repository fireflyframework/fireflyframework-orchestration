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

package org.fireflyframework.orchestration.unit.tcc;

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.tcc.builder.TccBuilder;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccExecutionOrchestrator;
import org.fireflyframework.orchestration.tcc.engine.TccInputs;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TccRetryEndToEndTest {

    private TccEngine engine;

    @BeforeEach
    void setUp() {
        var events = new OrchestrationEvents() {};
        var stepInvoker = new StepInvoker(new ArgumentResolver());
        var noOpPublisher = new NoOpEventPublisher();
        var orchestrator = new TccExecutionOrchestrator(stepInvoker, events, noOpPublisher);
        engine = new TccEngine(null, events, orchestrator, null, null, noOpPublisher);
    }

    @Test
    void tryRetry_failsOnceThenSucceeds_confirmsTransaction() {
        var tryAttempts = new AtomicInteger(0);

        TccDefinition tcc = TccBuilder.tcc("RetrySuccessTcc")
                .participant("p1")
                    .tryRetry(2)
                    .tryBackoffMs(10)
                    .tryHandler((input, ctx) -> {
                        if (tryAttempts.incrementAndGet() == 1) {
                            return Mono.error(new RuntimeException("transient failure"));
                        }
                        return Mono.just("success-on-retry");
                    })
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isConfirmed()).isTrue();
                    assertThat(tryAttempts.get()).isEqualTo(2);
                })
                .verifyComplete();
    }

    @Test
    void tryTimeout_exceedsLimit_cancelsTransaction() {
        TccDefinition tcc = TccBuilder.tcc("TimeoutTcc")
                .participant("p1")
                    .tryTimeoutMs(100)
                    .tryRetry(0)
                    .tryHandler((input, ctx) ->
                            Mono.just("slow").delayElement(Duration.ofMillis(500)))
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> assertThat(result.isCanceled() || result.isFailed()).isTrue())
                .verifyComplete();
    }

    @Test
    void tccNoRetry_failureDoesNotRetry_immediateCancel() {
        var tryAttempts = new AtomicInteger(0);

        TccDefinition tcc = TccBuilder.tccNoRetry("NoRetryTcc")
                .participant("p1")
                    .tryHandler((input, ctx) -> {
                        tryAttempts.incrementAndGet();
                        return Mono.error(new RuntimeException("permanent failure"));
                    })
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isCanceled()).isTrue();
                    // With noRetry, only 1 attempt (the initial call, no retries)
                    assertThat(tryAttempts.get()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void perPhaseRetry_retriesExhausted_cancelsTransaction() {
        var tryAttempts = new AtomicInteger(0);

        TccDefinition tcc = TccBuilder.tcc("ExhaustedRetryTcc")
                .participant("p1")
                    .tryRetry(2)
                    .tryBackoffMs(10)
                    .tryHandler((input, ctx) -> {
                        tryAttempts.incrementAndGet();
                        return Mono.error(new RuntimeException("always fails"));
                    })
                    .confirmHandler((input, ctx) -> Mono.just("confirmed"))
                    .cancelHandler((input, ctx) -> Mono.just("cancelled"))
                    .add()
                .build();

        StepVerifier.create(engine.execute(tcc, TccInputs.empty()))
                .assertNext(result -> {
                    assertThat(result.isCanceled()).isTrue();
                    // 1 initial + 2 retries = 3 total attempts
                    assertThat(tryAttempts.get()).isEqualTo(3);
                })
                .verifyComplete();
    }
}
