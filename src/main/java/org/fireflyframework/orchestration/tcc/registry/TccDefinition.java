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

package org.fireflyframework.orchestration.tcc.registry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Metadata for a discovered TCC transaction coordinator.
 */
public class TccDefinition {

    public final String name;
    public final Object bean;
    public final Object target;
    public final long timeoutMs;
    public final boolean retryEnabled;
    public final int maxRetries;
    public final long backoffMs;
    public final Map<String, TccParticipantDefinition> participants = new LinkedHashMap<>();

    public TccDefinition(String name, Object bean, Object target,
                         long timeoutMs, boolean retryEnabled, int maxRetries, long backoffMs) {
        this.name = name;
        this.bean = bean;
        this.target = target;
        this.timeoutMs = timeoutMs;
        this.retryEnabled = retryEnabled;
        this.maxRetries = maxRetries;
        this.backoffMs = backoffMs;
    }
}
