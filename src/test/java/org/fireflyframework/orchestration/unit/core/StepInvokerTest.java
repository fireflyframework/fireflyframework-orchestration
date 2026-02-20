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

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.argument.Input;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.step.StepHandler;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class StepInvokerTest {

    private final ArgumentResolver argumentResolver = new ArgumentResolver();
    private final StepInvoker invoker = new StepInvoker(argumentResolver);

    @SuppressWarnings("unused")
    static class TestBean {
        public Mono<String> successStep(@Input String input) {
            return Mono.just("processed:" + input);
        }

        public Mono<String> failStep(@Input String input) {
            return Mono.error(new RuntimeException("step failed"));
        }

        public String syncStep(@Input String input) {
            return "sync:" + input;
        }
    }

    @Test
    void successfulInvocation_returnsResult() throws Exception {
        var bean = new TestBean();
        var method = TestBean.class.getMethod("successStep", String.class);
        var ctx = ExecutionContext.forSaga(null, "test");

        StepVerifier.create(invoker.attemptCall(bean, method, "hello", ctx, 0, 0, 0, false, 0, "step1", false))
                .expectNext("processed:hello")
                .verifyComplete();
    }

    @Test
    void syncMethod_wrappedInMono() throws Exception {
        var bean = new TestBean();
        var method = TestBean.class.getMethod("syncStep", String.class);
        var ctx = ExecutionContext.forSaga(null, "test");

        StepVerifier.create(invoker.attemptCall(bean, method, "hello", ctx, 0, 0, 0, false, 0, "step1", false))
                .expectNext("sync:hello")
                .verifyComplete();
    }

    @Test
    void retryExhaustion_propagatesError() throws Exception {
        var bean = new TestBean();
        var method = TestBean.class.getMethod("failStep", String.class);
        var ctx = ExecutionContext.forSaga(null, "test");

        StepVerifier.create(invoker.attemptCall(bean, method, "hello", ctx, 0, 2, 10, false, 0, "step1", false))
                .expectErrorMessage("step failed")
                .verify(Duration.ofSeconds(5));
        // 1 initial + 2 retries = 3 attempts
        assertThat(ctx.getAttempts("step1")).isEqualTo(3);
    }

    @Test
    void handlerBasedInvocation_works() {
        StepHandler<String, String> handler = (input, ctx) -> Mono.just("handler:" + input);
        var ctx = ExecutionContext.forSaga(null, "test");

        StepVerifier.create(invoker.attemptCallHandler(handler, "hello", ctx, 0, 0, 0, false, 0, "step1"))
                .expectNext("handler:hello")
                .verifyComplete();
    }

    @Test
    void handlerRetry_works() {
        var attempts = new AtomicInteger(0);
        StepHandler<String, String> handler = (input, ctx) -> {
            if (attempts.incrementAndGet() < 3) {
                return Mono.error(new RuntimeException("not yet"));
            }
            return Mono.just("success");
        };
        var ctx = ExecutionContext.forSaga(null, "test");

        StepVerifier.create(invoker.attemptCallHandler(handler, "hello", ctx, 0, 5, 10, false, 0, "step1"))
                .expectNext("success")
                .verifyComplete();
    }

    @Test
    void computeDelay_noJitter_returnsBackoff() {
        assertThat(StepInvoker.computeDelay(100, false, 0)).isEqualTo(100);
    }

    @Test
    void computeDelay_withJitter_withinBounds() {
        for (int i = 0; i < 100; i++) {
            long delay = StepInvoker.computeDelay(1000, true, 0.5);
            assertThat(delay).isBetween(500L, 1500L);
        }
    }

    @Test
    void computeDelay_zeroBackoff_returnsZero() {
        assertThat(StepInvoker.computeDelay(0, true, 0.5)).isEqualTo(0);
    }
}
