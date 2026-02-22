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

package org.fireflyframework.orchestration.tcc.composition;

import org.fireflyframework.orchestration.tcc.registry.TccRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Validates a {@link TccComposition} definition for structural correctness:
 * <ul>
 *   <li>No duplicate aliases</li>
 *   <li>All dependencies reference existing aliases</li>
 *   <li>No circular dependencies</li>
 *   <li>All data mappings reference known aliases</li>
 *   <li>All TCC names exist in the registry</li>
 * </ul>
 */
@Slf4j
public class TccCompositionValidator {

    private final TccRegistry tccRegistry;

    public TccCompositionValidator(TccRegistry tccRegistry) {
        this.tccRegistry = tccRegistry;
    }

    /**
     * Validates the composition and returns a list of validation errors.
     * An empty list indicates a valid composition.
     */
    public List<String> validate(TccComposition composition) {
        List<String> errors = new ArrayList<>();

        if (composition.tccs().isEmpty()) {
            errors.add("Composition '" + composition.name() + "' has no TCCs");
            return errors;
        }

        // Check for duplicate aliases
        Set<String> seenAliases = new HashSet<>();
        for (var tcc : composition.tccs()) {
            if (!seenAliases.add(tcc.alias())) {
                errors.add("Duplicate alias '" + tcc.alias() + "' in composition '" + composition.name() + "'");
            }
        }

        Set<String> knownAliases = composition.tccMap().keySet();

        // Check all dependencies reference existing aliases
        for (var tcc : composition.tccs()) {
            for (String dep : tcc.dependsOn()) {
                if (!knownAliases.contains(dep)) {
                    errors.add("TCC '" + tcc.alias() + "' depends on unknown alias '" + dep + "'");
                }
            }
        }

        // Check data mappings reference known aliases
        for (var mapping : composition.dataMappings()) {
            if (!knownAliases.contains(mapping.sourceTcc())) {
                errors.add("Data mapping source '" + mapping.sourceTcc() + "' is not a known TCC alias");
            }
            if (!knownAliases.contains(mapping.targetTcc())) {
                errors.add("Data mapping target '" + mapping.targetTcc() + "' is not a known TCC alias");
            }
        }

        // Check no circular dependencies
        try {
            composition.getExecutableLayers();
        } catch (Exception e) {
            errors.add("Circular dependency detected: " + e.getMessage());
        }

        // Check TCC names exist in registry
        if (tccRegistry != null) {
            for (var tcc : composition.tccs()) {
                if (!tccRegistry.hasTcc(tcc.tccName())) {
                    errors.add("TCC name '" + tcc.tccName() + "' (alias: '" + tcc.alias()
                            + "') not found in registry");
                }
            }
        }

        if (!errors.isEmpty()) {
            log.warn("[tcc-composition] Validation failed for '{}': {}", composition.name(), errors);
        }

        return errors;
    }

    /**
     * Validates the composition and throws if invalid.
     */
    public void validateAndThrow(TccComposition composition) {
        List<String> errors = validate(composition);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("TCC composition '" + composition.name()
                    + "' validation failed: " + String.join("; ", errors));
        }
    }
}
