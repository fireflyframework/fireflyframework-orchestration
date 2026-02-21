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
import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.saga.compensation.CompensationErrorHandler;
import org.fireflyframework.orchestration.saga.compensation.DefaultCompensationErrorHandler;
import org.fireflyframework.orchestration.saga.compensation.SagaCompensator;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.SagaExecutionOrchestrator;
import org.fireflyframework.orchestration.saga.registry.SagaRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Saga execution pattern.
 *
 * <p>Activated when {@code firefly.orchestration.saga.enabled=true} (default).
 */
@Slf4j
@AutoConfiguration(after = OrchestrationAutoConfiguration.class)
@ConditionalOnProperty(name = "firefly.orchestration.saga.enabled", havingValue = "true", matchIfMissing = true)
public class SagaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SagaRegistry sagaRegistry(ApplicationContext applicationContext) {
        log.info("[orchestration] Saga registry initialized");
        return new SagaRegistry(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public SagaExecutionOrchestrator sagaExecutionOrchestrator(StepInvoker stepInvoker,
                                                                OrchestrationEvents events,
                                                                OrchestrationEventPublisher eventPublisher,
                                                                ExecutionPersistenceProvider persistence) {
        return new SagaExecutionOrchestrator(stepInvoker, events, eventPublisher, persistence);
    }

    @Bean
    @ConditionalOnMissingBean(CompensationErrorHandler.class)
    public CompensationErrorHandler compensationErrorHandler() {
        return new DefaultCompensationErrorHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public SagaCompensator sagaCompensator(OrchestrationEvents events,
                                            OrchestrationProperties properties,
                                            StepInvoker stepInvoker,
                                            CompensationErrorHandler errorHandler) {
        CompensationPolicy policy = properties.getSaga().getCompensationPolicy();
        return new SagaCompensator(events, policy, stepInvoker, errorHandler);
    }

    @Bean
    @ConditionalOnMissingBean
    public SagaEngine sagaEngine(SagaRegistry registry,
                                  OrchestrationEvents events,
                                  OrchestrationProperties properties,
                                  SagaExecutionOrchestrator orchestrator,
                                  ExecutionPersistenceProvider persistence,
                                  ObjectProvider<DeadLetterService> dlqService,
                                  SagaCompensator compensator,
                                  OrchestrationEventPublisher eventPublisher) {
        log.info("[orchestration] Saga engine initialized with compensation policy: {}",
                properties.getSaga().getCompensationPolicy());
        return new SagaEngine(registry, events, orchestrator,
                persistence, dlqService.getIfAvailable(), compensator, eventPublisher);
    }
}
