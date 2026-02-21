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

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.argument.Input;
import org.fireflyframework.orchestration.core.event.NoOpEventPublisher;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.StepTriggerMode;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import org.fireflyframework.orchestration.workflow.engine.WorkflowExecutor;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class AsyncTriggerModeTest {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;
    private InMemoryPersistenceProvider persistence;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
        var events = new OrchestrationEvents() {};
        var noOpPublisher = new NoOpEventPublisher();
        var executor = new WorkflowExecutor(new ArgumentResolver(), events, noOpPublisher, null, null);
        persistence = new InMemoryPersistenceProvider();
        engine = new WorkflowEngine(registry, executor, persistence, events, noOpPublisher);
    }

    @SuppressWarnings("unused")
    public static class SlowSteps {
        private final CountDownLatch latch;
        private final AtomicBoolean executed;

        public SlowSteps(CountDownLatch latch, AtomicBoolean executed) {
            this.latch = latch;
            this.executed = executed;
        }

        public Mono<String> slowStep(@Input Map<String, Object> input) {
            return Mono.delay(Duration.ofMillis(200))
                    .doOnNext(v -> {
                        executed.set(true);
                        latch.countDown();
                    })
                    .thenReturn("slow-result");
        }
    }

    @SuppressWarnings("unused")
    public static class FastSteps {
        public Mono<String> step1(@Input Map<String, Object> input) {
            return Mono.just("fast-result");
        }
    }

    @Test
    void asyncTriggerMode_returnsImmediately() throws Exception {
        var latch = new CountDownLatch(1);
        var executed = new AtomicBoolean(false);
        var slowSteps = new SlowSteps(latch, executed);

        var def = new WorkflowDefinition("async-wf", "Async Workflow", "test", "1.0",
                List.of(
                        new WorkflowStepDefinition("slow", "Slow Step", "", List.of(), 0,
                                StepTriggerMode.BOTH, "", "", 10000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                slowSteps, SlowSteps.class.getMethod("slowStep", Map.class),
                                null, 0, 0, null)
                ),
                TriggerMode.ASYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        // Start async workflow — should return RUNNING immediately
        StepVerifier.create(engine.startWorkflow("async-wf", Map.of("key", "value")))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.RUNNING);
                    assertThat(state.executionName()).isEqualTo("async-wf");
                    // Step should NOT have completed yet (it has a 200ms delay)
                    assertThat(executed.get()).isFalse();
                })
                .verifyComplete();

        // Wait for the background execution to complete
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(executed.get()).isTrue();

        // Verify the final state was persisted as COMPLETED
        // Give a small window for the background persistence to finish
        Thread.sleep(500);
        StepVerifier.create(persistence.findByStatus(ExecutionStatus.COMPLETED).collectList())
                .assertNext(states -> {
                    assertThat(states).anySatisfy(s -> {
                        assertThat(s.executionName()).isEqualTo("async-wf");
                        assertThat(s.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    });
                })
                .verifyComplete();
    }

    @Test
    void syncTriggerMode_blocksUntilComplete() throws Exception {
        var fastSteps = new FastSteps();

        var def = new WorkflowDefinition("sync-wf", "Sync Workflow", "test", "1.0",
                List.of(
                        new WorkflowStepDefinition("step1", "Step 1", "", List.of(), 0,
                                StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY, "",
                                false, false, "",
                                fastSteps, FastSteps.class.getMethod("step1", Map.class),
                                null, 0, 0, null)
                ),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT, null, null, null, null);
        registry.register(def);

        // SYNC workflow should complete before returning
        StepVerifier.create(engine.startWorkflow("sync-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
                    assertThat(state.executionName()).isEqualTo("sync-wf");
                    assertThat(state.stepResults()).containsKey("step1");
                })
                .verifyComplete();
    }
}
