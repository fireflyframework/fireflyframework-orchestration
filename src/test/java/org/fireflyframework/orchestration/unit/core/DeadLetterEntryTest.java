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

import org.fireflyframework.orchestration.core.dlq.DeadLetterEntry;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DeadLetterEntryTest {

    @Test
    void create_acceptsInputMapWithNullValues() {
        Map<String, Object> input = new HashMap<>();
        input.put("present", "value");
        input.put("missing", null);

        assertThatCode(() ->
                DeadLetterEntry.create("saga", "corr", ExecutionPattern.SAGA, "step",
                        ExecutionStatus.FAILED, new RuntimeException("boom"), input))
                .doesNotThrowAnyException();

        DeadLetterEntry entry = DeadLetterEntry.create("saga", "corr", ExecutionPattern.SAGA, "step",
                ExecutionStatus.FAILED, new RuntimeException("boom"), input);

        assertThat(entry.input()).hasSize(2);
        assertThat(entry.input()).containsEntry("present", "value");
        assertThat(entry.input()).containsEntry("missing", null);
    }

    @Test
    void create_returnsEmptyMapForNullInput() {
        DeadLetterEntry entry = DeadLetterEntry.create("saga", "corr", ExecutionPattern.SAGA, "step",
                ExecutionStatus.FAILED, new RuntimeException("boom"), null);

        assertThat(entry.input()).isEmpty();
    }

    @Test
    void create_returnsEmptyMapForEmptyInput() {
        DeadLetterEntry entry = DeadLetterEntry.create("saga", "corr", ExecutionPattern.SAGA, "step",
                ExecutionStatus.FAILED, new RuntimeException("boom"), Map.of());

        assertThat(entry.input()).isEmpty();
    }

    @Test
    void create_preservesInsertionOrder() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("first", 1);
        input.put("second", 2);
        input.put("third", 3);

        DeadLetterEntry entry = DeadLetterEntry.create("saga", "corr", ExecutionPattern.SAGA, "step",
                ExecutionStatus.FAILED, new RuntimeException("boom"), input);

        assertThat(entry.input().keySet()).containsExactly("first", "second", "third");
    }

    @Test
    void create_handlesNullThrowableGracefully() {
        DeadLetterEntry entry = DeadLetterEntry.create("saga", "corr", ExecutionPattern.SAGA, "step",
                ExecutionStatus.FAILED, null, Map.of("key", "value"));

        assertThat(entry.errorMessage()).isNull();
        assertThat(entry.errorType()).isNull();
        assertThat(entry.input()).containsEntry("key", "value");
    }
}
