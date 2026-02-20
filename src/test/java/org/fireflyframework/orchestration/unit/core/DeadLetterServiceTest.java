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

import org.fireflyframework.orchestration.core.dlq.*;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class DeadLetterServiceTest {

    private InMemoryDeadLetterStore store;
    private DeadLetterService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryDeadLetterStore();
        service = new DeadLetterService(store, new OrchestrationEvents() {});
    }

    @Test
    void deadLetter_savesEntry() {
        var entry = DeadLetterEntry.create("saga1", "corr-1", ExecutionPattern.SAGA,
                "step1", ExecutionStatus.FAILED, new RuntimeException("boom"), Map.of());

        StepVerifier.create(service.deadLetter(entry).then(service.count()))
                .assertNext(count -> assertThat(count).isEqualTo(1))
                .verifyComplete();
    }

    @Test
    void getEntry_findsById() {
        var entry = DeadLetterEntry.create("wf1", "corr-2", ExecutionPattern.WORKFLOW,
                "step1", ExecutionStatus.FAILED, new RuntimeException("err"), Map.of());

        StepVerifier.create(service.deadLetter(entry).then(service.getEntry(entry.id())))
                .assertNext(opt -> assertThat(opt).isPresent())
                .verifyComplete();
    }

    @Test
    void markRetried_incrementsCount() {
        var entry = DeadLetterEntry.create("tcc1", "corr-3", ExecutionPattern.TCC,
                "p1", ExecutionStatus.FAILED, new RuntimeException("phase err"), Map.of());

        StepVerifier.create(service.deadLetter(entry).then(service.markRetried(entry.id())))
                .assertNext(updated -> assertThat(updated.retryCount()).isEqualTo(1))
                .verifyComplete();
    }

    @Test
    void deleteEntry_removesFromStore() {
        var entry = DeadLetterEntry.create("saga1", "corr-4", ExecutionPattern.SAGA,
                "step1", ExecutionStatus.FAILED, new RuntimeException("err"), Map.of());

        StepVerifier.create(service.deadLetter(entry).then(service.deleteEntry(entry.id())).then(service.count()))
                .assertNext(count -> assertThat(count).isEqualTo(0))
                .verifyComplete();
    }

    @Test
    void markRetried_nonexistentId_throwsError() {
        StepVerifier.create(service.markRetried("does-not-exist"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void getByExecutionName_filtersCorrectly() {
        var e1 = DeadLetterEntry.create("saga1", "c1", ExecutionPattern.SAGA, "s1", ExecutionStatus.FAILED, new RuntimeException("err"), Map.of());
        var e2 = DeadLetterEntry.create("saga2", "c2", ExecutionPattern.SAGA, "s1", ExecutionStatus.FAILED, new RuntimeException("err"), Map.of());

        StepVerifier.create(service.deadLetter(e1).then(service.deadLetter(e2))
                .thenMany(service.getByExecutionName("saga1")).collectList())
                .assertNext(list -> assertThat(list).hasSize(1))
                .verifyComplete();
    }
}
