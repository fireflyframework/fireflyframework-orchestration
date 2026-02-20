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

package org.fireflyframework.orchestration.tcc.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable container for per-participant inputs in a TCC transaction.
 */
public final class TccInputs {

    private final Map<String, Object> values;

    private TccInputs(Map<String, Object> values) {
        this.values = values;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TccInputs empty() {
        return new Builder().build();
    }

    public static TccInputs of(String participantId, Object input) {
        return builder().forParticipant(participantId, input).build();
    }

    public static TccInputs of(Map<String, Object> values) {
        Builder b = builder();
        if (values != null) values.forEach(b::forParticipant);
        return b.build();
    }

    public Object getInput(String participantId) {
        return values.get(participantId);
    }

    public boolean hasInput(String participantId) {
        return values.containsKey(participantId);
    }

    public Map<String, Object> asMap() {
        return values;
    }

    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        public Builder forParticipant(String participantId, Object input) {
            Objects.requireNonNull(participantId, "participantId");
            values.put(participantId, input);
            return this;
        }

        public TccInputs build() {
            return new TccInputs(Collections.unmodifiableMap(new LinkedHashMap<>(values)));
        }
    }
}
