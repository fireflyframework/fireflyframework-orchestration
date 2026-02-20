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

package org.fireflyframework.orchestration.core.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.util.Map;

/**
 * Serializes and deserializes {@link ExecutionState} to/from JSON for durable persistence.
 */
public class StateSerializer {

    private final ObjectMapper mapper;

    public StateSerializer() {
        this.mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public StateSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String serialize(ExecutionState state) {
        try {
            return mapper.writeValueAsString(toSerializable(state));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ExecutionState", e);
        }
    }

    public ExecutionState deserialize(String json) {
        try {
            Map<String, Object> raw = mapper.readValue(json, new TypeReference<>() {});
            return fromSerializable(raw);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize ExecutionState", e);
        }
    }

    public byte[] serializeToBytes(ExecutionState state) {
        try {
            return mapper.writeValueAsBytes(toSerializable(state));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ExecutionState to bytes", e);
        }
    }

    public ExecutionState deserializeFromBytes(byte[] data) {
        try {
            Map<String, Object> raw = mapper.readValue(data, new TypeReference<>() {});
            return fromSerializable(raw);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialize ExecutionState from bytes", e);
        }
    }

    private Map<String, Object> toSerializable(ExecutionState state) {
        return mapper.convertValue(state, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private ExecutionState fromSerializable(Map<String, Object> raw) {
        return mapper.convertValue(raw, ExecutionState.class);
    }
}
