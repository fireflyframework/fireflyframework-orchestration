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

package org.fireflyframework.orchestration.saga.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Marker to indicate that a step expecting a single input should be expanded
 * into one step per item. The engine clones the step definition with derived IDs
 * and wires compensations identically.
 *
 * <p>Usage:
 * <pre>{@code
 * StepInputs inputs = StepInputs.builder()
 *     .forStepId("insertItems", ExpandEach.of(items))
 *     .build();
 * }</pre>
 */
public final class ExpandEach {

    private final List<?> items;
    private final Function<Object, String> idSuffixFn;

    private ExpandEach(List<?> items, Function<Object, String> idSuffixFn) {
        this.items = items;
        this.idSuffixFn = idSuffixFn;
    }

    public static ExpandEach of(Iterable<?> items) {
        Objects.requireNonNull(items, "items");
        List<Object> list = new ArrayList<>();
        for (Object it : items) list.add(it);
        return new ExpandEach(list, null);
    }

    public static ExpandEach of(Iterable<?> items, Function<Object, String> idSuffixFn) {
        Objects.requireNonNull(items, "items");
        List<Object> list = new ArrayList<>();
        for (Object it : items) list.add(it);
        return new ExpandEach(list, Objects.requireNonNull(idSuffixFn, "idSuffixFn"));
    }

    public static ExpandEach of(Object[] items) {
        Objects.requireNonNull(items, "items");
        List<Object> list = new ArrayList<>(items.length);
        for (Object it : items) list.add(it);
        return new ExpandEach(list, null);
    }

    public List<?> items() { return items; }
    public Optional<Function<Object, String>> idSuffixFn() { return Optional.ofNullable(idSuffixFn); }
}
