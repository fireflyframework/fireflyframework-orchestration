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

package org.fireflyframework.orchestration.core.scheduling;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class OrchestrationScheduler {
    private final ScheduledExecutorService executor;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public OrchestrationScheduler(int threadPoolSize) {
        var counter = new AtomicInteger(0);
        this.executor = Executors.newScheduledThreadPool(threadPoolSize, r -> {
            Thread t = new Thread(r, "orchestration-scheduler-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public void scheduleAtFixedRate(String taskId, Runnable task, long initialDelayMs, long periodMs) {
        var future = executor.scheduleAtFixedRate(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("[scheduler] Task '{}' failed: {}", taskId, e.getMessage(), e);
            }
        }, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
        var existing = scheduledTasks.put(taskId, future);
        if (existing != null) {
            existing.cancel(false);
        }
        log.info("[scheduler] Scheduled task '{}' with period {}ms", taskId, periodMs);
    }

    public void scheduleWithCron(String taskId, Runnable task, String cronExpression) {
        // Simplified: for now, cron expressions are not parsed — use fixed rate
        // Full cron support would use Spring's CronTrigger
        log.warn("[scheduler] Cron scheduling for '{}' not yet implemented, use scheduleAtFixedRate", taskId);
    }

    public void cancel(String taskId) {
        var future = scheduledTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
            log.info("[scheduler] Cancelled task '{}'", taskId);
        }
    }

    public void shutdown() {
        scheduledTasks.values().forEach(f -> f.cancel(false));
        scheduledTasks.clear();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[scheduler] Scheduler shutdown completed");
    }

    public int activeTaskCount() {
        return scheduledTasks.size();
    }
}
