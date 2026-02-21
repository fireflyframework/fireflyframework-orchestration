[← Back to Index](README.md) | [Previous: Workflow Pattern](workflow.md) | [Next: TCC Pattern →](tcc.md)

# Part III: Saga Pattern

**Contents:**
- [§12 Saga Annotation Reference](#12-saga-annotation-reference)
- [§13 Saga Tutorial](#13-saga-tutorial)
- [§14 Saga Compensation Deep Dive](#14-saga-compensation-deep-dive)
- [§15 ExpandEach (Fan-Out)](#15-expandeach-fan-out)
- [§16 Saga Builder DSL](#16-saga-builder-dsl)
- [§17 SagaEngine API](#17-sagaengine-api)
- [§18 SagaResult](#18-sagaresult)

## 12. Saga Annotation Reference

### @Saga

Marks a class as a saga definition. Applied to a Spring-managed bean.

**Target:** `ElementType.TYPE` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | `String` | *(required)* | Unique saga name |
| `layerConcurrency` | `int` | `0` | Max parallel steps per DAG layer (`0` = unbounded) |
| `triggerEventType` | `String` | `""` | Event type that triggers this saga via `EventGateway` |

> **Note:** Unlike `@Workflow`, the `@Saga` annotation does not carry retry or timeout defaults. These are configured per-step via `@SagaStep` attributes, per-saga via `OrchestrationProperties.saga.defaultTimeout`, or globally via the `CompensationPolicy`.

### @SagaStep

Marks a method as a saga step. The method must return `Mono<T>`.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `id` | `String` | *(required)* | Unique step identifier |
| `compensate` | `String` | `""` | Name of the compensation method on the same bean |
| `dependsOn` | `String[]` | `{}` | Step IDs that must complete before this step runs |
| `retry` | `int` | `0` | Max retry attempts on failure |
| `backoffMs` | `long` | `-1` | Base backoff in milliseconds (`-1` = use default 100ms) |
| `timeoutMs` | `long` | `-1` | Step timeout in milliseconds (`-1` = no timeout) |
| `jitter` | `boolean` | `false` | Apply jitter to backoff delays |
| `jitterFactor` | `double` | `0.5` | Jitter factor (`0.0`–`1.0`) |
| `idempotencyKey` | `String` | `""` | Literal string for deduplication |
| `cpuBound` | `boolean` | `false` | Schedule on bounded-elastic scheduler for CPU-intensive work |
| `compensationRetry` | `int` | `-1` | Compensation retry attempts (`-1` = use step's `retry`) |
| `compensationTimeoutMs` | `long` | `-1` | Compensation timeout (`-1` = use step's `timeoutMs`) |
| `compensationBackoffMs` | `long` | `-1` | Compensation backoff (`-1` = use step's `backoffMs`) |
| `compensationCritical` | `boolean` | `false` | If `true`, compensation failure opens the circuit breaker (when using `CIRCUIT_BREAKER` policy) |

### @StepEvent

Publishes an event when a saga step completes successfully.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `topic` | `String` | `""` | Event topic |
| `type` | `String` | `""` | Event type |
| `key` | `String` | `""` | Event key (for partitioning) |

### @ExternalSagaStep

Defines a saga step on a bean that lives outside the `@Saga` class. Useful for reusing existing services as saga steps without modifying them.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `saga` | `String` | *(required)* | Name of the saga this step belongs to |
| `id` | `String` | *(required)* | Unique step identifier |
| `compensate` | `String` | `""` | Compensation method name |
| `dependsOn` | `String[]` | `{}` | Step dependencies |
| `retry` | `int` | `0` | Max retry attempts |
| `backoffMs` | `long` | `-1` | Base backoff in milliseconds |
| `timeoutMs` | `long` | `-1` | Step timeout in milliseconds |
| `jitter` | `boolean` | `false` | Apply jitter to backoff |
| `jitterFactor` | `double` | `0.5` | Jitter factor |
| `idempotencyKey` | `String` | `""` | Idempotency key (SpEL expression) |
| `cpuBound` | `boolean` | `false` | CPU-bound scheduling |
| `compensationRetry` | `int` | `-1` | Compensation retry attempts |
| `compensationTimeoutMs` | `long` | `-1` | Compensation timeout |
| `compensationBackoffMs` | `long` | `-1` | Compensation backoff |
| `compensationCritical` | `boolean` | `false` | Circuit breaker flag for compensation |

### @CompensationSagaStep

Defines a compensation method on a bean outside the `@Saga` class. Links to a specific step by saga name and step ID.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `saga` | `String` | *(required)* | Name of the saga |
| `forStepId` | `String` | *(required)* | Step ID this compensates |

### @OnSagaComplete

Callback invoked when the saga completes successfully.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `async` | `boolean` | `false` | Run callback asynchronously |
| `priority` | `int` | `0` | Ordering priority |

The callback method can declare `ExecutionContext` and/or `SagaResult` as parameters — both are resolved automatically by the `ArgumentResolver`.

### @OnSagaError

Callback invoked when the saga fails.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `errorTypes` | `Class<? extends Throwable>[]` | `{}` | Filter by exception type (empty = all errors) |
| `suppressError` | `boolean` | `false` | If `true`, converts the failure to a success result |
| `async` | `boolean` | `false` | Run callback asynchronously |
| `priority` | `int` | `0` | Ordering priority |

The callback method can declare `Throwable` and/or `ExecutionContext` as parameters.

### @ScheduledSaga

Schedules automatic execution of a saga. This annotation is `@Repeatable`.

**Target:** `ElementType.TYPE` | **Retention:** `RUNTIME` | **Repeatable:** Yes (container: `@ScheduledSagas`)

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `cron` | `String` | `""` | Cron expression (6 fields) |
| `zone` | `String` | `""` | Timezone for cron |
| `fixedDelay` | `long` | `-1` | Fixed delay in milliseconds |
| `fixedRate` | `long` | `-1` | Fixed rate in milliseconds |
| `initialDelay` | `long` | `0` | Initial delay before first execution |
| `enabled` | `boolean` | `true` | Whether this schedule is active |
| `input` | `String` | `"{}"` | JSON input passed to the engine |
| `description` | `String` | `""` | Human-readable description |

---

## 13. Saga Tutorial

A **Saga** is a sequence of local transactions where each step has a **compensating action**. If any step fails, the saga engine automatically runs compensations for all completed steps.

```
Step 1: Reserve Inventory   →  Compensation: Cancel Reservation
Step 2: Charge Payment      →  Compensation: Refund Payment
Step 3: Ship Order            (no compensation — final step)

If Step 2 fails:
  1. Run compensation for Step 1 (Cancel Reservation)
  2. Report failure with compensation results
```

### Step 1: Define the Saga with Annotations

```java
@Saga(name = "TransferFunds")
public class TransferFundsSaga {

    private final AccountService accountService;

    public TransferFundsSaga(AccountService accountService) {
        this.accountService = accountService;
    }

    @SagaStep(id = "debit", compensate = "creditBack",
              retry = 3, backoffMs = 500, timeoutMs = 10000,
              jitter = true, jitterFactor = 0.2)
    public Mono<DebitResult> debitAccount(@Input TransferRequest request) {
        return accountService.debit(request.fromAccount(), request.amount());
    }

    public Mono<Void> creditBack(@Input DebitResult debitResult) {
        return accountService.credit(debitResult.accountId(), debitResult.amount());
    }

    @SagaStep(id = "credit", dependsOn = "debit",
              retry = 3, backoffMs = 500, timeoutMs = 10000)
    public Mono<CreditResult> creditAccount(
            @Input TransferRequest request,
            @FromStep("debit") DebitResult debitResult) {
        return accountService.credit(request.toAccount(), request.amount());
    }

    @OnSagaComplete
    public void onComplete(SagaResult result) {
        log.info("Transfer completed: {}", result.correlationId());
    }

    @OnSagaError
    public void onError(Throwable error, ExecutionContext ctx) {
        log.error("Transfer failed: {}", ctx.getCorrelationId(), error);
    }
}
```

Key points:

- `@SagaStep(compensate = "creditBack")` links the `"debit"` step to its compensation method
- Compensation methods are regular methods on the same bean — they don't need a `@SagaStep` annotation
- The `"credit"` step depends on `"debit"` via `dependsOn` — creating a two-layer DAG
- `@FromStep("debit")` injects the result of the completed `"debit"` step

### Step 2: Execute the Saga

```java
@Service
public class TransferService {
    private final SagaEngine sagaEngine;

    public Mono<SagaResult> transfer(TransferRequest request) {
        return sagaEngine.execute("TransferFunds",
            StepInputs.of("debit", request));
    }
}
```

`StepInputs.of("debit", request)` maps the input to the `"debit"` step. Steps without explicit inputs receive `null` for `@Input` parameters — they typically use `@FromStep` to access upstream results.

### Step 3: Handle the Result

```java
sagaEngine.execute("TransferFunds", StepInputs.of("debit", request))
    .subscribe(result -> {
        if (result.isSuccess()) {
            DebitResult debit = result.resultOf("debit", DebitResult.class)
                .orElseThrow();
            CreditResult credit = result.resultOf("credit", CreditResult.class)
                .orElseThrow();
            log.info("Transfer complete: {} -> {}", debit, credit);
        } else {
            log.error("Transfer failed: {}", result.error().orElse(null));
            log.info("Failed steps: {}", result.failedSteps());
            log.info("Compensated steps: {}", result.compensatedSteps());
        }
    });
```

### External Steps and Compensations

For steps defined on external beans (not in the `@Saga` class):

```java
@Service
public class InventoryService {

    @ExternalSagaStep(saga = "OrderSaga", id = "reserve",
                       compensate = "cancelReservation",
                       retry = 2, timeoutMs = 5000)
    public Mono<String> reserve(@Input OrderRequest order) {
        return doReserve(order.items());
    }

    @CompensationSagaStep(saga = "OrderSaga", forStepId = "reserve")
    public Mono<Void> cancelReservation(@FromStep("reserve") String reservationId) {
        return doCancel(reservationId);
    }
}
```

---

## 14. Saga Compensation Deep Dive

When a saga step fails, the engine compensates all previously completed steps. The **compensation policy** determines how those compensations execute.

### Setting the Policy

Globally via configuration:

```yaml
firefly:
  orchestration:
    saga:
      compensation-policy: STRICT_SEQUENTIAL
```

Or programmatically via the builder (see [§16](#16-saga-builder-dsl)).

### CompensationPolicy Enum Values

#### STRICT_SEQUENTIAL (Default)

Compensations run one at a time in **reverse completion order** (last completed to first completed). If one compensation fails, subsequent compensations still run.

```
Step 1 ok → Step 2 ok → Step 3 FAIL
Compensate Step 2 → Compensate Step 1
```

Best for most applications. Predictable, debuggable, safe.

#### GROUPED_PARALLEL

Steps in the same DAG layer are compensated in parallel; layers are compensated in reverse order.

```
Layer 2: [Step3] ok           → Compensate Step3
Layer 1: [Step2a, Step2b] ok  → Compensate Step2a || Step2b  (parallel)
Layer 0: [Step1] ok           → Compensate Step1
```

Best for sagas with many independent steps in the same layer where compensation ordering within a layer doesn't matter.

#### RETRY_WITH_BACKOFF

Like `STRICT_SEQUENTIAL` but each compensation is retried with exponential backoff. Failed compensations are logged and skipped (best-effort).

```
Compensate Step 2 (attempt 1: fail, attempt 2: fail, attempt 3: success)
Compensate Step 1 (attempt 1: success)
```

Retry count and backoff are controlled by the step's `compensationRetry` and `compensationBackoffMs` attributes.

#### CIRCUIT_BREAKER

Like `RETRY_WITH_BACKOFF`, but tracks compensation failures. If a step marked `compensationCritical = true` fails compensation, the circuit opens and all remaining compensations are skipped.

```java
@SagaStep(id = "critical", compensate = "undoCritical",
          compensationCritical = true)
public Mono<String> criticalStep(@Input Request req) { ... }
```

Best for sagas where some compensations are critical for data integrity and failed critical compensation should halt the process.

#### BEST_EFFORT_PARALLEL

All compensations run in parallel with no ordering guarantee. Failures are logged but do not stop other compensations.

```
Compensate Step 1 || Step 2 || Step 3  (all parallel, all best-effort)
```

Best for performance-critical sagas where compensation ordering does not matter and speed is preferred over determinism.

### Per-Step Compensation Configuration

Each step can override compensation behavior independently of the forward step:

```java
@SagaStep(id = "charge",
          compensate = "refund",
          retry = 3,                 // forward step: 3 retries
          backoffMs = 500,           // forward step: 500ms backoff
          compensationRetry = 5,     // compensation: 5 retries (overrides 3)
          compensationBackoffMs = 1000,  // compensation: 1s backoff
          compensationTimeoutMs = 30000, // compensation: 30s timeout
          compensationCritical = true)   // halt if this compensation fails
public Mono<ChargeResult> charge(@Input OrderRequest req) { ... }
```

When compensation settings are `-1`, they fall back to the forward step's values.

### How Compensation Is Triggered

1. A step fails (throws an exception or times out)
2. The `SagaExecutionOrchestrator` marks the step as `FAILED`
3. The `SagaCompensator` is invoked with the list of completed steps (in completion order)
4. The compensator reverses the order and runs compensations per the active `CompensationPolicy`
5. Compensation results are stored in `ExecutionContext.compensationResults`
6. Compensation errors are stored in `ExecutionContext.compensationErrors`
7. The final `SagaResult` includes compensation status for each step

---

## 15. ExpandEach (Fan-Out)

`ExpandEach` dynamically clones a saga step for each item in a collection. This is useful for processing a batch of items where each needs its own step instance with independent retry and compensation.

### How It Works

```
Original:  reserve          → charge → ship
Expanded:  reserve:SKU-001 \
           reserve:SKU-002  |→ charge → ship
           reserve:SKU-003 /
```

Each cloned step runs independently within the same layer. Downstream steps wait for **all** expanded steps to complete.

### Usage

```java
List<LineItem> items = List.of(
    new LineItem("SKU-001", 2),
    new LineItem("SKU-002", 1),
    new LineItem("SKU-003", 5)
);

StepInputs inputs = StepInputs.builder()
    .forStepId("reserve", ExpandEach.of(items, item -> ((LineItem) item).sku()))
    .build();

sagaEngine.execute("OrderSaga", inputs);
```

### Suffix Modes

```java
ExpandEach.of(items)                     // uses #0, #1, #2 suffixes
ExpandEach.of(items, item -> item.id())  // uses :id suffix (e.g., reserve:SKU-001)
```

### Compensation for Expanded Steps

Each expanded step instance is compensated independently. If `reserve:SKU-002` fails, only `reserve:SKU-001` (if completed) is compensated — `reserve:SKU-003` may or may not have run depending on layer timing.

---

## 16. Saga Builder DSL

The programmatic builder provides a fluent API for constructing sagas without annotations — useful for dynamic composition, testing, or runtime configuration.

### SagaBuilder Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `SagaBuilder.saga(String name)` | `SagaBuilder` | Create a builder with the given saga name |
| `SagaBuilder.named(String name)` | `SagaBuilder` | Alias for `saga()` |
| `SagaBuilder.saga(String name, int layerConcurrency)` | `SagaBuilder` | Create with layer concurrency limit |
| `.triggerEventType(String)` | `SagaBuilder` | Event type that triggers this saga via `EventGateway` |
| `.step(String id)` | `Step` | Begin defining a step (returns inner `Step` builder) |
| `.build()` | `SagaDefinition` | Build the immutable definition |

### Step Builder Methods

**Handler variants** — choose one per step:

| Method | Signature | Use When |
|--------|-----------|----------|
| `.handler(StepHandler)` | `StepHandler<I, O>` | Full interface implementation |
| `.handler(BiFunction)` | `(I, ExecutionContext) → Mono<O>` | Lambda with input + context |
| `.handlerInput(Function)` | `(I) → Mono<O>` | Lambda needing only the input |
| `.handlerCtx(Function)` | `(ExecutionContext) → Mono<O>` | Lambda needing only the context |
| `.handler(Supplier)` | `() → Mono<O>` | Lambda needing no arguments |

**Compensation variants** — optional, choose one:

| Method | Signature |
|--------|-----------|
| `.compensation(BiFunction)` | `(Object, ExecutionContext) → Mono<Void>` |
| `.compensationCtx(Function)` | `(ExecutionContext) → Mono<Void>` |
| `.compensation(Supplier)` | `() → Mono<Void>` |

**Step configuration:**

| Method | Description |
|--------|-------------|
| `.dependsOn(String...)` | Step dependencies |
| `.retry(int)` | Max retry attempts |
| `.backoff(Duration)` / `.backoffMs(long)` | Backoff between retries |
| `.timeout(Duration)` / `.timeoutMs(long)` | Step timeout |
| `.jitter()` / `.jitter(boolean)` | Enable jitter on backoff |
| `.jitterFactor(double)` | Jitter factor (`0.0`–`1.0`, default `0.5`) |
| `.idempotencyKey(String)` | Literal key for deduplication |
| `.cpuBound(boolean)` | Schedule on bounded-elastic scheduler |
| `.stepEvent(String topic, String type, String key)` | Per-step event publishing |
| `.compensationRetry(int)` | Retry attempts for compensation |
| `.compensationBackoff(Duration)` | Backoff for compensation retries |
| `.compensationTimeout(Duration)` | Timeout for compensation |
| `.compensationCritical(boolean)` | Circuit breaker flag for compensation |
| `.add()` | Finish step definition, return to `SagaBuilder` |

### Full Builder Example

```java
SagaDefinition def = SagaBuilder.saga("OrderSaga")
    .triggerEventType("OrderCreated")
    .step("reserve")
        .retry(3)
        .backoffMs(500)
        .timeoutMs(10_000)
        .jitter()
        .jitterFactor(0.2)
        .idempotencyKey("reserve-order")
        .compensationRetry(2)
        .compensationTimeout(Duration.ofSeconds(5))
        .compensationCritical(true)
        .stepEvent("inventory", "InventoryReserved", "orderId")
        .handler((input, ctx) -> {
            OrderRequest order = (OrderRequest) input;
            return inventoryService.reserve(order.items());
        })
        .compensation((result, ctx) -> {
            String reservationId = (String) result;
            return inventoryService.cancel(reservationId);
        })
        .add()
    .step("charge")
        .dependsOn("reserve")
        .handlerInput(input -> paymentService.charge((String) input))
        .compensation((result, ctx) -> paymentService.refund((String) result))
        .add()
    .step("notify")
        .dependsOn("charge")
        .handler(() -> notificationService.sendConfirmation())
        .add()
    .build();
```

---

## 17. SagaEngine API

`SagaEngine` is the primary entry point for saga execution. It supports both annotation-registered and builder-created saga definitions.

### Method Reference

| Method | Return Type | Description |
|--------|-------------|-------------|
| `execute(String sagaName, StepInputs inputs)` | `Mono<SagaResult>` | Execute by name with per-step inputs |
| `execute(String sagaName, StepInputs inputs, ExecutionContext ctx)` | `Mono<SagaResult>` | Execute with an explicit context |
| `execute(String sagaName, Map<String, Object> stepInputs)` | `Mono<SagaResult>` | Execute with a raw input map |
| `execute(SagaDefinition saga, StepInputs inputs)` | `Mono<SagaResult>` | Execute a builder-created definition |
| `execute(SagaDefinition saga, StepInputs inputs, ExecutionContext ctx)` | `Mono<SagaResult>` | Execute with definition and explicit context |

### StepInputs Construction

```java
// Single step input
StepInputs inputs = StepInputs.of("debit", request);

// Multiple step inputs
StepInputs inputs = StepInputs.builder()
    .forStepId("debit", request)
    .forStepId("credit", creditRequest)
    .build();

// No inputs (steps rely on @FromStep or @Variable)
StepInputs inputs = StepInputs.empty();

// From a raw map
sagaEngine.execute("MySaga", Map.of("debit", request, "credit", creditRequest));
```

### Execution Flow

1. Look up `SagaDefinition` from `SagaRegistry` (or use the provided definition)
2. Create `ExecutionContext` (or use the provided one)
3. Compute DAG topology via `TopologyBuilder`
4. Execute steps layer-by-layer via `SagaExecutionOrchestrator`
5. On step failure → trigger `SagaCompensator` for all completed steps
6. Fire lifecycle callbacks (`@OnSagaComplete` / `@OnSagaError`)
7. Persist `ExecutionState` and publish events
8. Return `SagaResult`

---

## 18. SagaResult

`SagaResult` is an immutable snapshot of a completed saga execution. It provides both high-level success/failure status and per-step details.

### Top-Level Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `sagaName()` | `String` | Saga name |
| `correlationId()` | `String` | Unique execution ID |
| `isSuccess()` | `boolean` | `true` if all steps succeeded |
| `duration()` | `Duration` | Time between start and completion |
| `startedAt()` | `Instant` | Execution start time |
| `completedAt()` | `Instant` | Execution completion time |
| `error()` | `Optional<Throwable>` | Primary error (if failed) |
| `headers()` | `Map<String, String>` | Execution headers |

### Step Inspection Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `steps()` | `Map<String, StepOutcome>` | All step outcomes |
| `failedSteps()` | `List<String>` | IDs of failed steps |
| `compensatedSteps()` | `List<String>` | IDs of compensated steps |
| `firstErrorStepId()` | `Optional<String>` | First step that failed |
| `stepResults()` | `Map<String, Object>` | Raw result values keyed by step ID |
| `resultOf(String stepId, Class<T> type)` | `Optional<T>` | Type-safe result extraction |

### StepOutcome Record

Each step's outcome is captured as a `StepOutcome` record:

| Field | Type | Description |
|-------|------|-------------|
| `status` | `StepStatus` | Step status (`DONE`, `FAILED`, `COMPENSATED`, etc.) |
| `attempts` | `int` | Number of execution attempts |
| `latencyMs` | `long` | Execution duration in milliseconds |
| `result` | `Object` | Step return value |
| `error` | `Throwable` | Step error (`null` if succeeded) |
| `compensated` | `boolean` | Whether compensation was run |
| `startedAt` | `Instant` | When the step started |
| `compensationResult` | `Object` | Compensation return value |
| `compensationError` | `Throwable` | Compensation error (`null` if succeeded) |

### Example: Handling Success vs Failure

```java
sagaEngine.execute("TransferFunds", StepInputs.of("debit", request))
    .subscribe(result -> {
        if (result.isSuccess()) {
            DebitResult debit = result.resultOf("debit", DebitResult.class)
                .orElseThrow();
            CreditResult credit = result.resultOf("credit", CreditResult.class)
                .orElseThrow();
            log.info("Transfer OK: {} -> {}", debit, credit);
        } else {
            log.error("Transfer failed at step: {}",
                result.firstErrorStepId().orElse("unknown"));
            log.error("Error: {}", result.error().map(Throwable::getMessage).orElse("none"));
            log.info("Compensated: {}", result.compensatedSteps());

            // Inspect per-step details
            result.steps().forEach((stepId, outcome) -> {
                log.info("  {} → status={}, attempts={}, latency={}ms, compensated={}",
                    stepId, outcome.status(), outcome.attempts(),
                    outcome.latencyMs(), outcome.compensated());
            });
        }
    });
```

### Factory Methods

| Method | Description |
|--------|-------------|
| `SagaResult.from(sagaName, ctx, compensatedFlags, stepErrors, allStepIds)` | Build from execution context (used internally by engine) |
| `SagaResult.failed(sagaName, correlationId, failedStepId, error, steps)` | Build a pre-failed result |

---

[← Back to Index](README.md) | [Previous: Workflow Pattern](workflow.md) | [Next: TCC Pattern →](tcc.md)

---
Copyright 2026 Firefly Software Solutions Inc. Licensed under Apache 2.0.
