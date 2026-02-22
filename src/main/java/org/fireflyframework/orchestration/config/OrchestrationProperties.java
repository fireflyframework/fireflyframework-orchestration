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

package org.fireflyframework.orchestration.config;

import org.fireflyframework.orchestration.core.model.CompensationPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * Unified configuration properties for the Orchestration framework.
 *
 * <p>Consolidates configuration from the former workflow and transactional-engine
 * projects under a single {@code firefly.orchestration} prefix.
 *
 * <p>Example YAML:
 * <pre>{@code
 * firefly:
 *   orchestration:
 *     workflow:
 *       enabled: true
 *     saga:
 *       enabled: true
 *       compensation-policy: STRICT_SEQUENTIAL
 *     tcc:
 *       enabled: true
 *       default-timeout: 30s
 *     persistence:
 *       provider: in-memory
 *       key-prefix: orchestration:
 *     recovery:
 *       enabled: true
 *       stale-threshold: 1h
 *     scheduling:
 *       thread-pool-size: 4
 *     rest:
 *       enabled: true
 *     health:
 *       enabled: true
 *     metrics:
 *       enabled: true
 *     tracing:
 *       enabled: true
 *     backpressure:
 *       strategy: adaptive
 *       batch-size: 10
 *       circuit-breaker:
 *         failure-threshold: 5
 *         recovery-timeout: 30s
 *         half-open-max-calls: 3
 *     validation:
 *       enabled: true
 *       fail-on-warning: false
 *     eventsourcing:
 *       snapshot-interval: 100
 *       projection-poll-interval: 5s
 * }</pre>
 */
@ConfigurationProperties(prefix = "firefly.orchestration")
public class OrchestrationProperties {

    @NestedConfigurationProperty
    private WorkflowProperties workflow = new WorkflowProperties();

    @NestedConfigurationProperty
    private SagaProperties saga = new SagaProperties();

    @NestedConfigurationProperty
    private TccProperties tcc = new TccProperties();

    @NestedConfigurationProperty
    private PersistenceProperties persistence = new PersistenceProperties();

    @NestedConfigurationProperty
    private RecoveryProperties recovery = new RecoveryProperties();

    @NestedConfigurationProperty
    private SchedulingProperties scheduling = new SchedulingProperties();

    @NestedConfigurationProperty
    private RestProperties rest = new RestProperties();

    @NestedConfigurationProperty
    private HealthProperties health = new HealthProperties();

    @NestedConfigurationProperty
    private MetricsProperties metrics = new MetricsProperties();

    @NestedConfigurationProperty
    private TracingProperties tracing = new TracingProperties();

    @NestedConfigurationProperty
    private DlqProperties dlq = new DlqProperties();

    @NestedConfigurationProperty
    private ResilienceProperties resilience = new ResilienceProperties();

    @NestedConfigurationProperty
    private BackpressureProperties backpressure = new BackpressureProperties();

    @NestedConfigurationProperty
    private ValidationProperties validation = new ValidationProperties();

    @NestedConfigurationProperty
    private EventSourcingProperties eventsourcing = new EventSourcingProperties();

    // --- Getters and Setters ---

    public WorkflowProperties getWorkflow() { return workflow; }
    public void setWorkflow(WorkflowProperties workflow) { this.workflow = workflow; }

    public SagaProperties getSaga() { return saga; }
    public void setSaga(SagaProperties saga) { this.saga = saga; }

    public TccProperties getTcc() { return tcc; }
    public void setTcc(TccProperties tcc) { this.tcc = tcc; }

    public PersistenceProperties getPersistence() { return persistence; }
    public void setPersistence(PersistenceProperties persistence) { this.persistence = persistence; }

    public RecoveryProperties getRecovery() { return recovery; }
    public void setRecovery(RecoveryProperties recovery) { this.recovery = recovery; }

    public SchedulingProperties getScheduling() { return scheduling; }
    public void setScheduling(SchedulingProperties scheduling) { this.scheduling = scheduling; }

    public RestProperties getRest() { return rest; }
    public void setRest(RestProperties rest) { this.rest = rest; }

    public HealthProperties getHealth() { return health; }
    public void setHealth(HealthProperties health) { this.health = health; }

    public MetricsProperties getMetrics() { return metrics; }
    public void setMetrics(MetricsProperties metrics) { this.metrics = metrics; }

    public TracingProperties getTracing() { return tracing; }
    public void setTracing(TracingProperties tracing) { this.tracing = tracing; }

    public DlqProperties getDlq() { return dlq; }
    public void setDlq(DlqProperties dlq) { this.dlq = dlq; }

    public ResilienceProperties getResilience() { return resilience; }
    public void setResilience(ResilienceProperties resilience) { this.resilience = resilience; }

    public BackpressureProperties getBackpressure() { return backpressure; }
    public void setBackpressure(BackpressureProperties backpressure) { this.backpressure = backpressure; }

    public ValidationProperties getValidation() { return validation; }
    public void setValidation(ValidationProperties validation) { this.validation = validation; }

    public EventSourcingProperties getEventsourcing() { return eventsourcing; }
    public void setEventsourcing(EventSourcingProperties eventsourcing) { this.eventsourcing = eventsourcing; }

    // --- Nested property classes ---

    public static class WorkflowProperties {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class SagaProperties {
        private boolean enabled = true;
        private CompensationPolicy compensationPolicy = CompensationPolicy.STRICT_SEQUENTIAL;
        private Duration defaultTimeout = Duration.ofMinutes(5);
        private String compensationErrorHandler = "default";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public CompensationPolicy getCompensationPolicy() { return compensationPolicy; }
        public void setCompensationPolicy(CompensationPolicy compensationPolicy) { this.compensationPolicy = compensationPolicy; }

        public Duration getDefaultTimeout() { return defaultTimeout; }
        public void setDefaultTimeout(Duration defaultTimeout) { this.defaultTimeout = defaultTimeout; }

        public String getCompensationErrorHandler() { return compensationErrorHandler; }
        public void setCompensationErrorHandler(String compensationErrorHandler) { this.compensationErrorHandler = compensationErrorHandler; }
    }

    public static class TccProperties {
        private boolean enabled = true;
        private Duration defaultTimeout = Duration.ofSeconds(30);

        @NestedConfigurationProperty
        private TccCompositionProperties composition = new TccCompositionProperties();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public Duration getDefaultTimeout() { return defaultTimeout; }
        public void setDefaultTimeout(Duration defaultTimeout) { this.defaultTimeout = defaultTimeout; }

        public TccCompositionProperties getComposition() { return composition; }
        public void setComposition(TccCompositionProperties composition) { this.composition = composition; }
    }

    public static class TccCompositionProperties {
        private boolean enabled = true;
        private CompensationPolicy compensationPolicy = CompensationPolicy.STRICT_SEQUENTIAL;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public CompensationPolicy getCompensationPolicy() { return compensationPolicy; }
        public void setCompensationPolicy(CompensationPolicy compensationPolicy) { this.compensationPolicy = compensationPolicy; }
    }

    public static class PersistenceProperties {
        private String provider = "in-memory";
        private String keyPrefix = "orchestration:";
        private Duration keyTtl;
        private Duration retentionPeriod = Duration.ofDays(7);
        private Duration cleanupInterval = Duration.ofHours(1);

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }

        public Duration getKeyTtl() { return keyTtl; }
        public void setKeyTtl(Duration keyTtl) { this.keyTtl = keyTtl; }

        public Duration getRetentionPeriod() { return retentionPeriod; }
        public void setRetentionPeriod(Duration retentionPeriod) { this.retentionPeriod = retentionPeriod; }

        public Duration getCleanupInterval() { return cleanupInterval; }
        public void setCleanupInterval(Duration cleanupInterval) { this.cleanupInterval = cleanupInterval; }
    }

    public static class RecoveryProperties {
        private boolean enabled = true;
        private Duration staleThreshold = Duration.ofHours(1);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public Duration getStaleThreshold() { return staleThreshold; }
        public void setStaleThreshold(Duration staleThreshold) { this.staleThreshold = staleThreshold; }
    }

    public static class SchedulingProperties {
        private int threadPoolSize = 4;

        public int getThreadPoolSize() { return threadPoolSize; }
        public void setThreadPoolSize(int threadPoolSize) { this.threadPoolSize = threadPoolSize; }
    }

    public static class RestProperties {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class HealthProperties {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class MetricsProperties {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class TracingProperties {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class DlqProperties {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class ResilienceProperties {
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class BackpressureProperties {
        private String strategy = "adaptive";
        private int batchSize = 10;

        @NestedConfigurationProperty
        private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

        public CircuitBreakerProperties getCircuitBreaker() { return circuitBreaker; }
        public void setCircuitBreaker(CircuitBreakerProperties circuitBreaker) { this.circuitBreaker = circuitBreaker; }
    }

    public static class CircuitBreakerProperties {
        private int failureThreshold = 5;
        private Duration recoveryTimeout = Duration.ofSeconds(30);
        private int halfOpenMaxCalls = 3;

        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }

        public Duration getRecoveryTimeout() { return recoveryTimeout; }
        public void setRecoveryTimeout(Duration recoveryTimeout) { this.recoveryTimeout = recoveryTimeout; }

        public int getHalfOpenMaxCalls() { return halfOpenMaxCalls; }
        public void setHalfOpenMaxCalls(int halfOpenMaxCalls) { this.halfOpenMaxCalls = halfOpenMaxCalls; }
    }

    public static class ValidationProperties {
        private boolean enabled = true;
        private boolean failOnWarning = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isFailOnWarning() { return failOnWarning; }
        public void setFailOnWarning(boolean failOnWarning) { this.failOnWarning = failOnWarning; }
    }

    public static class EventSourcingProperties {
        private int snapshotInterval = 100;
        private Duration projectionPollInterval = Duration.ofSeconds(5);

        public int getSnapshotInterval() { return snapshotInterval; }
        public void setSnapshotInterval(int snapshotInterval) { this.snapshotInterval = snapshotInterval; }

        public Duration getProjectionPollInterval() { return projectionPollInterval; }
        public void setProjectionPollInterval(Duration projectionPollInterval) { this.projectionPollInterval = projectionPollInterval; }
    }
}
