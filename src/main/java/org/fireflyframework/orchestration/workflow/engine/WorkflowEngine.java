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

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.dlq.DeadLetterEntry;
import org.fireflyframework.orchestration.core.dlq.DeadLetterService;
import org.fireflyframework.orchestration.core.event.OrchestrationEvent;
import org.fireflyframework.orchestration.core.event.OrchestrationEventPublisher;
import org.fireflyframework.orchestration.core.exception.ExecutionNotFoundException;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.report.ExecutionReport;
import org.fireflyframework.orchestration.core.report.ExecutionReportBuilder;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.observability.OrchestrationTracer;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.workflow.annotation.OnWorkflowComplete;
import org.fireflyframework.orchestration.workflow.annotation.OnWorkflowError;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
public class WorkflowEngine {

    private final WorkflowRegistry registry;
    private final WorkflowExecutor executor;
    private final StepInvoker stepInvoker;
    private final ExecutionPersistenceProvider persistence;
    private final OrchestrationEvents events;
    private final OrchestrationEventPublisher eventPublisher;
    private final DeadLetterService dlqService;
    private final OrchestrationTracer tracer;

    public WorkflowEngine(WorkflowRegistry registry, WorkflowExecutor executor, StepInvoker stepInvoker,
                           ExecutionPersistenceProvider persistence, OrchestrationEvents events,
                           OrchestrationEventPublisher eventPublisher) {
        this(registry, executor, stepInvoker, persistence, events, eventPublisher, null, null);
    }

    public WorkflowEngine(WorkflowRegistry registry, WorkflowExecutor executor, StepInvoker stepInvoker,
                           ExecutionPersistenceProvider persistence, OrchestrationEvents events,
                           OrchestrationEventPublisher eventPublisher, DeadLetterService dlqService) {
        this(registry, executor, stepInvoker, persistence, events, eventPublisher, dlqService, null);
    }

    public WorkflowEngine(WorkflowRegistry registry, WorkflowExecutor executor, StepInvoker stepInvoker,
                           ExecutionPersistenceProvider persistence, OrchestrationEvents events,
                           OrchestrationEventPublisher eventPublisher, DeadLetterService dlqService,
                           OrchestrationTracer tracer) {
        this.registry = registry;
        this.executor = executor;
        this.stepInvoker = stepInvoker;
        this.persistence = persistence;
        this.events = events;
        this.eventPublisher = java.util.Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.dlqService = dlqService;
        this.tracer = tracer;
    }

    public Mono<ExecutionState> startWorkflow(String workflowId, Map<String, Object> input) {
        return startWorkflow(workflowId, input, null, "api", false);
    }

    public Mono<ExecutionState> startWorkflow(String workflowId, Map<String, Object> input,
                                               String correlationId, String triggeredBy, boolean dryRun) {
        return Mono.defer(() -> {
            WorkflowDefinition def = registry.get(workflowId)
                    .orElseThrow(() -> new ExecutionNotFoundException(workflowId));

            ExecutionContext ctx = ExecutionContext.forWorkflow(correlationId, workflowId, dryRun);
            if (input != null) {
                input.forEach(ctx::putVariable);
            }

            ExecutionState initialState = new ExecutionState(
                    ctx.getCorrelationId(), workflowId, ExecutionPattern.WORKFLOW,
                    ExecutionStatus.RUNNING, Map.of(), Map.of(), Map.of(), Map.of(),
                    input != null ? input : Map.of(), Map.of(), Set.of(), List.of(),
                    null, Instant.now(), Instant.now(), Optional.empty());

            // Check if ASYNC trigger mode — execute in background and return immediately
            if (def.triggerMode() == TriggerMode.ASYNC) {
                return persistence.save(initialState)
                        .then(eventPublisher.publish(OrchestrationEvent.executionStarted(
                                workflowId, ctx.getCorrelationId(), ExecutionPattern.WORKFLOW)))
                        .then(Mono.defer(() -> {
                            // Fire onStart after persist+publish, aligned with Saga/TCC
                            events.onStart(workflowId, ctx.getCorrelationId(), ExecutionPattern.WORKFLOW);
                            // Execute the full workflow pipeline in the background
                            tracedExecute(def, ctx)
                                    .flatMap(resultCtx -> {
                                        long durationMs = Duration.between(ctx.getStartedAt(), Instant.now()).toMillis();
                                        events.onCompleted(workflowId, ctx.getCorrelationId(),
                                                ExecutionPattern.WORKFLOW, true, durationMs);
                                        ExecutionState completedState = buildStateFromContext(workflowId, resultCtx, ExecutionStatus.COMPLETED);
                                        return persistence.save(completedState)
                                                .then(eventPublisher.publish(OrchestrationEvent.executionCompleted(
                                                        workflowId, ctx.getCorrelationId(), ExecutionPattern.WORKFLOW, ExecutionStatus.COMPLETED)))
                                                .then(invokeWorkflowCompleteCallbacks(def, ctx))
                                                .thenReturn(completedState);
                                    })
                                    .onErrorResume(error -> {
                                        long durationMs = Duration.between(ctx.getStartedAt(), Instant.now()).toMillis();
                                        events.onCompleted(workflowId, ctx.getCorrelationId(),
                                                ExecutionPattern.WORKFLOW, false, durationMs);
                                        return compensateWorkflow(def, ctx)
                                                .then(saveToDlq(workflowId, ctx, error, input))
                                                .then(invokeWorkflowErrorCallbacks(def, ctx, error))
                                                .then(Mono.defer(() -> {
                                                    boolean suppress = shouldSuppressError(def, ctx, error);
                                                    ExecutionStatus status = suppress ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED;
                                                    String reason = suppress ? null : error.getMessage();
                                                    ExecutionState finalState = buildStateFromContext(workflowId, ctx, status, reason);
                                                    return persistence.save(finalState)
                                                            .then(eventPublisher.publish(OrchestrationEvent.executionCompleted(
                                                                    workflowId, ctx.getCorrelationId(), ExecutionPattern.WORKFLOW, status)))
                                                            .thenReturn(finalState);
                                                }));
                                    })
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .subscribe(
                                            state -> log.info("[workflow] Async workflow '{}' completed with status {}",
                                                    workflowId, state.status()),
                                            error -> log.error("[workflow] Async workflow '{}' failed unexpectedly",
                                                    workflowId, error)
                                    );
                            // Return initial RUNNING state immediately
                            return Mono.just(initialState);
                        }));
            }

            // SYNC (default) trigger mode — execute and block until complete
            return persistence.save(initialState)
                    .then(eventPublisher.publish(OrchestrationEvent.executionStarted(
                            workflowId, ctx.getCorrelationId(), ExecutionPattern.WORKFLOW)))
                    .then(Mono.fromRunnable(() -> events.onStart(workflowId, ctx.getCorrelationId(), ExecutionPattern.WORKFLOW)))
                    .then(tracedExecute(def, ctx))
                    .flatMap(resultCtx -> {
                        long durationMs = Duration.between(ctx.getStartedAt(), Instant.now()).toMillis();
                        events.onCompleted(workflowId, ctx.getCorrelationId(),
                                ExecutionPattern.WORKFLOW, true, durationMs);
                        ExecutionState completedState = buildStateFromContext(workflowId, resultCtx, ExecutionStatus.COMPLETED);
                        return persistence.save(completedState)
                                .then(eventPublisher.publish(OrchestrationEvent.executionCompleted(
                                        workflowId, ctx.getCorrelationId(), ExecutionPattern.WORKFLOW, ExecutionStatus.COMPLETED)))
                                .then(invokeWorkflowCompleteCallbacks(def, ctx))
                                .thenReturn(completedState);
                    })
                    .onErrorResume(error -> {
                        long durationMs = Duration.between(ctx.getStartedAt(), Instant.now()).toMillis();
                        events.onCompleted(workflowId, ctx.getCorrelationId(),
                                ExecutionPattern.WORKFLOW, false, durationMs);
                        return compensateWorkflow(def, ctx)
                                .then(saveToDlq(workflowId, ctx, error, input))
                                .then(invokeWorkflowErrorCallbacks(def, ctx, error))
                                .then(Mono.defer(() -> {
                                    boolean suppress = shouldSuppressError(def, ctx, error);
                                    ExecutionStatus status = suppress ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED;
                                    String reason = suppress ? null : error.getMessage();
                                    ExecutionState finalState = buildStateFromContext(workflowId, ctx, status, reason);
                                    return persistence.save(finalState)
                                            .then(eventPublisher.publish(OrchestrationEvent.executionCompleted(
                                                    workflowId, ctx.getCorrelationId(), ExecutionPattern.WORKFLOW, status)))
                                            .thenReturn(finalState);
                                }));
                    });
        });
    }

    public Mono<ExecutionState> cancelWorkflow(String correlationId) {
        return persistence.findById(correlationId)
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return Mono.error(new ExecutionNotFoundException(correlationId));
                    }
                    ExecutionState state = opt.get();
                    if (state.status().isTerminal()) {
                        return Mono.error(new IllegalStateException(
                                "Cannot cancel workflow in terminal status: " + state.status()));
                    }
                    ExecutionState cancelled = state.withStatus(ExecutionStatus.CANCELLED);
                    return persistence.save(cancelled).thenReturn(cancelled);
                });
    }

    public Mono<ExecutionState> suspendWorkflow(String correlationId, String reason) {
        return persistence.findById(correlationId)
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return Mono.error(new ExecutionNotFoundException(correlationId));
                    }
                    if (!opt.get().status().canSuspend()) {
                        return Mono.error(new IllegalStateException(
                                "Cannot suspend workflow in status: " + opt.get().status()));
                    }
                    String effectiveReason = reason != null ? reason : "User requested";
                    events.onWorkflowSuspended(opt.get().executionName(), correlationId, effectiveReason);
                    ExecutionState suspended = opt.get().withStatus(ExecutionStatus.SUSPENDED);
                    return persistence.save(suspended).thenReturn(suspended);
                });
    }

    public Mono<ExecutionState> suspendWorkflow(String correlationId) {
        return suspendWorkflow(correlationId, null);
    }

    public Mono<ExecutionState> resumeWorkflow(String correlationId) {
        return persistence.findById(correlationId)
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return Mono.error(new ExecutionNotFoundException(correlationId));
                    }
                    ExecutionState state = opt.get();
                    if (!state.status().canResume()) {
                        return Mono.error(new IllegalStateException(
                                "Cannot resume workflow in status: " + state.status()));
                    }
                    events.onWorkflowResumed(state.executionName(), correlationId);

                    WorkflowDefinition def = registry.get(state.executionName())
                            .orElseThrow(() -> new ExecutionNotFoundException(state.executionName()));

                    ExecutionContext ctx = ExecutionContext.forWorkflow(correlationId, state.executionName());
                    state.variables().forEach(ctx::putVariable);
                    state.headers().forEach(ctx::putHeader);
                    state.stepResults().forEach(ctx::putResult);
                    state.stepStatuses().forEach(ctx::setStepStatus);

                    ExecutionState runningState = state.withStatus(ExecutionStatus.RUNNING);
                    String workflowId = state.executionName();
                    return persistence.save(runningState)
                            .then(tracedExecute(def, ctx))
                            .flatMap(resultCtx -> {
                                long durationMs = Duration.between(ctx.getStartedAt(), Instant.now()).toMillis();
                                events.onCompleted(workflowId, correlationId,
                                        ExecutionPattern.WORKFLOW, true, durationMs);
                                ExecutionState completed = buildStateFromContext(
                                        workflowId, resultCtx, ExecutionStatus.COMPLETED);
                                return persistence.save(completed)
                                        .then(eventPublisher.publish(OrchestrationEvent.executionCompleted(
                                                workflowId, correlationId, ExecutionPattern.WORKFLOW,
                                                ExecutionStatus.COMPLETED)))
                                        .then(invokeWorkflowCompleteCallbacks(def, ctx))
                                        .thenReturn(completed);
                            })
                            .onErrorResume(error -> {
                                long durationMs = Duration.between(ctx.getStartedAt(), Instant.now()).toMillis();
                                events.onCompleted(workflowId, correlationId,
                                        ExecutionPattern.WORKFLOW, false, durationMs);
                                return compensateWorkflow(def, ctx)
                                        .then(saveToDlq(workflowId, ctx, error,
                                                new HashMap<>(state.variables())))
                                        .then(invokeWorkflowErrorCallbacks(def, ctx, error))
                                        .then(Mono.defer(() -> {
                                            boolean suppress = shouldSuppressError(def, ctx, error);
                                            ExecutionStatus status = suppress
                                                    ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED;
                                            String reason = suppress ? null : error.getMessage();
                                            ExecutionState finalState = buildStateFromContext(
                                                    workflowId, ctx, status, reason);
                                            return persistence.save(finalState)
                                                    .then(eventPublisher.publish(
                                                            OrchestrationEvent.executionCompleted(
                                                                    workflowId, correlationId,
                                                                    ExecutionPattern.WORKFLOW, status)))
                                                    .thenReturn(finalState);
                                        }));
                            });
                });
    }

    public Flux<ExecutionState> findByStatus(ExecutionStatus status) {
        return persistence.findByStatus(status);
    }

    public Mono<Optional<ExecutionState>> findByCorrelationId(String correlationId) {
        return persistence.findById(correlationId);
    }

    public void registerWorkflow(WorkflowDefinition definition) {
        registry.register(definition);
    }

    private Mono<ExecutionContext> tracedExecute(WorkflowDefinition def, ExecutionContext ctx) {
        Mono<ExecutionContext> execution = executor.execute(def, ctx);
        if (tracer != null) {
            execution = tracer.traceExecution(def.workflowId(), ExecutionPattern.WORKFLOW,
                    ctx.getCorrelationId(), execution);
        }
        return execution;
    }

    private Mono<Void> saveToDlq(String workflowId, ExecutionContext ctx, Throwable error, Map<String, Object> inputs) {
        if (dlqService == null || error == null) return Mono.empty();

        // Find the first FAILED step
        String failedStep = ctx.getStepStatuses().entrySet().stream()
                .filter(e -> e.getValue() == StepStatus.FAILED)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("unknown");

        DeadLetterEntry entry = DeadLetterEntry.create(
                workflowId, ctx.getCorrelationId(), ExecutionPattern.WORKFLOW, failedStep,
                ExecutionStatus.FAILED, error, inputs != null ? inputs : Map.of());
        return dlqService.deadLetter(entry)
                .onErrorResume(err -> {
                    log.warn("[workflow] Failed to save to DLQ: {}", ctx.getCorrelationId(), err);
                    return Mono.empty();
                });
    }

    /**
     * Run compensation methods for all previously completed compensatable steps in reverse order.
     * Compensation errors are logged and swallowed so they do not prevent the workflow from reaching
     * its final FAILED state or abort compensation of remaining steps.
     */
    private Mono<Void> compensateWorkflow(WorkflowDefinition def, ExecutionContext ctx) {
        List<String> compensatableSteps = ctx.getCompletedCompensatableSteps();
        if (compensatableSteps.isEmpty()) {
            return Mono.empty();
        }

        // Reverse order: last completed step compensated first (Saga-style)
        List<String> reversed = new ArrayList<>(compensatableSteps);
        Collections.reverse(reversed);

        String workflowId = def.workflowId();
        String correlationId = ctx.getCorrelationId();
        events.onCompensationStarted(workflowId, correlationId);

        return Flux.fromIterable(reversed)
                .concatMap(stepId -> {
                    WorkflowStepDefinition stepDef = def.findStep(stepId).orElse(null);
                    if (stepDef == null || stepDef.compensationMethod() == null
                            || stepDef.compensationMethod().isBlank()) {
                        return Mono.empty();
                    }

                    Object bean = stepDef.bean();
                    String compMethodName = stepDef.compensationMethod();

                    try {
                        Method compMethod = findCompensationMethod(bean.getClass(), compMethodName);
                        if (compMethod == null) {
                            log.warn("[workflow] Compensation method '{}' not found on bean '{}'",
                                    compMethodName, bean.getClass().getSimpleName());
                            return Mono.empty();
                        }
                        compMethod.setAccessible(true);

                        Object stepResult = ctx.getResult(stepId);

                        if (stepInvoker != null) {
                            return stepInvoker.attemptCall(bean, compMethod, stepResult, ctx,
                                            stepDef.timeoutMs(), 1, 1000L, false, 0,
                                            stepId + ":compensate", false)
                                    .then(Mono.fromRunnable(() -> events.onStepCompensated(workflowId, correlationId, stepId)))
                                    .then()
                                    .onErrorResume(e -> {
                                        log.warn("[workflow] Compensation failed for step '{}'", stepId, e);
                                        events.onStepCompensationFailed(workflowId, correlationId, stepId, e);
                                        return Mono.empty();
                                    });
                        }

                        // Fallback: raw invoke if no StepInvoker available
                        Object[] args = resolveCompensationArgs(compMethod, stepResult, ctx);
                        Object result = compMethod.invoke(bean, args);
                        if (result instanceof Mono<?> mono) {
                            return mono.then()
                                    .doOnSuccess(v -> events.onStepCompensated(workflowId, correlationId, stepId))
                                    .onErrorResume(e -> {
                                        log.warn("[workflow] Compensation failed for step '{}'", stepId, e);
                                        events.onStepCompensationFailed(workflowId, correlationId, stepId, e);
                                        return Mono.empty();
                                    });
                        }
                        events.onStepCompensated(workflowId, correlationId, stepId);
                        return Mono.empty();
                    } catch (Exception e) {
                        log.warn("[workflow] Compensation failed for step '{}'", stepId, e);
                        events.onStepCompensationFailed(workflowId, correlationId, stepId, e);
                        return Mono.empty();
                    }
                })
                .then();
    }

    /**
     * Find a compensation method by name on the given class, searching declared methods.
     */
    private Method findCompensationMethod(Class<?> clazz, String methodName) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                return m;
            }
        }
        // Also check superclass methods
        if (clazz.getSuperclass() != null && clazz.getSuperclass() != Object.class) {
            return findCompensationMethod(clazz.getSuperclass(), methodName);
        }
        return null;
    }

    /**
     * Resolve arguments for a compensation method by matching parameter types.
     * Supports: step result (by assignability), ExecutionContext.
     */
    private Object[] resolveCompensationArgs(Method method, Object stepResult, ExecutionContext ctx) {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] args = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> pt = paramTypes[i];
            if (ExecutionContext.class.isAssignableFrom(pt)) {
                args[i] = ctx;
            } else if (stepResult != null && pt.isAssignableFrom(stepResult.getClass())) {
                args[i] = stepResult;
            } else if (pt == Object.class) {
                args[i] = stepResult;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    /**
     * Determine whether the error should be suppressed based on matching {@code @OnWorkflowError} handlers.
     * Only handlers whose {@code errorTypes} and {@code stepIds} filters match the current error/context
     * contribute to the suppress decision.
     */
    private boolean shouldSuppressError(WorkflowDefinition def, ExecutionContext ctx, Throwable error) {
        List<Method> methods = def.onWorkflowErrorMethods();
        if (methods == null || methods.isEmpty()) return false;

        for (Method m : methods) {
            OnWorkflowError ann = m.getAnnotation(OnWorkflowError.class);

            // Apply same errorTypes filter as invokeWorkflowErrorCallbacks
            if (ann.errorTypes().length > 0) {
                boolean matches = Arrays.stream(ann.errorTypes()).anyMatch(t -> t.isInstance(error));
                if (!matches) continue;
            }

            // Apply same stepIds filter as invokeWorkflowErrorCallbacks
            if (ann.stepIds().length > 0) {
                boolean stepMatch = ctx.getStepStatuses().entrySet().stream()
                        .anyMatch(e -> Arrays.asList(ann.stepIds()).contains(e.getKey())
                                && e.getValue() == org.fireflyframework.orchestration.core.model.StepStatus.FAILED);
                if (!stepMatch) continue;
            }

            // This handler matched — check its suppressError flag
            if (ann.suppressError()) return true;
        }
        return false;
    }

    private ExecutionState buildStateFromContext(String workflowId, ExecutionContext ctx, ExecutionStatus status) {
        return buildStateFromContext(workflowId, ctx, status, null);
    }

    private ExecutionState buildStateFromContext(String workflowId, ExecutionContext ctx,
                                                  ExecutionStatus status, String failureReason) {
        ExecutionReport report = ExecutionReportBuilder.fromContext(ctx, status, failureReason);
        return new ExecutionState(ctx.getCorrelationId(), workflowId, ExecutionPattern.WORKFLOW, status,
                new HashMap<>(ctx.getStepResults()), new HashMap<>(ctx.getStepStatuses()),
                new HashMap<>(ctx.getStepAttempts()), new HashMap<>(ctx.getStepLatenciesMs()),
                new HashMap<>(ctx.getVariables()),
                new HashMap<>(ctx.getHeaders()), Set.copyOf(ctx.getIdempotencyKeys()),
                ctx.getTopologyLayers(), failureReason, ctx.getStartedAt(), Instant.now(), Optional.of(report));
    }

    /**
     * Invoke all {@code @OnWorkflowComplete} callbacks registered on the workflow bean.
     * Async callbacks are fire-and-forget on boundedElastic. Exceptions are logged and swallowed.
     */
    private Mono<Void> invokeWorkflowCompleteCallbacks(WorkflowDefinition def, ExecutionContext ctx) {
        List<Method> methods = def.onWorkflowCompleteMethods();
        if (methods == null || methods.isEmpty()) {
            return Mono.empty();
        }

        Object bean = def.workflowBean();
        if (bean == null) return Mono.empty();

        List<Mono<Void>> syncInvocations = new ArrayList<>();

        for (Method m : methods) {
            OnWorkflowComplete ann = m.getAnnotation(OnWorkflowComplete.class);

            Mono<Void> invoke = Mono.fromRunnable(() -> {
                try {
                    m.setAccessible(true);
                    Object[] args = resolveCallbackArgs(m, ctx);
                    m.invoke(bean, args);
                } catch (Exception e) {
                    log.warn("[workflow] @OnWorkflowComplete callback '{}' failed", m.getName(), e);
                }
            });

            if (ann.async()) {
                invoke.subscribeOn(Schedulers.boundedElastic()).subscribe();
            } else {
                syncInvocations.add(invoke);
            }
        }

        return Flux.concat(syncInvocations).then();
    }

    /**
     * Invoke all {@code @OnWorkflowError} callbacks registered on the workflow bean.
     * Filters by {@code errorTypes} and {@code stepIds} if specified. Async callbacks are fire-and-forget.
     * Exceptions in callbacks are logged and swallowed.
     */
    private Mono<Void> invokeWorkflowErrorCallbacks(WorkflowDefinition def, ExecutionContext ctx, Throwable error) {
        List<Method> methods = def.onWorkflowErrorMethods();
        if (methods == null || methods.isEmpty()) {
            return Mono.empty();
        }

        Object bean = def.workflowBean();
        if (bean == null || error == null) return Mono.empty();

        List<Mono<Void>> syncInvocations = new ArrayList<>();

        for (Method m : methods) {
            OnWorkflowError ann = m.getAnnotation(OnWorkflowError.class);

            // Filter by errorTypes if specified
            if (ann.errorTypes().length > 0) {
                boolean matches = Arrays.stream(ann.errorTypes())
                        .anyMatch(t -> t.isInstance(error));
                if (!matches) continue;
            }

            // Filter by stepIds if specified (check if a matching step has failed)
            if (ann.stepIds().length > 0) {
                boolean stepMatch = ctx.getStepStatuses().entrySet().stream()
                        .anyMatch(e -> Arrays.asList(ann.stepIds()).contains(e.getKey())
                                && e.getValue() == org.fireflyframework.orchestration.core.model.StepStatus.FAILED);
                if (!stepMatch) continue;
            }

            Mono<Void> invoke = Mono.fromRunnable(() -> {
                try {
                    m.setAccessible(true);
                    Object[] args = resolveCallbackArgs(m, error, ctx);
                    m.invoke(bean, args);
                } catch (Exception e) {
                    log.warn("[workflow] @OnWorkflowError callback '{}' failed", m.getName(), e);
                }
            });

            if (ann.async()) {
                invoke.subscribeOn(Schedulers.boundedElastic()).subscribe();
            } else {
                syncInvocations.add(invoke);
            }
        }

        return Flux.concat(syncInvocations).then();
    }

    /**
     * Resolve arguments for a lifecycle callback method by matching parameter types.
     */
    private Object[] resolveCallbackArgs(Method method, Object... candidates) {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] args = new Object[paramTypes.length];

        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> pt = paramTypes[i];
            if (ExecutionContext.class.isAssignableFrom(pt)) {
                for (Object c : candidates) {
                    if (c instanceof ExecutionContext) { args[i] = c; break; }
                }
            } else if (Throwable.class.isAssignableFrom(pt)) {
                for (Object c : candidates) {
                    if (c instanceof Throwable) { args[i] = c; break; }
                }
            } else if (pt == Object.class) {
                // For Object, prefer Throwable or result based on context
                for (Object c : candidates) {
                    if (c != null && !(c instanceof ExecutionContext)) {
                        args[i] = c;
                        break;
                    }
                }
            } else {
                for (Object c : candidates) {
                    if (c != null && pt.isAssignableFrom(c.getClass())) {
                        args[i] = c;
                        break;
                    }
                }
            }
        }
        return args;
    }
}
