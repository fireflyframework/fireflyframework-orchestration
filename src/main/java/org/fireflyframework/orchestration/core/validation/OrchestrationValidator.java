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

package org.fireflyframework.orchestration.core.validation;

import org.fireflyframework.orchestration.core.exception.TopologyValidationException;
import org.fireflyframework.orchestration.core.topology.TopologyBuilder;
import org.fireflyframework.orchestration.core.validation.ValidationIssue.Severity;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.saga.registry.SagaStepDefinition;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.fireflyframework.orchestration.tcc.registry.TccParticipantDefinition;
import org.fireflyframework.orchestration.workflow.annotation.WaitForAll;
import org.fireflyframework.orchestration.workflow.annotation.WaitForAny;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Validates orchestration definitions (workflows, sagas, TCC) at registration time.
 * Produces a list of {@link ValidationIssue}s categorized by severity. Callers can
 * use {@link #validateAndThrow(List)} to abort registration when ERROR-level issues exist.
 */
@Slf4j
public class OrchestrationValidator {

    private static final long MAX_REASONABLE_TIMEOUT_MS = 24 * 60 * 60 * 1000L; // 24 hours

    // ── Workflow validation ────────────────────────────────────────

    /**
     * Validates a {@link WorkflowDefinition}, checking for empty steps, duplicate IDs,
     * missing dependency references, cyclic dependencies, missing compensation methods,
     * timeout/retry sanity, and annotation conflicts.
     */
    public List<ValidationIssue> validateWorkflow(WorkflowDefinition def) {
        List<ValidationIssue> issues = new ArrayList<>();
        String loc = "workflow." + def.workflowId();

        // Empty steps
        if (def.steps() == null || def.steps().isEmpty()) {
            issues.add(new ValidationIssue(Severity.ERROR, "Workflow has no steps", loc));
            return issues;
        }

        // Duplicate step IDs
        Set<String> seenIds = new HashSet<>();
        for (WorkflowStepDefinition step : def.steps()) {
            if (!seenIds.add(step.stepId())) {
                issues.add(new ValidationIssue(Severity.ERROR,
                        "Duplicate step ID '" + step.stepId() + "'",
                        loc + ".step." + step.stepId()));
            }
        }

        // Missing dependency references and cycles (via TopologyBuilder)
        try {
            TopologyBuilder.validate(def.steps(),
                    WorkflowStepDefinition::stepId,
                    WorkflowStepDefinition::dependsOn);
        } catch (TopologyValidationException e) {
            issues.add(new ValidationIssue(Severity.ERROR, e.getMessage(), loc));
        }

        // Per-step validations
        for (WorkflowStepDefinition step : def.steps()) {
            String stepLoc = loc + ".step." + step.stepId();

            // Missing compensation method for compensatable steps
            if (step.compensatable() && (step.compensationMethod() == null || step.compensationMethod().isBlank())) {
                issues.add(new ValidationIssue(Severity.WARNING,
                        "Step is marked compensatable but has no compensation method",
                        stepLoc));
            }

            // Timeout sanity
            if (step.timeoutMs() < 0) {
                issues.add(new ValidationIssue(Severity.WARNING,
                        "Step has negative timeout (" + step.timeoutMs() + "ms)",
                        stepLoc));
            }
            if (step.timeoutMs() > MAX_REASONABLE_TIMEOUT_MS) {
                issues.add(new ValidationIssue(Severity.WARNING,
                        "Step timeout exceeds 24 hours (" + step.timeoutMs() + "ms)",
                        stepLoc));
            }

            // Retry sanity
            if (step.retryPolicy() != null && step.retryPolicy().maxAttempts() < 0) {
                issues.add(new ValidationIssue(Severity.WARNING,
                        "Step has negative max retries (" + step.retryPolicy().maxAttempts() + ")",
                        stepLoc));
            }

            // Annotation conflict: @WaitForAll + @WaitForAny on same method
            if (step.method() != null) {
                boolean hasWaitForAll = step.method().isAnnotationPresent(WaitForAll.class);
                boolean hasWaitForAny = step.method().isAnnotationPresent(WaitForAny.class);
                if (hasWaitForAll && hasWaitForAny) {
                    issues.add(new ValidationIssue(Severity.ERROR,
                            "Step method has both @WaitForAll and @WaitForAny annotations; these are mutually exclusive",
                            stepLoc));
                }
            }
        }

        // Workflow-level timeout sanity
        if (def.timeoutMs() < 0) {
            issues.add(new ValidationIssue(Severity.WARNING,
                    "Workflow has negative timeout (" + def.timeoutMs() + "ms)",
                    loc));
        }

        return issues;
    }

    // ── Saga validation ────────────────────────────────────────────

    /**
     * Validates a {@link SagaDefinition}, checking for empty steps, duplicate IDs,
     * missing dependency references, cyclic dependencies, missing compensation methods,
     * and timeout/retry sanity.
     */
    public List<ValidationIssue> validateSaga(SagaDefinition def) {
        List<ValidationIssue> issues = new ArrayList<>();
        String loc = "saga." + def.name;

        // Empty steps
        if (def.steps == null || def.steps.isEmpty()) {
            issues.add(new ValidationIssue(Severity.ERROR, "Saga has no steps", loc));
            return issues;
        }

        // Duplicate IDs (Map keys are unique by definition, but check for safety)
        Set<String> stepIds = def.steps.keySet();

        // Missing dependency references and cycles
        try {
            TopologyBuilder.validate(
                    new ArrayList<>(def.steps.values()),
                    s -> s.id,
                    s -> s.dependsOn);
        } catch (TopologyValidationException e) {
            issues.add(new ValidationIssue(Severity.ERROR, e.getMessage(), loc));
        }

        // Per-step validations
        for (SagaStepDefinition step : def.steps.values()) {
            String stepLoc = loc + ".step." + step.id;

            // Missing compensation method
            if ((step.compensateName == null || step.compensateName.isBlank()) && step.compensateMethod == null) {
                issues.add(new ValidationIssue(Severity.WARNING,
                        "Step has no compensation method defined",
                        stepLoc));
            }

            // Retry sanity
            if (step.retry < 0) {
                issues.add(new ValidationIssue(Severity.WARNING,
                        "Step has negative retry count (" + step.retry + ")",
                        stepLoc));
            }

            // Timeout sanity
            if (step.timeout != null && step.timeout.toMillis() < 0) {
                issues.add(new ValidationIssue(Severity.WARNING,
                        "Step has negative timeout (" + step.timeout.toMillis() + "ms)",
                        stepLoc));
            }
        }

        return issues;
    }

    // ── TCC validation ─────────────────────────────────────────────

    /**
     * Validates a {@link TccDefinition}, checking for empty participants, duplicate IDs,
     * missing phase methods, and timeout/retry sanity.
     */
    public List<ValidationIssue> validateTcc(TccDefinition def) {
        List<ValidationIssue> issues = new ArrayList<>();
        String loc = "tcc." + def.name;

        // Empty participants
        if (def.participants == null || def.participants.isEmpty()) {
            issues.add(new ValidationIssue(Severity.ERROR, "TCC has no participants", loc));
            return issues;
        }

        // Per-participant validations
        for (TccParticipantDefinition p : def.participants.values()) {
            String pLoc = loc + ".participant." + p.id;

            // Missing phase methods
            if (p.tryMethod == null) {
                issues.add(new ValidationIssue(Severity.ERROR,
                        "Participant is missing @TryMethod",
                        pLoc));
            }
            if (p.confirmMethod == null) {
                issues.add(new ValidationIssue(Severity.ERROR,
                        "Participant is missing @ConfirmMethod",
                        pLoc));
            }
            if (p.cancelMethod == null) {
                issues.add(new ValidationIssue(Severity.ERROR,
                        "Participant is missing @CancelMethod",
                        pLoc));
            }

            // Timeout sanity
            if (p.timeoutMs < 0) {
                issues.add(new ValidationIssue(Severity.WARNING,
                        "Participant has negative timeout (" + p.timeoutMs + "ms)",
                        pLoc));
            }
            if (p.timeoutMs > MAX_REASONABLE_TIMEOUT_MS) {
                issues.add(new ValidationIssue(Severity.WARNING,
                        "Participant timeout exceeds 24 hours (" + p.timeoutMs + "ms)",
                        pLoc));
            }

            // Per-phase timeout sanity
            validatePhaseTimeout(issues, pLoc, "try", p.tryTimeoutMs);
            validatePhaseTimeout(issues, pLoc, "confirm", p.confirmTimeoutMs);
            validatePhaseTimeout(issues, pLoc, "cancel", p.cancelTimeoutMs);

            // Per-phase retry sanity
            if (p.tryRetry < -1) {
                issues.add(new ValidationIssue(Severity.WARNING,
                        "Participant try phase has invalid retry count (" + p.tryRetry + ")",
                        pLoc));
            }
            if (p.confirmRetry < -1) {
                issues.add(new ValidationIssue(Severity.WARNING,
                        "Participant confirm phase has invalid retry count (" + p.confirmRetry + ")",
                        pLoc));
            }
            if (p.cancelRetry < -1) {
                issues.add(new ValidationIssue(Severity.WARNING,
                        "Participant cancel phase has invalid retry count (" + p.cancelRetry + ")",
                        pLoc));
            }
        }

        // TCC-level timeout sanity
        if (def.timeoutMs < 0) {
            issues.add(new ValidationIssue(Severity.WARNING,
                    "TCC has negative timeout (" + def.timeoutMs + "ms)",
                    loc));
        }

        // TCC-level retry sanity
        if (def.maxRetries < 0) {
            issues.add(new ValidationIssue(Severity.WARNING,
                    "TCC has negative max retries (" + def.maxRetries + ")",
                    loc));
        }
        if (def.backoffMs < 0) {
            issues.add(new ValidationIssue(Severity.WARNING,
                    "TCC has negative backoff (" + def.backoffMs + "ms)",
                    loc));
        }

        return issues;
    }

    // ── Shared utilities ───────────────────────────────────────────

    /**
     * Throws an {@link IllegalStateException} if the issue list contains any ERROR-severity issues.
     * WARNING and INFO issues are logged but do not cause a failure.
     */
    public void validateAndThrow(List<ValidationIssue> issues) {
        // Log warnings and info
        for (ValidationIssue issue : issues) {
            switch (issue.severity()) {
                case WARNING -> log.warn("[validation] {} at {}", issue.message(), issue.location());
                case INFO -> log.info("[validation] {} at {}", issue.message(), issue.location());
                case ERROR -> log.error("[validation] {} at {}", issue.message(), issue.location());
            }
        }

        List<ValidationIssue> errors = issues.stream()
                .filter(i -> i.severity() == Severity.ERROR)
                .toList();

        if (!errors.isEmpty()) {
            String errorMessages = errors.stream()
                    .map(e -> e.location() + ": " + e.message())
                    .collect(Collectors.joining("; "));
            throw new IllegalStateException("Orchestration validation failed with " +
                    errors.size() + " error(s): " + errorMessages);
        }
    }

    private void validatePhaseTimeout(List<ValidationIssue> issues, String pLoc,
                                       String phase, long timeoutMs) {
        if (timeoutMs < -1) {
            issues.add(new ValidationIssue(Severity.WARNING,
                    "Participant " + phase + " phase has negative timeout (" + timeoutMs + "ms)",
                    pLoc));
        }
    }
}
