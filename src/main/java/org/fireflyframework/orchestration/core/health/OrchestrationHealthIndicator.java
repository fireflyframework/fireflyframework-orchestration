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

package org.fireflyframework.orchestration.core.health;

import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import reactor.core.publisher.Mono;

public class OrchestrationHealthIndicator implements ReactiveHealthIndicator {

    private final ExecutionPersistenceProvider persistence;

    public OrchestrationHealthIndicator(ExecutionPersistenceProvider persistence) {
        this.persistence = persistence;
    }

    @Override
    public Mono<Health> health() {
        return persistence.isHealthy()
                .map(healthy -> healthy ? Health.up().build() : Health.down().withDetail("reason", "Persistence unhealthy").build())
                .onErrorResume(e -> Mono.just(Health.down().withException(e).build()));
    }
}
