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

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.dlq.DeadLetterService;
import org.fireflyframework.orchestration.core.dlq.DeadLetterStore;
import org.fireflyframework.orchestration.core.dlq.InMemoryDeadLetterStore;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.event.OrchestrationEventPublisher;
import org.fireflyframework.orchestration.core.observability.CompositeOrchestrationEvents;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.observability.OrchestrationLoggerEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.recovery.RecoveryService;
import org.fireflyframework.orchestration.core.scheduling.OrchestrationScheduler;
import org.fireflyframework.orchestration.core.scheduling.SchedulingPostProcessor;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * Main auto-configuration for the Orchestration framework.
 *
 * <p>Wires shared core beans used by all execution patterns (Workflow, Saga, TCC):
 * argument resolution, step invocation, observability, persistence, DLQ,
 * scheduling, and recovery.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(OrchestrationProperties.class)
public class OrchestrationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ArgumentResolver argumentResolver() {
        return new ArgumentResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public StepInvoker stepInvoker(ArgumentResolver argumentResolver) {
        return new StepInvoker(argumentResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public OrchestrationLoggerEvents orchestrationLoggerEvents() {
        return new OrchestrationLoggerEvents();
    }

    @Bean
    @ConditionalOnMissingBean(OrchestrationEvents.class)
    public OrchestrationEvents orchestrationEvents(ObjectProvider<OrchestrationLoggerEvents> loggerEvents) {
        List<OrchestrationEvents> delegates = new ArrayList<>();
        OrchestrationLoggerEvents logger = loggerEvents.getIfAvailable();
        if (logger != null) {
            delegates.add(logger);
        }
        if (delegates.size() == 1) {
            return delegates.get(0);
        }
        return new CompositeOrchestrationEvents(delegates);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExecutionPersistenceProvider executionPersistenceProvider() {
        log.info("[orchestration] Using in-memory persistence provider (default)");
        return new InMemoryPersistenceProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadLetterStore deadLetterStore() {
        return new InMemoryDeadLetterStore();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "firefly.orchestration.dlq.enabled", havingValue = "true", matchIfMissing = true)
    public DeadLetterService deadLetterService(DeadLetterStore store, OrchestrationEvents events) {
        log.info("[orchestration] Dead letter queue service initialized");
        return new DeadLetterService(store, events);
    }

    @Bean
    @ConditionalOnMissingBean(OrchestrationEventPublisher.class)
    public OrchestrationEventPublisher orchestrationEventPublisher() {
        log.info("[orchestration] Using no-op event publisher (default)");
        return new NoOpEventPublisher();
    }

    @Bean
    @ConditionalOnMissingBean
    public OrchestrationScheduler orchestrationScheduler(OrchestrationProperties properties) {
        int poolSize = properties.getScheduling().getThreadPoolSize();
        log.info("[orchestration] Scheduler initialized with thread pool size: {}", poolSize);
        return new OrchestrationScheduler(poolSize);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "firefly.orchestration.recovery.enabled", havingValue = "true", matchIfMissing = true)
    public RecoveryService recoveryService(ExecutionPersistenceProvider persistence,
                                           OrchestrationEvents events,
                                           OrchestrationProperties properties) {
        log.info("[orchestration] Recovery service initialized with stale threshold: {}",
                properties.getRecovery().getStaleThreshold());
        return new RecoveryService(persistence, events, properties.getRecovery().getStaleThreshold());
    }

    @Bean
    @ConditionalOnMissingBean
    public SchedulingPostProcessor schedulingPostProcessor(ApplicationContext applicationContext,
                                                           OrchestrationScheduler scheduler,
                                                           ObjectProvider<SagaEngine> sagaEngine,
                                                           ObjectProvider<TccEngine> tccEngine) {
        log.info("[orchestration] Scheduling post-processor initialized");
        return new SchedulingPostProcessor(applicationContext, scheduler,
                sagaEngine.getIfAvailable(), tccEngine.getIfAvailable());
    }
}
