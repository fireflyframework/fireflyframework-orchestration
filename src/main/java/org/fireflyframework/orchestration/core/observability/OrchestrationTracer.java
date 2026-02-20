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

package org.fireflyframework.orchestration.core.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import reactor.core.publisher.Mono;

public class OrchestrationTracer {
    private final ObservationRegistry observationRegistry;

    public OrchestrationTracer(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    public <T> Mono<T> traceExecution(String name, ExecutionPattern pattern, String correlationId, Mono<T> mono) {
        return Mono.defer(() -> {
            Observation observation = Observation.createNotStarted("orchestration.execution", observationRegistry)
                    .lowCardinalityKeyValue("orchestration.name", name)
                    .lowCardinalityKeyValue("orchestration.pattern", pattern.name())
                    .highCardinalityKeyValue("orchestration.correlationId", correlationId);
            return mono.doOnSubscribe(s -> observation.start())
                       .doOnTerminate(observation::stop)
                       .doOnError(observation::error);
        });
    }

    public <T> Mono<T> traceStep(String executionName, String stepId, String correlationId, Mono<T> mono) {
        return Mono.defer(() -> {
            Observation observation = Observation.createNotStarted("orchestration.step", observationRegistry)
                    .lowCardinalityKeyValue("orchestration.name", executionName)
                    .lowCardinalityKeyValue("orchestration.step", stepId)
                    .highCardinalityKeyValue("orchestration.correlationId", correlationId);
            return mono.doOnSubscribe(s -> observation.start())
                       .doOnTerminate(observation::stop)
                       .doOnError(observation::error);
        });
    }
}
