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
import org.fireflyframework.orchestration.core.event.OrchestrationEventPublisher;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccExecutionOrchestrator;
import org.fireflyframework.orchestration.tcc.registry.TccRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the TCC (Try-Confirm-Cancel) execution pattern.
 *
 * <p>Activated when {@code firefly.orchestration.tcc.enabled=true} (default).
 */
@Slf4j
@AutoConfiguration(after = OrchestrationAutoConfiguration.class)
@ConditionalOnProperty(name = "firefly.orchestration.tcc.enabled", havingValue = "true", matchIfMissing = true)
public class TccAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TccRegistry tccRegistry(ApplicationContext applicationContext) {
        log.info("[orchestration] TCC registry initialized");
        return new TccRegistry(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public TccExecutionOrchestrator tccExecutionOrchestrator(StepInvoker stepInvoker,
                                                              OrchestrationEvents events,
                                                              OrchestrationEventPublisher eventPublisher) {
        return new TccExecutionOrchestrator(stepInvoker, events, eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public TccEngine tccEngine(TccRegistry registry,
                                OrchestrationEvents events,
                                TccExecutionOrchestrator orchestrator,
                                ExecutionPersistenceProvider persistence,
                                ObjectProvider<DeadLetterService> dlqService,
                                OrchestrationEventPublisher eventPublisher) {
        log.info("[orchestration] TCC engine initialized");
        return new TccEngine(registry, events, orchestrator,
                persistence, dlqService.getIfAvailable(), eventPublisher);
    }
}
