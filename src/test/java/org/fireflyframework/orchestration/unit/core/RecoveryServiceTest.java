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

import org.fireflyframework.orchestration.config.OrchestrationProperties;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.recovery.RecoveryService;
import org.fireflyframework.orchestration.core.scheduling.OrchestrationScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class RecoveryServiceTest {

    private InMemoryPersistenceProvider persistence;
    private RecoveryService recoveryService;
    private OrchestrationScheduler scheduler;

    @BeforeEach
    void setUp() {
        persistence = new InMemoryPersistenceProvider();
        var events = new OrchestrationEvents() {};
        recoveryService = new RecoveryService(persistence, events, Duration.ofMinutes(30));
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void findStaleExecutions_detectsOldRunningExecutions() {
        // Create a RUNNING execution with an old updatedAt timestamp
        ExecutionState staleState = new ExecutionState(
                "stale-1", "my-saga", ExecutionPattern.SAGA,
                ExecutionStatus.RUNNING, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Set.of(), List.of(), null,
                Instant.now().minus(Duration.ofHours(2)),
                Instant.now().minus(Duration.ofHours(1))); // Updated 1 hour ago

        // Create a fresh RUNNING execution
        ExecutionState freshState = new ExecutionState(
                "fresh-1", "my-saga", ExecutionPattern.SAGA,
                ExecutionStatus.RUNNING, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Set.of(), List.of(), null,
                Instant.now(), Instant.now()); // Updated just now

        StepVerifier.create(
                persistence.save(staleState)
                        .then(persistence.save(freshState))
                        .thenMany(recoveryService.findStaleExecutions())
                        .collectList())
                .assertNext(staleList -> {
                    assertThat(staleList).hasSize(1);
                    assertThat(staleList.get(0).correlationId()).isEqualTo("stale-1");
                })
                .verifyComplete();
    }

    @Test
    void findStaleExecutions_ignoresCompletedExecutions() {
        ExecutionState completedState = new ExecutionState(
                "completed-1", "my-saga", ExecutionPattern.SAGA,
                ExecutionStatus.COMPLETED, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Set.of(), List.of(), null,
                Instant.now().minus(Duration.ofHours(2)),
                Instant.now().minus(Duration.ofHours(1)));

        StepVerifier.create(
                persistence.save(completedState)
                        .thenMany(recoveryService.findStaleExecutions())
                        .collectList())
                .assertNext(list -> assertThat(list).isEmpty())
                .verifyComplete();
    }

    @Test
    void cleanupCompletedExecutions_removesOldTerminalEntries() {
        // Old completed execution
        ExecutionState oldCompleted = new ExecutionState(
                "old-1", "my-wf", ExecutionPattern.WORKFLOW,
                ExecutionStatus.COMPLETED, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Set.of(), List.of(), null,
                Instant.now().minus(Duration.ofDays(10)),
                Instant.now().minus(Duration.ofDays(10)));

        // Recent completed execution
        ExecutionState recentCompleted = new ExecutionState(
                "recent-1", "my-wf", ExecutionPattern.WORKFLOW,
                ExecutionStatus.COMPLETED, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Set.of(), List.of(), null,
                Instant.now(), Instant.now());

        StepVerifier.create(
                persistence.save(oldCompleted)
                        .then(persistence.save(recentCompleted))
                        .then(recoveryService.cleanupCompletedExecutions(Duration.ofDays(7))))
                .assertNext(count -> assertThat(count).isEqualTo(1L))
                .verifyComplete();

        // Verify only the recent one remains
        assertThat(persistence.size()).isEqualTo(1);
    }

    @Test
    void constructor_invalidThreshold_throws() {
        assertThatThrownBy(() -> new RecoveryService(
                persistence, new OrchestrationEvents() {}, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        assertThatThrownBy(() -> new RecoveryService(
                persistence, new OrchestrationEvents() {}, Duration.ofMinutes(-5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void recoverySchedulingInitializer_registersCleanupTask() {
        scheduler = new OrchestrationScheduler(1);
        var properties = new OrchestrationProperties();

        assertThat(scheduler.activeTaskCount()).isZero();

        // Simulate what the SmartInitializingSingleton bean does
        Duration interval = properties.getPersistence().getCleanupInterval();
        Duration retention = properties.getPersistence().getRetentionPeriod();
        scheduler.scheduleAtFixedRate("recovery:cleanup",
                () -> recoveryService.cleanupCompletedExecutions(retention).subscribe(),
                interval.toMillis(), interval.toMillis());

        assertThat(scheduler.activeTaskCount()).isEqualTo(1);
    }
}
