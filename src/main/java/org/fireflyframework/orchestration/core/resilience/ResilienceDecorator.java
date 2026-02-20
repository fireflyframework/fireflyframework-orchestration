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

package org.fireflyframework.orchestration.core.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import reactor.core.publisher.Mono;

public class ResilienceDecorator {
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ResilienceDecorator(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    public <T> Mono<T> decorateStep(String executionName, String stepId, Mono<T> mono) {
        String cbName = executionName + "." + stepId;
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(cbName);
        return mono.transformDeferred(CircuitBreakerOperator.of(cb));
    }

    public CircuitBreaker getCircuitBreaker(String executionName, String stepId) {
        return circuitBreakerRegistry.circuitBreaker(executionName + "." + stepId);
    }

    public void resetCircuitBreaker(String executionName, String stepId) {
        circuitBreakerRegistry.circuitBreaker(executionName + "." + stepId).reset();
    }
}
