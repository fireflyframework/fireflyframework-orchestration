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
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.workflow.child.ChildWorkflowService;
import org.fireflyframework.orchestration.workflow.continueasnew.ContinueAsNewService;
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import org.fireflyframework.orchestration.workflow.engine.WorkflowExecutor;
import org.fireflyframework.orchestration.workflow.query.WorkflowQueryService;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import org.fireflyframework.orchestration.workflow.rest.WorkflowController;
import org.fireflyframework.orchestration.workflow.search.SearchAttributeProjection;
import org.fireflyframework.orchestration.workflow.search.WorkflowSearchService;
import org.fireflyframework.orchestration.workflow.service.WorkflowService;
import org.fireflyframework.orchestration.workflow.signal.SignalService;
import org.fireflyframework.orchestration.workflow.timer.TimerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Workflow execution pattern.
 *
 * <p>Activated when {@code firefly.orchestration.workflow.enabled=true} (default).
 */
@Slf4j
@AutoConfiguration(after = OrchestrationAutoConfiguration.class)
@ConditionalOnProperty(name = "firefly.orchestration.workflow.enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WorkflowRegistry workflowRegistry(ApplicationContext applicationContext) {
        log.info("[orchestration] Workflow registry initialized");
        return new WorkflowRegistry(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowExecutor workflowExecutor(ArgumentResolver argumentResolver,
                                              OrchestrationEvents events) {
        return new WorkflowExecutor(argumentResolver, events);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowEngine workflowEngine(WorkflowRegistry registry,
                                          WorkflowExecutor executor,
                                          ExecutionPersistenceProvider persistence,
                                          OrchestrationEvents events) {
        log.info("[orchestration] Workflow engine initialized");
        return new WorkflowEngine(registry, executor, persistence, events);
    }

    @Bean
    @ConditionalOnMissingBean
    public SignalService signalService(ExecutionPersistenceProvider persistence,
                                        OrchestrationEvents events) {
        return new SignalService(persistence, events);
    }

    @Bean
    @ConditionalOnMissingBean
    public TimerService timerService(OrchestrationEvents events) {
        return new TimerService(events);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChildWorkflowService childWorkflowService(WorkflowEngine engine,
                                                       OrchestrationEvents events) {
        return new ChildWorkflowService(engine, events);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowQueryService workflowQueryService(ExecutionPersistenceProvider persistence) {
        return new WorkflowQueryService(persistence);
    }

    @Bean
    @ConditionalOnMissingBean
    public SearchAttributeProjection searchAttributeProjection() {
        return new SearchAttributeProjection();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowSearchService workflowSearchService(SearchAttributeProjection projection,
                                                         ExecutionPersistenceProvider persistence) {
        return new WorkflowSearchService(projection, persistence);
    }

    @Bean
    @ConditionalOnMissingBean
    public ContinueAsNewService continueAsNewService(WorkflowEngine engine,
                                                       ExecutionPersistenceProvider persistence,
                                                       OrchestrationEvents events,
                                                       SignalService signalService,
                                                       TimerService timerService) {
        return new ContinueAsNewService(engine, persistence, events, signalService, timerService);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowService workflowService(WorkflowEngine engine, SignalService signalService,
                                             TimerService timerService,
                                             ChildWorkflowService childWorkflowService,
                                             ContinueAsNewService continueAsNewService,
                                             WorkflowQueryService queryService,
                                             WorkflowSearchService searchService) {
        log.info("[orchestration] Workflow service facade initialized");
        return new WorkflowService(engine, signalService, timerService, childWorkflowService,
                continueAsNewService, queryService, searchService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    public WorkflowController workflowController(WorkflowEngine engine, WorkflowRegistry registry) {
        log.info("[orchestration] Workflow REST controller initialized");
        return new WorkflowController(engine, registry);
    }
}
