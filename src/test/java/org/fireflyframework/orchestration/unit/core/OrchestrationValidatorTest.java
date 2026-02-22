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

package org.fireflyframework.orchestration.unit.core;

import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.validation.OrchestrationValidator;
import org.fireflyframework.orchestration.core.validation.ValidationIssue;
import org.fireflyframework.orchestration.core.validation.ValidationIssue.Severity;
import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.saga.registry.SagaStepDefinition;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.fireflyframework.orchestration.tcc.registry.TccParticipantDefinition;
import org.fireflyframework.orchestration.workflow.annotation.WaitForAll;
import org.fireflyframework.orchestration.workflow.annotation.WaitForAny;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class OrchestrationValidatorTest {

    private OrchestrationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new OrchestrationValidator();
    }

    // ── Workflow validations ───────────────────────────────────────

    @Test
    void validateWorkflow_emptySteps_returnsError() {
        WorkflowDefinition def = new WorkflowDefinition(
                "empty-wf", "Empty", "", "1.0", List.of(),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT,
                null, null, null, null);

        List<ValidationIssue> issues = validator.validateWorkflow(def);

        assertThat(issues).anyMatch(i ->
                i.severity() == Severity.ERROR && i.message().contains("no steps"));
    }

    @Test
    void validateWorkflow_duplicateStepIds_returnsError() {
        WorkflowStepDefinition step1 = new WorkflowStepDefinition(
                "dup", "Step 1", "", List.of(), 1,
                "", 0, RetryPolicy.NO_RETRY, "", false, false, "",
                null, null, null, 0, 0, null);
        WorkflowStepDefinition step2 = new WorkflowStepDefinition(
                "dup", "Step 2", "", List.of(), 2,
                "", 0, RetryPolicy.NO_RETRY, "", false, false, "",
                null, null, null, 0, 0, null);

        WorkflowDefinition def = new WorkflowDefinition(
                "dup-wf", "Dup", "", "1.0", List.of(step1, step2),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT,
                null, null, null, null);

        List<ValidationIssue> issues = validator.validateWorkflow(def);

        assertThat(issues).anyMatch(i ->
                i.severity() == Severity.ERROR && i.message().contains("Duplicate step ID"));
    }

    @Test
    void validateWorkflow_conflictingWaitAnnotations_returnsError() throws NoSuchMethodException {
        Method conflictingMethod = ConflictingAnnotations.class
                .getDeclaredMethod("conflictingStep");

        WorkflowStepDefinition step = new WorkflowStepDefinition(
                "conflict", "Conflict Step", "", List.of(), 1,
                "", 0, RetryPolicy.NO_RETRY, "", false, false, "",
                null, conflictingMethod, null, 0, 0, null);

        WorkflowDefinition def = new WorkflowDefinition(
                "conflict-wf", "Conflict", "", "1.0", List.of(step),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT,
                null, null, null, null);

        List<ValidationIssue> issues = validator.validateWorkflow(def);

        assertThat(issues).anyMatch(i ->
                i.severity() == Severity.ERROR
                        && i.message().contains("@WaitForAll")
                        && i.message().contains("@WaitForAny"));
    }

    // ── Saga validations ───────────────────────────────────────────

    @Test
    void validateSaga_missingCompensation_returnsWarning() {
        SagaDefinition saga = new SagaDefinition("test-saga", new Object(), new Object(), 0);

        SagaStepDefinition step = new SagaStepDefinition(
                "step1", "", List.of(),
                0, Duration.ofMillis(100), Duration.ZERO,
                "", false, 0.0, false, null);
        saga.steps.put(step.id, step);

        List<ValidationIssue> issues = validator.validateSaga(saga);

        assertThat(issues).anyMatch(i ->
                i.severity() == Severity.WARNING && i.message().contains("no compensation"));
    }

    // ── TCC validations ────────────────────────────────────────────

    @Test
    void validateTcc_missingPhaseMethod_returnsError() {
        TccDefinition tcc = new TccDefinition("test-tcc", new Object(), new Object(),
                30000, false, 0, 100);

        // Participant with no try, confirm, or cancel methods
        TccParticipantDefinition participant = new TccParticipantDefinition(
                "p1", 1, 5000, false, new Object(), new Object(),
                null, -1, -1, -1,     // no try
                null, -1, -1, -1,     // no confirm
                null, -1, -1, -1,     // no cancel
                false, 0.0);
        tcc.participants.put(participant.id, participant);

        List<ValidationIssue> issues = validator.validateTcc(tcc);

        assertThat(issues)
                .filteredOn(i -> i.severity() == Severity.ERROR)
                .hasSizeGreaterThanOrEqualTo(3)
                .anyMatch(i -> i.message().contains("@TryMethod"))
                .anyMatch(i -> i.message().contains("@ConfirmMethod"))
                .anyMatch(i -> i.message().contains("@CancelMethod"));
    }

    @Test
    void validateTcc_negativeTimeout_returnsWarning() {
        TccDefinition tcc = new TccDefinition("neg-tcc", new Object(), new Object(),
                -5000, false, 0, 100);

        // Provide a valid participant so we don't get empty-participant errors
        Method dummyMethod;
        try {
            dummyMethod = DummyParticipant.class.getDeclaredMethod("doWork");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }

        TccParticipantDefinition participant = new TccParticipantDefinition(
                "p1", 1, -1000, false, new Object(), new Object(),
                dummyMethod, -1, -1, -1,
                dummyMethod, -1, -1, -1,
                dummyMethod, -1, -1, -1,
                false, 0.0);
        tcc.participants.put(participant.id, participant);

        List<ValidationIssue> issues = validator.validateTcc(tcc);

        assertThat(issues).anyMatch(i ->
                i.severity() == Severity.WARNING && i.message().contains("negative timeout"));
    }

    // ── validateAndThrow ───────────────────────────────────────────

    @Test
    void validateAndThrow_withErrors_throwsIllegalStateException() {
        List<ValidationIssue> issues = List.of(
                new ValidationIssue(Severity.ERROR, "bad thing", "some.location"));

        assertThatThrownBy(() -> validator.validateAndThrow(issues))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad thing");
    }

    @Test
    void validateAndThrow_withOnlyWarnings_doesNotThrow() {
        List<ValidationIssue> issues = List.of(
                new ValidationIssue(Severity.WARNING, "mild thing", "some.location"));

        assertThatCode(() -> validator.validateAndThrow(issues))
                .doesNotThrowAnyException();
    }

    // ── Helper types ───────────────────────────────────────────────

    /**
     * Helper class with conflicting @WaitForAll and @WaitForAny on the same method.
     */
    static class ConflictingAnnotations {
        @WaitForAll
        @WaitForAny
        void conflictingStep() {}
    }

    /**
     * Dummy participant class providing a method for TCC test construction.
     */
    static class DummyParticipant {
        void doWork() {}
    }
}
