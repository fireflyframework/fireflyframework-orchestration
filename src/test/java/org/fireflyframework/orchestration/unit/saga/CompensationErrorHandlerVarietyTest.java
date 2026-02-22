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

package org.fireflyframework.orchestration.unit.saga;

import org.fireflyframework.orchestration.saga.compensation.CompensationErrorHandler;
import org.fireflyframework.orchestration.saga.compensation.CompensationErrorHandler.CompensationErrorResult;
import org.fireflyframework.orchestration.saga.compensation.CompensationErrorHandlerFactory;
import org.fireflyframework.orchestration.saga.compensation.CompositeCompensationErrorHandler;
import org.fireflyframework.orchestration.saga.compensation.DefaultCompensationErrorHandler;
import org.fireflyframework.orchestration.saga.compensation.FailFastErrorHandler;
import org.fireflyframework.orchestration.saga.compensation.LogAndContinueErrorHandler;
import org.fireflyframework.orchestration.saga.compensation.RetryWithBackoffErrorHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for compensation error handler variety: FailFast, LogAndContinue,
 * Composite, and the CompensationErrorHandlerFactory.
 */
class CompensationErrorHandlerVarietyTest {

    @BeforeEach
    void setUp() {
        CompensationErrorHandlerFactory.resetDefaults();
    }

    @Test
    void failFast_alwaysReturnsFail() {
        var handler = new FailFastErrorHandler();

        assertThat(handler.handle("saga1", "step1", new RuntimeException("err"), 0))
                .isEqualTo(CompensationErrorResult.FAIL_SAGA);
        assertThat(handler.handle("saga1", "step1", new RuntimeException("err"), 1))
                .isEqualTo(CompensationErrorResult.FAIL_SAGA);
        assertThat(handler.handle("saga2", "step2", new IllegalStateException("other"), 5))
                .isEqualTo(CompensationErrorResult.FAIL_SAGA);
    }

    @Test
    void logAndContinue_alwaysReturnsContinue() {
        var handler = new LogAndContinueErrorHandler();

        assertThat(handler.handle("saga1", "step1", new RuntimeException("err"), 0))
                .isEqualTo(CompensationErrorResult.CONTINUE);
        assertThat(handler.handle("saga1", "step1", new RuntimeException("err"), 1))
                .isEqualTo(CompensationErrorResult.CONTINUE);
        assertThat(handler.handle("saga2", "step2", new IllegalStateException("other"), 5))
                .isEqualTo(CompensationErrorResult.CONTINUE);
    }

    @Test
    void composite_delegatesToFirstNonContinue() {
        // First handler continues, second handler fails the saga
        var composite = new CompositeCompensationErrorHandler(List.of(
                new LogAndContinueErrorHandler(),
                new FailFastErrorHandler()
        ));

        assertThat(composite.handle("saga1", "step1", new RuntimeException("err"), 0))
                .isEqualTo(CompensationErrorResult.FAIL_SAGA);
    }

    @Test
    void composite_returnsContinueIfAllContinue() {
        var composite = new CompositeCompensationErrorHandler(List.of(
                new LogAndContinueErrorHandler(),
                new DefaultCompensationErrorHandler(),
                new LogAndContinueErrorHandler()
        ));

        assertThat(composite.handle("saga1", "step1", new RuntimeException("err"), 0))
                .isEqualTo(CompensationErrorResult.CONTINUE);
    }

    @Test
    void factory_returnsRegisteredHandlers() {
        assertThat(CompensationErrorHandlerFactory.getHandler("default"))
                .isPresent()
                .get().isInstanceOf(DefaultCompensationErrorHandler.class);

        assertThat(CompensationErrorHandlerFactory.getHandler("fail-fast"))
                .isPresent()
                .get().isInstanceOf(FailFastErrorHandler.class);

        assertThat(CompensationErrorHandlerFactory.getHandler("log-and-continue"))
                .isPresent()
                .get().isInstanceOf(LogAndContinueErrorHandler.class);

        assertThat(CompensationErrorHandlerFactory.getHandler("retry"))
                .isPresent()
                .get().isInstanceOf(RetryWithBackoffErrorHandler.class);

        assertThat(CompensationErrorHandlerFactory.getHandler("robust"))
                .isPresent()
                .get().isInstanceOf(CompositeCompensationErrorHandler.class);

        assertThat(CompensationErrorHandlerFactory.getHandler("nonexistent"))
                .isEmpty();

        // Verify fresh instances are returned (no shared mutable state)
        CompensationErrorHandler first = CompensationErrorHandlerFactory.getHandler("retry").orElseThrow();
        CompensationErrorHandler second = CompensationErrorHandlerFactory.getHandler("retry").orElseThrow();
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void factory_robustHandler_retriesThenContinues() {
        CompensationErrorHandler robust = CompensationErrorHandlerFactory.getHandler("robust").orElseThrow();

        // Attempts 0, 1, 2 are under max retries (3) — retry wrapper returns RETRY
        assertThat(robust.handle("saga1", "step1", new RuntimeException("err"), 0))
                .isEqualTo(CompensationErrorResult.RETRY);
        assertThat(robust.handle("saga1", "step1", new RuntimeException("err"), 1))
                .isEqualTo(CompensationErrorResult.RETRY);
        assertThat(robust.handle("saga1", "step1", new RuntimeException("err"), 2))
                .isEqualTo(CompensationErrorResult.RETRY);

        // Attempt 3 exceeds max retries — retry handler would return FAIL_SAGA,
        // but the robust wrapper converts FAIL_SAGA to CONTINUE, allowing the
        // composite to fall through to the log-and-continue handler which returns CONTINUE.
        assertThat(robust.handle("saga1", "step1", new RuntimeException("err"), 3))
                .isEqualTo(CompensationErrorResult.CONTINUE);
    }
}
