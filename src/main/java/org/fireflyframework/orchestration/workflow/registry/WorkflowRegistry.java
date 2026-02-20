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

package org.fireflyframework.orchestration.workflow.registry;

import org.fireflyframework.orchestration.core.exception.DuplicateDefinitionException;
import org.fireflyframework.orchestration.core.topology.TopologyBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class WorkflowRegistry {

    private final ConcurrentHashMap<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();

    public void register(WorkflowDefinition definition) {
        // Validate topology
        TopologyBuilder.validate(definition.steps(),
                WorkflowStepDefinition::stepId,
                WorkflowStepDefinition::dependsOn);

        if (definitions.putIfAbsent(definition.workflowId(), definition) != null) {
            throw new DuplicateDefinitionException(definition.workflowId());
        }
        log.info("[workflow-registry] Registered workflow '{}'", definition.workflowId());
    }

    public Optional<WorkflowDefinition> get(String workflowId) {
        return Optional.ofNullable(definitions.get(workflowId));
    }

    public Collection<WorkflowDefinition> getAll() {
        return Collections.unmodifiableCollection(definitions.values());
    }

    public void unregister(String workflowId) {
        definitions.remove(workflowId);
    }
}
