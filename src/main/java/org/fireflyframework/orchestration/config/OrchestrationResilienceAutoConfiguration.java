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

package org.fireflyframework.orchestration.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.fireflyframework.orchestration.core.resilience.ResilienceDecorator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for resilience4j circuit breaker integration.
 *
 * <p>Activated when resilience4j is on the classpath and a {@code CircuitBreakerRegistry}
 * bean is available.
 */
@Slf4j
@AutoConfiguration(after = OrchestrationAutoConfiguration.class)
@ConditionalOnClass(CircuitBreakerRegistry.class)
@ConditionalOnBean(CircuitBreakerRegistry.class)
@ConditionalOnProperty(name = "firefly.orchestration.resilience.enabled", havingValue = "true", matchIfMissing = true)
public class OrchestrationResilienceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ResilienceDecorator resilienceDecorator(CircuitBreakerRegistry registry) {
        log.info("[orchestration] Resilience decorator initialized with circuit breaker support");
        return new ResilienceDecorator(registry);
    }
}
