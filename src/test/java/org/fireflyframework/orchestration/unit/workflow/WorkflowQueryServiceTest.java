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

package org.fireflyframework.orchestration.unit.workflow;

import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.workflow.query.WorkflowQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class WorkflowQueryServiceTest {

    private WorkflowQueryService queryService;
    private InMemoryPersistenceProvider persistence;

    @BeforeEach
    void setUp() {
        persistence = new InMemoryPersistenceProvider();
        queryService = new WorkflowQueryService(persistence);

        var state = new ExecutionState("wf-1", "order-wf", ExecutionPattern.WORKFLOW,
                ExecutionStatus.RUNNING,
                Map.of("validate", "ok", "process", "done"),
                Map.of("validate", StepStatus.DONE, "process", StepStatus.RUNNING),
                Map.of(), Map.of(),
                Map.of("orderId", "ORD-123"),
                Map.of("x-trace", "abc"),
                Set.of(), List.of(List.of("validate"), List.of("process")),
                null, Instant.now(), Instant.now(), Optional.empty());
        persistence.save(state).block();
    }

    @Test
    void getStatus_returnsCurrentStatus() {
        StepVerifier.create(queryService.getStatus("wf-1"))
                .assertNext(opt -> assertThat(opt).contains(ExecutionStatus.RUNNING))
                .verifyComplete();
    }

    @Test
    void getCurrentSteps_returnsRunningSteps() {
        StepVerifier.create(queryService.getCurrentSteps("wf-1"))
                .assertNext(opt -> {
                    assertThat(opt).isPresent();
                    assertThat(opt.get()).containsExactly("process");
                })
                .verifyComplete();
    }

    @Test
    void getStepResults_returnsAllResults() {
        StepVerifier.create(queryService.getStepResults("wf-1"))
                .assertNext(opt -> {
                    assertThat(opt).isPresent();
                    assertThat(opt.get()).containsKeys("validate", "process");
                })
                .verifyComplete();
    }

    @Test
    void getVariable_returnsSingleVariable() {
        StepVerifier.create(queryService.getVariable("wf-1", "orderId"))
                .assertNext(opt -> assertThat(opt).contains("ORD-123"))
                .verifyComplete();
    }

    @Test
    void getTopologyLayers_returnsLayers() {
        StepVerifier.create(queryService.getTopologyLayers("wf-1"))
                .assertNext(opt -> {
                    assertThat(opt).isPresent();
                    assertThat(opt.get()).hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    void query_missingInstance_returnsEmpty() {
        StepVerifier.create(queryService.getStatus("nonexistent"))
                .assertNext(opt -> assertThat(opt).isEmpty())
                .verifyComplete();
    }
}
