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

import org.fireflyframework.orchestration.core.event.EventGateway;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.registry.SagaRegistry;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.registry.TccRegistry;
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the {@link EventGateway}.
 *
 * <p>Creates an {@code EventGateway} bean and scans all registered workflows
 * (and potentially sagas/TCCs in the future) for {@code triggerEventType}
 * declarations, building a routing table that maps event types to execution
 * targets.
 */
@Slf4j
@AutoConfiguration(after = {WorkflowAutoConfiguration.class, SagaAutoConfiguration.class, TccAutoConfiguration.class})
public class EventGatewayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EventGateway eventGateway() {
        log.info("[orchestration] Event gateway initialized");
        return new EventGateway();
    }

    @Bean
    public SmartInitializingSingleton eventGatewayInitializer(
            EventGateway eventGateway,
            ObjectProvider<WorkflowRegistry> workflowRegistryProvider,
            ObjectProvider<WorkflowEngine> workflowEngineProvider,
            ObjectProvider<SagaRegistry> sagaRegistryProvider,
            ObjectProvider<SagaEngine> sagaEngineProvider,
            ObjectProvider<TccRegistry> tccRegistryProvider,
            ObjectProvider<TccEngine> tccEngineProvider) {
        return () -> {
            // Scan workflows for triggerEventType
            WorkflowRegistry workflowRegistry = workflowRegistryProvider.getIfAvailable();
            WorkflowEngine workflowEngine = workflowEngineProvider.getIfAvailable();
            if (workflowRegistry != null && workflowEngine != null) {
                for (WorkflowDefinition def : workflowRegistry.getAll()) {
                    if (def.triggerEventType() != null && !def.triggerEventType().isBlank()) {
                        eventGateway.register(
                                def.triggerEventType(),
                                def.workflowId(),
                                ExecutionPattern.WORKFLOW,
                                payload -> workflowEngine.startWorkflow(def.workflowId(), payload));
                    }
                }
            }

            // Note: @Saga and @Tcc annotations do not currently support triggerEventType.
            // When they do, scanning logic can be added here following the same pattern.

            if (eventGateway.registrationCount() > 0) {
                log.info("[event-gateway] Scan complete: {} trigger(s) registered for event types {}",
                        eventGateway.registrationCount(), eventGateway.registeredEventTypes());
            } else {
                log.debug("[event-gateway] Scan complete: no event triggers registered");
            }
        };
    }
}
