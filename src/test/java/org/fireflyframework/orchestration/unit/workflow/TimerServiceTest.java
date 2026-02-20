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

import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import org.fireflyframework.orchestration.workflow.timer.TimerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class TimerServiceTest {

    private TimerService timerService;

    @BeforeEach
    void setUp() {
        timerService = new TimerService(new OrchestrationEvents() {});
    }

    @Test
    void schedule_createsTimerEntry() {
        StepVerifier.create(timerService.schedule("wf-1", "reminder", Duration.ofMinutes(5), "data"))
                .assertNext(entry -> {
                    assertThat(entry.correlationId()).isEqualTo("wf-1");
                    assertThat(entry.timerId()).isEqualTo("reminder");
                    assertThat(entry.data()).isEqualTo("data");
                    assertThat(entry.fireAt()).isAfter(Instant.now());
                })
                .verifyComplete();

        assertThat(timerService.getPendingTimers("wf-1")).hasSize(1);
    }

    @Test
    void scheduleAt_createsTimerAtSpecificInstant() {
        Instant fireAt = Instant.now().plusSeconds(300);
        StepVerifier.create(timerService.scheduleAt("wf-1", "t1", fireAt, null))
                .assertNext(entry -> assertThat(entry.fireAt()).isEqualTo(fireAt))
                .verifyComplete();
    }

    @Test
    void cancel_removesTimer() {
        timerService.schedule("wf-1", "t1", Duration.ofMinutes(5), null).block();
        assertThat(timerService.getPendingTimers("wf-1")).hasSize(1);

        StepVerifier.create(timerService.cancel("wf-1", "t1"))
                .assertNext(removed -> assertThat(removed).isTrue())
                .verifyComplete();

        assertThat(timerService.getPendingTimers("wf-1")).isEmpty();
    }

    @Test
    void getReadyTimers_returnsOnlyFiredTimers() {
        // Schedule a timer in the past (already fired)
        timerService.scheduleAt("wf-1", "past", Instant.now().minusSeconds(1), "fired").block();
        // Schedule a timer in the future (not yet fired)
        timerService.scheduleAt("wf-1", "future", Instant.now().plusSeconds(3600), "waiting").block();

        StepVerifier.create(timerService.getReadyTimers("wf-1"))
                .assertNext(entry -> {
                    assertThat(entry.timerId()).isEqualTo("past");
                    assertThat(entry.data()).isEqualTo("fired");
                })
                .verifyComplete();

        // past timer removed, future timer still pending
        assertThat(timerService.getPendingTimers("wf-1")).hasSize(1);
        assertThat(timerService.getPendingTimers("wf-1").getFirst().timerId()).isEqualTo("future");
    }

    @Test
    void cleanup_removesAllTimers() {
        timerService.schedule("wf-1", "t1", Duration.ofMinutes(1), null).block();
        timerService.schedule("wf-1", "t2", Duration.ofMinutes(2), null).block();

        timerService.cleanup("wf-1");
        assertThat(timerService.getPendingTimers("wf-1")).isEmpty();
    }
}
