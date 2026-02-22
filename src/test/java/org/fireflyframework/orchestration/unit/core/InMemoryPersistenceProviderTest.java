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

import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class InMemoryPersistenceProviderTest {

    private InMemoryPersistenceProvider provider;

    @BeforeEach
    void setUp() { provider = new InMemoryPersistenceProvider(); }

    private ExecutionState createState(String id, ExecutionPattern pattern, ExecutionStatus status) {
        return new ExecutionState(id, "test-" + id, pattern, status,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Set.of(), List.of(), null, Instant.now(), Instant.now(), Optional.empty());
    }

    @Test
    void save_and_findById() {
        var state = createState("1", ExecutionPattern.SAGA, ExecutionStatus.RUNNING);
        StepVerifier.create(provider.save(state).then(provider.findById("1")))
                .assertNext(opt -> assertThat(opt).isPresent().hasValueSatisfying(s -> assertThat(s.correlationId()).isEqualTo("1")))
                .verifyComplete();
    }

    @Test
    void findById_nonExistent_returnsEmpty() {
        StepVerifier.create(provider.findById("nonexistent"))
                .assertNext(opt -> assertThat(opt).isEmpty())
                .verifyComplete();
    }

    @Test
    void updateStatus_updatesCorrectly() {
        var state = createState("1", ExecutionPattern.WORKFLOW, ExecutionStatus.RUNNING);
        StepVerifier.create(provider.save(state)
                .then(provider.updateStatus("1", ExecutionStatus.COMPLETED))
                .then(provider.findById("1")))
                .assertNext(opt -> assertThat(opt.get().status()).isEqualTo(ExecutionStatus.COMPLETED))
                .verifyComplete();
    }

    @Test
    void findByPattern_filtersCorrectly() {
        StepVerifier.create(
                provider.save(createState("1", ExecutionPattern.SAGA, ExecutionStatus.RUNNING))
                .then(provider.save(createState("2", ExecutionPattern.WORKFLOW, ExecutionStatus.RUNNING)))
                .then(provider.save(createState("3", ExecutionPattern.SAGA, ExecutionStatus.COMPLETED)))
                .thenMany(provider.findByPattern(ExecutionPattern.SAGA))
                .collectList())
                .assertNext(list -> assertThat(list).hasSize(2))
                .verifyComplete();
    }

    @Test
    void findInFlight_returnsActiveOnly() {
        StepVerifier.create(
                provider.save(createState("1", ExecutionPattern.SAGA, ExecutionStatus.RUNNING))
                .then(provider.save(createState("2", ExecutionPattern.SAGA, ExecutionStatus.COMPLETED)))
                .thenMany(provider.findInFlight())
                .collectList())
                .assertNext(list -> assertThat(list).hasSize(1))
                .verifyComplete();
    }

    @Test
    void cleanup_removesOldTerminalEntries() {
        var oldState = new ExecutionState("old", "test", ExecutionPattern.SAGA, ExecutionStatus.COMPLETED,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), List.of(),
                null, Instant.now().minus(Duration.ofHours(2)), Instant.now().minus(Duration.ofHours(2)),
                Optional.empty());
        var recentState = createState("recent", ExecutionPattern.SAGA, ExecutionStatus.COMPLETED);

        StepVerifier.create(
                provider.save(oldState)
                .then(provider.save(recentState))
                .then(provider.cleanup(Duration.ofHours(1))))
                .assertNext(count -> assertThat(count).isEqualTo(1))
                .verifyComplete();
        assertThat(provider.size()).isEqualTo(1);
    }

    @Test
    void isHealthy_returnsTrue() {
        StepVerifier.create(provider.isHealthy())
                .expectNext(true)
                .verifyComplete();
    }
}
