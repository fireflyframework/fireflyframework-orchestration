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

import java.util.Map;

/**
 * Result of executing a TCC composition.
 */
public record TccCompositionResult(
        String compositionName,
        String correlationId,
        boolean success,
        Map<String, TccResult> tccResults,
        Throwable error
) {
    public static TccCompositionResult success(String name, String correlationId,
                                                Map<String, TccResult> results) {
        return new TccCompositionResult(name, correlationId, true, results, null);
    }

    public static TccCompositionResult failure(String name, String correlationId,
                                                Map<String, TccResult> results, Throwable error) {
        return new TccCompositionResult(name, correlationId, false, results, error);
    }
}
