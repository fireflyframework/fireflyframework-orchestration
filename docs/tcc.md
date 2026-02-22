[← Back to Index](README.md) | [Previous: Saga Pattern](saga.md) | [Next: Core Infrastructure →](core-infrastructure.md)

# Part IV: TCC Pattern

**Contents:**
- [§21 TCC Annotation Reference](#21-tcc-annotation-reference)
- [§22 TCC Tutorial](#22-tcc-tutorial)
- [§23 TCC Phases & Timeout/Retry](#23-tcc-phases--timeoutretry)
- [§24 TCC Builder DSL](#24-tcc-builder-dsl)
- [§25 TccEngine API](#25-tccengine-api)
- [§26 TccResult](#26-tccresult)
- [§27 TCC Composition](#27-tcc-composition)

## 21. TCC Annotation Reference

### @Tcc

Marks a class as a TCC (Try-Confirm-Cancel) transaction coordinator. The coordinator class contains nested `@TccParticipant` classes.

**Target:** `ElementType.TYPE` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | `String` | *(required)* | Unique TCC name |
| `timeoutMs` | `long` | `-1` | Global timeout in milliseconds (`-1` = use default from properties) |
| `retryEnabled` | `boolean` | `true` | Whether retries are enabled |
| `maxRetries` | `int` | `3` | Default max retry attempts |
| `backoffMs` | `long` | `1000` | Default backoff in milliseconds |
| `triggerEventType` | `String` | `""` | Event type that triggers this TCC via `EventGateway` |

### @TccParticipant

Marks a nested class as a TCC participant. Each participant implements a Try/Confirm/Cancel lifecycle.

**Target:** `ElementType.TYPE` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `id` | `String` | *(required)* | Unique participant identifier |
| `order` | `int` | `0` | Execution order (Try phase runs in this order) |
| `timeoutMs` | `long` | `-1` | Participant-level timeout (`-1` = use TCC default) |
| `optional` | `boolean` | `false` | If `true`, Try failure does not trigger Cancel for other participants |
| `jitter` | `boolean` | `false` | Apply jitter to backoff delays |
| `jitterFactor` | `double` | `0.0` | Jitter factor (`0.0`–`1.0`) |

### @TryMethod

Marks the Try phase method on a `@TccParticipant`. The method must return `Mono<T>`. There must be exactly one `@TryMethod` per participant.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `timeoutMs` | `long` | `-1` | Try-phase timeout (`-1` = use participant/TCC default) |
| `retry` | `int` | `-1` | Try-phase retry attempts (`-1` = use TCC default) |
| `backoffMs` | `long` | `-1` | Try-phase backoff (`-1` = use TCC default) |

### @ConfirmMethod

Marks the Confirm phase method. Runs when **all** Try phases succeed. Must return `Mono<?>`.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `timeoutMs` | `long` | `-1` | Confirm-phase timeout |
| `retry` | `int` | `-1` | Confirm-phase retry attempts |
| `backoffMs` | `long` | `-1` | Confirm-phase backoff |

### @CancelMethod

Marks the Cancel phase method. Runs when **any** Try phase fails. Must return `Mono<?>`.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `timeoutMs` | `long` | `-1` | Cancel-phase timeout |
| `retry` | `int` | `-1` | Cancel-phase retry attempts |
| `backoffMs` | `long` | `-1` | Cancel-phase backoff |

### @TccEvent

Publishes an event when a TCC participant completes its Confirm phase.

**Target:** `ElementType.TYPE` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `topic` | `String` | *(required)* | Event topic |
| `eventType` | `String` | *(required)* | Event type |
| `key` | `String` | `""` | Event key (for partitioning) |

### @OnTccComplete

Callback invoked when the TCC transaction completes successfully (all Confirms succeed).

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `async` | `boolean` | `false` | Run callback asynchronously |
| `priority` | `int` | `0` | Ordering priority |

The callback method can declare `ExecutionContext` and/or `TccResult` as parameters.

### @OnTccError

Callback invoked when the TCC transaction fails.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `errorTypes` | `Class<? extends Throwable>[]` | `{}` | Filter by exception type |
| `suppressError` | `boolean` | `false` | Convert failure to success |
| `async` | `boolean` | `false` | Run callback asynchronously |
| `priority` | `int` | `0` | Ordering priority |

### @ScheduledTcc

Schedules automatic execution of a TCC transaction. `@Repeatable`.

**Target:** `ElementType.TYPE` | **Retention:** `RUNTIME` | **Repeatable:** Yes (container: `@ScheduledTccs`)

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `cron` | `String` | `""` | Cron expression |
| `zone` | `String` | `""` | Timezone |
| `fixedDelay` | `long` | `-1` | Fixed delay in milliseconds |
| `fixedRate` | `long` | `-1` | Fixed rate in milliseconds |
| `initialDelay` | `long` | `0` | Initial delay |
| `enabled` | `boolean` | `true` | Whether active |
| `input` | `String` | `"{}"` | JSON input |
| `description` | `String` | `""` | Description |

---

## 22. TCC Tutorial

**TCC (Try-Confirm-Cancel)** is a two-phase distributed transaction protocol. Each participant implements three operations:

| Phase       | Purpose                              | When It Runs                   |
|-------------|--------------------------------------|--------------------------------|
| **Try**     | Reserve resources, validate          | Always runs first              |
| **Confirm** | Commit the reservation               | Only if ALL tries succeed      |
| **Cancel**  | Release the reservation              | Only if ANY try fails          |

```
Participant A: Try ok   Participant B: Try ok   → Confirm A, Confirm B
Participant A: Try ok   Participant B: Try FAIL → Cancel A (B was never reserved)
Participant A: Try ok   Confirm A FAIL          → Cancel A (confirm failure triggers cancel)
```

**Key difference from Saga:** In a Saga, the forward action is committed immediately and must be compensated on failure. In TCC, the Try phase only *reserves* resources — nothing is committed until the Confirm phase.

### Step 1: Define the TCC Coordinator

```java
@Tcc(name = "TransferFunds", timeoutMs = 30000,
     retryEnabled = true, maxRetries = 3, backoffMs = 500)
public class TransferFundsTcc {

    @TccParticipant(id = "debit", order = 0)
    public static class DebitParticipant {

        private final AccountService accountService;

        public DebitParticipant(AccountService accountService) {
            this.accountService = accountService;
        }

        @TryMethod(timeoutMs = 5000, retry = 2)
        public Mono<String> tryDebit(@Input TransferRequest request) {
            return accountService.holdFunds(
                request.fromAccount(), request.amount());
        }

        @ConfirmMethod(timeoutMs = 5000, retry = 3)
        public Mono<Void> confirmDebit(@FromTry("debit") String holdId) {
            return accountService.commitHold(holdId);
        }

        @CancelMethod(timeoutMs = 5000, retry = 3)
        public Mono<Void> cancelDebit(@FromTry("debit") String holdId) {
            return accountService.releaseHold(holdId);
        }
    }

    @TccParticipant(id = "credit", order = 1)
    public static class CreditParticipant {

        private final AccountService accountService;

        public CreditParticipant(AccountService accountService) {
            this.accountService = accountService;
        }

        @TryMethod
        public Mono<String> tryCredit(@Input TransferRequest request) {
            return accountService.prepareCredit(
                request.toAccount(), request.amount());
        }

        @ConfirmMethod
        public Mono<Void> confirmCredit(@FromTry("credit") String prepId) {
            return accountService.commitCredit(prepId);
        }

        @CancelMethod
        public Mono<Void> cancelCredit(@FromTry("credit") String prepId) {
            return accountService.cancelCredit(prepId);
        }
    }

    @OnTccComplete
    public void onComplete(TccResult result) {
        log.info("Transfer confirmed: {}", result.correlationId());
    }

    @OnTccError
    public void onError(Throwable error, ExecutionContext ctx) {
        log.error("Transfer failed: {}", ctx.getCorrelationId(), error);
    }
}
```

Key points:

- Participants are **nested static classes** inside the `@Tcc` coordinator
- `order` determines Try phase execution order — `debit` runs before `credit`
- `@FromTry("debit")` injects the Try-phase result into Confirm/Cancel methods
- Each phase has independent timeout and retry settings

### Step 2: Execute the TCC Transaction

```java
@Service
public class TransferService {
    private final TccEngine tccEngine;

    public Mono<TccResult> transfer(TransferRequest request) {
        return tccEngine.execute("TransferFunds",
            TccInputs.builder()
                .forParticipant("debit", request)
                .forParticipant("credit", request)
                .build());
    }
}
```

### Step 3: Handle the Result

```java
tccEngine.execute("TransferFunds", inputs)
    .subscribe(result -> {
        switch (result.status()) {
            case CONFIRMED -> {
                log.info("Transfer confirmed: {}", result.correlationId());
                Optional<String> holdId = result.tryResultOf("debit", String.class);
            }
            case CANCELED -> {
                log.warn("Transfer canceled: participant={}, error={}",
                    result.failedParticipantId().orElse("unknown"),
                    result.error().map(Throwable::getMessage).orElse("none"));
            }
            case FAILED -> {
                log.error("Transfer failed: phase={}, participant={}",
                    result.failedPhase().orElse(null),
                    result.failedParticipantId().orElse("unknown"));
            }
        }
    });
```

### Optional Participants

An optional participant's Try failure does **not** trigger Cancel for other participants:

```java
@TccParticipant(id = "loyalty-points", order = 2, optional = true)
public static class LoyaltyParticipant {
    @TryMethod
    public Mono<Integer> tryAwardPoints(@Input TransferRequest req) {
        return loyaltyService.tryAward(req.amount());
    }
    // ... confirm/cancel methods
}
```

If `loyalty-points` Try fails, `debit` and `credit` proceed normally to Confirm.

---

## 23. TCC Phases & Timeout/Retry

### Phase Lifecycle

```
TRY Phase
  ├── All participants run Try in order
  ├── Each Try can have its own timeout, retry, and backoff
  ├── If ALL succeed → CONFIRM Phase
  └── If ANY fails → CANCEL Phase (for participants whose Try succeeded)

CONFIRM Phase
  ├── All participants run Confirm
  ├── Each Confirm can have its own timeout and retry
  ├── If ALL succeed → result = CONFIRMED
  └── If ANY fails → CANCEL Phase (fallback), result = CANCELED or FAILED

CANCEL Phase
  ├── Run for participants whose Try succeeded
  ├── Each Cancel can have its own timeout and retry
  ├── If ALL succeed → result = CANCELED
  └── If ANY fails → result = FAILED (routes to DLQ)
```

### Per-Phase Configuration

Each phase can be configured independently at the annotation level:

```java
@TccParticipant(id = "payment", order = 0,
                jitter = true, jitterFactor = 0.3)
public static class PaymentParticipant {

    @TryMethod(timeoutMs = 5000, retry = 2, backoffMs = 200)
    public Mono<String> tryReserve(@Input PaymentRequest req) { ... }

    @ConfirmMethod(timeoutMs = 10000, retry = 5, backoffMs = 500)
    public Mono<Void> confirm(@FromTry("payment") String reserveId) { ... }

    @CancelMethod(timeoutMs = 10000, retry = 5, backoffMs = 500)
    public Mono<Void> cancel(@FromTry("payment") String reserveId) { ... }
}
```

### Effective Value Resolution

When a phase-level attribute is `-1`, the framework resolves the effective value using this fallback chain:

1. Phase-level attribute (`@TryMethod.retry`, `@ConfirmMethod.retry`, `@CancelMethod.retry`)
2. TCC-level default (`@Tcc.maxRetries`, `@Tcc.backoffMs`)
3. Properties default (`firefly.orchestration.tcc.default-timeout`)

The `TccParticipantDefinition` provides helper methods for resolution:

```java
long getEffectiveTryTimeout(long defaultTimeout)
long getEffectiveConfirmTimeout(long defaultTimeout)
long getEffectiveCancelTimeout(long defaultTimeout)
int  getEffectiveTryRetry(int defaultRetry)
int  getEffectiveConfirmRetry(int defaultRetry)
int  getEffectiveCancelRetry(int defaultRetry)
long getEffectiveTryBackoff(long defaultBackoff)
long getEffectiveConfirmBackoff(long defaultBackoff)
long getEffectiveCancelBackoff(long defaultBackoff)
```

### Confirm Failure Behavior

If the Confirm phase fails for any participant, the framework automatically chains into the Cancel phase to restore consistency:

- If Cancel succeeds → result status is `CANCELED`
- If Cancel also fails → result status is `FAILED`, and a DLQ entry is created

---

## 24. TCC Builder DSL

The programmatic builder provides a fluent API for constructing TCC transactions without annotations.

### TccBuilder Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `TccBuilder.tcc(String name)` | `TccBuilder` | Create with defaults (`retryEnabled=true`, `maxRetries=3`, `backoffMs=1000`) |
| `TccBuilder.named(String name)` | `TccBuilder` | Alias for `tcc()` |
| `TccBuilder.tccNoRetry(String name)` | `TccBuilder` | Create with retries disabled (`retryEnabled=false`, `maxRetries=0`, `backoffMs=0`) |
| `TccBuilder.tcc(String name, long timeoutMs)` | `TccBuilder` | Create with explicit timeout |
| `TccBuilder.tcc(String name, long timeoutMs, boolean retryEnabled, int maxRetries, long backoffMs)` | `TccBuilder` | Full control over defaults |
| `.triggerEventType(String)` | `TccBuilder` | Event type that triggers via `EventGateway` |
| `.participant(String id)` | `Participant` | Begin defining a participant |
| `.build()` | `TccDefinition` | Build the immutable definition |

### Participant Builder Methods

| Method | Description |
|--------|-------------|
| `.order(int)` | Execution order for Try phase |
| `.timeoutMs(long)` | Participant-level timeout |
| `.optional(boolean)` | Try failure doesn't trigger Cancel for others |
| `.tryTimeoutMs(long)` | Try-phase timeout |
| `.tryRetry(int)` | Try-phase retry attempts |
| `.tryBackoffMs(long)` | Try-phase backoff |
| `.confirmTimeoutMs(long)` | Confirm-phase timeout |
| `.confirmRetry(int)` | Confirm-phase retry |
| `.confirmBackoffMs(long)` | Confirm-phase backoff |
| `.cancelTimeoutMs(long)` | Cancel-phase timeout |
| `.cancelRetry(int)` | Cancel-phase retry |
| `.cancelBackoffMs(long)` | Cancel-phase backoff |
| `.jitter(boolean)` | Apply jitter to backoff |
| `.jitterFactor(double)` | Jitter factor |
| `.event(String topic, String eventType, String key)` | Event published on Confirm |
| `.handler(Object bean)` | Convention-based handler (bean with `doTry`, `doConfirm`, `doCancel`) |
| `.tryHandler(BiFunction<Object, ExecutionContext, Mono<?>>)` | Lambda for Try phase |
| `.confirmHandler(BiFunction<Object, ExecutionContext, Mono<?>>)` | Lambda for Confirm phase |
| `.cancelHandler(BiFunction<Object, ExecutionContext, Mono<?>>)` | Lambda for Cancel phase |
| `.add()` | Finish participant, return to `TccBuilder` |

### Full Builder Example

```java
TccDefinition def = TccBuilder.tcc("TransferFunds")
    .triggerEventType("TransferRequested")
    .participant("debit")
        .order(0)
        .tryTimeoutMs(5_000L)
        .tryRetry(2)
        .tryBackoffMs(200L)
        .confirmTimeoutMs(5_000L)
        .confirmRetry(3)
        .cancelTimeoutMs(5_000L)
        .cancelRetry(3)
        .jitter(true)
        .event("transfers", "DebitConfirmed", "holdId")
        .tryHandler((input, ctx) -> {
            TransferRequest req = (TransferRequest) input;
            return accountService.holdFunds(req.fromAccount(), req.amount());
        })
        .confirmHandler((tryResult, ctx) -> {
            String holdId = (String) tryResult;
            return accountService.commitHold(holdId);
        })
        .cancelHandler((tryResult, ctx) -> {
            String holdId = (String) tryResult;
            return accountService.releaseHold(holdId);
        })
        .add()
    .participant("credit")
        .order(1)
        .tryHandler((input, ctx) ->
            accountService.prepareCredit(
                ((TransferRequest) input).toAccount(),
                ((TransferRequest) input).amount()))
        .confirmHandler((tryResult, ctx) ->
            accountService.commitCredit((String) tryResult))
        .cancelHandler((tryResult, ctx) ->
            accountService.cancelCredit((String) tryResult))
        .add()
    .build();
```

### Convention-Based Handler

Pass a bean with `doTry`, `doConfirm`, and `doCancel` methods:

```java
public class DebitHandler {
    public Mono<?> doTry(Object input, ExecutionContext ctx) { ... }
    public Mono<?> doConfirm(Object input, ExecutionContext ctx) { ... }
    public Mono<?> doCancel(Object input, ExecutionContext ctx) { ... }
}

TccBuilder.tcc("Transfer")
    .participant("debit")
        .handler(new DebitHandler())
        .add()
    .build();
```

### No-Retry Shorthand

For TCC transactions that should not retry any phase:

```java
TccDefinition def = TccBuilder.tccNoRetry("QuickTransfer")
    .participant("debit")
        .tryHandler(...)
        .confirmHandler(...)
        .cancelHandler(...)
        .add()
    .build();
```

---

## 25. TccEngine API

`TccEngine` is the primary entry point for TCC transaction execution.

### Method Reference

| Method | Return Type | Description |
|--------|-------------|-------------|
| `execute(String tccName, TccInputs inputs)` | `Mono<TccResult>` | Execute by name with per-participant inputs |
| `execute(String tccName, TccInputs inputs, ExecutionContext ctx)` | `Mono<TccResult>` | Execute with explicit context |
| `execute(String tccName, Map<String, Object> participantInputs)` | `Mono<TccResult>` | Execute with raw input map |
| `execute(TccDefinition tcc, TccInputs inputs)` | `Mono<TccResult>` | Execute a builder-created definition |
| `execute(TccDefinition tcc, TccInputs inputs, ExecutionContext ctx)` | `Mono<TccResult>` | Execute with definition and explicit context |

### TccInputs Construction

```java
// Builder pattern
TccInputs inputs = TccInputs.builder()
    .forParticipant("debit", request)
    .forParticipant("credit", request)
    .build();

// Shorthand from map
TccInputs inputs = TccInputs.of(Map.of(
    "debit", request,
    "credit", request
));

// Empty (participants rely on @FromTry or context)
TccInputs inputs = TccInputs.empty();
```

### Execution Flow

1. Look up `TccDefinition` from `TccRegistry` (or use the provided definition)
2. Create `ExecutionContext` (or use the provided one)
3. Execute Try phase for all participants in `order` sequence
4. If all Tries succeed → execute Confirm phase for all participants
5. If any Try fails → execute Cancel phase for participants whose Try succeeded
6. If Confirm fails → fall back to Cancel phase
7. Fire lifecycle callbacks (`@OnTccComplete` / `@OnTccError`)
8. Persist `ExecutionState` and publish events
9. Return `TccResult`

---

## 26. TccResult

`TccResult` is an immutable snapshot of a completed TCC transaction.

### Status Enum

| Value | Meaning |
|-------|---------|
| `CONFIRMED` | All Try and Confirm phases succeeded |
| `CANCELED` | A Try or Confirm failed, Cancel phases ran successfully |
| `FAILED` | A Cancel phase failed (data may be inconsistent — DLQ entry created) |

### Top-Level Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `tccName()` | `String` | TCC name |
| `correlationId()` | `String` | Unique execution ID |
| `status()` | `Status` | `CONFIRMED`, `CANCELED`, or `FAILED` |
| `isConfirmed()` | `boolean` | All phases succeeded |
| `isCanceled()` | `boolean` | Try failed, Cancel ran |
| `isFailed()` | `boolean` | Cancel phase failed |
| `duration()` | `Duration` | Time between start and completion |
| `startedAt()` | `Instant` | Start time |
| `completedAt()` | `Instant` | Completion time |
| `error()` | `Optional<Throwable>` | Primary error |
| `failedParticipantId()` | `Optional<String>` | Participant that caused the failure |
| `failedPhase()` | `Optional<TccPhase>` | Phase that failed (`TRY`, `CONFIRM`, or `CANCEL`) |
| `participants()` | `Map<String, ParticipantOutcome>` | All participant outcomes |
| `tryResultOf(String participantId, Class<T> type)` | `Optional<T>` | Type-safe Try-phase result extraction |
| `report()` | `Optional<ExecutionReport>` | Execution report with per-step details (populated by engine) |
| `withReport(ExecutionReport)` | `TccResult` | Returns new TccResult with report attached |

### ExecutionReport

`TccResult` can carry an `ExecutionReport` — a unified report record shared across all three orchestration patterns (Workflow, Saga, TCC). The engine populates this report automatically after execution.

**`ExecutionReport` record fields:**

| Field | Type | Description |
|-------|------|-------------|
| `executionName` | `String` | TCC name |
| `correlationId` | `String` | Unique execution ID |
| `pattern` | `ExecutionPattern` | Always `TCC` for TCC results |
| `status` | `ExecutionStatus` | Overall execution status |
| `startedAt` | `Instant` | Start time |
| `completedAt` | `Instant` | Completion time |
| `duration` | `Duration` | Wall-clock duration |
| `stepReports` | `Map<String, StepReport>` | Per-participant step reports |
| `executionOrder` | `List<String>` | Participants in execution order |
| `variables` | `Map<String, Object>` | Execution variables snapshot |
| `failureReason` | `String` | Human-readable failure reason |
| `compensationReport` | `CompensationReport` | Compensation details (if Cancel was triggered) |

**Computed methods on `ExecutionReport`:**

| Method | Return Type | Description |
|--------|-------------|-------------|
| `isSuccess()` | `boolean` | `true` if status is `COMPLETED` or `CONFIRMED` |
| `stepCount()` | `int` | Total number of steps |
| `failedStepCount()` | `int` | Count of steps with a failed status |
| `completedStepCount()` | `int` | Count of steps that completed successfully |
| `totalRetries()` | `int` | Sum of retry attempts across all steps |

**`StepReport` record:** `stepId`, `status` (`StepStatus`), `attempts`, `latency` (`Duration`), `result`, `error`, `startedAt`, `completedAt`.

**Example: Accessing the report from a TccResult**

```java
tccEngine.execute("TransferFunds", inputs)
    .subscribe(result -> {
        result.report().ifPresent(report -> {
            log.info("Execution: {} steps, {} failed, {} retries, duration={}",
                report.stepCount(),
                report.failedStepCount(),
                report.totalRetries(),
                report.duration());

            report.stepReports().forEach((stepId, step) ->
                log.info("  {} → status={}, attempts={}, latency={}",
                    stepId, step.status(), step.attempts(), step.latency()));

            if (report.compensationReport() != null) {
                log.info("Compensation: allCompensated={}, duration={}",
                    report.compensationReport().allCompensated(),
                    report.compensationReport().totalDuration());
            }
        });
    });
```

### ParticipantOutcome Record

| Field | Type | Description |
|-------|------|-------------|
| `participantId` | `String` | Participant identifier |
| `tryResult` | `Object` | Try phase return value |
| `trySucceeded` | `boolean` | Whether Try succeeded |
| `confirmSucceeded` | `boolean` | Whether Confirm succeeded |
| `cancelExecuted` | `boolean` | Whether Cancel was run |
| `error` | `Throwable` | Error (`null` if no error) |
| `attempts` | `int` | Total attempts across all phases |
| `latencyMs` | `long` | Total latency in milliseconds |

### Example: Handling All Three Outcomes

```java
tccEngine.execute("TransferFunds", inputs)
    .subscribe(result -> {
        switch (result.status()) {
            case CONFIRMED -> {
                log.info("Transfer confirmed: {}", result.correlationId());
                String holdId = result.tryResultOf("debit", String.class)
                    .orElse("N/A");
                log.info("Debit hold: {}", holdId);
            }
            case CANCELED -> {
                log.warn("Transfer canceled");
                log.warn("  Failed participant: {}",
                    result.failedParticipantId().orElse("unknown"));
                log.warn("  Failed phase: {}",
                    result.failedPhase().orElse(null));
                log.warn("  Error: {}",
                    result.error().map(Throwable::getMessage).orElse("none"));
            }
            case FAILED -> {
                log.error("Transfer FAILED — manual intervention may be needed");
                log.error("  Failed participant: {}",
                    result.failedParticipantId().orElse("unknown"));
                // Check DLQ for details
            }
        }

        // Inspect per-participant details
        result.participants().forEach((id, outcome) -> {
            log.info("  {} → try={}, confirm={}, cancel={}, attempts={}, latency={}ms",
                id, outcome.trySucceeded(), outcome.confirmSucceeded(),
                outcome.cancelExecuted(), outcome.attempts(), outcome.latencyMs());
        });
    });
```

### Factory Methods

| Method | Description |
|--------|-------------|
| `TccResult.confirmed(tccName, ctx, participants)` | All phases succeeded |
| `TccResult.canceled(tccName, ctx, failedId, failedPhase, error, participants)` | Try/Confirm failed, Cancel ran |
| `TccResult.failed(tccName, ctx, failedId, failedPhase, error, participants)` | Cancel phase failed |

---

## 27. TCC Composition

### Overview

TCC Composition provides multi-TCC orchestration with DAG topology — parallel to the saga composition subsystem. Multiple independent TCC transactions can be composed into a single coordinated execution where:

- TCCs within a composition can **depend on each other**, forming a directed acyclic graph
- TCCs **share data via mappings** — output from one TCC flows as input to downstream TCCs
- On failure, confirmed TCCs are **compensated (canceled) in reverse execution order**
- TCCs in the same layer of the DAG execute **in parallel**

### TccComposition

`TccComposition` is the immutable definition of a composition — a DAG of TCC transactions with data flow between them.

| Field | Type | Description |
|-------|------|-------------|
| `name` | `String` | Composition name |
| `tccs` | `List<CompositionTcc>` | Ordered list of TCC entries |
| `tccMap` | `Map<String, CompositionTcc>` | TCC entries keyed by alias (derived from `tccs`) |
| `dataMappings` | `List<DataMapping>` | Data flow mappings between TCCs |

**Key method:**

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getExecutableLayers()` | `List<List<CompositionTcc>>` | Topologically sorted layers; delegates to `TopologyBuilder.buildLayers()` |

TCCs within the same layer have no mutual dependencies and can execute in parallel. Layers execute sequentially, ensuring all upstream TCCs complete before downstream TCCs begin.

#### CompositionTcc Record

Each TCC entry in a composition is represented by a `CompositionTcc` record:

| Field | Type | Description |
|-------|------|-------------|
| `alias` | `String` | Unique alias within the composition |
| `tccName` | `String` | Name of the TCC transaction (as registered in `TccRegistry`) |
| `dependsOn` | `List<String>` | Aliases of TCCs that must complete before this one |
| `staticInput` | `Map<String, Object>` | Static input values merged into this TCC's input |
| `optional` | `boolean` | If `true`, failure does not trigger compensation of other TCCs |
| `condition` | `String` | Optional condition expression for conditional execution |

#### DataMapping Record

Data flows between TCCs are defined by `DataMapping` records:

| Field | Type | Description |
|-------|------|-------------|
| `sourceTcc` | `String` | Alias of the upstream TCC |
| `sourceField` | `String` | Field name extracted from the upstream TCC's Try results |
| `targetTcc` | `String` | Alias of the downstream TCC |
| `targetField` | `String` | Input field name injected into the downstream TCC |

### TccCompositionBuilder

`TccCompositionBuilder` provides a fluent API for constructing `TccComposition` instances with built-in validation.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `TccCompositionBuilder.composition(String name)` | `TccCompositionBuilder` | Static factory — creates a new builder |
| `.tcc(String alias)` | `TccEntryBuilder` | Begin defining a TCC entry |
| `.dataFlow(String sourceTcc, String sourceField, String targetTcc, String targetField)` | `TccCompositionBuilder` | Add a data flow mapping |
| `.build()` | `TccComposition` | Validate and build the immutable composition |

**`TccEntryBuilder` methods:**

| Method | Return Type | Description |
|--------|-------------|-------------|
| `.tccName(String)` | `TccEntryBuilder` | TCC name (defaults to alias if not set) |
| `.dependsOn(String...)` | `TccEntryBuilder` | Aliases this TCC depends on |
| `.input(String key, Object value)` | `TccEntryBuilder` | Add a static input entry |
| `.optional(boolean)` | `TccEntryBuilder` | Mark as optional |
| `.condition(String)` | `TccEntryBuilder` | Set a condition expression |
| `.add()` | `TccCompositionBuilder` | Finish this TCC entry and return to the composition builder |

**Full example — multi-TCC composition:**

```java
TccComposition composition = TccCompositionBuilder.composition("OrderFulfillment")
    .tcc("reserve-inventory")
        .tccName("ReserveInventory")
        .input("warehouse", "primary")
        .add()
    .tcc("reserve-payment")
        .tccName("ReservePayment")
        .add()
    .tcc("apply-discount")
        .tccName("ApplyDiscount")
        .dependsOn("reserve-payment")
        .optional(true)
        .add()
    .tcc("confirm-shipment")
        .tccName("ConfirmShipment")
        .dependsOn("reserve-inventory", "reserve-payment")
        .add()
    .tcc("send-notification")
        .tccName("SendNotification")
        .dependsOn("confirm-shipment", "apply-discount")
        .optional(true)
        .add()
    .dataFlow("reserve-inventory", "reservationId",
              "confirm-shipment", "inventoryReservationId")
    .dataFlow("reserve-payment", "holdId",
              "confirm-shipment", "paymentHoldId")
    .dataFlow("reserve-payment", "amount",
              "apply-discount", "originalAmount")
    .build();
```

This produces a three-layer DAG:

```
Layer 0: [reserve-inventory, reserve-payment]   ← parallel
Layer 1: [apply-discount]                        ← depends on reserve-payment
Layer 2: [confirm-shipment, send-notification]   ← depends on upstream layers
```

### TccCompositor

`TccCompositor` orchestrates the execution of a `TccComposition`. It delegates individual TCC execution to `TccEngine` and coordinates layer-by-layer processing with parallel execution within layers.

**Dependencies:** `TccEngine`, `OrchestrationEvents`, `TccCompositionDataFlowManager`, `TccCompositionCompensationManager`, optional `BackpressureStrategy`.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `compose(TccComposition composition, Map<String, Object> globalInput)` | `Mono<TccCompositionResult>` | Execute the full composition |

**Execution behavior:**

1. Generates a `correlationId` and creates a `TccCompositionContext`
2. Computes executable layers via `composition.getExecutableLayers()`
3. Executes layers sequentially; TCCs within each layer run **in parallel**
4. For each TCC, resolves inputs via `TccCompositionDataFlowManager` (global input + static input + upstream results)
5. Delegates to `TccEngine.execute(tccName, inputMap)` for each TCC
6. Tracks results and statuses in `TccCompositionContext`
7. If a non-optional TCC fails, halts execution and triggers compensation via `TccCompositionCompensationManager`
8. Returns `TccCompositionResult.success(...)` or `TccCompositionResult.failure(...)`

```java
TccCompositor compositor = new TccCompositor(
    tccEngine, events, dataFlowManager, compensationManager);

compositor.compose(composition, Map.of("orderId", "ORD-123", "amount", 99.99))
    .subscribe(result -> {
        if (result.success()) {
            log.info("Composition '{}' succeeded: {}",
                result.compositionName(), result.correlationId());
            result.tccResults().forEach((alias, tccResult) ->
                log.info("  {} → {}", alias, tccResult.status()));
        } else {
            log.error("Composition '{}' failed: {}",
                result.compositionName(),
                result.error().getMessage());
        }
    });
```

### TccCompositionResult

`TccCompositionResult` is the outcome record of a TCC composition execution.

| Field | Type | Description |
|-------|------|-------------|
| `compositionName` | `String` | Composition name |
| `correlationId` | `String` | Unique execution ID |
| `success` | `boolean` | Whether all non-optional TCCs confirmed |
| `tccResults` | `Map<String, TccResult>` | Per-alias TCC results |
| `error` | `Throwable` | Root cause error (`null` on success) |

**Static factories:**

| Method | Description |
|--------|-------------|
| `TccCompositionResult.success(String name, String correlationId, Map<String, TccResult> results)` | All TCCs confirmed |
| `TccCompositionResult.failure(String name, String correlationId, Map<String, TccResult> results, Throwable error)` | Composition failed |

### TccCompositionContext

`TccCompositionContext` is the mutable execution context that tracks progress during composition execution. It is thread-safe (uses `ConcurrentHashMap` and synchronized lists).

**TccStatus Enum:**

| Value | Meaning |
|-------|---------|
| `PENDING` | TCC has not started |
| `RUNNING` | TCC is currently executing |
| `CONFIRMED` | TCC completed successfully |
| `CANCELED` | TCC was compensated |
| `FAILED` | TCC execution failed |
| `SKIPPED` | TCC was skipped (condition not met) |

**Tracked state:**

| Field | Type | Description |
|-------|------|-------------|
| `compositionCorrelationId` | `String` | Composition-level correlation ID |
| `startTime` | `Instant` | Composition start time |
| `tccCorrelationIds` | `Map<String, String>` | Per-alias TCC correlation IDs |
| `tccResults` | `Map<String, TccResult>` | Per-alias TCC results |
| `tccStatuses` | `Map<String, TccStatus>` | Per-alias TCC statuses |
| `executionOrder` | `List<String>` | Aliases in the order they were executed |

**Key methods:**

| Method | Return Type | Description |
|--------|-------------|-------------|
| `setTccResult(String alias, TccResult result)` | `void` | Store a TCC result |
| `getTccResult(String alias)` | `TccResult` | Retrieve a TCC result by alias |
| `setTccStatus(String alias, TccStatus status)` | `void` | Update TCC status |
| `getTccStatus(String alias)` | `TccStatus` | Get TCC status (defaults to `PENDING`) |
| `getConfirmedAliasesInOrder()` | `List<String>` | Confirmed aliases in execution order (used for reverse compensation) |
| `getExecutionOrder()` | `List<String>` | All aliases in execution order |
| `getTccResults()` | `Map<String, TccResult>` | Unmodifiable view of all TCC results |
| `getTccStatuses()` | `Map<String, TccStatus>` | Unmodifiable view of all statuses |

### TccCompositionCompensationManager

`TccCompositionCompensationManager` handles reverse-order compensation when a composition fails. It uses `CompensationPolicy` to determine the compensation strategy.

**Dependencies:** `TccEngine`, `CompensationPolicy`.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `compensate(TccComposition composition, TccCompositionContext context)` | `Mono<Void>` | Cancel all confirmed TCCs in reverse execution order |

**Compensation strategies based on `CompensationPolicy`:**

| Policy | Behavior |
|--------|----------|
| `BEST_EFFORT_PARALLEL` | Compensate all confirmed TCCs in parallel |
| `GROUPED_PARALLEL` | Compensate all confirmed TCCs in parallel |
| `STRICT_SEQUENTIAL` (default) | Compensate one at a time in reverse execution order |
| `RETRY_WITH_BACKOFF` | Sequential compensation with retry and backoff |
| `CIRCUIT_BREAKER` | Sequential compensation with circuit breaker protection |

The manager retrieves confirmed aliases from `context.getConfirmedAliasesInOrder()`, reverses the list, and compensates each TCC according to the selected policy.

### TccCompositionDataFlowManager

`TccCompositionDataFlowManager` resolves `DataMapping` entries to build input maps for each TCC in the composition.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `resolveInputs(TccComposition composition, CompositionTcc tcc, Map<String, Object> globalInput, TccCompositionContext context)` | `Map<String, Object>` | Build merged input map |

**Input resolution order (later entries override earlier):**

1. **Global input** — the `Map<String, Object>` passed to `compose()`
2. **Static input** — the `staticInput` map from the `CompositionTcc` entry
3. **Data flow mappings** — values extracted from upstream TCC Try results via `DataMapping`

For data flow extraction, the manager searches participant outcomes in the upstream `TccResult`: first checking if any participant's `tryResult` is a `Map` containing the `sourceField` key, then falling back to matching the `sourceField` against a participant ID's `tryResult` directly.

### TccCompositionValidator

`TccCompositionValidator` checks a `TccComposition` for structural correctness before execution.

**Dependencies:** `TccRegistry` (for verifying TCC names exist).

| Method | Return Type | Description |
|--------|-------------|-------------|
| `validate(TccComposition composition)` | `List<String>` | Returns a list of error messages (empty if valid) |
| `validateAndThrow(TccComposition composition)` | `void` | Throws `IllegalStateException` if validation fails |

**Validation checks:**

| Check | Error Condition |
|-------|-----------------|
| Empty composition | No TCCs defined |
| Duplicate aliases | Two TCCs share the same alias |
| Missing dependencies | A `dependsOn` alias does not exist in the composition |
| Missing TCC names | A `tccName` is not registered in `TccRegistry` |
| Circular dependencies | The DAG contains a cycle (detected via `getExecutableLayers()`) |
| Invalid data mappings | A `sourceTcc` or `targetTcc` in a `DataMapping` is not a known alias |

```java
TccCompositionValidator validator = new TccCompositionValidator(tccRegistry);

// Lenient — inspect errors
List<String> errors = validator.validate(composition);
if (!errors.isEmpty()) {
    errors.forEach(e -> log.warn("Validation: {}", e));
}

// Strict — fail fast
validator.validateAndThrow(composition);
```

### TccCompositionVisualizationService

`TccCompositionVisualizationService` generates graph representations of a TCC composition DAG for debugging and documentation.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `toDot(TccComposition composition)` | `String` | Graphviz DOT format |
| `toMermaid(TccComposition composition)` | `String` | Mermaid diagram format |

Both methods render:
- TCC nodes with alias and TCC name labels
- Dependency edges (solid arrows)
- Data flow edges (dashed arrows with field labels)
- Optional TCCs shown with dashed borders

```java
TccCompositionVisualizationService viz = new TccCompositionVisualizationService();

// Graphviz DOT
String dot = viz.toDot(composition);
// digraph "OrderFulfillment" {
//   rankdir=TB;
//   "reserve-inventory" [label="reserve-inventory\n(ReserveInventory)"];
//   "reserve-payment" [label="reserve-payment\n(ReservePayment)"];
//   "confirm-shipment" [label="confirm-shipment\n(ConfirmShipment)"];
//   "reserve-inventory" -> "confirm-shipment";
//   "reserve-payment" -> "confirm-shipment";
//   ...
// }

// Mermaid
String mermaid = viz.toMermaid(composition);
// graph TD
//   reserve_inventory["reserve-inventory<br/>ReserveInventory"]
//   reserve_payment["reserve-payment<br/>ReservePayment"]
//   confirm_shipment["confirm-shipment<br/>ConfirmShipment"]
//   reserve_inventory --> confirm_shipment
//   reserve_payment --> confirm_shipment
//   ...
```

---

[← Back to Index](README.md) | [Previous: Saga Pattern](saga.md) | [Next: Core Infrastructure →](core-infrastructure.md)

*Copyright 2024-2026 Firefly Software Solutions Inc. Licensed under the Apache License, Version 2.0.*
