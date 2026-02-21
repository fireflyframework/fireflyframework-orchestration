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

package org.fireflyframework.orchestration.core.event;

import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Routes incoming events to matching workflow, saga, or TCC executions.
 *
 * <p>At startup, the gateway is populated with trigger registrations discovered
 * from {@code @Workflow(triggerEventType=...)} annotations (and potentially
 * saga/TCC triggers in the future). When {@link #routeEvent(String, Map)} is
 * called, the gateway looks up the matching registration and starts execution.
 */
@Slf4j
public class EventGateway {

    private final Map<String, EventTriggerRegistration> triggers = new ConcurrentHashMap<>();

    /**
     * Describes a single event-to-execution mapping.
     *
     * @param targetName the workflow/saga/TCC identifier to execute
     * @param pattern    the execution pattern (WORKFLOW, SAGA, TCC)
     * @param executor   function that starts execution given a payload map
     */
    public record EventTriggerRegistration(
            String targetName,
            ExecutionPattern pattern,
            Function<Map<String, Object>, Mono<?>> executor
    ) {}

    /**
     * Register an event type trigger.
     *
     * @param eventType  the event type string to listen for
     * @param targetName the name of the target execution
     * @param pattern    the execution pattern
     * @param executor   function that starts execution
     */
    public void register(String eventType, String targetName, ExecutionPattern pattern,
                         Function<Map<String, Object>, Mono<?>> executor) {
        triggers.put(eventType, new EventTriggerRegistration(targetName, pattern, executor));
        log.info("[event-gateway] Registered trigger: event '{}' -> {} '{}'",
                eventType, pattern, targetName);
    }

    /**
     * Route an incoming event to the matching execution target.
     *
     * @param eventType the event type
     * @param payload   the event payload to pass as execution input
     * @return a Mono that completes when execution has been started (or immediately if no match)
     */
    public Mono<Void> routeEvent(String eventType, Map<String, Object> payload) {
        EventTriggerRegistration reg = triggers.get(eventType);
        if (reg == null) {
            log.debug("[event-gateway] No trigger registered for event type '{}'", eventType);
            return Mono.empty();
        }
        log.info("[event-gateway] Routing event '{}' to {} '{}'",
                eventType, reg.pattern(), reg.targetName());
        return reg.executor().apply(payload).then();
    }

    /**
     * Check whether a trigger registration exists for the given event type.
     */
    public boolean hasRegistration(String eventType) {
        return triggers.containsKey(eventType);
    }

    /**
     * Return the number of registered triggers.
     */
    public int registrationCount() {
        return triggers.size();
    }

    /**
     * Return an unmodifiable copy of all registered event types.
     */
    public Set<String> registeredEventTypes() {
        return Set.copyOf(triggers.keySet());
    }
}
