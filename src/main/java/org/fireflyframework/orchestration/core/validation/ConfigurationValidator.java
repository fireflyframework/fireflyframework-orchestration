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

import org.fireflyframework.orchestration.saga.registry.SagaDefinition;
import org.fireflyframework.orchestration.saga.registry.SagaRegistry;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.fireflyframework.orchestration.tcc.registry.TccRegistry;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates orchestration configuration at startup, detecting common issues
 * like missing handlers, empty definitions, and invalid topology.
 */
@Slf4j
public class ConfigurationValidator {

    private final WorkflowRegistry workflowRegistry;
    private final SagaRegistry sagaRegistry;
    private final TccRegistry tccRegistry;

    public ConfigurationValidator(WorkflowRegistry workflowRegistry,
                                    SagaRegistry sagaRegistry,
                                    TccRegistry tccRegistry) {
        this.workflowRegistry = workflowRegistry;
        this.sagaRegistry = sagaRegistry;
        this.tccRegistry = tccRegistry;
    }

    public record ValidationResult(List<String> errors, List<String> warnings) {
        public boolean isValid() { return errors.isEmpty(); }
    }

    /**
     * Validates all registered orchestration definitions.
     */
    public ValidationResult validate() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        validateWorkflows(errors, warnings);
        validateSagas(errors, warnings);
        validateTccs(errors, warnings);

        if (errors.isEmpty()) {
            log.info("[validation] All orchestration configurations valid");
        } else {
            log.error("[validation] Found {} error(s) in orchestration configuration", errors.size());
            errors.forEach(e -> log.error("[validation]   - {}", e));
        }
        if (!warnings.isEmpty()) {
            warnings.forEach(w -> log.warn("[validation]   - {}", w));
        }

        return new ValidationResult(List.copyOf(errors), List.copyOf(warnings));
    }

    private void validateWorkflows(List<String> errors, List<String> warnings) {
        if (workflowRegistry == null) return;
        for (WorkflowDefinition wf : workflowRegistry.getAll()) {
            if (wf.steps().isEmpty()) {
                errors.add("Workflow '" + wf.workflowId() + "' has no steps");
            }
            if (wf.timeoutMs() <= 0) {
                warnings.add("Workflow '" + wf.workflowId() + "' has no timeout configured");
            }
            // Check for steps with no handler method
            for (var step : wf.steps()) {
                if (step.method() == null && step.bean() == null) {
                    errors.add("Workflow '" + wf.workflowId() + "' step '" + step.stepId()
                            + "' has no handler method");
                }
            }
        }
    }

    private void validateSagas(List<String> errors, List<String> warnings) {
        if (sagaRegistry == null) return;
        for (SagaDefinition saga : sagaRegistry.getAll()) {
            if (saga.steps.isEmpty()) {
                errors.add("Saga '" + saga.name + "' has no steps");
            }
            for (var step : saga.steps.values()) {
                if (step.compensateName != null && !step.compensateName.isEmpty()
                        && step.compensateMethod == null) {
                    errors.add("Saga '" + saga.name + "' step '" + step.id
                            + "' references compensation '" + step.compensateName + "' but method not found");
                }
            }
        }
    }

    private void validateTccs(List<String> errors, List<String> warnings) {
        if (tccRegistry == null) return;
        for (TccDefinition tcc : tccRegistry.getAll()) {
            if (tcc.participants.isEmpty()) {
                errors.add("TCC '" + tcc.name + "' has no participants");
            }
            for (var entry : tcc.participants.entrySet()) {
                var p = entry.getValue();
                if (p.tryMethod == null) {
                    warnings.add("TCC '" + tcc.name + "' participant '" + p.id
                            + "' has no try method (may use builder handler)");
                }
                if (p.confirmMethod == null) {
                    warnings.add("TCC '" + tcc.name + "' participant '" + p.id
                            + "' has no confirm method (may use builder handler)");
                }
                if (p.cancelMethod == null) {
                    warnings.add("TCC '" + tcc.name + "' participant '" + p.id
                            + "' has no cancel method (may use builder handler)");
                }
            }
        }
    }
}
