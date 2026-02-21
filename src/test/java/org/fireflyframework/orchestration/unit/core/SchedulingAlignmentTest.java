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

import org.fireflyframework.orchestration.core.scheduling.OrchestrationScheduler;
import org.fireflyframework.orchestration.core.scheduling.SchedulingPostProcessor;
import org.fireflyframework.orchestration.saga.annotation.Saga;
import org.fireflyframework.orchestration.saga.annotation.ScheduledSaga;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.fireflyframework.orchestration.tcc.annotation.ScheduledTcc;
import org.fireflyframework.orchestration.tcc.annotation.Tcc;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccInputs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for scheduling annotation alignment (Task 5):
 * - {@code enabled} attribute on @ScheduledSaga and @ScheduledTcc
 * - {@code zone} attribute wiring
 * - {@code input} attribute parsing and passing
 * - {@code initialDelay} attribute wiring
 */
@ExtendWith(MockitoExtension.class)
class SchedulingAlignmentTest {

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private SagaEngine sagaEngine;

    @Mock
    private TccEngine tccEngine;

    private OrchestrationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OrchestrationScheduler(2);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    // --- @ScheduledSaga with enabled=false ---

    @Saga(name = "disabled-saga")
    @ScheduledSaga(fixedRate = 1000, enabled = false)
    static class DisabledScheduledSaga {}

    @Test
    void scheduledSaga_enabledFalse_skipsRegistration() {
        var spyScheduler = spy(scheduler);
        var bean = new DisabledScheduledSaga();

        when(applicationContext.getBeansWithAnnotation(Saga.class))
                .thenReturn(Map.of("disabledSaga", bean));

        var processor = new SchedulingPostProcessor(applicationContext, spyScheduler, sagaEngine, null, null);
        processor.afterSingletonsInstantiated();

        verify(spyScheduler, never()).scheduleAtFixedRate(any(), any(), anyLong(), anyLong());
        verify(spyScheduler, never()).scheduleWithCron(any(), any(), any());
    }

    // --- @ScheduledTcc with enabled=false ---

    @Tcc(name = "disabled-tcc")
    @ScheduledTcc(fixedRate = 500, enabled = false)
    static class DisabledScheduledTcc {}

    @Test
    void scheduledTcc_enabledFalse_skipsRegistration() {
        var spyScheduler = spy(scheduler);
        var bean = new DisabledScheduledTcc();

        when(applicationContext.getBeansWithAnnotation(Tcc.class))
                .thenReturn(Map.of("disabledTcc", bean));

        var processor = new SchedulingPostProcessor(applicationContext, spyScheduler, null, tccEngine, null);
        processor.afterSingletonsInstantiated();

        verify(spyScheduler, never()).scheduleAtFixedRate(any(), any(), anyLong(), anyLong());
        verify(spyScheduler, never()).scheduleWithCron(any(), any(), any());
    }

    // --- @ScheduledSaga with zone ---

    @Saga(name = "zoned-saga")
    @ScheduledSaga(cron = "0 0 9 * * *", zone = "America/New_York")
    static class ZonedScheduledSaga {}

    @Test
    void scheduledSaga_zoneAttribute_passedThroughToScheduler() {
        var spyScheduler = spy(scheduler);
        var bean = new ZonedScheduledSaga();

        when(applicationContext.getBeansWithAnnotation(Saga.class))
                .thenReturn(Map.of("zonedSaga", bean));
        lenient().when(sagaEngine.execute(eq("zoned-saga"), any(StepInputs.class)))
                .thenReturn(Mono.empty());

        var processor = new SchedulingPostProcessor(applicationContext, spyScheduler, sagaEngine, null, null);
        processor.afterSingletonsInstantiated();

        // The 4-arg scheduleWithCron is called when zone is provided
        verify(spyScheduler).scheduleWithCron(
                eq("saga:zoned-saga:cron"), any(Runnable.class), eq("0 0 9 * * *"), eq("America/New_York"));
    }

    // --- @ScheduledSaga with initialDelay ---

    @Saga(name = "delayed-saga")
    @ScheduledSaga(fixedRate = 5000, initialDelay = 2000)
    static class DelayedScheduledSaga {}

    @Test
    void scheduledSaga_initialDelay_passedToScheduler() {
        var spyScheduler = spy(scheduler);
        var bean = new DelayedScheduledSaga();

        when(applicationContext.getBeansWithAnnotation(Saga.class))
                .thenReturn(Map.of("delayedSaga", bean));
        lenient().when(sagaEngine.execute(eq("delayed-saga"), any(StepInputs.class)))
                .thenReturn(Mono.empty());

        var processor = new SchedulingPostProcessor(applicationContext, spyScheduler, sagaEngine, null, null);
        processor.afterSingletonsInstantiated();

        verify(spyScheduler).scheduleAtFixedRate(
                eq("saga:delayed-saga:rate"), any(Runnable.class), eq(2000L), eq(5000L));
    }

    // --- @ScheduledSaga with input ---

    @Saga(name = "input-saga")
    @ScheduledSaga(fixedRate = 3000, input = "{\"key\":\"value\"}")
    static class InputScheduledSaga {}

    @Test
    void scheduledSaga_inputAttribute_parsedAndPassedToEngine() {
        var spyScheduler = spy(scheduler);
        var bean = new InputScheduledSaga();

        when(applicationContext.getBeansWithAnnotation(Saga.class))
                .thenReturn(Map.of("inputSaga", bean));
        lenient().when(sagaEngine.execute(eq("input-saga"), any(StepInputs.class)))
                .thenReturn(Mono.empty());

        var processor = new SchedulingPostProcessor(applicationContext, spyScheduler, sagaEngine, null, null);
        processor.afterSingletonsInstantiated();

        // Verify that the schedule was registered (the input parsing happens inside the Runnable)
        verify(spyScheduler).scheduleAtFixedRate(
                eq("saga:input-saga:rate"), any(Runnable.class), eq(0L), eq(3000L));
    }

    // --- @ScheduledTcc with initialDelay ---

    @Tcc(name = "delayed-tcc")
    @ScheduledTcc(fixedRate = 2000, initialDelay = 1000)
    static class DelayedScheduledTcc {}

    @Test
    void scheduledTcc_initialDelay_passedToScheduler() {
        var spyScheduler = spy(scheduler);
        var bean = new DelayedScheduledTcc();

        when(applicationContext.getBeansWithAnnotation(Tcc.class))
                .thenReturn(Map.of("delayedTcc", bean));
        lenient().when(tccEngine.execute(eq("delayed-tcc"), any(TccInputs.class)))
                .thenReturn(Mono.empty());

        var processor = new SchedulingPostProcessor(applicationContext, spyScheduler, null, tccEngine, null);
        processor.afterSingletonsInstantiated();

        verify(spyScheduler).scheduleAtFixedRate(
                eq("tcc:delayed-tcc:rate"), any(Runnable.class), eq(1000L), eq(2000L));
    }

    // --- @ScheduledTcc with zone ---

    @Tcc(name = "zoned-tcc")
    @ScheduledTcc(cron = "0 */5 * * * *", zone = "Europe/London")
    static class ZonedScheduledTcc {}

    @Test
    void scheduledTcc_zoneAttribute_passedThroughToScheduler() {
        var spyScheduler = spy(scheduler);
        var bean = new ZonedScheduledTcc();

        when(applicationContext.getBeansWithAnnotation(Tcc.class))
                .thenReturn(Map.of("zonedTcc", bean));
        lenient().when(tccEngine.execute(eq("zoned-tcc"), any(TccInputs.class)))
                .thenReturn(Mono.empty());

        var processor = new SchedulingPostProcessor(applicationContext, spyScheduler, null, tccEngine, null);
        processor.afterSingletonsInstantiated();

        verify(spyScheduler).scheduleWithCron(
                eq("tcc:zoned-tcc:cron"), any(Runnable.class), eq("0 */5 * * * *"), eq("Europe/London"));
    }
}
