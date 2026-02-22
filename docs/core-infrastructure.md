[← Back to Index](README.md) | [Previous: TCC Pattern](tcc.md) | [Next: Configuration →](configuration.md)

# Part V: Core Infrastructure

**Contents:**
- [§25 ExecutionContext](#25-executioncontext)
- [§26 Argument Injection (Parameter Resolution)](#26-argument-injection-parameter-resolution)
- [§27 RetryPolicy](#27-retrypolicy)
- [§28 Event Integration](#28-event-integration)
- [§29 Scheduling](#29-scheduling)
- [§30 Lifecycle Callbacks](#30-lifecycle-callbacks)
- [§31 Persistence](#31-persistence)
- [§32 Dead-Letter Queue](#32-dead-letter-queue)
- [§33 Recovery Service](#33-recovery-service)
- [§34 Observability: Events Interface](#34-observability-events-interface)
- [§35 Observability: Metrics & Tracing](#35-observability-metrics--tracing)
- [§36 Topology & DAG Execution](#36-topology--dag-execution)
- [§37 REST API](#37-rest-api)
- [§38 Backpressure Strategies](#38-backpressure-strategies)
- [§39 Execution Reporting](#39-execution-reporting)
- [§40 Validation Framework](#40-validation-framework)
- [§41 Metrics Endpoint](#41-metrics-endpoint)
- [§42 Event Sourcing](#42-event-sourcing)

## 25. ExecutionContext

`ExecutionContext` is the thread-safe state carrier shared across all steps in an execution. It holds variables, headers, step results, step statuses, and pattern-specific state. All internal maps use `ConcurrentHashMap`.

### Factory Methods

| Method | Description |
|--------|-------------|
| `ExecutionContext.forWorkflow(String correlationId, String name)` | Create for a workflow |
| `ExecutionContext.forWorkflow(String correlationId, String name, boolean dryRun)` | Create for a workflow with dry-run flag |
| `ExecutionContext.forSaga(String correlationId, String name)` | Create for a saga (`null` correlationId = auto-generate UUID) |
| `ExecutionContext.forTcc(String correlationId, String name)` | Create for a TCC transaction |

### Identity

```java
ctx.getCorrelationId();     // unique execution ID
ctx.getExecutionName();     // saga/workflow/tcc name
ctx.getPattern();           // ExecutionPattern (WORKFLOW, SAGA, TCC)
ctx.getStartedAt();         // Instant when execution began
ctx.isDryRun();             // true if dry-run mode (workflow only)
```

### Variables

Execution-wide key-value pairs that persist across steps. Useful for sharing state between steps without `@FromStep`.

```java
ctx.putVariable("orderId", "ORD-123");
String orderId = ctx.getVariableAs("orderId", String.class);
Object raw = ctx.getVariable("orderId");
ctx.removeVariable("tempData");
Map<String, Object> all = ctx.getVariables();
```

### Headers

String key-value metadata, similar to HTTP headers. Useful for tenant IDs, trace context, or audit info.

```java
ctx.putHeader("X-Tenant-Id", "acme");
String tenant = ctx.getHeader("X-Tenant-Id");
Map<String, String> all = ctx.getHeaders();
```

### Step Results

Each step's return value is stored by step ID automatically by the engine. You can also set them manually.

```java
ctx.putResult("validate", validationResult);
Object result = ctx.getResult("validate");
Map<String, Object> typed = ctx.getResult("validate", Map.class);
Map<String, Object> all = ctx.getStepResults();
```

### Step Status Tracking

```java
ctx.setStepStatus("validate", StepStatus.RUNNING);
StepStatus status = ctx.getStepStatus("validate");   // PENDING if not set
Map<String, StepStatus> all = ctx.getStepStatuses();
```

### Step Attempts and Latency

```java
int attempts = ctx.incrementAttempts("validate");     // returns new count
int current = ctx.getAttempts("validate");
Map<String, Integer> allAttempts = ctx.getStepAttempts();

ctx.setStepLatency("validate", 150L);                // milliseconds
long latency = ctx.getStepLatency("validate");
Map<String, Long> allLatencies = ctx.getStepLatenciesMs();

ctx.markStepStarted("validate");                     // records Instant.now()
Instant started = ctx.getStepStartedAt("validate");
```

### Idempotency Keys

Prevent duplicate step execution across retries or re-runs.

```java
boolean isNew = ctx.addIdempotencyKey("debit:ORD-123");    // true if new
boolean exists = ctx.hasIdempotencyKey("debit:ORD-123");   // true if exists
Set<String> keys = ctx.getIdempotencyKeys();
```

### Saga-Specific: Compensation State

```java
ctx.putCompensationResult("debit", refundResult);
Object result = ctx.getCompensationResult("debit");
Map<String, Object> allResults = ctx.getCompensationResults();

ctx.putCompensationError("debit", error);
Throwable error = ctx.getCompensationError("debit");
Map<String, Throwable> allErrors = ctx.getCompensationErrors();
```

### Workflow-Specific: Compensatable Step Tracking

```java
ctx.addCompensatableStep("charge");
List<String> compensatable = ctx.getCompletedCompensatableSteps();
```

### TCC-Specific: Try Results and Phase Tracking

```java
ctx.setCurrentPhase(TccPhase.TRY);
TccPhase phase = ctx.getCurrentPhase();

ctx.putTryResult("debit", holdId);
Object tryResult = ctx.getTryResult("debit");
Map<String, Object> allTryResults = ctx.getTryResults();
```

### Topology

```java
ctx.setTopologyLayers(layers);
List<List<String>> layers = ctx.getTopologyLayers();
```

---

## 26. Argument Injection (Parameter Resolution)

The `ArgumentResolver` automatically injects values into step method parameters based on annotations. This works across all three patterns (Workflow, Saga, TCC).

### Supported Annotations

| Annotation | What It Injects | Example |
|------------|----------------|---------|
| `@Input` | The step's input object | `@Input OrderRequest req` |
| `@Input("key")` | A specific key from a Map input | `@Input("orderId") String id` |
| `@FromStep("stepId")` | The result of a completed step | `@FromStep("validate") Map result` |
| `@FromCompensationResult("stepId")` | Compensation result of a step | `@FromCompensationResult("debit") Object r` |
| `@CompensationError("stepId")` | Compensation error of a step | `@CompensationError("debit") Throwable err` |
| `@FromTry("participantId")` | TCC Try-phase result (for Confirm/Cancel methods) | `@FromTry("debit") String holdId` |
| `@Header("name")` | A single header value from context | `@Header("X-Tenant") String tenant` |
| `@Headers` | The full headers map | `@Headers Map<String,String> headers` |
| `@Variable("name")` | A single context variable | `@Variable("orderId") String id` |
| `@Variables` | The full variables map | `@Variables Map<String,Object> vars` |
| `@CorrelationId` | The execution's correlation ID | `@CorrelationId String corrId` |
| `@Required` | Modifier — enforces non-null (throws if null) | `@Required @FromStep("x") Object r` |

### Method-Level Annotations

| Annotation | What It Does | Example |
|------------|-------------|---------|
| `@SetVariable("name")` | Stores the method's return value as a context variable | See below |

```java
@SagaStep(id = "lookup")
@SetVariable("customerId")
public Mono<String> lookupCustomer(@Input OrderRequest req) {
    return customerService.findId(req.email());
}

@SagaStep(id = "charge", dependsOn = "lookup")
public Mono<Void> charge(@Variable("customerId") String customerId) {
    return paymentService.charge(customerId);
}
```

### ExecutionContext Injection

If a parameter's type is `ExecutionContext`, it is injected by type — no annotation needed:

```java
@SagaStep(id = "process")
public Mono<Void> process(@Input OrderRequest req, ExecutionContext ctx) {
    ctx.putVariable("processedAt", Instant.now());
    return doProcess(req);
}
```

### Unannotated Parameters

If a parameter has no annotation and is not `ExecutionContext`, the resolver applies these rules:

1. If the parameter type matches the input type → inject the input
2. Otherwise → inject `null`

Only one unannotated parameter is allowed per method. Multiple unannotated parameters result in an error.

### Resolution Order

The `ArgumentResolver` processes parameters in this order:

1. `ExecutionContext` (by type)
2. `@Input` / `@Input("key")`
3. `@FromStep`
4. `@FromCompensationResult`
5. `@CompensationError`
6. `@FromTry`
7. `@Header`
8. `@Headers`
9. `@Variable`
10. `@Variables`
11. `@CorrelationId`
12. Implicit (unannotated)

### Caching

The resolver caches the compiled argument resolution strategy per method in a `ConcurrentHashMap`. The first invocation of each method compiles the resolver chain; subsequent invocations reuse it.

### Example: Multiple Annotations

```java
@SagaStep(id = "ship", dependsOn = {"charge", "reserve"})
public Mono<String> ship(
        @FromStep("charge") String chargeId,
        @FromStep("reserve") String reservationId,
        @Header("X-Tenant-Id") String tenantId,
        @Variable("priority") String priority,
        @CorrelationId String correlationId,
        ExecutionContext ctx) {
    log.info("Shipping for tenant {} with priority {}, corr={}",
        tenantId, priority, correlationId);
    return shippingService.ship(chargeId, reservationId);
}
```

---

## 27. RetryPolicy

`RetryPolicy` configures retry behavior with exponential backoff and jitter for step execution.

### Record Fields

```java
public record RetryPolicy(
    int maxAttempts,              // total attempts (1 = no retry)
    Duration initialDelay,        // delay before first retry
    Duration maxDelay,            // cap on exponential growth
    double multiplier,            // backoff multiplier (2.0 = doubling)
    double jitterFactor,          // 0.0–1.0, randomizes delay
    String[] retryableExceptions  // only retry these exception types (empty = all)
)
```

### Predefined Policies

```java
RetryPolicy.DEFAULT    // maxAttempts=3, initialDelay=1s, maxDelay=5m, multiplier=2.0, jitter=0.0
RetryPolicy.NO_RETRY   // maxAttempts=1, initialDelay=0, maxDelay=0, multiplier=1.0, jitter=0.0
```

### How Backoff Works

```
Attempt 1: execute (fail)
  Delay = initialDelay * multiplier^0 = 1s
Attempt 2: execute (fail)
  Delay = initialDelay * multiplier^1 = 2s
Attempt 3: execute (fail)
  Delay = initialDelay * multiplier^2 = 4s  (capped at maxDelay)
```

With jitter (factor = 0.1):

```
Base delay = 2s
Jittered delay = 2s ± (2s * 0.1) = between 1.8s and 2.2s
```

### Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `calculateDelay(int attempt)` | `Duration` | Compute delay for a given attempt number |
| `shouldRetry(int currentAttempt)` | `boolean` | `true` if `currentAttempt < maxAttempts` |

### Validation

- `maxAttempts` must be ≥ 1
- `multiplier` must be ≥ 1.0
- `jitterFactor` must be between 0.0 and 1.0

### Configuration Levels

Retry can be configured at multiple levels with this precedence (highest first):

1. **Step-level annotation:** `@SagaStep(retry = 5, backoffMs = 1000)` or `@WorkflowStep(maxRetries = 5)`
2. **Step-level builder:** `.retry(5).backoffMs(1000)`
3. **Workflow-level annotation:** `@Workflow(maxRetries = 3, retryDelayMs = 1000)`
4. **Workflow-level builder:** `.retryPolicy(RetryPolicy.DEFAULT)`
5. **Global default:** `RetryPolicy.DEFAULT`

For sagas and TCCs, there is no saga/tcc-level default — retry is always per-step or per-participant.

---

## 28. Event Integration

The event system has two directions: **inbound** (triggering orchestrations from external events) and **outbound** (publishing events when steps complete).

### Inbound: Event-Driven Triggering

Any orchestration can be triggered by an external event using `triggerEventType`. The `EventGateway` maintains a registry of event type → orchestration mappings.

**Annotation:**

```java
@Saga(name = "OrderSaga", triggerEventType = "OrderCreated")
@Tcc(name = "PaymentTcc", triggerEventType = "PaymentRequested")
@Workflow(id = "Pipeline", triggerEventType = "DataIngested")
```

**Builder:**

```java
SagaBuilder.saga("OrderSaga").triggerEventType("OrderCreated")
TccBuilder.tcc("PaymentTcc").triggerEventType("PaymentRequested")
new WorkflowBuilder("Pipeline").triggerEventType("DataIngested")
```

**Routing events:**

```java
eventGateway.routeEvent("OrderCreated", Map.of("orderId", "ORD-123"))
    .subscribe();
```

### EventGateway API

| Method | Return Type | Description |
|--------|-------------|-------------|
| `register(eventType, targetName, pattern, executor)` | `void` | Register an event → orchestration mapping |
| `routeEvent(eventType, payload)` | `Mono<Void>` | Route an event to its registered orchestration |
| `hasRegistration(eventType)` | `boolean` | Check if an event type is registered |
| `registrationCount()` | `int` | Number of registered event types |
| `registeredEventTypes()` | `Set<String>` | All registered event types |

Registration happens automatically during startup via `EventGatewayAutoConfiguration`, which scans all registries for definitions with `triggerEventType` set.

### Outbound: Step-Level Event Publishing

**Saga — @StepEvent:**

Publishes an event each time a step completes successfully:

```java
@SagaStep(id = "reserve")
@StepEvent(topic = "inventory", type = "InventoryReserved", key = "orderId")
public Mono<String> reserve(@Input OrderRequest req) { ... }
```

Builder equivalent: `.stepEvent("inventory", "InventoryReserved", "orderId")`

**TCC — @TccEvent:**

Publishes an event when a participant completes its Confirm phase:

```java
@TccEvent(topic = "payments", eventType = "PaymentConfirmed", key = "txId")
@TccParticipant(id = "debit", order = 0)
public static class DebitParticipant { ... }
```

Builder equivalent: `.event("payments", "PaymentConfirmed", "txId")`

**Workflow — publishEvents:**

When `publishEvents = true`, the engine publishes an `OrchestrationEvent` for each step completion:

```java
@Workflow(id = "Pipeline", publishEvents = true)
```

Individual steps can also specify custom event types: `@WorkflowStep(id = "extract", outputEventType = "DataExtracted")`

### OrchestrationEvent Record

```java
record OrchestrationEvent(
    String eventType,       // "EXECUTION_STARTED", "STEP_COMPLETED", etc.
    String executionName,
    String correlationId,
    ExecutionPattern pattern,
    String stepId,
    ExecutionStatus status,
    Map<String, Object> payload,
    Instant timestamp,
    String topic,           // from @StepEvent/@TccEvent
    String type,            // from @StepEvent/@TccEvent
    String key              // from @StepEvent/@TccEvent
)
```

Factory methods: `executionStarted(...)`, `executionCompleted(...)`, `stepCompleted(...)`, `stepFailed(...)`.

### OrchestrationEventPublisher

Events are published through the `OrchestrationEventPublisher` interface. The default is `NoOpEventPublisher` — replace it with a custom bean for your messaging infrastructure:

```java
@Component
public class KafkaEventPublisher implements OrchestrationEventPublisher {
    @Override
    public Mono<Void> publish(OrchestrationEvent event) {
        return kafkaTemplate.send(event.topic(), event.key(), event).then();
    }
}
```

The auto-configuration uses `@ConditionalOnMissingBean`, so your custom bean takes precedence automatically.

---

## 29. Scheduling

All three patterns support scheduled execution with full attribute parity.

### Scheduling Annotations

All scheduling annotations share the same 8 attributes (see [§4](#4-workflow-annotation-reference), [§12](#12-saga-annotation-reference), [§19](#19-tcc-annotation-reference) for the full tables):

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `cron` | `String` | `""` | Cron expression (6 fields: `second minute hour day month weekday`) |
| `zone` | `String` | `""` | Timezone for cron (e.g., `"America/New_York"`) |
| `fixedRate` | `long` | `-1` | Fixed rate in milliseconds |
| `fixedDelay` | `long` | `-1` | Fixed delay in milliseconds |
| `initialDelay` | `long` | `0` | Initial delay before first execution |
| `enabled` | `boolean` | `true` | Whether this schedule is active |
| `input` | `String` | `"{}"` | JSON input passed to the engine |
| `description` | `String` | `""` | Human-readable description |

### Examples

```java
// Saga on a cron schedule
@Saga(name = "CleanupSaga")
@ScheduledSaga(cron = "0 0 2 * * *", zone = "America/New_York",
               input = "{\"daysOld\": 30}", description = "Nightly cleanup")
public class CleanupSaga { ... }

// TCC on a fixed rate
@Tcc(name = "ReconciliationTcc")
@ScheduledTcc(fixedRate = 60000, initialDelay = 5000,
              input = "{\"batchSize\": 100}")
public class ReconciliationTcc { ... }

// Workflow on a fixed delay
@Workflow(id = "ReportWorkflow")
@ScheduledWorkflow(fixedDelay = 3600000, zone = "UTC")
public class ReportWorkflow { ... }
```

### Multiple Schedules

All scheduling annotations are `@Repeatable` — apply multiple to a single class:

```java
@Saga(name = "multi-schedule")
@ScheduledSaga(fixedRate = 2000)
@ScheduledSaga(cron = "0 0 * * * *")
public class MultiScheduleSaga { ... }
```

### How Scanning Works

`SchedulingPostProcessor` is a `BeanPostProcessor` that scans all beans for `@ScheduledWorkflow`, `@ScheduledSaga`, and `@ScheduledTcc` annotations. For each annotation found, it registers a task with `OrchestrationScheduler` that invokes the corresponding engine.

### OrchestrationScheduler API

| Method | Description |
|--------|-------------|
| `scheduleAtFixedRate(taskId, task, initialDelayMs, periodMs)` | Schedule a task at a fixed rate |
| `scheduleWithFixedDelay(taskId, task, initialDelayMs, delayMs)` | Schedule with fixed delay between completions |
| `scheduleWithCron(taskId, task, cronExpression)` | Schedule using a cron expression |
| `scheduleWithCron(taskId, task, cronExpression, zone)` | Schedule with cron and timezone |
| `cancel(taskId)` | Cancel a scheduled task |
| `shutdown()` | Shut down the scheduler |
| `activeTaskCount()` | Number of active scheduled tasks |

Thread pool size:

```yaml
firefly:
  orchestration:
    scheduling:
      thread-pool-size: 4   # default
```

---

## 30. Lifecycle Callbacks

All three patterns support lifecycle callbacks for handling success and error conditions. Callbacks are declared as methods on the same bean as the orchestration definition.

### Common Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `async` | `boolean` | `false` | Run callback on bounded-elastic scheduler (non-blocking) |
| `priority` | `int` | `0` | Ordering — lower values execute first |

Error callbacks add:

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `errorTypes` | `Class<? extends Throwable>[]` | `{}` | Filter by exception type (empty = all) |
| `suppressError` | `boolean` | `false` | Convert failure to success |

### Saga Callbacks

```java
@OnSagaComplete(async = false, priority = 0)
public void onComplete(ExecutionContext ctx, SagaResult result) {
    // Both parameters are optional — declare only what you need
    log.info("Saga {} completed: {}", result.sagaName(), result.isSuccess());
}

@OnSagaError(errorTypes = {TimeoutException.class}, suppressError = false)
public void onError(Throwable error, ExecutionContext ctx) {
    log.error("Saga {} failed: {}", ctx.getExecutionName(), error.getMessage());
}
```

`@OnSagaComplete` receives both `ExecutionContext` and `SagaResult` via argument resolution. Either is optional.

`@OnSagaError` with `suppressError = true` converts the saga result to a success — useful for fallback behavior.

### TCC Callbacks

```java
@OnTccComplete(async = false, priority = 0)
public void onComplete(TccResult result) {
    log.info("TCC {} confirmed: {}", result.tccName(), result.isConfirmed());
}

@OnTccError(errorTypes = {}, suppressError = false)
public void onError(Throwable error, ExecutionContext ctx) {
    log.error("TCC {} failed at {}: {}",
        ctx.getExecutionName(), ctx.getCurrentPhase(), error.getMessage());
}
```

### Workflow Callbacks

```java
@OnWorkflowComplete(async = false, priority = 0)
public void onComplete(ExecutionContext ctx) {
    log.info("Workflow {} completed", ctx.getExecutionName());
}

@OnWorkflowError(errorTypes = {}, stepIds = {}, suppressError = false)
public void onError(Throwable error, ExecutionContext ctx) {
    log.error("Workflow {} failed: {}", ctx.getExecutionName(), error.getMessage());
}
```

`@OnWorkflowError` has an additional `stepIds` attribute to filter by which steps caused the error.

### Step-Level Callback (Workflow Only)

```java
@OnStepComplete(stepIds = {"charge", "reserve"}, async = false, priority = 0)
public void onStepDone(ExecutionContext ctx) {
    log.info("Payment/inventory step completed");
}
```

### Priority Ordering

When multiple callbacks are declared, they execute in `priority` order (ascending):

```java
@OnSagaComplete(priority = 0)  // runs first
public void logCompletion(SagaResult result) { ... }

@OnSagaComplete(priority = 10) // runs second
public void notifyTeam(SagaResult result) { ... }
```

### Async Execution

When `async = true`, the callback runs on a separate bounded-elastic thread, preventing it from blocking the main execution path:

```java
@OnSagaComplete(async = true)
public void sendNotification(SagaResult result) {
    // This runs on a separate thread — won't block saga completion
    emailService.sendSync(result.correlationId());
}
```

---

## 31. Persistence

### ExecutionPersistenceProvider Interface

All execution state is stored through this pluggable interface:

```java
public interface ExecutionPersistenceProvider {
    Mono<Void> save(ExecutionState state);
    Mono<Optional<ExecutionState>> findById(String correlationId);
    Mono<Void> updateStatus(String correlationId, ExecutionStatus status);
    Flux<ExecutionState> findByPattern(ExecutionPattern pattern);
    Flux<ExecutionState> findByStatus(ExecutionStatus status);
    Flux<ExecutionState> findInFlight();
    Flux<ExecutionState> findStale(Instant before);
    Mono<Long> cleanup(Duration olderThan);
    Mono<Boolean> isHealthy();
}
```

### ExecutionState Record

The persisted snapshot of an execution's progress:

```java
record ExecutionState(
    String correlationId,              // unique execution ID
    String executionName,              // saga/workflow/tcc name
    ExecutionPattern pattern,          // WORKFLOW, SAGA, or TCC
    ExecutionStatus status,            // PENDING, RUNNING, COMPLETED, FAILED, etc.
    Map<String, Object> stepResults,   // step ID → return value
    Map<String, StepStatus> stepStatuses,  // step ID → status
    Map<String, Integer> stepAttempts,     // step ID → attempt count
    Map<String, Long> stepLatenciesMs,     // step ID → latency in ms
    Map<String, Object> variables,     // execution variables
    Map<String, String> headers,       // execution headers
    Set<String> idempotencyKeys,       // idempotency deduplication
    List<List<String>> topologyLayers, // DAG layers
    String failureReason,              // error message (if failed)
    Instant startedAt,                 // execution start
    Instant updatedAt                  // last update
)
```

Methods: `withStatus(ExecutionStatus)`, `withFailure(String)`, `isTerminal()`.

### Provider Selection

```yaml
firefly:
  orchestration:
    persistence:
      provider: in-memory    # default
      # provider: redis
      # provider: cache
      # provider: event-sourced
```

### Built-In Providers

#### InMemoryPersistenceProvider (Default)

- Storage: `ConcurrentHashMap<String, ExecutionState>`
- Full query support (`findByPattern`, `findByStatus`, `findInFlight`, `findStale`)
- Cleanup supported
- Data lost on process restart
- Best for: development, testing

#### RedisPersistenceProvider

- Requires: `spring-boot-starter-data-redis-reactive` on classpath
- Condition: `firefly.orchestration.persistence.provider=redis`
- Key patterns:
  - `{prefix}state:{correlationId}` — serialized execution state
  - `{prefix}meta:{correlationId}` — lightweight metadata (pattern, status, timestamp)
- Configurable key prefix and TTL
- Full query support, durable across restarts
- Best for: production, multi-instance deployments

#### CachePersistenceProvider

- Requires: `fireflyframework-cache` module on classpath
- Condition: `firefly.orchestration.persistence.provider=cache`
- Uses the Firefly `CacheAdapter` abstraction
- Key patterns:
  - `orchestration:state:{correlationId}` — full execution state
  - `orchestration:pattern:{pattern}:{correlationId}` — pattern index
  - `orchestration:status:{status}:{correlationId}` — status index
- Configurable TTL via `firefly.orchestration.persistence.key-ttl`
- Best for: when you already use the Firefly Cache module

#### EventSourcedPersistenceProvider

- Requires: `fireflyframework-eventsourcing` module on classpath
- Condition: `firefly.orchestration.persistence.provider=event-sourced`
- Aggregate type: `orchestration-execution`
- Event type: `ExecutionStateChanged`
- Write-optimized (append-only events)
- **Limitations:** `findByPattern()`, `findByStatus()`, `findInFlight()`, `findStale()` return empty; `cleanup()` is a no-op (events are immutable)
- Best for: audit-heavy environments requiring full event history

### Provider Comparison

| Feature       | InMemory          | Redis    | Cache            | EventSourced     |
|---------------|-------------------|----------|------------------|------------------|
| Persistence   | Process lifetime  | Durable  | Adapter-dependent| Durable          |
| Query support | Full              | Full     | Full             | Limited          |
| Cleanup       | Supported         | Supported| Supported        | No-op            |
| Clustering    | No                | Yes      | Adapter-dependent| Yes              |
| Performance   | Fastest           | Fast     | Varies           | Write-optimized  |

### Custom Persistence Provider

Implement the interface and register as a Spring bean:

```java
@Component
public class JdbcPersistenceProvider implements ExecutionPersistenceProvider {
    // Your JDBC-based implementation
}
```

The auto-configuration uses `@ConditionalOnMissingBean`, so your custom bean takes precedence.

### Persistence Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `firefly.orchestration.persistence.provider` | `String` | `in-memory` | Provider type |
| `firefly.orchestration.persistence.key-prefix` | `String` | `orchestration:` | Key prefix for Redis/Cache |
| `firefly.orchestration.persistence.key-ttl` | `Duration` | *(none)* | Optional key TTL |
| `firefly.orchestration.persistence.retention-period` | `Duration` | `7d` | Completed execution retention |
| `firefly.orchestration.persistence.cleanup-interval` | `Duration` | `1h` | Cleanup task interval |

---

## 32. Dead-Letter Queue

Failed executions are automatically sent to the DLQ for later inspection and retry.

### Configuration

```yaml
firefly:
  orchestration:
    dlq:
      enabled: true   # default
```

### DeadLetterEntry Record

```java
record DeadLetterEntry(
    String id,                       // unique DLQ entry ID (UUID)
    String executionName,            // saga/workflow/tcc name
    String correlationId,            // execution correlation ID
    ExecutionPattern pattern,        // WORKFLOW, SAGA, or TCC
    String stepId,                   // which step/participant failed
    ExecutionStatus statusAtFailure, // status when failure occurred
    String errorMessage,             // error description
    String errorType,                // exception class name
    Map<String, Object> input,       // execution inputs captured at failure
    int retryCount,                  // number of retry attempts
    Instant createdAt,
    Instant lastRetriedAt
)
```

Factory method: `DeadLetterEntry.create(executionName, correlationId, pattern, stepId, statusAtFailure, error, input)`

### DeadLetterService API

| Method | Return Type | Description |
|--------|-------------|-------------|
| `deadLetter(DeadLetterEntry entry)` | `Mono<Void>` | Add an entry to the DLQ |
| `getAllEntries()` | `Flux<DeadLetterEntry>` | List all entries |
| `getEntry(String id)` | `Mono<Optional<DeadLetterEntry>>` | Get a specific entry |
| `getByExecutionName(String name)` | `Flux<DeadLetterEntry>` | Find by saga/workflow/tcc name |
| `getByCorrelationId(String correlationId)` | `Flux<DeadLetterEntry>` | Find by correlation ID |
| `deleteEntry(String id)` | `Mono<Void>` | Remove an entry |
| `count()` | `Mono<Long>` | Count total entries |
| `markRetried(String id)` | `Mono<DeadLetterEntry>` | Increment retry count and update timestamp |

### When Entries Are Created

- **Saga:** One DLQ entry per failed step (each with its own `stepId`)
- **TCC:** One DLQ entry for the failed transaction (only for `FAILED` status, not `CANCELED`)
- **Workflow:** One DLQ entry per failed step when the step exhausts all retries

### Custom DLQ Store

The default `InMemoryDeadLetterStore` can be replaced:

```java
@Component
public class JdbcDeadLetterStore implements DeadLetterStore {
    Mono<Void> save(DeadLetterEntry entry);
    Mono<Optional<DeadLetterEntry>> findById(String id);
    Flux<DeadLetterEntry> findAll();
    Flux<DeadLetterEntry> findByExecutionName(String executionName);
    Flux<DeadLetterEntry> findByCorrelationId(String correlationId);
    Mono<Void> delete(String id);
    Mono<Long> count();
}
```

### DLQ REST Endpoints

```
GET    /api/orchestration/dlq          # list all entries
GET    /api/orchestration/dlq/count    # count entries
DELETE /api/orchestration/dlq/{id}     # remove an entry
```

---

## 33. Recovery Service

The `RecoveryService` detects and cleans up stale executions — those that started but never reached a terminal state.

### Configuration

```yaml
firefly:
  orchestration:
    recovery:
      enabled: true        # default
      stale-threshold: 1h  # default — executions older than this are considered stale
    persistence:
      retention-period: 7d # completed executions are cleaned up after this period
      cleanup-interval: 1h # how often the cleanup task runs
```

### API

| Method | Return Type | Description |
|--------|-------------|-------------|
| `findStaleExecutions()` | `Flux<ExecutionState>` | Find executions older than `staleThreshold` that are not terminal |
| `cleanupCompletedExecutions(Duration olderThan)` | `Mono<Long>` | Delete completed executions older than the given duration; returns count deleted |

### Automatic Cleanup

When recovery is enabled, `OrchestrationAutoConfiguration` registers a `SmartInitializingSingleton` bean that schedules periodic cleanup:

- **Interval:** `firefly.orchestration.persistence.cleanup-interval` (default: 1 hour)
- **Retention:** `firefly.orchestration.persistence.retention-period` (default: 7 days)

### Usage

```java
@Autowired RecoveryService recoveryService;

// Find stale executions for alerting
recoveryService.findStaleExecutions()
    .subscribe(state -> log.warn("Stale execution: {} (started: {})",
        state.correlationId(), state.startedAt()));

// Manual cleanup
recoveryService.cleanupCompletedExecutions(Duration.ofDays(30))
    .subscribe(count -> log.info("Cleaned up {} old executions", count));
```

---

## 34. Observability: Events Interface

All orchestration lifecycle events flow through the `OrchestrationEvents` interface. Every method has a default no-op implementation, so you only override what you need.

### Lifecycle Events (All Patterns)

| Method | When It Fires |
|--------|---------------|
| `onStart(String name, String correlationId, ExecutionPattern pattern)` | Execution begins |
| `onStepStarted(String name, String correlationId, String stepId)` | A step begins execution |
| `onStepSuccess(String name, String correlationId, String stepId, int attempts, long latencyMs)` | A step completes successfully |
| `onStepFailed(String name, String correlationId, String stepId, Throwable error, int attempts)` | A step fails after all retries |
| `onStepSkipped(String name, String correlationId, String stepId)` | A step is skipped (condition not met or dry-run) |
| `onStepSkippedIdempotent(String name, String correlationId, String stepId)` | A step is skipped due to idempotency key |
| `onCompleted(String name, String correlationId, ExecutionPattern pattern, boolean success, long durationMs)` | Execution completes (success or failure) |
| `onDeadLettered(String name, String correlationId, String stepId, Throwable error)` | An entry is added to the DLQ |

### Saga-Specific Events

| Method | When It Fires |
|--------|---------------|
| `onCompensationStarted(String name, String correlationId)` | Compensation phase begins |
| `onStepCompensated(String name, String correlationId, String stepId)` | A step's compensation succeeds |
| `onStepCompensationFailed(String name, String correlationId, String stepId, Throwable error)` | A step's compensation fails |

### TCC-Specific Events

| Method | When It Fires |
|--------|---------------|
| `onPhaseStarted(String name, String correlationId, TccPhase phase)` | TRY/CONFIRM/CANCEL phase begins |
| `onPhaseCompleted(String name, String correlationId, TccPhase phase, long durationMs)` | A phase completes |
| `onPhaseFailed(String name, String correlationId, TccPhase phase, Throwable error)` | A phase fails |
| `onParticipantStarted(String name, String correlationId, String participantId, TccPhase phase)` | A participant begins a phase |
| `onParticipantSuccess(String name, String correlationId, String participantId, TccPhase phase)` | A participant completes a phase |
| `onParticipantFailed(String name, String correlationId, String participantId, TccPhase phase, Throwable error)` | A participant fails a phase |

### Workflow-Specific Events

| Method | When It Fires |
|--------|---------------|
| `onWorkflowSuspended(String name, String correlationId, String reason)` | A workflow is suspended |
| `onWorkflowResumed(String name, String correlationId)` | A workflow is resumed |
| `onSignalDelivered(String name, String correlationId, String signalName)` | A signal is delivered |
| `onTimerFired(String name, String correlationId, String timerId)` | A timer fires |
| `onChildWorkflowStarted(String parentName, String parentCorrId, String childWorkflowId, String childCorrId)` | A child workflow starts |
| `onChildWorkflowCompleted(String parentName, String parentCorrId, String childCorrId, boolean success)` | A child workflow completes |
| `onContinueAsNew(String name, String oldCorrId, String newCorrId)` | Workflow continues as new execution |

### Composition Events

| Method | When It Fires |
|--------|---------------|
| `onCompositionStarted(String compositionName, String correlationId)` | Cross-pattern composition begins |
| `onCompositionCompleted(String compositionName, String correlationId, boolean success)` | Composition completes |

### CompositeOrchestrationEvents

The auto-configuration collects **all** `OrchestrationEvents` beans and wraps them in a `CompositeOrchestrationEvents` that fans out each call to every delegate. Each delegate has error isolation — one listener failing does not prevent others from firing.

### Custom Event Listener

```java
@Component
public class SlackNotifier implements OrchestrationEvents {
    @Override
    public void onCompleted(String name, String correlationId,
                            ExecutionPattern pattern, boolean success, long durationMs) {
        if (!success) {
            slackClient.send("#alerts",
                "Orchestration FAILED: " + name + " (" + correlationId + ")");
        }
    }
}
```

---

## 35. Observability: Metrics & Tracing

### Metrics (Micrometer)

Enabled automatically when a `MeterRegistry` bean is on the classpath.

```yaml
firefly:
  orchestration:
    metrics:
      enabled: true   # default
```

`OrchestrationMetrics` implements `OrchestrationEvents` and records the following metrics:

| Metric Name | Type | Tags | Description |
|-------------|------|------|-------------|
| `firefly.orchestration.executions.started` | Counter | `name`, `pattern` | Executions started |
| `firefly.orchestration.executions.completed` | Counter | `name`, `pattern`, `success` | Executions completed |
| `firefly.orchestration.executions.duration` | Timer | `name`, `pattern` | Execution duration |
| `firefly.orchestration.steps.completed` | Counter | `name`, `stepId`, `success` | Steps completed |
| `firefly.orchestration.steps.duration` | Timer | `name`, `stepId` | Step duration |
| `firefly.orchestration.steps.retries` | Counter | `name`, `stepId` | Step retry attempts |
| `firefly.orchestration.compensations.started` | Counter | `name` | Compensation phases started |
| `firefly.orchestration.dlq.entries` | Counter | `name` | DLQ entries created |

### Tracing (Micrometer Observation)

Enabled automatically when an `ObservationRegistry` bean is on the classpath.

```yaml
firefly:
  orchestration:
    tracing:
      enabled: true   # default
```

`OrchestrationTracer` creates Micrometer Observations (spans) at two levels:

**Execution-level span:**
- Observation name: `orchestration.execution`
- Tags: `orchestration.name`, `orchestration.pattern`, `orchestration.correlationId`

**Step-level span:**
- Observation name: `orchestration.step`
- Tags: `orchestration.name`, `orchestration.step`, `orchestration.correlationId`

### OrchestrationTracer API

| Method | Description |
|--------|-------------|
| `<T> Mono<T> traceExecution(String name, ExecutionPattern pattern, String correlationId, Mono<T> mono)` | Wrap an execution in a traced observation |
| `<T> Mono<T> traceStep(String executionName, String stepId, String correlationId, Mono<T> mono)` | Wrap a step in a traced observation |

The tracer is wired automatically into engines when present — you don't need to call these methods directly.

---

## 36. Topology & DAG Execution

### TopologyBuilder

`TopologyBuilder` computes execution layers from step dependency graphs using **Kahn's BFS algorithm**. It is used internally by all three engines.

### Algorithm

1. Build adjacency list from `dependsOn` declarations
2. Compute in-degree for each step
3. BFS: steps with in-degree 0 form Layer 0
4. Remove Layer 0, recompute in-degrees → Layer 1
5. Repeat until all steps are layered
6. If steps remain unlayered → cycle detected → `TopologyValidationException`

### Public API

```java
// Build layers from steps
public static <T> List<List<String>> buildLayers(
    List<T> steps,
    Function<T, String> idExtractor,
    Function<T, List<String>> dependsOnExtractor
) throws TopologyValidationException

// Validate without building
public static <T> void validate(
    List<T> steps,
    Function<T, String> idExtractor,
    Function<T, List<String>> dependsOnExtractor
) throws TopologyValidationException
```

### Validation Checks

1. Steps list is not empty
2. No duplicate step IDs
3. All dependencies reference existing steps
4. No self-dependencies (`dependsOn` does not contain own ID)
5. No circular dependencies (DFS cycle detection)

### Layer Execution

Within each layer, steps execute **concurrently** (via `Flux.merge`). The optional `layerConcurrency` setting limits how many steps in a single layer can run at the same time.

```
Layer 0: [validate]              → runs alone
Layer 1: [charge, reserve, log]  → all three run in parallel
Layer 2: [ship]                  → waits for all Layer 1 steps
```

Within a layer, steps are ordered by their `order` attribute (ascending) as a tiebreaker — but this only affects order within the concurrent batch, not serial execution.

### Example DAG Visualization

```java
// Get topology from WorkflowQueryService
workflowQueryService.getTopologyLayers(correlationId)
    .subscribe(opt -> opt.ifPresent(layers -> {
        for (int i = 0; i < layers.size(); i++) {
            System.out.println("Layer " + i + ": " + layers.get(i));
        }
    }));
// Output:
// Layer 0: [validate]
// Layer 1: [charge, reserve]
// Layer 2: [ship]
```

---

## 37. REST API

### Configuration

```yaml
firefly:
  orchestration:
    rest:
      enabled: true   # default — exposed only for reactive web applications
```

### Workflow Controller

**Base Path:** `/api/orchestration/workflows`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | List all workflow definitions |
| `GET` | `/{workflowId}` | Get workflow definition details |
| `GET` | `/{workflowId}/topology` | Get execution topology (DAG layers) |
| `POST` | `/{workflowId}/start` | Start a workflow (accepts `StartExecutionRequest` body) |
| `POST` | `/instances/{correlationId}/cancel` | Cancel a running workflow |
| `POST` | `/instances/{correlationId}/suspend` | Suspend a running workflow |
| `POST` | `/instances/{correlationId}/resume` | Resume a suspended workflow |
| `GET` | `/instances/{correlationId}` | Get execution summary |
| `GET` | `/instances` | List instances (optional `?status=` filter) |
| `POST` | `/instances/{correlationId}/signal/{signalName}` | Deliver a signal |
| `GET` | `/instances/{correlationId}/timers` | List pending timers |
| `DELETE` | `/instances/{correlationId}/timers/{timerId}` | Cancel a timer |

### Orchestration Controller (Cross-Pattern)

**Base Path:** `/api/orchestration`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/executions` | List all executions (optional `?status=` filter) |
| `GET` | `/executions/{id}` | Get execution by correlation ID |

### Dead-Letter Controller

**Base Path:** `/api/orchestration/dlq`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/` | List all DLQ entries |
| `GET` | `/count` | Count DLQ entries |
| `DELETE` | `/{id}` | Delete a DLQ entry |

### Health Indicator

```yaml
firefly:
  orchestration:
    health:
      enabled: true   # default
```

Reports `UP` when `ExecutionPersistenceProvider.isHealthy()` returns `true`, `DOWN` otherwise. Visible at `/actuator/health`.

### Exception Hierarchy

All orchestration exceptions extend the sealed `OrchestrationException`:

```
OrchestrationException (sealed)
├── ExecutionNotFoundException       — workflow/saga/tcc ID not found in registry
├── StepExecutionException           — step method invocation failed
│     getStepId()                    — which step failed
├── TopologyValidationException      — DAG has cycles, missing deps, duplicates
├── DuplicateDefinitionException     — two definitions share the same name
├── ExecutionTimeoutException        — execution or step timed out
├── CompensationException            — saga compensation step failed
└── TccPhaseException                — TCC Try/Confirm/Cancel phase error
      getPhase()                     — which phase (TRY, CONFIRM, CANCEL)
```

---

## 38. Backpressure Strategies

The backpressure subsystem in `org.fireflyframework.orchestration.core.backpressure` provides reactive flow-control strategies for parallel step execution.

### BackpressureStrategy Interface

```java
public interface BackpressureStrategy {

    @FunctionalInterface
    interface ItemProcessor<T, R> {
        Mono<R> process(T item);
    }

    <T, R> Flux<R> applyBackpressure(List<T> items, ItemProcessor<T, R> processor);
}
```

All strategies accept a list of items and an `ItemProcessor` that processes each item reactively, returning a `Flux<R>` of results with the strategy's flow-control applied.

### Implementations

#### AdaptiveBackpressureStrategy

Dynamic concurrency adjustment based on real-time error rates. Uses `AtomicInteger currentConcurrency` and `AtomicLong totalProcessed` / `AtomicLong totalErrors` for thread-safe tracking.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `initialConcurrency` | `int` | `4` | Starting concurrency level |
| `minConcurrency` | `int` | `1` | Floor for concurrency scaling |
| `maxConcurrency` | `int` | `16` | Ceiling for concurrency scaling |
| `errorRateThreshold` | `double` | `0.1` | Error rate above which concurrency is reduced |

**Scaling behavior:**

- When error rate exceeds `errorRateThreshold` → concurrency is **halved** (floored at `minConcurrency`)
- When error rate drops below half of `errorRateThreshold` → concurrency is **incremented by 1** (capped at `maxConcurrency`)

Uses `Flux.flatMap(processor, currentConcurrency.get())` internally to enforce the dynamic concurrency limit.

#### BatchedBackpressureStrategy

Fixed-size batch processing. Batches are processed **sequentially**; items **within** a batch are processed concurrently.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `batchSize` | `int` | `10` | Number of items per batch |

```java
Flux.fromIterable(items)
    .buffer(batchSize)
    .concatMap(batch -> Flux.fromIterable(batch)
        .flatMap(processor::process))
```

#### CircuitBreakerBackpressureStrategy

Three-state circuit breaker using `AtomicReference<State>`.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `failureThreshold` | `int` | `5` | Consecutive failures before opening |
| `recoveryTimeout` | `Duration` | `30s` | Time in OPEN before transitioning to HALF_OPEN |
| `halfOpenMaxCalls` | `int` | `3` | Successful calls in HALF_OPEN to close the circuit |

**State transitions:**

```
CLOSED ──(failureThreshold consecutive failures)──→ OPEN
OPEN ──(recoveryTimeout elapsed)──→ HALF_OPEN
HALF_OPEN ──(halfOpenMaxCalls successes)──→ CLOSED
HALF_OPEN ──(any failure)──→ OPEN
```

When the circuit is OPEN, all processing attempts immediately fail with `CircuitBreakerOpenException`.

### BackpressureConfig Record

Configuration record with 9 fields:

| Field | Type | Description |
|-------|------|-------------|
| `strategy` | `String` | Strategy name (e.g., `"adaptive"`, `"batched"`, `"circuit-breaker"`) |
| `batchSize` | `int` | Batch size for `BatchedBackpressureStrategy` |
| `failureThreshold` | `int` | Failure threshold for circuit breaker |
| `recoveryTimeout` | `Duration` | Recovery timeout for circuit breaker |
| `halfOpenMaxCalls` | `int` | Half-open max calls for circuit breaker |
| `initialConcurrency` | `int` | Initial concurrency for adaptive strategy |
| `maxConcurrency` | `int` | Maximum concurrency for adaptive strategy |
| `minConcurrency` | `int` | Minimum concurrency for adaptive strategy |
| `errorRateThreshold` | `double` | Error rate threshold for adaptive strategy |

Factory method: `BackpressureConfig.defaults()` — returns an adaptive strategy configuration with sensible defaults.

### BackpressureStrategyFactory

Static utility class with a `ConcurrentHashMap<String, Supplier<BackpressureStrategy>>` registry.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getStrategy(String name)` | `Optional<BackpressureStrategy>` | Look up and instantiate a strategy by name |
| `registerStrategy(String name, Supplier<BackpressureStrategy> supplier)` | `void` | Register a custom strategy |
| `fromConfig(BackpressureConfig config)` | `BackpressureStrategy` | Build a strategy from a configuration record |
| `resetDefaults()` | `void` | Reset the registry to the 5 built-in strategies |

**Pre-registered strategies (5):**

| Name | Strategy | Configuration |
|------|----------|---------------|
| `"adaptive"` | `AdaptiveBackpressureStrategy` | Default parameters |
| `"batched"` | `BatchedBackpressureStrategy` | Default batch size (10) |
| `"circuit-breaker"` | `CircuitBreakerBackpressureStrategy` | Default parameters (threshold=5, recovery=30s, halfOpen=3) |
| `"circuit-breaker-aggressive"` | `CircuitBreakerBackpressureStrategy` | threshold=2, recovery=60s, halfOpen=1 |
| `"circuit-breaker-conservative"` | `CircuitBreakerBackpressureStrategy` | threshold=10, recovery=15s, halfOpen=5 |

### Usage in Engines

`SagaExecutionOrchestrator` and `TccCompositor` accept an optional `BackpressureStrategy`. When present, parallel execution uses `backpressureStrategy.applyBackpressure(items, processor)` instead of raw `Flux.flatMap()`.

```java
// Example: Saga with adaptive backpressure
BackpressureStrategy strategy = BackpressureStrategyFactory
    .getStrategy("adaptive")
    .orElseThrow();

SagaResult result = sagaEngine.execute("OrderSaga", input, strategy).block();
```

---

## 39. Execution Reporting

The reporting subsystem in `org.fireflyframework.orchestration.core.report` provides structured, immutable execution reports generated automatically by all three engines.

### ExecutionReport Record

| Field | Type | Description |
|-------|------|-------------|
| `executionName` | `String` | Name of the saga, workflow, or TCC |
| `correlationId` | `String` | Unique execution identifier |
| `pattern` | `ExecutionPattern` | `WORKFLOW`, `SAGA`, or `TCC` |
| `status` | `ExecutionStatus` | Terminal status of the execution |
| `startedAt` | `Instant` | When the execution started |
| `completedAt` | `Instant` | When the execution completed |
| `duration` | `Duration` | Total execution duration |
| `stepReports` | `Map<String, StepReport>` | Per-step detailed reports |
| `executionOrder` | `List<String>` | Ordered list of step IDs as executed |
| `variables` | `Map<String, Object>` | Final execution variables |
| `failureReason` | `String` | Error message (if failed) |
| `compensationReport` | `CompensationReport` | Compensation details (if applicable) |

**Computed methods (5):**

| Method | Return Type | Description |
|--------|-------------|-------------|
| `isSuccess()` | `boolean` | `true` if status is `COMPLETED` or `CONFIRMED` |
| `stepCount()` | `int` | Total number of steps |
| `failedStepCount()` | `int` | Count of steps with status `FAILED`, `TRY_FAILED`, `CONFIRM_FAILED`, `CANCEL_FAILED`, or `COMPENSATION_FAILED` |
| `completedStepCount()` | `int` | Count of successfully completed steps |
| `totalRetries()` | `int` | Sum of `max(0, attempts - 1)` across all step reports |

### StepReport Record

| Field | Type | Description |
|-------|------|-------------|
| `stepId` | `String` | Step identifier |
| `status` | `StepStatus` | Final step status |
| `attempts` | `int` | Total execution attempts (1 = no retries) |
| `latency` | `Duration` | Step execution duration |
| `result` | `Object` | Step return value |
| `error` | `Throwable` | Step error (if failed) |
| `startedAt` | `Instant` | When the step started |
| `completedAt` | `Instant` | When the step completed |

### CompensationReport Record

| Field | Type | Description |
|-------|------|-------------|
| `policy` | `CompensationPolicy` | Compensation policy used |
| `steps` | `List<CompensationStepReport>` | Per-step compensation details |
| `totalDuration` | `Duration` | Total compensation duration |
| `allCompensated` | `boolean` | `true` if all compensations succeeded |

**CompensationStepReport** (inner record):

| Field | Type | Description |
|-------|------|-------------|
| `stepId` | `String` | Compensated step identifier |
| `success` | `boolean` | Whether compensation succeeded |
| `error` | `Throwable` | Compensation error (if failed) |
| `duration` | `Duration` | Compensation step duration |

### ExecutionReportBuilder

Builds an immutable `ExecutionReport` from context state:

```java
ExecutionReport report = ExecutionReportBuilder.fromContext(ctx, status, failureReason);
```

The `fromContext(ExecutionContext ctx, ExecutionStatus status, String failureReason)` method extracts all step results, statuses, attempts, latencies, and variables from the context to construct the report.

### Accessing Reports

All three engine result types expose reports via an `Optional`:

```java
// Saga
SagaResult sagaResult = sagaEngine.execute("OrderSaga", input).block();
sagaResult.report().ifPresent(report -> {
    log.info("Saga completed: {} steps, {} retries, {} failed",
        report.stepCount(), report.totalRetries(), report.failedStepCount());
});

// TCC
TccResult tccResult = tccEngine.execute("PaymentTcc", input).block();
tccResult.report().ifPresent(report -> {
    log.info("TCC duration: {}", report.duration());
});

// ExecutionState (persistence)
ExecutionState state = persistenceProvider.findById(correlationId).block().orElseThrow();
state.report().ifPresent(report -> {
    log.info("Execution {}: {}", report.correlationId(), report.status());
});
```

Reports are wired automatically by all three engines — no additional configuration is required.

---

## 40. Validation Framework

The validation subsystem in `org.fireflyframework.orchestration.core.validation` validates orchestration definitions at registration time, catching configuration errors before execution.

### OrchestrationValidator

Validates definitions and returns a list of issues. Each validation method performs pattern-specific checks.

#### validateWorkflow(WorkflowDefinition) → List\<ValidationIssue\>

Checks:

- Empty steps list
- Duplicate step IDs
- Missing dependency references (step declares `dependsOn` a non-existent step)
- Cyclic dependencies (delegates to `TopologyBuilder`)
- Missing compensation methods (step declares compensation but method is missing)
- Timeout/retry sanity: negative values, timeouts exceeding 24 hours
- Annotation conflicts: `@WaitForAll` and `@WaitForAny` are mutually exclusive on the same step

#### validateSaga(SagaDefinition) → List\<ValidationIssue\>

Checks:

- Empty steps list
- Missing dependency references
- Cyclic dependencies
- Missing compensation methods
- Retry/timeout sanity: negative values, unreasonable maximums

#### validateTcc(TccDefinition) → List\<ValidationIssue\>

Checks:

- Empty participants list
- Missing `@TryMethod`, `@ConfirmMethod`, or `@CancelMethod` on participants
- Per-phase timeout/retry sanity

#### validateAndThrow(List\<ValidationIssue\>)

Logs all issues at their severity level (`ERROR` → `error`, `WARNING` → `warn`, `INFO` → `info`). Throws `IllegalStateException` if any `ERROR`-level issues are present.

```java
List<ValidationIssue> issues = validator.validateWorkflow(definition);
validator.validateAndThrow(issues); // throws if any ERROR-level issues
```

### ValidationIssue Record

| Field | Type | Description |
|-------|------|-------------|
| `severity` | `Severity` | `ERROR`, `WARNING`, or `INFO` |
| `message` | `String` | Human-readable description of the issue |
| `location` | `String` | Dotted path identifying the issue location (e.g., `"workflow.order-processing.step.validate"`) |

```java
public enum Severity {
    ERROR,    // blocks registration — definition will not be registered
    WARNING,  // logged but does not block registration
    INFO      // informational only
}
```

### Integration with Registries

Registries invoke the validator during component scanning when validation is enabled:

```yaml
firefly:
  orchestration:
    validation:
      enabled: true   # default
```

- `ERROR` issues abort registration — the definition is not added to the registry
- `WARNING` and `INFO` issues are logged but registration proceeds

```java
// Internal registry behavior (simplified)
List<ValidationIssue> issues = validator.validateSaga(definition);
validator.validateAndThrow(issues); // aborts if ERRORs present
registry.register(definition);      // only reached if no ERRORs
```

---

## 41. Metrics Endpoint

The `org.fireflyframework.orchestration.core.observability.OrchestrationMetricsEndpoint` exposes orchestration metrics through Spring Boot Actuator.

### Configuration

```java
@Endpoint(id = "orchestration-metrics")
```

Conditional on Spring Boot Actuator being on the classpath. Reads counters from the `MeterRegistry` registered by `OrchestrationMetrics` (see [§35](#35-observability-metrics--tracing)).

### Endpoints

#### GET /actuator/orchestration-metrics

Returns all orchestration metrics as JSON:

```json
{
  "executions": {
    "total": 1250,
    "active": 3,
    "completed": 1200,
    "failed": 47
  },
  "patterns": {
    "WORKFLOW": { "total": 500, "completed": 480, "failed": 20 },
    "SAGA": { "total": 600, "completed": 580, "failed": 20 },
    "TCC": { "total": 150, "completed": 140, "failed": 7 }
  },
  "steps": {
    "total": 5000,
    "failed": 120,
    "retried": 350
  },
  "compensation": {
    "total": 47,
    "failed": 3
  },
  "dlq": {
    "entries": 5
  },
  "uptime": "PT48H30M"
}
```

#### GET /actuator/orchestration-metrics/{pattern}

Returns the same structure but filtered to a single execution pattern (`WORKFLOW`, `SAGA`, or `TCC`). Returns an error map for unknown patterns.

### Read Operations

Two `@ReadOperation` methods:

```java
@ReadOperation
public Map<String, Object> metrics() {
    // Returns all orchestration metrics
}

@ReadOperation
public Map<String, Object> metrics(@Selector String pattern) {
    // Returns metrics filtered by ExecutionPattern
}
```

---

## 42. Event Sourcing

The event sourcing subsystem in `org.fireflyframework.orchestration.persistence.eventsourced` provides full event-sourced persistence with aggregate reconstruction, snapshots, and read-side projections.

### OrchestrationDomainEvent

Sealed interface with 26 permitted event records covering the complete execution lifecycle:

**Execution lifecycle (6):**

| Event | Description |
|-------|-------------|
| `ExecutionStartedEvent` | Execution initiated |
| `ExecutionCompletedEvent` | Execution completed successfully |
| `ExecutionFailedEvent` | Execution failed |
| `ExecutionCancelledEvent` | Execution cancelled |
| `ExecutionSuspendedEvent` | Execution suspended (workflow) |
| `ExecutionResumedEvent` | Execution resumed (workflow) |

**Step lifecycle (5):**

| Event | Description |
|-------|-------------|
| `StepStartedEvent` | Step execution began |
| `StepCompletedEvent` | Step completed successfully |
| `StepFailedEvent` | Step failed |
| `StepSkippedEvent` | Step skipped (condition or dry-run) |
| `StepRetriedEvent` | Step retry attempted |

**Compensation (3):**

| Event | Description |
|-------|-------------|
| `CompensationStartedEvent` | Compensation phase began |
| `CompensationStepCompletedEvent` | Compensation step succeeded |
| `CompensationStepFailedEvent` | Compensation step failed |

**TCC phases (3):**

| Event | Description |
|-------|-------------|
| `PhaseStartedEvent` | TCC phase (TRY/CONFIRM/CANCEL) started |
| `PhaseCompletedEvent` | TCC phase completed |
| `PhaseFailedEvent` | TCC phase failed |

**Signals (2):**

| Event | Description |
|-------|-------------|
| `SignalReceivedEvent` | Signal received by workflow |
| `SignalConsumedEvent` | Signal consumed by a waiting step |

**Timers (2):**

| Event | Description |
|-------|-------------|
| `TimerRegisteredEvent` | Timer registered for a workflow |
| `TimerFiredEvent` | Timer fired |

**Child workflows (2):**

| Event | Description |
|-------|-------------|
| `ChildWorkflowSpawnedEvent` | Child workflow started |
| `ChildWorkflowCompletedEvent` | Child workflow completed |

**Search attributes (1):**

| Event | Description |
|-------|-------------|
| `SearchAttributeUpdatedEvent` | Search attribute updated on execution |

**Continue-as-new (1):**

| Event | Description |
|-------|-------------|
| `ContinueAsNewEvent` | Workflow continued as new execution |

**Checkpoints (1):**

| Event | Description |
|-------|-------------|
| `CheckpointSavedEvent` | Execution checkpoint saved |

All 26 events share three common methods: `correlationId()`, `timestamp()`, `pattern()`.

### OrchestrationAggregate

Mutable aggregate that accumulates state by applying domain events.

| Method | Description |
|--------|-------------|
| `raise(OrchestrationDomainEvent event)` | Adds event to uncommitted list and applies it to state |
| `apply(OrchestrationDomainEvent event)` | Pattern matches on the sealed type to update aggregate state |
| `getUncommittedEvents()` | Returns the list of events not yet persisted |
| `markEventsCommitted()` | Clears the uncommitted events list |

**State maintained by the aggregate:**

| Field | Type | Description |
|-------|------|-------------|
| `correlationId` | `String` | Execution identifier |
| `executionName` | `String` | Saga/workflow/TCC name |
| `pattern` | `ExecutionPattern` | Execution pattern |
| `status` | `ExecutionStatus` | Current execution status |
| `stepStatuses` | `Map<String, StepStatus>` | Per-step statuses |
| `stepResults` | `Map<String, Object>` | Per-step results |
| `stepAttempts` | `Map<String, Integer>` | Per-step attempt counts |
| `stepLatenciesMs` | `Map<String, Long>` | Per-step latencies |
| `variables` | `Map<String, Object>` | Execution variables |
| `headers` | `Map<String, String>` | Execution headers |
| `failureReason` | `String` | Failure message (if failed) |
| `startedAt` | `Instant` | When execution started |
| `updatedAt` | `Instant` | Last state update |
| `currentPhase` | `TccPhase` | Current TCC phase (TCC only) |

The `apply()` method uses pattern matching on the sealed `OrchestrationDomainEvent` type to route each event to its corresponding state update logic.

### OrchestrationSnapshot

Immutable record capturing the full aggregate state at a point in time (14 fields matching the aggregate state above).

```java
// Create a snapshot from an aggregate
OrchestrationSnapshot snapshot = OrchestrationSnapshot.from(aggregate);

// Restore an aggregate from a snapshot
OrchestrationAggregate aggregate = snapshot.restore();
```

All map fields are defensively copied via `Map.copyOf()` to ensure immutability.

### OrchestrationProjection

Read-side projection maintaining an in-memory view of execution summaries using `ConcurrentHashMap<String, ExecutionSummary>`.

**Update method:**

```java
public void processEvent(OrchestrationDomainEvent event)
```

Processes each domain event to create or update the corresponding `ExecutionSummary`.

**Query methods:**

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getExecutionSummary(String correlationId)` | `Optional<ExecutionSummary>` | Look up a single execution |
| `findByStatus(ExecutionStatus status)` | `List<ExecutionSummary>` | Find all executions with a given status |
| `findByPattern(ExecutionPattern pattern)` | `List<ExecutionSummary>` | Find all executions of a given pattern |

**ExecutionSummary record:**

| Field | Type | Description |
|-------|------|-------------|
| `correlationId` | `String` | Execution identifier |
| `executionName` | `String` | Saga/workflow/TCC name |
| `pattern` | `ExecutionPattern` | Execution pattern |
| `status` | `ExecutionStatus` | Current status |
| `stepStatuses` | `Map<String, StepStatus>` | Per-step statuses |
| `startedAt` | `Instant` | When execution started |
| `lastUpdated` | `Instant` | Last event timestamp |
| `failureReason` | `String` | Failure message (if failed) |
| `eventCount` | `int` | Total events processed for this execution |

### EventSourcedPersistenceProvider

Implements `ExecutionPersistenceProvider` with full event-sourcing semantics.

**Dependencies:**

| Component | Role |
|-----------|------|
| `EventStore` | Appending and reading domain events |
| `OrchestrationAggregate` | Reconstructing state from event streams |
| `OrchestrationSnapshot` | Caching aggregate state for efficient hydration |
| `OrchestrationProjection` | Cross-aggregate queries (by pattern, by status) |

**Method implementations:**

| Method | Behavior |
|--------|----------|
| `save(ExecutionState)` | Emits domain events with serialized state as event payload |
| `findById(String)` | Loads event stream and deserializes last event to reconstruct state |
| `updateStatus(String, ExecutionStatus)` | Appends a status-change event |
| `findByPattern(ExecutionPattern)` | Delegates to `OrchestrationProjection` for efficient lookup |
| `findByStatus(ExecutionStatus)` | Delegates to `OrchestrationProjection` for efficient lookup |
| `findInFlight()` | Delegates to projection, filtering for non-terminal statuses |
| `findStale(Instant)` | Delegates to projection, filtering by timestamp |
| `cleanup(Duration)` | No-op — events are immutable and are never deleted |
| `isHealthy()` | Returns `true` if the event store is accessible |

---

[← Back to Index](README.md) | [Previous: TCC Pattern](tcc.md) | [Next: Configuration →](configuration.md)

---
Copyright 2026 Firefly Software Solutions Inc. Licensed under Apache 2.0.
