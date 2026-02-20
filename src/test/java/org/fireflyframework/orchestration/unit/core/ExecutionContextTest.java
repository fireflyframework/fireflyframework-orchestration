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

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.context.TccPhase;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

class ExecutionContextTest {

    @Test
    void forWorkflow_setsPatternCorrectly() {
        var ctx = ExecutionContext.forWorkflow("corr-1", "myWorkflow");
        assertThat(ctx.getPattern()).isEqualTo(ExecutionPattern.WORKFLOW);
        assertThat(ctx.getCorrelationId()).isEqualTo("corr-1");
        assertThat(ctx.getExecutionName()).isEqualTo("myWorkflow");
    }

    @Test
    void forSaga_setsPatternCorrectly() {
        var ctx = ExecutionContext.forSaga("corr-2", "mySaga");
        assertThat(ctx.getPattern()).isEqualTo(ExecutionPattern.SAGA);
    }

    @Test
    void forTcc_setsPatternCorrectly() {
        var ctx = ExecutionContext.forTcc("corr-3", "myTcc");
        assertThat(ctx.getPattern()).isEqualTo(ExecutionPattern.TCC);
    }

    @Test
    void headers_putAndGet() {
        var ctx = ExecutionContext.forSaga(null, "test");
        ctx.putHeader("key", "value");
        assertThat(ctx.getHeader("key")).isEqualTo("value");
        assertThat(ctx.getHeaders()).containsEntry("key", "value");
    }

    @Test
    void variables_putGetRemove() {
        var ctx = ExecutionContext.forSaga(null, "test");
        ctx.putVariable("count", 42);
        assertThat(ctx.getVariable("count")).isEqualTo(42);
        assertThat(ctx.getVariableAs("count", Integer.class)).isEqualTo(42);
        ctx.removeVariable("count");
        assertThat(ctx.getVariable("count")).isNull();
    }

    @Test
    void stepResults_putAndGet() {
        var ctx = ExecutionContext.forSaga(null, "test");
        ctx.putResult("step1", "result1");
        assertThat(ctx.getResult("step1")).isEqualTo("result1");
        assertThat(ctx.getResult("step1", String.class)).isEqualTo("result1");
    }

    @Test
    void stepStatus_setAndGet() {
        var ctx = ExecutionContext.forSaga(null, "test");
        assertThat(ctx.getStepStatus("step1")).isEqualTo(StepStatus.PENDING);
        ctx.setStepStatus("step1", StepStatus.RUNNING);
        assertThat(ctx.getStepStatus("step1")).isEqualTo(StepStatus.RUNNING);
    }

    @Test
    void attempts_incrementAndGet() {
        var ctx = ExecutionContext.forSaga(null, "test");
        assertThat(ctx.getAttempts("step1")).isEqualTo(0);
        assertThat(ctx.incrementAttempts("step1")).isEqualTo(1);
        assertThat(ctx.incrementAttempts("step1")).isEqualTo(2);
        assertThat(ctx.getAttempts("step1")).isEqualTo(2);
    }

    @Test
    void idempotency_addAndCheck() {
        var ctx = ExecutionContext.forSaga(null, "test");
        assertThat(ctx.hasIdempotencyKey("key1")).isFalse();
        assertThat(ctx.addIdempotencyKey("key1")).isTrue();
        assertThat(ctx.hasIdempotencyKey("key1")).isTrue();
        assertThat(ctx.addIdempotencyKey("key1")).isFalse(); // duplicate
    }

    @Test
    void dryRun_workflowSpecific() {
        var ctx = ExecutionContext.forWorkflow("corr-1", "test", true);
        assertThat(ctx.isDryRun()).isTrue();
    }

    @Test
    void tccPhase_setAndGet() {
        var ctx = ExecutionContext.forTcc(null, "test");
        assertThat(ctx.getCurrentPhase()).isNull();
        ctx.setCurrentPhase(TccPhase.TRY);
        assertThat(ctx.getCurrentPhase()).isEqualTo(TccPhase.TRY);
    }

    @Test
    void tryResults_tccSpecific() {
        var ctx = ExecutionContext.forTcc(null, "test");
        ctx.putTryResult("p1", "reserved");
        assertThat(ctx.getTryResult("p1")).isEqualTo("reserved");
        assertThat(ctx.getTryResults()).containsEntry("p1", "reserved");
    }

    @Test
    void compensationTracking_sagaSpecific() {
        var ctx = ExecutionContext.forSaga(null, "test");
        ctx.putCompensationResult("step1", "undone");
        var error = new RuntimeException("comp failed");
        ctx.putCompensationError("step2", error);
        assertThat(ctx.getCompensationResult("step1")).isEqualTo("undone");
        assertThat(ctx.getCompensationError("step2")).isEqualTo(error);
    }

    @Test
    void concurrentAccess_isThreadSafe() throws Exception {
        var ctx = ExecutionContext.forSaga(null, "test");
        int threads = 10;
        var latch = new CountDownLatch(threads);
        try (var executor = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                executor.submit(() -> {
                    ctx.putResult("step" + idx, "result" + idx);
                    ctx.setStepStatus("step" + idx, StepStatus.DONE);
                    ctx.incrementAttempts("step" + idx);
                    latch.countDown();
                });
            }
            latch.await();
        }
        assertThat(ctx.getStepResults()).hasSize(threads);
        assertThat(ctx.getStepStatuses()).hasSize(threads);
    }

    @Test
    void nullCorrelationId_generatesUUID() {
        var ctx = ExecutionContext.forSaga(null, "test");
        assertThat(ctx.getCorrelationId()).isNotNull().isNotEmpty();
    }
}
