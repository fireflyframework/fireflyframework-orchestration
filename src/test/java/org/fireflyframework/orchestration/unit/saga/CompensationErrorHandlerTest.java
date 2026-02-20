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

import org.fireflyframework.orchestration.saga.compensation.CompensationErrorHandler.CompensationErrorResult;
import org.fireflyframework.orchestration.saga.compensation.RetryWithBackoffErrorHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class CompensationErrorHandlerTest {

    @Test
    void retryHandler_retriesWithinLimit() {
        var handler = new RetryWithBackoffErrorHandler(3);
        assertThat(handler.handle("saga", "step1", new RuntimeException(), 0))
                .isEqualTo(CompensationErrorResult.RETRY);
        assertThat(handler.handle("saga", "step1", new RuntimeException(), 1))
                .isEqualTo(CompensationErrorResult.RETRY);
        assertThat(handler.handle("saga", "step1", new RuntimeException(), 2))
                .isEqualTo(CompensationErrorResult.RETRY);
    }

    @Test
    void retryHandler_failsAfterMaxRetries() {
        var handler = new RetryWithBackoffErrorHandler(2);
        assertThat(handler.handle("saga", "step1", new RuntimeException(), 2))
                .isEqualTo(CompensationErrorResult.FAIL_SAGA);
    }

    @Test
    void retryHandler_filtersRetryableExceptions() {
        var handler = new RetryWithBackoffErrorHandler(3, Set.of(IOException.class));

        // IOException is retryable
        assertThat(handler.handle("saga", "s1", new IOException(), 0))
                .isEqualTo(CompensationErrorResult.RETRY);

        // RuntimeException is NOT retryable
        assertThat(handler.handle("saga", "s1", new RuntimeException(), 0))
                .isEqualTo(CompensationErrorResult.FAIL_SAGA);
    }

    @Test
    void retryHandler_emptyRetryableTypesRetriesAll() {
        var handler = new RetryWithBackoffErrorHandler(3, Set.of());
        assertThat(handler.handle("saga", "s1", new RuntimeException(), 0))
                .isEqualTo(CompensationErrorResult.RETRY);
        assertThat(handler.handle("saga", "s1", new IOException(), 0))
                .isEqualTo(CompensationErrorResult.RETRY);
    }
}
