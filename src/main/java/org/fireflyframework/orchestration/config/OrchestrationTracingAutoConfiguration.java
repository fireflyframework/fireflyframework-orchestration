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

import io.micrometer.observation.ObservationRegistry;
import org.fireflyframework.orchestration.core.observability.OrchestrationTracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Micrometer Observation/tracing integration.
 *
 * <p>Activated when Micrometer Observation is on the classpath and an
 * {@code ObservationRegistry} bean is available.
 */
@Slf4j
@AutoConfiguration(after = OrchestrationAutoConfiguration.class)
@ConditionalOnClass(ObservationRegistry.class)
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(name = "firefly.orchestration.tracing.enabled", havingValue = "true", matchIfMissing = true)
public class OrchestrationTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OrchestrationTracer orchestrationTracer(ObservationRegistry observationRegistry) {
        log.info("[orchestration] Tracing initialized with ObservationRegistry");
        return new OrchestrationTracer(observationRegistry);
    }
}
