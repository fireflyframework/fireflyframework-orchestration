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

import org.fireflyframework.orchestration.core.dlq.DeadLetterService;
import org.fireflyframework.orchestration.core.health.OrchestrationHealthIndicator;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.rest.DeadLetterController;
import org.fireflyframework.orchestration.core.rest.OrchestrationController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for REST endpoints and health indicators.
 */
@Slf4j
@AutoConfiguration(after = OrchestrationAutoConfiguration.class)
public class OrchestrationRestAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnProperty(name = "firefly.orchestration.rest.enabled", havingValue = "true", matchIfMissing = true)
    public OrchestrationController orchestrationController(ExecutionPersistenceProvider persistence) {
        log.info("[orchestration] REST controller initialized");
        return new OrchestrationController(persistence);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnProperty(name = "firefly.orchestration.rest.enabled", havingValue = "true", matchIfMissing = true)
    public DeadLetterController deadLetterController(ObjectProvider<DeadLetterService> dlqService) {
        DeadLetterService service = dlqService.getIfAvailable();
        if (service == null) {
            log.info("[orchestration] DLQ controller skipped — DeadLetterService not available");
            return null;
        }
        log.info("[orchestration] DLQ REST controller initialized");
        return new DeadLetterController(service);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "firefly.orchestration.health.enabled", havingValue = "true", matchIfMissing = true)
    public OrchestrationHealthIndicator orchestrationHealthIndicator(ExecutionPersistenceProvider persistence) {
        log.info("[orchestration] Health indicator initialized");
        return new OrchestrationHealthIndicator(persistence);
    }
}
