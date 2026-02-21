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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SchedulingTest {

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

    // --- Test stubs annotated with @ScheduledSaga ---

    @Saga(name = "scheduled-saga")
    @ScheduledSaga(fixedRate = 1000)
    static class FixedRateScheduledSaga {}

    @Saga(name = "cron-saga")
    @ScheduledSaga(cron = "*/5 * * * * *")
    static class CronScheduledSaga {}

    @Saga(name = "multi-saga")
    @ScheduledSaga(fixedRate = 2000)
    @ScheduledSaga(cron = "0 0 * * * *")
    static class MultiScheduledSaga {}

    @Saga(name = "plain-saga")
    static class PlainSaga {}

    // --- Test stubs annotated with @ScheduledTcc ---

    @Tcc(name = "scheduled-tcc")
    @ScheduledTcc(fixedRate = 500)
    static class FixedRateScheduledTcc {}

    @Tcc(name = "cron-tcc")
    @ScheduledTcc(cron = "*/10 * * * * *")
    static class CronScheduledTcc {}

    // --- Post-processor tests ---

    @Test
    void scheduledSaga_registersFixedRate() {
        var spyScheduler = spy(scheduler);
        var bean = new FixedRateScheduledSaga();

        when(applicationContext.getBeansWithAnnotation(Saga.class))
                .thenReturn(Map.of("fixedRateSaga", bean));
        lenient().when(sagaEngine.execute(eq("scheduled-saga"), any(StepInputs.class)))
                .thenReturn(Mono.empty());

        var processor = new SchedulingPostProcessor(applicationContext, spyScheduler, sagaEngine, null);
        processor.afterSingletonsInstantiated();

        verify(spyScheduler).scheduleAtFixedRate(
                eq("saga:scheduled-saga:rate"), any(Runnable.class), eq(1000L), eq(1000L));
    }

    @Test
    void scheduledSaga_registersCron() {
        var spyScheduler = spy(scheduler);
        var bean = new CronScheduledSaga();

        when(applicationContext.getBeansWithAnnotation(Saga.class))
                .thenReturn(Map.of("cronSaga", bean));
        lenient().when(sagaEngine.execute(eq("cron-saga"), any(StepInputs.class)))
                .thenReturn(Mono.empty());

        var processor = new SchedulingPostProcessor(applicationContext, spyScheduler, sagaEngine, null);
        processor.afterSingletonsInstantiated();

        verify(spyScheduler).scheduleWithCron(
                eq("saga:cron-saga:cron"), any(Runnable.class), eq("*/5 * * * * *"));
    }

    @Test
    void scheduledSaga_multipleAnnotations_registersBoth() {
        var spyScheduler = spy(scheduler);
        var bean = new MultiScheduledSaga();

        when(applicationContext.getBeansWithAnnotation(Saga.class))
                .thenReturn(Map.of("multiSaga", bean));
        lenient().when(sagaEngine.execute(eq("multi-saga"), any(StepInputs.class)))
                .thenReturn(Mono.empty());

        var processor = new SchedulingPostProcessor(applicationContext, spyScheduler, sagaEngine, null);
        processor.afterSingletonsInstantiated();

        verify(spyScheduler).scheduleAtFixedRate(
                eq("saga:multi-saga:rate"), any(Runnable.class), eq(2000L), eq(2000L));
        verify(spyScheduler).scheduleWithCron(
                eq("saga:multi-saga:cron"), any(Runnable.class), eq("0 0 * * * *"));
    }

    @Test
    void plainSaga_noScheduledAnnotation_doesNotRegister() {
        var spyScheduler = spy(scheduler);
        var bean = new PlainSaga();

        when(applicationContext.getBeansWithAnnotation(Saga.class))
                .thenReturn(Map.of("plainSaga", bean));

        var processor = new SchedulingPostProcessor(applicationContext, spyScheduler, sagaEngine, null);
        processor.afterSingletonsInstantiated();

        verify(spyScheduler, never()).scheduleAtFixedRate(any(), any(), anyLong(), anyLong());
        verify(spyScheduler, never()).scheduleWithCron(any(), any(), any());
    }

    @Test
    void scheduledTcc_registersFixedRate() {
        var spyScheduler = spy(scheduler);
        var bean = new FixedRateScheduledTcc();

        when(applicationContext.getBeansWithAnnotation(Tcc.class))
                .thenReturn(Map.of("fixedRateTcc", bean));
        lenient().when(tccEngine.execute(eq("scheduled-tcc"), any(TccInputs.class)))
                .thenReturn(Mono.empty());

        var processor = new SchedulingPostProcessor(applicationContext, spyScheduler, null, tccEngine);
        processor.afterSingletonsInstantiated();

        verify(spyScheduler).scheduleAtFixedRate(
                eq("tcc:scheduled-tcc:rate"), any(Runnable.class), eq(500L), eq(500L));
    }

    @Test
    void scheduledTcc_registersCron() {
        var spyScheduler = spy(scheduler);
        var bean = new CronScheduledTcc();

        when(applicationContext.getBeansWithAnnotation(Tcc.class))
                .thenReturn(Map.of("cronTcc", bean));
        lenient().when(tccEngine.execute(eq("cron-tcc"), any(TccInputs.class)))
                .thenReturn(Mono.empty());

        var processor = new SchedulingPostProcessor(applicationContext, spyScheduler, null, tccEngine);
        processor.afterSingletonsInstantiated();

        verify(spyScheduler).scheduleWithCron(
                eq("tcc:cron-tcc:cron"), any(Runnable.class), eq("*/10 * * * * *"));
    }

    @Test
    void nullEngines_handledGracefully() {
        var spyScheduler = spy(scheduler);

        var processor = new SchedulingPostProcessor(applicationContext, spyScheduler, null, null);
        processor.afterSingletonsInstantiated();

        verify(spyScheduler, never()).scheduleAtFixedRate(any(), any(), anyLong(), anyLong());
        verify(spyScheduler, never()).scheduleWithCron(any(), any(), any());
    }

    // --- Cron scheduler tests ---

    @Test
    void scheduleWithCron_executesTask() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        // Every second cron expression
        scheduler.scheduleWithCron("cron-test", latch::countDown, "* * * * * *");

        boolean executed = latch.await(3, TimeUnit.SECONDS);
        assertThat(executed).isTrue();
    }

    @Test
    void scheduleWithCron_reSchedulesAfterExecution() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        scheduler.scheduleWithCron("cron-reschedule", () -> {
            counter.incrementAndGet();
            latch.countDown();
        }, "* * * * * *");

        boolean executed = latch.await(5, TimeUnit.SECONDS);
        assertThat(executed).isTrue();
        assertThat(counter.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void scheduleWithCron_canBeCancelled() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        scheduler.scheduleWithCron("cron-cancel", counter::incrementAndGet, "* * * * * *");

        // Cancel immediately
        scheduler.cancel("cron-cancel");

        // Wait a bit and verify task did not execute (or at most once if it was already scheduled)
        Thread.sleep(2000);
        assertThat(counter.get()).isLessThanOrEqualTo(1);
    }

    @Test
    void scheduleWithCron_taskFailure_doesNotStopRescheduling() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        scheduler.scheduleWithCron("cron-fail-recover", () -> {
            int count = counter.incrementAndGet();
            latch.countDown();
            if (count == 1) {
                throw new RuntimeException("intentional failure");
            }
        }, "* * * * * *");

        boolean executed = latch.await(5, TimeUnit.SECONDS);
        assertThat(executed).isTrue();
        assertThat(counter.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void scheduleAtFixedRate_existingFunctionality_stillWorks() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger counter = new AtomicInteger(0);

        scheduler.scheduleAtFixedRate("rate-test", () -> {
            counter.incrementAndGet();
            latch.countDown();
        }, 0, 200);

        boolean executed = latch.await(3, TimeUnit.SECONDS);
        assertThat(executed).isTrue();
        assertThat(counter.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void activeTaskCount_reflectsCronTasks() {
        scheduler.scheduleWithCron("cron-count-1", () -> {}, "0 0 * * * *");
        scheduler.scheduleWithCron("cron-count-2", () -> {}, "0 0 * * * *");

        assertThat(scheduler.activeTaskCount()).isEqualTo(2);

        scheduler.cancel("cron-count-1");
        assertThat(scheduler.activeTaskCount()).isEqualTo(1);
    }
}
