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

package org.fireflyframework.orchestration.workflow.engine;

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.core.topology.TopologyBuilder;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
public class WorkflowExecutor {

    private final StepInvoker stepInvoker;
    private final OrchestrationEvents events;

    public WorkflowExecutor(ArgumentResolver argumentResolver, OrchestrationEvents events) {
        this.stepInvoker = new StepInvoker(argumentResolver);
        this.events = events;
    }

    public Mono<ExecutionContext> execute(WorkflowDefinition definition, ExecutionContext ctx) {
        // Build topology layers
        List<List<String>> layers = TopologyBuilder.buildLayers(
                definition.steps(), WorkflowStepDefinition::stepId, WorkflowStepDefinition::dependsOn);
        ctx.setTopologyLayers(layers);

        return executeLayersSequentially(definition, ctx, layers, 0);
    }

    private Mono<ExecutionContext> executeLayersSequentially(WorkflowDefinition def, ExecutionContext ctx,
                                                              List<List<String>> layers, int layerIndex) {
        if (layerIndex >= layers.size()) {
            return Mono.just(ctx);
        }

        List<String> layer = layers.get(layerIndex);

        return executeLayer(def, ctx, layer)
                .then(Mono.defer(() -> executeLayersSequentially(def, ctx, layers, layerIndex + 1)));
    }

    private Mono<Void> executeLayer(WorkflowDefinition def, ExecutionContext ctx, List<String> stepIds) {
        if (stepIds.size() == 1) {
            return executeStep(def, ctx, stepIds.get(0));
        }
        // Parallel execution within layer
        return Flux.fromIterable(stepIds)
                .flatMap(stepId -> executeStep(def, ctx, stepId))
                .then();
    }

    private Mono<Void> executeStep(WorkflowDefinition def, ExecutionContext ctx, String stepId) {
        return def.findStep(stepId)
                .map(stepDef -> {
                    events.onStepStarted(def.workflowId(), ctx.getCorrelationId(), stepId);
                    ctx.setStepStatus(stepId, StepStatus.RUNNING);
                    ctx.markStepStarted(stepId);

                    Object input = ctx.getVariables();
                    long timeout = stepDef.timeoutMs() > 0 ? stepDef.timeoutMs() : def.timeoutMs();
                    int retries = stepDef.retryPolicy() != null ? stepDef.retryPolicy().maxAttempts() - 1 : 0;
                    long backoff = stepDef.retryPolicy() != null ? stepDef.retryPolicy().initialDelay().toMillis() : 1000;

                    return stepInvoker.attemptCall(stepDef.bean(), stepDef.method(), input, ctx,
                                    timeout, retries, backoff, false, 0, stepId, false)
                            .doOnNext(result -> {
                                ctx.putResult(stepId, result);
                                ctx.setStepStatus(stepId, StepStatus.DONE);
                                long latency = Duration.between(ctx.getStepStartedAt(stepId), Instant.now()).toMillis();
                                ctx.setStepLatency(stepId, latency);
                                events.onStepSuccess(def.workflowId(), ctx.getCorrelationId(), stepId,
                                        ctx.getAttempts(stepId), latency);
                            })
                            .onErrorResume(error -> {
                                ctx.setStepStatus(stepId, StepStatus.FAILED);
                                events.onStepFailed(def.workflowId(), ctx.getCorrelationId(), stepId,
                                        error, ctx.getAttempts(stepId));
                                return Mono.error(error);
                            })
                            .then();
                })
                .orElse(Mono.empty());
    }
}
