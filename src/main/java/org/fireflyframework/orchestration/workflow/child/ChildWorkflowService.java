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

package org.fireflyframework.orchestration.workflow.child;

import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages child workflow lifecycle: spawn, track, cancel.
 * Maintains parent-child mapping for cleanup and cascading cancellation.
 */
@Slf4j
public class ChildWorkflowService {

    private final WorkflowEngine engine;
    private final OrchestrationEvents events;

    // parentCorrelationId -> list of child correlationIds
    private final Map<String, CopyOnWriteArrayList<String>> parentToChildren = new ConcurrentHashMap<>();

    public ChildWorkflowService(WorkflowEngine engine, OrchestrationEvents events) {
        this.engine = engine;
        this.events = events;
    }

    /**
     * Spawns a child workflow from within a parent workflow.
     */
    public Mono<ChildWorkflowResult> spawn(String parentCorrelationId, String childWorkflowId,
                                             Map<String, Object> input) {
        return engine.startWorkflow(childWorkflowId, input, null, "child:" + parentCorrelationId, false)
                .doOnSubscribe(s -> log.info("[child-workflow] Spawning child '{}' from parent '{}'",
                        childWorkflowId, parentCorrelationId))
                .doOnNext(state -> {
                    parentToChildren.computeIfAbsent(parentCorrelationId, k -> new CopyOnWriteArrayList<>())
                            .add(state.correlationId());
                    events.onChildWorkflowStarted(
                            "workflow", parentCorrelationId, childWorkflowId, state.correlationId());
                })
                .map(state -> {
                    events.onChildWorkflowCompleted(
                            "workflow", parentCorrelationId, state.correlationId(),
                            state.status() == org.fireflyframework.orchestration.core.model.ExecutionStatus.COMPLETED);
                    return ChildWorkflowResult.from(parentCorrelationId, state);
                });
    }

    /**
     * Returns all child correlation IDs for a parent workflow.
     */
    public List<String> getChildren(String parentCorrelationId) {
        var children = parentToChildren.get(parentCorrelationId);
        return children != null ? List.copyOf(children) : List.of();
    }

    /**
     * Cancels all child workflows of a parent.
     */
    public Mono<Void> cancelChildren(String parentCorrelationId) {
        var children = parentToChildren.get(parentCorrelationId);
        if (children == null || children.isEmpty()) return Mono.empty();

        return Flux.fromIterable(children)
                .flatMap(childId -> engine.cancelWorkflow(childId)
                        .onErrorResume(err -> {
                            log.warn("[child-workflow] Failed to cancel child '{}': {}", childId, err.getMessage());
                            return Mono.empty();
                        }))
                .then();
    }

    /**
     * Cleans up parent-child mappings for a completed workflow.
     */
    public void cleanup(String parentCorrelationId) {
        parentToChildren.remove(parentCorrelationId);
    }
}
