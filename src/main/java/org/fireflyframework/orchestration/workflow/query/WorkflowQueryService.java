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

package org.fireflyframework.orchestration.workflow.query;

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provides read-only queries against running or completed workflow instances.
 * Supports built-in queries: status, current step, step history, variables, etc.
 * Also supports custom {@code @WorkflowQuery}-annotated methods on workflow beans.
 */
@Slf4j
public class WorkflowQueryService {

    private final ExecutionPersistenceProvider persistence;
    private final WorkflowRegistry registry;

    public WorkflowQueryService(ExecutionPersistenceProvider persistence) {
        this(persistence, null);
    }

    public WorkflowQueryService(ExecutionPersistenceProvider persistence, WorkflowRegistry registry) {
        this.persistence = persistence;
        this.registry = registry;
    }

    public Mono<Optional<ExecutionStatus>> getStatus(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(ExecutionState::status));
    }

    public Mono<Optional<Map<String, StepStatus>>> getStepStatuses(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(ExecutionState::stepStatuses));
    }

    public Mono<Optional<List<String>>> getCurrentSteps(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(state -> state.stepStatuses().entrySet().stream()
                        .filter(e -> e.getValue() == StepStatus.RUNNING)
                        .map(Map.Entry::getKey)
                        .toList()));
    }

    public Mono<Optional<Map<String, Object>>> getStepResults(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(ExecutionState::stepResults));
    }

    public Mono<Optional<Object>> getStepResult(String correlationId, String stepId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(state -> state.stepResults().get(stepId)));
    }

    public Mono<Optional<Map<String, Object>>> getVariables(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(ExecutionState::variables));
    }

    public Mono<Optional<Object>> getVariable(String correlationId, String key) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(state -> state.variables().get(key)));
    }

    public Mono<Optional<List<List<String>>>> getTopologyLayers(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(ExecutionState::topologyLayers));
    }

    public Mono<Optional<Map<String, String>>> getHeaders(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(ExecutionState::headers));
    }

    public Mono<Optional<String>> getFailureReason(String correlationId) {
        return persistence.findById(correlationId)
                .map(opt -> opt.map(ExecutionState::failureReason));
    }

    /**
     * Execute a custom {@code @WorkflowQuery}-annotated method on the workflow bean.
     * The query method is looked up from the workflow registry by query name, and invoked
     * reflectively with an {@link ExecutionContext} reconstructed from persisted state.
     *
     * @param correlationId the workflow instance correlation ID
     * @param queryName     the query name (matching {@code @WorkflowQuery("...")})
     * @param args          optional arguments to pass to the query method
     * @return the query result, or error if the query or instance is not found
     */
    public Mono<Object> executeQuery(String correlationId, String queryName, Object... args) {
        if (registry == null) {
            return Mono.error(new IllegalStateException("WorkflowRegistry not configured for query execution"));
        }

        return persistence.findById(correlationId)
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return Mono.error(new IllegalArgumentException(
                                "No workflow instance found for correlationId: " + correlationId));
                    }

                    ExecutionState state = opt.get();
                    Optional<WorkflowDefinition> defOpt = registry.get(state.executionName());
                    if (defOpt.isEmpty()) {
                        return Mono.error(new IllegalArgumentException(
                                "No workflow definition found: " + state.executionName()));
                    }

                    WorkflowDefinition def = defOpt.get();
                    Map<String, Method> queryMethods = def.queryMethods();
                    if (queryMethods == null || !queryMethods.containsKey(queryName)) {
                        return Mono.error(new IllegalArgumentException(
                                "No @WorkflowQuery found for query name: " + queryName));
                    }

                    Method queryMethod = queryMethods.get(queryName);
                    Object bean = def.workflowBean();
                    if (bean == null) {
                        return Mono.error(new IllegalStateException(
                                "Workflow bean is null for workflow: " + def.workflowId()));
                    }

                    // Rebuild ExecutionContext from persisted state
                    ExecutionContext ctx = ExecutionContext.forWorkflow(
                            correlationId, state.executionName());
                    if (state.variables() != null) {
                        state.variables().forEach(ctx::putVariable);
                    }
                    if (state.headers() != null) {
                        state.headers().forEach(ctx::putHeader);
                    }
                    if (state.stepResults() != null) {
                        state.stepResults().forEach(ctx::putResult);
                    }
                    if (state.stepStatuses() != null) {
                        state.stepStatuses().forEach(ctx::setStepStatus);
                    }

                    try {
                        queryMethod.setAccessible(true);
                        Object[] invokeArgs = resolveQueryArgs(queryMethod, ctx, args);
                        Object result = queryMethod.invoke(bean, invokeArgs);
                        if (result instanceof Mono<?> mono) {
                            return mono.map(r -> (Object) r);
                        }
                        return Mono.justOrEmpty(result);
                    } catch (Exception e) {
                        log.warn("[workflow-query] Failed to execute query '{}' for instance '{}'",
                                queryName, correlationId, e);
                        return Mono.error(new RuntimeException(
                                "Query execution failed: " + e.getMessage(), e));
                    }
                });
    }

    /**
     * Resolve arguments for a query method by matching parameter types.
     */
    private Object[] resolveQueryArgs(Method method, ExecutionContext ctx, Object... extraArgs) {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] resolvedArgs = new Object[paramTypes.length];

        int extraIdx = 0;
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> pt = paramTypes[i];
            if (ExecutionContext.class.isAssignableFrom(pt)) {
                resolvedArgs[i] = ctx;
            } else if (extraArgs != null && extraIdx < extraArgs.length) {
                resolvedArgs[i] = extraArgs[extraIdx++];
            }
        }
        return resolvedArgs;
    }
}
