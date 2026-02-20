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

package org.fireflyframework.orchestration.workflow.timer;

import org.fireflyframework.orchestration.core.observability.OrchestrationEvents;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages workflow timers. Provides scheduling, cancellation, and
 * a reactive stream of fired timers.
 */
@Slf4j
public class TimerService {

    private final OrchestrationEvents events;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<TimerEntry>> timers = new ConcurrentHashMap<>();

    public TimerService(OrchestrationEvents events) {
        this.events = events;
    }

    /**
     * Schedules a timer that fires after the specified delay.
     */
    public Mono<TimerEntry> schedule(String correlationId, String timerId, Duration delay, Object data) {
        return Mono.fromCallable(() -> {
            TimerEntry entry = TimerEntry.create(correlationId, timerId, Instant.now().plus(delay), data);
            timers.computeIfAbsent(correlationId, k -> new CopyOnWriteArrayList<>()).add(entry);
            log.info("[timer] Scheduled timer '{}' for instance '{}', fires at {}",
                    timerId, correlationId, entry.fireAt());
            return entry;
        });
    }

    /**
     * Schedules a timer that fires at a specific instant.
     */
    public Mono<TimerEntry> scheduleAt(String correlationId, String timerId, Instant fireAt, Object data) {
        return Mono.fromCallable(() -> {
            TimerEntry entry = TimerEntry.create(correlationId, timerId, fireAt, data);
            timers.computeIfAbsent(correlationId, k -> new CopyOnWriteArrayList<>()).add(entry);
            log.info("[timer] Scheduled timer '{}' for instance '{}', fires at {}", timerId, correlationId, fireAt);
            return entry;
        });
    }

    /**
     * Cancels a timer by correlationId and timerId.
     */
    public Mono<Boolean> cancel(String correlationId, String timerId) {
        return Mono.fromCallable(() -> {
            var entries = timers.get(correlationId);
            if (entries == null) return false;
            boolean removed = entries.removeIf(e -> e.timerId().equals(timerId));
            if (removed) {
                log.info("[timer] Cancelled timer '{}' for instance '{}'", timerId, correlationId);
            }
            return removed;
        });
    }

    /**
     * Returns all ready (fired) timers for a workflow instance.
     */
    public Flux<TimerEntry> getReadyTimers(String correlationId) {
        return Flux.defer(() -> {
            var entries = timers.get(correlationId);
            if (entries == null) return Flux.empty();
            List<TimerEntry> ready = entries.stream().filter(TimerEntry::isReady).toList();
            // Remove fired timers
            entries.removeAll(ready);
            ready.forEach(t -> events.onTimerFired(
                    "workflow", correlationId, t.timerId()));
            return Flux.fromIterable(ready);
        });
    }

    /**
     * Returns all pending timers for a workflow instance.
     */
    public List<TimerEntry> getPendingTimers(String correlationId) {
        var entries = timers.get(correlationId);
        return entries != null ? List.copyOf(entries) : List.of();
    }

    /**
     * Creates a Mono that completes after the specified delay.
     * This is a convenience for workflow steps using @WaitForTimer.
     */
    public Mono<Void> delay(Duration duration) {
        return Mono.delay(duration).then();
    }

    /**
     * Cleans up all timers for a completed workflow instance.
     */
    public void cleanup(String correlationId) {
        timers.remove(correlationId);
    }
}
