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

package org.fireflyframework.orchestration.saga.composition;

import org.fireflyframework.orchestration.core.validation.ValidationIssue;
import org.fireflyframework.orchestration.saga.registry.SagaRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Validates a {@link SagaComposition} definition for structural correctness:
 * <ul>
 *   <li>No duplicate aliases</li>
 *   <li>All dependencies reference existing aliases</li>
 *   <li>No circular dependencies</li>
 *   <li>All data mappings reference known aliases</li>
 *   <li>All saga names exist in the registry (if registry is provided)</li>
 * </ul>
 */
@Slf4j
public class CompositionValidator {

    private final SagaRegistry sagaRegistry;

    public CompositionValidator(SagaRegistry sagaRegistry) {
        this.sagaRegistry = sagaRegistry;
    }

    /**
     * Validates the composition and returns a list of validation issues.
     * An empty list indicates a valid composition.
     */
    public List<ValidationIssue> validate(SagaComposition composition) {
        List<ValidationIssue> issues = new ArrayList<>();
        String prefix = "composition." + composition.name();

        if (composition.sagas().isEmpty()) {
            issues.add(new ValidationIssue(ValidationIssue.Severity.ERROR,
                    "Composition has no sagas", prefix));
            return issues;
        }

        // Check for duplicate aliases
        Set<String> seenAliases = new HashSet<>();
        for (var saga : composition.sagas()) {
            if (!seenAliases.add(saga.alias())) {
                issues.add(new ValidationIssue(ValidationIssue.Severity.ERROR,
                        "Duplicate alias '" + saga.alias() + "'",
                        prefix + ".saga." + saga.alias()));
            }
        }

        Set<String> knownAliases = composition.sagaMap().keySet();

        // Check all dependencies reference existing aliases
        for (var saga : composition.sagas()) {
            for (String dep : saga.dependsOn()) {
                if (!knownAliases.contains(dep)) {
                    issues.add(new ValidationIssue(ValidationIssue.Severity.ERROR,
                            "Depends on unknown alias '" + dep + "'",
                            prefix + ".saga." + saga.alias()));
                }
            }
        }

        // Check data mappings reference known aliases
        for (var mapping : composition.dataMappings()) {
            if (!knownAliases.contains(mapping.sourceSaga())) {
                issues.add(new ValidationIssue(ValidationIssue.Severity.ERROR,
                        "Data mapping source '" + mapping.sourceSaga() + "' is not a known saga alias",
                        prefix + ".dataMapping"));
            }
            if (!knownAliases.contains(mapping.targetSaga())) {
                issues.add(new ValidationIssue(ValidationIssue.Severity.ERROR,
                        "Data mapping target '" + mapping.targetSaga() + "' is not a known saga alias",
                        prefix + ".dataMapping"));
            }
        }

        // Check no circular dependencies
        if (issues.stream().noneMatch(i -> i.severity() == ValidationIssue.Severity.ERROR)) {
            try {
                composition.getExecutableLayers();
            } catch (Exception e) {
                issues.add(new ValidationIssue(ValidationIssue.Severity.ERROR,
                        "Circular dependency detected: " + e.getMessage(), prefix));
            }
        }

        // Check saga names exist in registry
        if (sagaRegistry != null) {
            for (var saga : composition.sagas()) {
                if (!sagaRegistry.hasSaga(saga.sagaName())) {
                    issues.add(new ValidationIssue(ValidationIssue.Severity.WARNING,
                            "Saga name '" + saga.sagaName() + "' (alias: '" + saga.alias()
                                    + "') not found in registry",
                            prefix + ".saga." + saga.alias()));
                }
            }
        }

        if (!issues.isEmpty()) {
            log.warn("[saga-composition] Validation found {} issues for '{}': {}",
                    issues.size(), composition.name(), issues);
        }

        return issues;
    }

    /**
     * Validates the composition and throws if any ERROR-level issues are found.
     */
    public void validateAndThrow(SagaComposition composition) {
        List<ValidationIssue> issues = validate(composition);
        List<ValidationIssue> errors = issues.stream()
                .filter(i -> i.severity() == ValidationIssue.Severity.ERROR)
                .toList();
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Saga composition '" + composition.name()
                    + "' validation failed: " + errors);
        }
    }
}
