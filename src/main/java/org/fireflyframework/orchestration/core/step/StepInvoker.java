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

package org.fireflyframework.orchestration.core.step;

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public final class StepInvoker {

    private final ArgumentResolver argumentResolver;

    public StepInvoker(ArgumentResolver argumentResolver) {
        this.argumentResolver = argumentResolver;
    }

    /**
     * Invoke a method-based step on a bean with retry, backoff, jitter, and timeout.
     */
    public Mono<Object> attemptCall(Object bean, Method method, Object input, ExecutionContext ctx,
                                     long timeoutMs, int retries, long backoffMs,
                                     boolean jitter, double jitterFactor,
                                     String stepId, boolean cpuBound) {
        Mono<Object> base = Mono.defer(() -> invokeMono(bean, method, input, ctx));
        if (cpuBound) {
            base = base.subscribeOn(Schedulers.parallel());
        }
        if (timeoutMs > 0) {
            base = base.timeout(Duration.ofMillis(timeoutMs));
        }
        Mono<Object> finalBase = base;
        return finalBase
                .onErrorResume(err -> {
                    if (retries > 0) {
                        long delay = computeDelay(backoffMs, jitter, jitterFactor);
                        return Mono.delay(Duration.ofMillis(Math.max(0, delay)))
                                .then(attemptCall(bean, method, input, ctx, timeoutMs, retries - 1,
                                        backoffMs, jitter, jitterFactor, stepId, cpuBound));
                    }
                    return Mono.error(err);
                })
                .doFirst(() -> ctx.incrementAttempts(stepId));
    }

    /**
     * Invoke a StepHandler-based step with retry, backoff, jitter, and timeout.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Mono<Object> attemptCallHandler(StepHandler handler, Object input, ExecutionContext ctx,
                                            long timeoutMs, int retries, long backoffMs,
                                            boolean jitter, double jitterFactor, String stepId) {
        Mono<Object> base = Mono.defer(() -> handler.execute(input, ctx).cast(Object.class));
        if (timeoutMs > 0) {
            base = base.timeout(Duration.ofMillis(timeoutMs));
        }
        Mono<Object> finalBase = base;
        return finalBase
                .onErrorResume(err -> {
                    if (retries > 0) {
                        long delay = computeDelay(backoffMs, jitter, jitterFactor);
                        return Mono.delay(Duration.ofMillis(Math.max(0, delay)))
                                .then(attemptCallHandler(handler, input, ctx, timeoutMs, retries - 1,
                                        backoffMs, jitter, jitterFactor, stepId));
                    }
                    return Mono.error(err);
                })
                .doFirst(() -> ctx.incrementAttempts(stepId));
    }

    @SuppressWarnings("unchecked")
    private Mono<Object> invokeMono(Object bean, Method method, Object input, ExecutionContext ctx) {
        try {
            if (!method.canAccess(bean)) {
                method.setAccessible(true);
            }
            Object[] args = argumentResolver.resolveArguments(method, input, ctx);
            Object result = method.invoke(bean, args);
            if (result instanceof Mono<?> mono) {
                return (Mono<Object>) mono;
            }
            return Mono.justOrEmpty(result);
        } catch (InvocationTargetException e) {
            return Mono.error(e.getTargetException());
        } catch (Throwable t) {
            return Mono.error(t);
        }
    }

    public static long computeDelay(long backoffMs, boolean jitter, double jitterFactor) {
        if (backoffMs <= 0) return 0L;
        if (!jitter) return backoffMs;
        double f = Math.max(0.0, Math.min(jitterFactor, 1.0));
        double min = backoffMs * (1.0 - f);
        double max = backoffMs * (1.0 + f);
        long v = Math.round(ThreadLocalRandom.current().nextDouble(min, max));
        return Math.max(0L, v);
    }
}
