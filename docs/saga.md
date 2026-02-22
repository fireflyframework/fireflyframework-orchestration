[← Back to Index](README.md) | [Previous: Workflow Pattern](workflow.md) | [Next: TCC Pattern →](tcc.md)

# Part III: Saga Pattern

**Contents:**
- [§13 Saga Annotation Reference](#13-saga-annotation-reference)
- [§14 Saga Tutorial](#14-saga-tutorial)
- [§15 Saga Compensation Deep Dive](#15-saga-compensation-deep-dive)
- [§16 ExpandEach (Fan-Out)](#16-expandeach-fan-out)
- [§17 Saga Builder DSL](#17-saga-builder-dsl)
- [§18 SagaEngine API](#18-sagaengine-api)
- [§19 SagaResult](#19-sagaresult)
- [§20 Saga Composition](#20-saga-composition)

## 13. Saga Annotation Reference

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

## 14. Saga Tutorial

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

## 15. Saga Compensation Deep Dive

When a saga step fails, the engine compensates all previously completed steps. The **compensation policy** determines how those compensations execute.

### Setting the Policy

Globally via configuration:

```yaml
firefly:
  orchestration:
    saga:
      compensation-policy: STRICT_SEQUENTIAL
```

Or programmatically via the builder (see [§17](#17-saga-builder-dsl)).

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

### Compensation Error Handlers

The `CompensationErrorHandler` interface determines what happens when a compensation step itself fails. Each handler receives the saga name, step ID, the error, and the current attempt count, and returns a `CompensationErrorResult` indicating the action to take.

```java
public interface CompensationErrorHandler {

    enum CompensationErrorResult {
        CONTINUE,           // Proceed to the next compensation step
        RETRY,              // Retry the current compensation
        FAIL_SAGA,          // Abort all remaining compensations
        SKIP_STEP,          // Skip this compensation step entirely
        MARK_COMPENSATED    // Mark the step as compensated despite the error
    }

    CompensationErrorResult handle(String sagaName, String stepId, Throwable error, int attempt);
}
```

#### DefaultCompensationErrorHandler

Always returns `CONTINUE`. This matches the legacy behavior where compensation errors are silently swallowed and subsequent compensation steps proceed regardless.

```java
public class DefaultCompensationErrorHandler implements CompensationErrorHandler {
    @Override
    public CompensationErrorResult handle(String sagaName, String stepId, Throwable error, int attempt) {
        return CompensationErrorResult.CONTINUE;
    }
}
```

#### RetryWithBackoffErrorHandler

Retries compensation up to `maxRetries` times. Optionally filters which exception types are retryable via the `retryableTypes` set. Returns `RETRY` if under the limit and the error is retryable, `FAIL_SAGA` otherwise.

```java
public class RetryWithBackoffErrorHandler implements CompensationErrorHandler {

    public RetryWithBackoffErrorHandler(int maxRetries) { ... }
    public RetryWithBackoffErrorHandler(int maxRetries, Set<Class<? extends Throwable>> retryableTypes) { ... }

    @Override
    public CompensationErrorResult handle(String sagaName, String stepId, Throwable error, int attempt) {
        if (!isRetryable(error)) return CompensationErrorResult.FAIL_SAGA;
        if (attempt < maxRetries)  return CompensationErrorResult.RETRY;
        return CompensationErrorResult.FAIL_SAGA;
    }
}
```

#### FailFastErrorHandler

Always returns `FAIL_SAGA` and logs the error at `ERROR` level. Use this when any compensation failure should immediately abort remaining compensations.

```java
public class FailFastErrorHandler implements CompensationErrorHandler {
    @Override
    public CompensationErrorResult handle(String sagaName, String stepId, Throwable error, int attempt) {
        log.error("Failing saga '{}' due to compensation error on step '{}': {}",
                sagaName, stepId, error.getMessage());
        return CompensationErrorResult.FAIL_SAGA;
    }
}
```

#### LogAndContinueErrorHandler

Always returns `CONTINUE` and logs the error at `WARN` level. Use this when compensation errors should be recorded but not block subsequent compensations.

```java
public class LogAndContinueErrorHandler implements CompensationErrorHandler {
    @Override
    public CompensationErrorResult handle(String sagaName, String stepId, Throwable error, int attempt) {
        log.warn("Compensation error on step '{}' in saga '{}' (attempt {}), continuing: {}",
                stepId, sagaName, attempt, error.getMessage());
        return CompensationErrorResult.CONTINUE;
    }
}
```

#### CompositeCompensationErrorHandler

Chains multiple handlers in order. Iterates through the list and returns the first non-`CONTINUE` result. If all handlers return `CONTINUE`, the composite returns `CONTINUE`.

```java
public class CompositeCompensationErrorHandler implements CompensationErrorHandler {

    public CompositeCompensationErrorHandler(List<CompensationErrorHandler> handlers) { ... }

    @Override
    public CompensationErrorResult handle(String sagaName, String stepId, Throwable error, int attempt) {
        for (CompensationErrorHandler handler : handlers) {
            CompensationErrorResult result = handler.handle(sagaName, stepId, error, attempt);
            if (result != CompensationErrorResult.CONTINUE) {
                return result;
            }
        }
        return CompensationErrorResult.CONTINUE;
    }
}
```

#### CompensationErrorHandlerFactory

Static utility class with a `ConcurrentHashMap<String, Supplier<CompensationErrorHandler>>` registry. Each call to `getHandler()` returns a fresh instance to avoid shared mutable state.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getHandler(String name)` | `Optional<CompensationErrorHandler>` | Fresh handler instance by name |
| `registerHandler(String name, Supplier<CompensationErrorHandler>)` | `void` | Register a handler supplier |
| `registerHandler(String name, CompensationErrorHandler)` | `void` | Register a handler instance |
| `composite(CompensationErrorHandler... handlers)` | `CompensationErrorHandler` | Create a composite handler |
| `resetDefaults()` | `void` | Reset registry to built-in defaults |

**Pre-registered handler names:**

| Name | Handler | Description |
|------|---------|-------------|
| `"default"` | `DefaultCompensationErrorHandler` | Always `CONTINUE` (legacy swallow behavior) |
| `"fail-fast"` | `FailFastErrorHandler` | Always `FAIL_SAGA`, logs at ERROR |
| `"log-and-continue"` | `LogAndContinueErrorHandler` | Always `CONTINUE`, logs at WARN |
| `"retry"` | `RetryWithBackoffErrorHandler(3)` | Retry up to 3 times, then `FAIL_SAGA` |
| `"robust"` | Composite: retry(3) + log-and-continue | Retries first, then logs and continues on exhaustion |

**Configuration:**

```yaml
firefly:
  orchestration:
    saga:
      compensation-error-handler: robust
```

**Example: Selecting a handler programmatically**

```java
CompensationErrorHandler handler = CompensationErrorHandlerFactory.getHandler("robust")
    .orElseGet(DefaultCompensationErrorHandler::new);

// Or register a custom handler
CompensationErrorHandlerFactory.registerHandler("custom",
    () -> new RetryWithBackoffErrorHandler(5, Set.of(TimeoutException.class)));

// Or build a composite inline
CompensationErrorHandler composite = CompensationErrorHandlerFactory.composite(
    new RetryWithBackoffErrorHandler(3),
    new LogAndContinueErrorHandler()
);
```

---

## 16. ExpandEach (Fan-Out)

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

## 17. Saga Builder DSL

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

## 18. SagaEngine API

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

## 19. SagaResult

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
| `report()` | `Optional<ExecutionReport>` | Execution report with per-step details (populated by engine) |

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
| `SagaResult.withReport(ExecutionReport)` | Returns a new `SagaResult` with the report attached |

### ExecutionReport

The `ExecutionReport` record is a unified execution report that captures top-level metadata, per-step outcomes via `StepReport`, execution order, and optional compensation details. It is populated by the engine and attached to the result via `withReport()`.

**Computed methods on `ExecutionReport`:**

| Method | Return Type | Description |
|--------|-------------|-------------|
| `isSuccess()` | `boolean` | `true` if status is `COMPLETED` or `CONFIRMED` |
| `stepCount()` | `int` | Total number of steps |
| `failedStepCount()` | `int` | Number of steps with a failed status |
| `completedStepCount()` | `int` | Number of steps that completed successfully |
| `totalRetries()` | `int` | Sum of retry attempts across all steps (excludes initial attempt) |

**Example: Accessing the report from `SagaResult`**

```java
sagaEngine.execute("TransferFunds", StepInputs.of("debit", request))
    .subscribe(result -> {
        result.report().ifPresent(report -> {
            log.info("Execution: {} steps, {} failed, {} completed, {} total retries",
                report.stepCount(),
                report.failedStepCount(),
                report.completedStepCount(),
                report.totalRetries());

            // Inspect per-step reports
            report.stepReports().forEach((stepId, stepReport) -> {
                log.info("  {} → status={}, attempts={}, latency={}",
                    stepId, stepReport.status(),
                    stepReport.attempts(), stepReport.latency());
            });
        });
    });
```

---

## 20. Saga Composition

### Overview

Saga composition enables multi-saga orchestration with DAG topology. Individual sagas within a composition can depend on each other, share data through flow mappings, and compensate in reverse order on failure. This is useful for complex business processes that span multiple sagas — for example, an e-commerce checkout that orchestrates inventory reservation, payment processing, and shipping fulfillment as separate sagas.

### SagaComposition

The `SagaComposition` class is the immutable definition of a composition — a DAG of multiple sagas.

| Field | Type | Description |
|-------|------|-------------|
| `name` | `String` | Composition name |
| `sagas` | `List<CompositionSaga>` | Ordered list of saga entries |
| `sagaMap` | `Map<String, CompositionSaga>` | Sagas keyed by alias |
| `dataMappings` | `List<DataMapping>` | Data flow mappings between sagas |

| Method | Return Type | Description |
|--------|-------------|-------------|
| `name()` | `String` | Composition name |
| `sagas()` | `List<CompositionSaga>` | All saga entries |
| `sagaMap()` | `Map<String, CompositionSaga>` | Alias-keyed lookup |
| `dataMappings()` | `List<DataMapping>` | Data flow mappings |
| `getExecutableLayers()` | `List<List<CompositionSaga>>` | Topologically sorted layers (sagas in the same layer can execute in parallel) |

#### CompositionSaga Record

Each saga entry in the composition:

```java
public record CompositionSaga(
    String alias,                    // Unique alias within the composition
    String sagaName,                 // Name of the registered saga
    List<String> dependsOn,          // Aliases that must complete first
    Map<String, Object> staticInput, // Static input passed to the saga
    boolean optional,                // If true, failure does not fail the composition
    String condition                 // Optional condition expression
) {}
```

#### DataMapping Record

Defines how data flows from one saga's output to another saga's input:

```java
public record DataMapping(
    String sourceSaga,   // Alias of the upstream saga
    String sourceField,  // Field name in the upstream result
    String targetSaga,   // Alias of the downstream saga
    String targetField   // Field name in the downstream input
) {}
```

### SagaCompositionBuilder

Fluent API for constructing `SagaComposition` instances with built-in validation.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `SagaCompositionBuilder.composition(String name)` | `SagaCompositionBuilder` | Static factory |
| `.saga(String alias)` | `SagaEntryBuilder` | Begin a saga entry |
| `.dataFlow(sourceSaga, sourceField, targetSaga, targetField)` | `SagaCompositionBuilder` | Add a data flow mapping |
| `.build()` | `SagaComposition` | Validate and build the composition |

**`SagaEntryBuilder` methods:**

| Method | Return Type | Description |
|--------|-------------|-------------|
| `.sagaName(String)` | `SagaEntryBuilder` | Set the registered saga name (defaults to alias) |
| `.dependsOn(String...)` | `SagaEntryBuilder` | Add dependency aliases |
| `.input(String key, Object value)` | `SagaEntryBuilder` | Add static input |
| `.optional(boolean)` | `SagaEntryBuilder` | Mark as optional |
| `.condition(String)` | `SagaEntryBuilder` | Set a condition expression |
| `.add()` | `SagaCompositionBuilder` | Finish entry, return to parent builder |

**Full builder example:**

```java
SagaComposition composition = SagaCompositionBuilder.composition("OrderFulfillment")
    .saga("inventory")
        .sagaName("InventoryReservationSaga")
        .input("warehouse", "US-EAST")
        .add()
    .saga("payment")
        .sagaName("PaymentProcessingSaga")
        .dependsOn("inventory")
        .add()
    .saga("shipping")
        .sagaName("ShippingFulfillmentSaga")
        .dependsOn("payment")
        .add()
    .saga("notification")
        .sagaName("NotificationSaga")
        .dependsOn("payment")
        .optional(true)
        .add()
    .dataFlow("inventory", "reservationId", "shipping", "reservationId")
    .dataFlow("payment", "transactionId", "shipping", "paymentRef")
    .dataFlow("payment", "transactionId", "notification", "paymentRef")
    .build();
```

This produces a DAG where `inventory` runs first, then `payment`, then `shipping` and `notification` run in parallel. Data flows automatically from upstream results to downstream inputs.

### CompositionContext

Mutable execution context that tracks the state of a running composition.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getGlobalCorrelationId()` | `String` | Global correlation ID for the composition |
| `getStartedAt()` | `Instant` | Composition start time |
| `recordResult(String alias, SagaResult result)` | `void` | Record a saga result and add to execution order |
| `getResult(String alias)` | `SagaResult` | Retrieve the result for a saga alias |
| `isCompleted(String alias)` | `boolean` | Check if a saga has a recorded result |
| `getExecutionOrder()` | `List<String>` | Aliases in execution order |
| `getSagaResults()` | `Map<String, SagaResult>` | All saga results |
| `getCorrelationIds()` | `Map<String, String>` | Correlation IDs keyed by alias |
| `getSagaStatuses()` | `Map<String, ExecutionStatus>` | Status for each saga |
| `setSagaStatus(String alias, ExecutionStatus)` | `void` | Update a saga's status |
| `getCompletedAliasesInOrder()` | `List<String>` | Aliases of completed sagas in execution order (for reverse compensation) |

### CompositionDataFlowManager

Resolves inputs for downstream sagas by merging global input, static input, and data flow mappings from upstream saga results.

```java
public class CompositionDataFlowManager {

    public Map<String, Object> resolveInputs(
            SagaComposition composition,
            SagaComposition.CompositionSaga saga,
            CompositionContext ctx,
            Map<String, Object> globalInput) { ... }
}
```

The merge order is:
1. Start with `globalInput`
2. Overlay `staticInput` from the `CompositionSaga` entry
3. Apply `DataMapping` entries — extract values from upstream saga step results and inject into the input map

### CompositionCompensationManager

Handles reverse-order compensation when a composition fails. Uses the configured `CompensationPolicy` to determine whether sagas are compensated sequentially or in parallel.

```java
public class CompositionCompensationManager {

    public CompositionCompensationManager(SagaEngine sagaEngine,
                                           CompensationPolicy compensationPolicy) { ... }

    public Mono<Void> compensateComposition(SagaComposition composition,
                                             CompositionContext context) { ... }
}
```

The manager retrieves the list of completed saga aliases in execution order, reverses it, and compensates each saga according to the policy:
- `STRICT_SEQUENTIAL`, `RETRY_WITH_BACKOFF`, `CIRCUIT_BREAKER` — compensate one at a time in reverse order
- `BEST_EFFORT_PARALLEL`, `GROUPED_PARALLEL` — compensate all in parallel

### CompositionValidator

Validates a `SagaComposition` definition for structural correctness before execution.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `validate(SagaComposition)` | `List<ValidationIssue>` | Returns all validation issues (empty = valid) |
| `validateAndThrow(SagaComposition)` | `void` | Throws `IllegalStateException` if any ERROR-level issues |

**Validation checks performed:**

| Check | Severity | Description |
|-------|----------|-------------|
| Empty saga list | ERROR | Composition must have at least one saga |
| Duplicate aliases | ERROR | Each alias must be unique |
| Missing dependencies | ERROR | All `dependsOn` values must reference existing aliases |
| Missing saga names | WARNING | All `sagaName` values should exist in `SagaRegistry` |
| Circular dependencies | ERROR | DAG must be acyclic |
| Data mapping references | ERROR | Source and target aliases in `DataMapping` must exist |

`ValidationIssue` is a record with `severity` (`ERROR`, `WARNING`, `INFO`), `message`, and `location`.

### CompositionVisualizationService

Generates graph diagrams from a `SagaComposition` for documentation and debugging.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `toDot(SagaComposition)` | `String` | Graphviz DOT representation |
| `toMermaid(SagaComposition)` | `String` | Mermaid diagram representation |

Both methods render saga nodes (with alias, saga name, and optional markers), dependency edges (solid arrows), and data flow mappings (dashed arrows with field labels).

```java
CompositionVisualizationService viz = new CompositionVisualizationService();
String dot = viz.toDot(composition);      // Graphviz DOT format
String mermaid = viz.toMermaid(composition); // Mermaid diagram format
```

### CompositionTemplateRegistry

Registry for reusable composition templates. Templates can be registered once and retrieved for repeated execution.

```java
public class CompositionTemplateRegistry {

    public record CompositionTemplate(
        String name,
        SagaComposition composition,
        String description
    ) {}

    public void register(CompositionTemplate template) { ... }
    public CompositionTemplate get(String name) { ... }
    public Collection<CompositionTemplate> getAll() { ... }
    public boolean has(String name) { ... }
}
```

**Example: Registering and using a template**

```java
CompositionTemplateRegistry registry = new CompositionTemplateRegistry();

SagaComposition composition = SagaCompositionBuilder.composition("OrderFulfillment")
    .saga("inventory").sagaName("InventoryReservationSaga").add()
    .saga("payment").sagaName("PaymentProcessingSaga").dependsOn("inventory").add()
    .saga("shipping").sagaName("ShippingFulfillmentSaga").dependsOn("payment").add()
    .build();

registry.register(new CompositionTemplateRegistry.CompositionTemplate(
    "order-fulfillment", composition, "Standard order fulfillment pipeline"));

// Later, retrieve and execute
if (registry.has("order-fulfillment")) {
    SagaComposition template = registry.get("order-fulfillment").composition();
    // execute the composition...
}
```

---

[← Back to Index](README.md) | [Previous: Workflow Pattern](workflow.md) | [Next: TCC Pattern →](tcc.md)

---
Copyright 2026 Firefly Software Solutions Inc. Licensed under Apache 2.0.
