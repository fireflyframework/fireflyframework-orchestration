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

import org.fireflyframework.orchestration.saga.engine.SagaResult;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves {@link SagaComposition.DataMapping} entries by extracting values from upstream
 * saga step results and injecting them into downstream saga inputs.
 */
@Slf4j
public class CompositionDataFlowManager {

    /**
     * Builds the input map for a saga by merging global input, static input, and
     * data flow mappings from upstream saga results.
     *
     * @param saga        the target composition saga entry
     * @param ctx         the composition context containing upstream results
     * @param globalInput the global input provided to the composition
     * @return the merged input map for the saga
     */
    public Map<String, Object> resolveInputs(SagaComposition.CompositionSaga saga,
                                              CompositionContext ctx,
                                              Map<String, Object> globalInput) {
        return resolveInputs(null, saga, ctx, globalInput);
    }

    /**
     * Builds the input map for a saga by merging global input, static input, and
     * data flow mappings from upstream saga results.
     *
     * @param composition the saga composition definition (for data mapping lookup)
     * @param saga        the target composition saga entry
     * @param ctx         the composition context containing upstream results
     * @param globalInput the global input provided to the composition
     * @return the merged input map for the saga
     */
    public Map<String, Object> resolveInputs(SagaComposition composition,
                                              SagaComposition.CompositionSaga saga,
                                              CompositionContext ctx,
                                              Map<String, Object> globalInput) {
        Map<String, Object> input = new LinkedHashMap<>(globalInput);
        input.putAll(saga.staticInput());

        if (composition == null) {
            return input;
        }

        for (SagaComposition.DataMapping mapping : composition.dataMappings()) {
            if (mapping.targetSaga().equals(saga.alias())) {
                SagaResult sourceResult = ctx.getResult(mapping.sourceSaga());
                if (sourceResult != null) {
                    Object value = extractField(sourceResult, mapping.sourceField());
                    if (value != null) {
                        input.put(mapping.targetField(), value);
                        log.debug("[saga-composition] Data flow: {}.{} -> {}.{} = {}",
                                mapping.sourceSaga(), mapping.sourceField(),
                                mapping.targetSaga(), mapping.targetField(), value);
                    }
                }
            }
        }

        return input;
    }

    /**
     * Extracts a field value from a saga result's step results.
     * Searches step result values for the given field name.
     */
    private Object extractField(SagaResult result, String fieldName) {
        Map<String, Object> stepResults = result.stepResults();

        // Check if any step result is a Map containing the field
        for (var entry : stepResults.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> map) {
                Object value = map.get(fieldName);
                if (value != null) return value;
            }
        }

        // Fall back: check if the fieldName matches a step ID's result directly
        Object direct = stepResults.get(fieldName);
        if (direct != null) return direct;

        return null;
    }
}
