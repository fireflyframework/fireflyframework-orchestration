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
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.workflow.signal.SignalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class SignalServiceTest {

    private SignalService signalService;
    private InMemoryPersistenceProvider persistence;

    @BeforeEach
    void setUp() {
        persistence = new InMemoryPersistenceProvider();
        signalService = new SignalService(persistence, new OrchestrationEvents() {});
    }

    private void saveRunningWorkflow(String correlationId) {
        var state = new ExecutionState(correlationId, "test-wf", ExecutionPattern.WORKFLOW,
                ExecutionStatus.RUNNING, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Set.of(), List.of(), null, Instant.now(), Instant.now());
        persistence.save(state).block();
    }

    @Test
    void signal_deliveredToRunningInstance() {
        saveRunningWorkflow("wf-1");

        StepVerifier.create(signalService.signal("wf-1", "approval", "approved"))
                .assertNext(result -> {
                    assertThat(result.delivered()).isTrue();
                    assertThat(result.signalName()).isEqualTo("approval");
                })
                .verifyComplete();
    }

    @Test
    void signal_notDeliveredToMissingInstance() {
        StepVerifier.create(signalService.signal("nonexistent", "sig", null))
                .assertNext(result -> assertThat(result.delivered()).isFalse())
                .verifyComplete();
    }

    @Test
    void signal_notDeliveredToTerminalInstance() {
        var state = new ExecutionState("wf-done", "test-wf", ExecutionPattern.WORKFLOW,
                ExecutionStatus.COMPLETED, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Set.of(), List.of(), null, Instant.now(), Instant.now());
        persistence.save(state).block();

        StepVerifier.create(signalService.signal("wf-done", "sig", null))
                .assertNext(result -> assertThat(result.delivered()).isFalse())
                .verifyComplete();
    }

    @Test
    void waitForSignal_receivesBufferedSignal() {
        saveRunningWorkflow("wf-2");
        // Buffer a signal first
        signalService.signal("wf-2", "ready", "go").block();

        // Wait should immediately resolve from buffer
        StepVerifier.create(signalService.waitForSignal("wf-2", "ready"))
                .assertNext(payload -> assertThat(payload).isEqualTo("go"))
                .verifyComplete();
    }

    @Test
    void waitForSignal_resumedByLaterSignal() {
        saveRunningWorkflow("wf-3");

        // Start waiting (no signal buffered yet)
        var waiting = signalService.waitForSignal("wf-3", "approval");

        // Deliver the signal (should resume the waiter)
        StepVerifier.create(
                signalService.signal("wf-3", "approval", "approved")
                        .then(waiting))
                .assertNext(payload -> assertThat(payload).isEqualTo("approved"))
                .verifyComplete();
    }

    @Test
    void cleanup_removesSignalState() {
        saveRunningWorkflow("wf-4");
        signalService.signal("wf-4", "buffered", "data").block();

        assertThat(signalService.getPendingSignals("wf-4")).isNotEmpty();

        signalService.cleanup("wf-4");
        assertThat(signalService.getPendingSignals("wf-4")).isEmpty();
    }
}
