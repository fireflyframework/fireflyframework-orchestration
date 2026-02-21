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
import org.fireflyframework.orchestration.core.event.OrchestrationEvent;
import org.fireflyframework.orchestration.core.event.OrchestrationEventPublisher;
import org.fireflyframework.orchestration.core.model.*;
import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.core.step.StepInvoker;
import org.fireflyframework.orchestration.workflow.engine.WorkflowEngine;
import org.fireflyframework.orchestration.workflow.engine.WorkflowExecutor;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

class WorkflowStepFailureEventTest {

    @SuppressWarnings("unused")
    public static class FailingBean {
        public Mono<String> failStep(@Input Map<String, Object> input) {
            return Mono.error(new RuntimeException("step blew up"));
        }
    }

    @Test
    void stepFailurePublishesEventViaPublisher() throws Exception {
        var publishedEvents = new CopyOnWriteArrayList<OrchestrationEvent>();
        OrchestrationEventPublisher trackingPublisher = event -> {
            publishedEvents.add(event);
            return Mono.empty();
        };

        var events = new OrchestrationEvents() {};
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, trackingPublisher, null, null);
        var registry = new WorkflowRegistry();
        var persistence = new InMemoryPersistenceProvider();
        var engine = new WorkflowEngine(registry, executor, new StepInvoker(new ArgumentResolver()), persistence, events, trackingPublisher);

        var bean = new FailingBean();
        var def = new WorkflowDefinition(
                "fail-event-wf", "fail-event-wf", "", "1.0",
                List.of(new WorkflowStepDefinition(
                        "failStep", "failStep", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY,
                        "", false, false, "",
                        bean, bean.getClass().getMethod("failStep", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT,
                null, null, null, null,
                true, 0);  // publishEvents=true, layerConcurrency=0
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("fail-event-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED);

                    var stepFailedEvents = publishedEvents.stream()
                            .filter(e -> "STEP_FAILED".equals(e.eventType()))
                            .toList();
                    assertThat(stepFailedEvents).hasSize(1);
                    assertThat(stepFailedEvents.get(0).stepId()).isEqualTo("failStep");
                })
                .verifyComplete();
    }

    @Test
    void stepFailureDoesNotPublishWhenPublishEventsDisabled() throws Exception {
        var publishedEvents = new CopyOnWriteArrayList<OrchestrationEvent>();
        OrchestrationEventPublisher trackingPublisher = event -> {
            publishedEvents.add(event);
            return Mono.empty();
        };

        var events = new OrchestrationEvents() {};
        var executor = new WorkflowExecutor(new StepInvoker(new ArgumentResolver()), events, trackingPublisher, null, null);
        var registry = new WorkflowRegistry();
        var persistence = new InMemoryPersistenceProvider();
        var engine = new WorkflowEngine(registry, executor, new StepInvoker(new ArgumentResolver()), persistence, events, trackingPublisher);

        var bean = new FailingBean();
        var def = new WorkflowDefinition(
                "no-event-wf", "no-event-wf", "", "1.0",
                List.of(new WorkflowStepDefinition(
                        "failStep", "failStep", "", List.of(), 0,
                        StepTriggerMode.BOTH, "", "", 5000, RetryPolicy.NO_RETRY,
                        "", false, false, "",
                        bean, bean.getClass().getMethod("failStep", Map.class),
                        null, 0, 0, null)),
                TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT,
                null, null, null, null,
                false, 0);  // publishEvents=false
        registry.register(def);

        StepVerifier.create(engine.startWorkflow("no-event-wf", Map.of()))
                .assertNext(state -> {
                    assertThat(state.status()).isEqualTo(ExecutionStatus.FAILED);

                    var stepFailedEvents = publishedEvents.stream()
                            .filter(e -> "STEP_FAILED".equals(e.eventType()))
                            .toList();
                    assertThat(stepFailedEvents).isEmpty();
                })
                .verifyComplete();
    }
}
