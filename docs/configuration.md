[← Back to Index](README.md) | [Previous: Core Infrastructure](core-infrastructure.md) | [Next: Recipes & Production →](recipes-and-production.md)

# Part VI: Configuration

**Contents:**
- [§38 Configuration Properties](#38-configuration-properties)
- [§39 Auto-Configuration Chain](#39-auto-configuration-chain)
- [§40 Spring Boot Integration](#40-spring-boot-integration)

## 38. Configuration Properties

All properties are prefixed with `firefly.orchestration`.

### Pattern Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `firefly.orchestration.workflow.enabled` | `boolean` | `true` | Enable workflow pattern |
| `firefly.orchestration.saga.enabled` | `boolean` | `true` | Enable saga pattern |
| `firefly.orchestration.saga.compensation-policy` | `CompensationPolicy` | `STRICT_SEQUENTIAL` | Default compensation strategy |
| `firefly.orchestration.saga.default-timeout` | `Duration` | `5m` | Default saga timeout |
| `firefly.orchestration.tcc.enabled` | `boolean` | `true` | Enable TCC pattern |
| `firefly.orchestration.tcc.default-timeout` | `Duration` | `30s` | Default TCC timeout |

### Persistence Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `firefly.orchestration.persistence.provider` | `String` | `in-memory` | Provider: `in-memory`, `redis`, `cache`, `event-sourced` |
| `firefly.orchestration.persistence.key-prefix` | `String` | `orchestration:` | Key prefix for Redis/Cache |
| `firefly.orchestration.persistence.key-ttl` | `Duration` | *(none)* | Optional key TTL for Redis/Cache |
| `firefly.orchestration.persistence.retention-period` | `Duration` | `7d` | Completed execution retention |
| `firefly.orchestration.persistence.cleanup-interval` | `Duration` | `1h` | Cleanup task interval |

### Infrastructure Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `firefly.orchestration.scheduling.thread-pool-size` | `int` | `4` | Scheduler thread pool size |
| `firefly.orchestration.recovery.enabled` | `boolean` | `true` | Enable stale execution detection |
| `firefly.orchestration.recovery.stale-threshold` | `Duration` | `1h` | Age before execution is stale |
| `firefly.orchestration.dlq.enabled` | `boolean` | `true` | Enable dead-letter queue |
| `firefly.orchestration.rest.enabled` | `boolean` | `true` | Expose REST endpoints (reactive web only) |
| `firefly.orchestration.health.enabled` | `boolean` | `true` | Expose health indicator |
| `firefly.orchestration.metrics.enabled` | `boolean` | `true` | Enable Micrometer metrics |
| `firefly.orchestration.tracing.enabled` | `boolean` | `true` | Enable distributed tracing |
| `firefly.orchestration.resilience.enabled` | `boolean` | `true` | Enable Resilience4j integration |

### Backpressure Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `firefly.orchestration.backpressure.strategy` | `String` | `adaptive` | Strategy name: `adaptive`, `batched`, `circuit-breaker` |
| `firefly.orchestration.backpressure.batch-size` | `int` | `10` | Batch size for batched strategy |
| `firefly.orchestration.backpressure.circuit-breaker.failure-threshold` | `int` | `5` | Failures before circuit opens |
| `firefly.orchestration.backpressure.circuit-breaker.recovery-timeout` | `Duration` | `30s` | Wait before HALF_OPEN transition |
| `firefly.orchestration.backpressure.circuit-breaker.half-open-max-calls` | `int` | `3` | Max probe calls in HALF_OPEN state |

### Validation Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `firefly.orchestration.validation.enabled` | `boolean` | `true` | Enable definition validation at registration |
| `firefly.orchestration.validation.fail-on-warning` | `boolean` | `false` | Treat warnings as errors |

### Event Sourcing Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `firefly.orchestration.eventsourcing.snapshot-interval` | `int` | `100` | Events between automatic snapshots |
| `firefly.orchestration.eventsourcing.projection-poll-interval` | `Duration` | `5s` | Projection polling interval |

### TCC Composition Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `firefly.orchestration.tcc.composition.enabled` | `boolean` | `true` | Enable TCC composition |
| `firefly.orchestration.tcc.composition.compensation-policy` | `CompensationPolicy` | `STRICT_SEQUENTIAL` | Compensation strategy for compositions |

### Saga Compensation Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `firefly.orchestration.saga.compensation-error-handler` | `String` | `default` | Named handler: `default`, `fail-fast`, `log-and-continue`, `retry`, `robust` |

### Example: Production Configuration

```yaml
firefly:
  orchestration:
    saga:
      compensation-policy: RETRY_WITH_BACKOFF
      default-timeout: 2m
      compensation-error-handler: robust
    tcc:
      default-timeout: 15s
      composition:
        enabled: true
        compensation-policy: STRICT_SEQUENTIAL
    persistence:
      provider: redis
      key-prefix: "prod:orchestration:"
      key-ttl: 30d
      retention-period: 14d
      cleanup-interval: 30m
    recovery:
      stale-threshold: 30m
    scheduling:
      thread-pool-size: 8
    backpressure:
      strategy: adaptive
      batch-size: 10
      circuit-breaker:
        failure-threshold: 5
        recovery-timeout: 30s
        half-open-max-calls: 3
    validation:
      enabled: true
      fail-on-warning: false
    eventsourcing:
      snapshot-interval: 100
      projection-poll-interval: 5s
    metrics:
      enabled: true
    tracing:
      enabled: true
```

---

## 39. Auto-Configuration Chain

The module's auto-configurations load in a specific order to ensure dependencies are available.

### Phase 0: Persistence (before everything else)

`OrchestrationPersistenceAutoConfiguration` runs first. It creates the persistence provider based on classpath and properties:

- If `spring-data-redis-reactive` + `provider=redis` → `RedisPersistenceProvider`
- If `fireflyframework-cache` + `provider=cache` → `CachePersistenceProvider`
- If `fireflyframework-eventsourcing` + `provider=event-sourced` → `EventSourcedPersistenceProvider`
- Otherwise → falls through to Phase 1 fallback

### Phase 1: Core Infrastructure

`OrchestrationAutoConfiguration` creates shared beans:

| Bean | Type | Condition |
|------|------|-----------|
| `ArgumentResolver` | `ArgumentResolver` | `@ConditionalOnMissingBean` |
| `StepInvoker` | `StepInvoker` | `@ConditionalOnMissingBean` |
| `OrchestrationLoggerEvents` | `OrchestrationLoggerEvents` | `@ConditionalOnMissingBean` |
| `OrchestrationEvents` | `CompositeOrchestrationEvents` | `@Primary` — wraps all event delegates |
| `ExecutionPersistenceProvider` | `InMemoryPersistenceProvider` | `@ConditionalOnMissingBean` (fallback) |
| `DeadLetterStore` | `InMemoryDeadLetterStore` | `@ConditionalOnMissingBean` |
| `DeadLetterService` | `DeadLetterService` | DLQ enabled (default: `true`) |
| `OrchestrationEventPublisher` | `NoOpEventPublisher` | `@ConditionalOnMissingBean` |
| `OrchestrationScheduler` | `OrchestrationScheduler` | `@ConditionalOnMissingBean` |
| `RecoveryService` | `RecoveryService` | Recovery enabled (default: `true`) |
| `SchedulingPostProcessor` | `SchedulingPostProcessor` | `@ConditionalOnMissingBean` |
| `BackpressureStrategyFactory` | Static utility | Always available (static registry) |
| `OrchestrationValidator` | `OrchestrationValidator` | `validation.enabled=true` (default) |
| `CompensationErrorHandlerFactory` | Static utility | Always available (static registry) |

### Phase 2: Pattern Engines (after core)

Each enabled pattern creates its registry, orchestrator, and engine:

**`WorkflowAutoConfiguration`** (when `workflow.enabled=true`):

| Bean | Type |
|------|------|
| `WorkflowRegistry` | `WorkflowRegistry` |
| `SignalService` | `SignalService` |
| `TimerService` | `TimerService` |
| `WorkflowExecutor` | `WorkflowExecutor` |
| `WorkflowEngine` | `WorkflowEngine` |
| `ChildWorkflowService` | `ChildWorkflowService` |
| `WorkflowQueryService` | `WorkflowQueryService` |
| `SearchAttributeProjection` | `SearchAttributeProjection` |
| `WorkflowSearchService` | `WorkflowSearchService` |
| `ContinueAsNewService` | `ContinueAsNewService` |
| `WorkflowService` | `WorkflowService` (facade) |
| `WorkflowController` | `WorkflowController` (reactive web only) |

**`SagaAutoConfiguration`** (when `saga.enabled=true`):

| Bean | Type |
|------|------|
| `SagaRegistry` | `SagaRegistry` |
| `SagaExecutionOrchestrator` | `SagaExecutionOrchestrator` |
| `CompensationErrorHandler` | Configurable via `saga.compensation-error-handler` |
| `SagaCompensator` | `SagaCompensator` |
| `SagaEngine` | `SagaEngine` |
| `BackpressureStrategy` | Optional, from `backpressure.strategy` via `BackpressureStrategyFactory` |

**`TccAutoConfiguration`** (when `tcc.enabled=true`):

| Bean | Type |
|------|------|
| `TccRegistry` | `TccRegistry` |
| `TccExecutionOrchestrator` | `TccExecutionOrchestrator` |
| `TccEngine` | `TccEngine` |
| `TccCompositor` | `TccCompositor` (when `tcc.composition.enabled=true`) |
| `TccCompositionCompensationManager` | `TccCompositionCompensationManager` |
| `TccCompositionDataFlowManager` | `TccCompositionDataFlowManager` |
| `TccCompositionValidator` | `TccCompositionValidator` |

### Phase 3: Extensions (after core)

| Auto-Configuration | Bean | Classpath Condition |
|--------------------|------|---------------------|
| `OrchestrationMetricsAutoConfiguration` | `OrchestrationMetrics` | `MeterRegistry` on classpath |
| `OrchestrationMetricsAutoConfiguration` | `OrchestrationMetricsEndpoint` | `MeterRegistry` + Actuator on classpath |
| `OrchestrationTracingAutoConfiguration` | `OrchestrationTracer` | `ObservationRegistry` on classpath |
| `OrchestrationResilienceAutoConfiguration` | `ResilienceDecorator` | `CircuitBreakerRegistry` on classpath |
| `OrchestrationRestAutoConfiguration` | `OrchestrationController`, `DeadLetterController`, `OrchestrationHealthIndicator` | Reactive web application |
| `EventGatewayAutoConfiguration` | `EventGateway` + initializer | Always (after pattern engines) |

Additional composition beans registered during Phase 3:

- `CompositionValidator` — validates saga composition structures
- `CompositionVisualizationService` — DOT/Mermaid graph generation
- `CompositionTemplateRegistry` — reusable composition templates

### Overriding Beans

All beans use `@ConditionalOnMissingBean`, so you can replace any of them by defining your own:

```java
@Configuration
public class CustomOrchestrationConfig {

    @Bean
    public ExecutionPersistenceProvider persistenceProvider() {
        return new MyCustomPersistenceProvider();
    }

    @Bean
    public OrchestrationEventPublisher eventPublisher() {
        return new KafkaOrchestrationEventPublisher();
    }
}
```

---

## 40. Spring Boot Integration

### Including the Starter

Add the Maven dependency and the auto-configuration activates automatically via Spring Boot's `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

### Profile-Based Configuration

```yaml
# application.yml — shared defaults
firefly:
  orchestration:
    metrics:
      enabled: true
    tracing:
      enabled: true

---
# application-dev.yml
spring:
  config:
    activate:
      on-profile: dev
firefly:
  orchestration:
    persistence:
      provider: in-memory
    rest:
      enabled: true

---
# application-prod.yml
spring:
  config:
    activate:
      on-profile: prod
firefly:
  orchestration:
    persistence:
      provider: redis
      key-prefix: "prod:orch:"
      retention-period: 30d
    recovery:
      stale-threshold: 15m
    scheduling:
      thread-pool-size: 16
    saga:
      compensation-policy: CIRCUIT_BREAKER
```

### Disabling Patterns

If your application only uses Sagas, disable the others:

```yaml
firefly:
  orchestration:
    workflow:
      enabled: false
    tcc:
      enabled: false
```

### Actuator Integration

When `health.enabled=true` and Spring Boot Actuator is on the classpath, the `OrchestrationHealthIndicator` is registered automatically. It reports:

- `UP` — persistence is healthy
- `DOWN` — persistence health check failed

Visible at `/actuator/health` with details under `orchestration`.

---

[← Back to Index](README.md) | [Previous: Core Infrastructure](core-infrastructure.md) | [Next: Recipes & Production →](recipes-and-production.md)

---
Copyright 2026 Firefly Software Solutions Inc. Licensed under Apache 2.0.
