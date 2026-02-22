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

import org.fireflyframework.orchestration.tcc.engine.TccResult;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves {@link TccComposition.DataMapping} entries by extracting values from upstream
 * TCC participant outcomes and injecting them into downstream TCC inputs.
 */
@Slf4j
public class TccCompositionDataFlowManager {

    /**
     * Builds the input map for a TCC by merging global input, static input, and
     * data flow mappings from upstream TCC results.
     *
     * @param composition the TCC composition definition
     * @param tcc         the target TCC entry
     * @param globalInput the global input provided to the composition
     * @param context     the composition context containing upstream results
     * @return the merged input map for the TCC
     */
    public Map<String, Object> resolveInputs(TccComposition composition,
                                              TccComposition.CompositionTcc tcc,
                                              Map<String, Object> globalInput,
                                              TccCompositionContext context) {
        Map<String, Object> input = new LinkedHashMap<>(globalInput);
        input.putAll(tcc.staticInput());

        for (TccComposition.DataMapping mapping : composition.dataMappings()) {
            if (mapping.targetTcc().equals(tcc.alias())) {
                TccResult sourceResult = context.getTccResult(mapping.sourceTcc());
                if (sourceResult != null) {
                    Object value = extractField(sourceResult, mapping.sourceField());
                    if (value != null) {
                        input.put(mapping.targetField(), value);
                        log.debug("[tcc-composition] Data flow: {}.{} -> {}.{} = {}",
                                mapping.sourceTcc(), mapping.sourceField(),
                                mapping.targetTcc(), mapping.targetField(), value);
                    }
                }
            }
        }

        return input;
    }

    /**
     * Extracts a field value from a TCC result's participant outcomes.
     * Searches participant tryResult values for the given field name.
     */
    private Object extractField(TccResult result, String fieldName) {
        // First check if any participant outcome has a tryResult that is a Map
        for (var entry : result.participants().entrySet()) {
            TccResult.ParticipantOutcome outcome = entry.getValue();
            if (outcome.tryResult() instanceof Map<?, ?> map) {
                Object value = map.get(fieldName);
                if (value != null) return value;
            }
        }

        // Fall back: check if the fieldName matches a participant ID's tryResult directly
        for (var entry : result.participants().entrySet()) {
            if (entry.getKey().equals(fieldName)) {
                return entry.getValue().tryResult();
            }
        }

        return null;
    }
}
