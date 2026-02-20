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
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import org.fireflyframework.orchestration.workflow.engine.WorkflowExecutor;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    public WorkflowRegistry workflowRegistry() {
        log.info("[orchestration] Workflow registry initialized");
        return new WorkflowRegistry();
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
}
