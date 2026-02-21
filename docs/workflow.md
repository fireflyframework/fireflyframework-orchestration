[← Back to Index](README.md) | [Previous: Foundations](foundations.md) | [Next: Saga Pattern →](saga.md)

# Part II: Workflow Pattern

**Contents:**
- [§4 Workflow Annotation Reference](#4-workflow-annotation-reference)
- [§5 Workflow Tutorial](#5-workflow-tutorial)
- [§6 Workflow Lifecycle Management](#6-workflow-lifecycle-management)
- [§7 Signals and Timers](#7-signals-and-timers)
- [§8 Workflow Builder DSL](#8-workflow-builder-dsl)
- [§9 WorkflowEngine API](#9-workflowengine-api)
- [§10 Child Workflows](#10-child-workflows)
- [§11 Search Attributes & Queries](#11-search-attributes--queries)

## 4. Workflow Annotation Reference

### @Workflow

Marks a class as a workflow definition. Applied to a Spring-managed bean.

**Target:** `ElementType.TYPE` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `id` | `String` | `""` | Unique workflow identifier. If empty, the bean name is used |
| `name` | `String` | `""` | Display name |
| `description` | `String` | `""` | Human-readable description |
| `version` | `String` | `"1.0"` | Version string |
| `triggerMode` | `TriggerMode` | `SYNC` | `SYNC` or `ASYNC` execution mode |
| `triggerEventType` | `String` | `""` | Event type that triggers this workflow via `EventGateway` |
| `timeoutMs` | `long` | `30000` | Global workflow timeout in milliseconds |
| `maxRetries` | `int` | `3` | Default max retry attempts for steps |
| `retryDelayMs` | `long` | `1000` | Default retry delay in milliseconds |
| `publishEvents` | `boolean` | `false` | Publish `OrchestrationEvent` for each step completion |
| `layerConcurrency` | `int` | `0` | Max parallel steps per layer (`0` = unbounded) |

### @WorkflowStep

Marks a method as a workflow step. The method must return `Mono<T>`.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `id` | `String` | `""` | Unique step identifier. If empty, the method name is used |
| `name` | `String` | `""` | Display name |
| `description` | `String` | `""` | Description |
| `dependsOn` | `String[]` | `{}` | Step IDs that must complete before this step runs |
| `outputEventType` | `String` | `""` | Custom event type published on step completion |
| `timeoutMs` | `long` | `0` | Step timeout in milliseconds (`0` = inherit from workflow) |
| `maxRetries` | `int` | `0` | Max retry attempts (`0` = inherit from workflow) |
| `retryDelayMs` | `long` | `1000` | Retry delay in milliseconds |
| `condition` | `String` | `""` | SpEL expression — step is skipped if it evaluates to `false` |
| `async` | `boolean` | `false` | Execute on bounded-elastic scheduler |
| `compensatable` | `boolean` | `false` | Whether this step supports compensation |
| `compensationMethod` | `String` | `""` | Name of the compensation method on the same bean |

### @WaitForSignal

Marks a workflow step as waiting for an external signal before proceeding. The step pauses execution until a signal with the matching name is delivered via `SignalService.signal()`.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `value` | `String` | *(required)* | Signal name to wait for |
| `timeoutMs` | `long` | `0` | Timeout in milliseconds (`0` = no timeout) |

### @WaitForTimer

Marks a workflow step as a timer-based delayed execution. The step fires after the specified delay.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `delayMs` | `long` | *(required)* | Delay in milliseconds before the step fires |
| `timerId` | `String` | `""` | Named timer ID (for external cancellation via `TimerService`) |

### @OnStepComplete

Callback invoked when specific workflow steps complete successfully.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `stepIds` | `String[]` | `{}` | Step IDs to listen for (empty = all steps) |
| `async` | `boolean` | `false` | Run callback asynchronously |
| `priority` | `int` | `0` | Ordering priority |

### @OnWorkflowComplete

Callback invoked when the workflow completes successfully.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `async` | `boolean` | `false` | Run callback asynchronously |
| `priority` | `int` | `0` | Ordering priority |

### @OnWorkflowError

Callback invoked when the workflow fails.

**Target:** `ElementType.METHOD` | **Retention:** `RUNTIME`

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `errorTypes` | `Class<? extends Throwable>[]` | `{}` | Filter by exception type (empty = all errors) |
| `stepIds` | `String[]` | `{}` | Filter by which steps caused the error (empty = any step) |
| `async` | `boolean` | `false` | Run callback asynchronously |
| `priority` | `int` | `0` | Ordering priority |
| `suppressError` | `boolean` | `false` | If `true`, converts the failure to success |

### @ScheduledWorkflow

Schedules automatic execution of a workflow on a cron, fixed-rate, or fixed-delay basis. This annotation is `@Repeatable` — you can apply multiple schedules to the same workflow.

**Target:** `ElementType.TYPE` | **Retention:** `RUNTIME` | **Repeatable:** Yes (container: `@ScheduledWorkflows`)

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `cron` | `String` | `""` | Cron expression (6 fields: `second minute hour day month weekday`) |
| `zone` | `String` | `""` | Timezone for cron (e.g., `"America/New_York"`) |
| `fixedRate` | `long` | `-1` | Fixed rate in milliseconds (`-1` = disabled) |
| `fixedDelay` | `long` | `-1` | Fixed delay in milliseconds (`-1` = disabled) |
| `initialDelay` | `long` | `0` | Initial delay before first execution |
| `enabled` | `boolean` | `true` | Whether this schedule is active |
| `input` | `String` | `"{}"` | JSON string passed as workflow input |
| `description` | `String` | `""` | Human-readable description |

---

## 5. Workflow Tutorial

A **Workflow** is a directed acyclic graph (DAG) of steps. Steps declare their dependencies via `dependsOn`, and the engine executes them in topological order — steps in the same layer run in parallel.

```
              +----------+
              | validate |   Layer 0 (runs first)
              +----+-----+
              +----|-----+
        +-----v--+ +--v-----+
        | charge | | reserve |   Layer 1 (run in parallel)
        +----+---+ +---+----+
             +----+----+
             +----v----+
             |   ship  |   Layer 2
             +---------+
```

### Step 1: Create the Workflow Class

```java
@Workflow(name = "OrderProcessing", version = "1.0",
          description = "Processes a customer order end-to-end")
public class OrderProcessingWorkflow {

    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final ShippingService shippingService;

    public OrderProcessingWorkflow(InventoryService inventoryService,
                                    PaymentService paymentService,
                                    ShippingService shippingService) {
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
        this.shippingService = shippingService;
    }

    @WorkflowStep(id = "validate", name = "Validate Order",
                  timeoutMs = 5000)
    public Mono<Map<String, Object>> validateOrder(@Input Map<String, Object> input) {
        String orderId = (String) input.get("orderId");
        return Mono.just(Map.of("orderId", orderId, "validated", true));
    }

    @WorkflowStep(id = "charge", name = "Charge Payment",
                  dependsOn = "validate", timeoutMs = 10000,
                  maxRetries = 3, retryDelayMs = 1000)
    public Mono<String> chargePayment(@FromStep("validate") Map<String, Object> validated) {
        String orderId = (String) validated.get("orderId");
        return paymentService.charge(orderId);
    }

    @WorkflowStep(id = "reserve", name = "Reserve Inventory",
                  dependsOn = "validate", timeoutMs = 10000)
    public Mono<String> reserveInventory(@FromStep("validate") Map<String, Object> validated) {
        String orderId = (String) validated.get("orderId");
        return inventoryService.reserve(orderId);
    }

    @WorkflowStep(id = "ship", name = "Ship Order",
                  dependsOn = {"charge", "reserve"}, timeoutMs = 15000)
    public Mono<String> shipOrder(@FromStep("charge") String chargeId,
                                   @FromStep("reserve") String reservationId) {
        return shippingService.ship(chargeId, reservationId);
    }

    @OnWorkflowComplete
    public void onComplete(ExecutionContext ctx) {
        log.info("Order processing completed: {}", ctx.getCorrelationId());
    }

    @OnWorkflowError(errorTypes = RuntimeException.class)
    public void onError(Throwable error, ExecutionContext ctx) {
        log.error("Order processing failed: {}", ctx.getCorrelationId(), error);
    }
}
```

Key points in this example:

- `"validate"` runs first (no `dependsOn`) — it forms Layer 0
- `"charge"` and `"reserve"` both depend on `"validate"` — they form Layer 1 and execute **in parallel**
- `"ship"` depends on both `"charge"` and `"reserve"` — it waits for both to complete (Layer 2)
- `@FromStep` injects the result of a previously completed step
- `@OnWorkflowComplete` and `@OnWorkflowError` handle lifecycle events

### Step 2: Execute the Workflow

```java
@Service
public class OrderController {
    private final WorkflowEngine workflowEngine;

    public Mono<ExecutionState> processOrder(String orderId) {
        Map<String, Object> input = Map.of("orderId", orderId);
        return workflowEngine.startWorkflow("OrderProcessing", input);
    }
}
```

### Step 3: Inspect the Result

```java
workflowEngine.startWorkflow("OrderProcessing", input)
    .subscribe(state -> {
        System.out.println("Status: " + state.status());
        System.out.println("Correlation: " + state.correlationId());
        System.out.println("Duration: " + Duration.between(
            state.startedAt(), state.updatedAt()).toMillis() + "ms");
        state.stepResults().forEach((stepId, result) ->
            System.out.println("  " + stepId + " -> " + result));
    });
```

### Conditional Steps

Use the `condition` attribute with a SpEL expression to conditionally skip steps:

```java
@WorkflowStep(id = "notify", dependsOn = "ship",
              condition = "#{results['ship'] != null}")
public Mono<Void> notifyCustomer(@FromStep("ship") String trackingId) {
    return notificationService.send(trackingId);
}
```

If the condition evaluates to `false`, the step is marked `SKIPPED` and downstream steps receive `null` for its result.

### Dry-Run Mode

Dry-run mode traverses the entire DAG without executing any step logic. All steps are marked `SKIPPED` and no handlers are invoked. This is useful for validating topology and step configuration before real execution.

```java
workflowEngine.startWorkflow("OrderProcessing", input, "corr-id", "test", true)
    .subscribe(state -> {
        state.stepStatuses().forEach((id, status) ->
            assertThat(status).isEqualTo(StepStatus.SKIPPED));
    });
```

---

## 6. Workflow Lifecycle Management

Workflows support full lifecycle management with suspend/resume/cancel operations.

### State Transitions

```
PENDING → RUNNING → COMPLETED
                  → FAILED
          RUNNING → SUSPENDED → RUNNING (resume)
          RUNNING → CANCELLED
```

### Suspend

Pauses a running workflow. Already-completed steps are preserved. In-flight steps complete but no new steps are started.

```java
// With a reason
workflowEngine.suspendWorkflow(correlationId, "Scheduled maintenance")
    .subscribe(state -> log.info("Suspended: {}", state.status()));

// Without a reason
workflowEngine.suspendWorkflow(correlationId)
    .subscribe(state -> log.info("Suspended: {}", state.status()));
```

**Method signatures:**
- `Mono<ExecutionState> suspendWorkflow(String correlationId, String reason)`
- `Mono<ExecutionState> suspendWorkflow(String correlationId)`

### Resume

Resumes a suspended workflow. Already-completed steps are skipped — execution picks up from where it left off.

```java
workflowEngine.resumeWorkflow(correlationId)
    .subscribe(state -> log.info("Resumed and completed: {}", state.status()));
```

**Method signature:** `Mono<ExecutionState> resumeWorkflow(String correlationId)`

### Cancel

Cancels a running workflow. Cancellation is rejected if the workflow is already in a terminal state (`COMPLETED`, `FAILED`, `CANCELLED`).

```java
workflowEngine.cancelWorkflow(correlationId)
    .subscribe(state -> log.info("Cancelled: {}", state.status()));
```

**Method signature:** `Mono<ExecutionState> cancelWorkflow(String correlationId)`

### Querying State

```java
// Look up a specific execution
workflowEngine.findByCorrelationId(correlationId)
    .subscribe(optState -> optState.ifPresent(state ->
        log.info("{}: {}", state.executionName(), state.status())));

// Find all workflows by status
workflowEngine.findByStatus(ExecutionStatus.RUNNING)
    .subscribe(state -> log.info("Running: {}", state.correlationId()));
```

---

## 7. Signals and Timers

Workflows uniquely support **signal gates** and **timer delays** — mechanisms that pause step execution until an external event occurs or a duration elapses.

### Signals

A signal gate pauses a workflow step until an external signal with a matching name is delivered. This is useful for human approvals, external system callbacks, or inter-workflow communication.

#### Defining a Signal Gate

```java
@WorkflowStep(id = "awaitApproval", dependsOn = "validate")
@WaitForSignal("manager-approval")
public Mono<String> awaitApproval(@Input Map<String, Object> input) {
    // This method executes AFTER the signal is delivered.
    // The signal payload is available in the execution context.
    return Mono.just("approved");
}
```

With a timeout:

```java
@WorkflowStep(id = "awaitApproval", dependsOn = "validate")
@WaitForSignal(value = "manager-approval", timeoutMs = 3600000)  // 1 hour
public Mono<String> awaitApproval(@Input Map<String, Object> input) {
    return Mono.just("approved");
}
```

#### Delivering a Signal

Use `SignalService` to deliver a signal to a waiting workflow:

```java
@Service
public class ApprovalService {
    private final SignalService signalService;

    public Mono<SignalResult> approve(String correlationId) {
        return signalService.signal(correlationId, "manager-approval",
            Map.of("approver", "jane.doe", "approved", true));
    }
}
```

#### SignalService API

| Method | Return Type | Description |
|--------|-------------|-------------|
| `signal(correlationId, signalName, payload)` | `Mono<SignalResult>` | Deliver a signal to a waiting workflow |
| `waitForSignal(correlationId, signalName)` | `Mono<Object>` | Programmatically wait for a signal |
| `getPendingSignals(correlationId)` | `List<PendingSignal>` | List all pending (undelivered) signals |
| `cleanup(correlationId)` | `void` | Remove all signal state for a correlation ID |

`PendingSignal` is a record: `PendingSignal(String signalName, Object payload)`.

### Timers

A timer delay pauses a workflow step for a fixed duration. This is useful for rate limiting, cooldown periods, or scheduled retries.

#### Defining a Timer Delay

Anonymous timer (fire-and-forget):

```java
@WorkflowStep(id = "cooldown", dependsOn = "charge")
@WaitForTimer(delayMs = 30000)  // 30 seconds
public Mono<Void> cooldown() {
    return Mono.empty();
}
```

Named timer (can be cancelled externally):

```java
@WorkflowStep(id = "reminderWait", dependsOn = "notify")
@WaitForTimer(delayMs = 86400000, timerId = "reminder-timer")  // 24 hours
public Mono<String> sendReminder() {
    return notificationService.sendReminder();
}
```

#### TimerService API

| Method | Return Type | Description |
|--------|-------------|-------------|
| `schedule(correlationId, timerId, delay, data)` | `Mono<TimerEntry>` | Schedule a timer with a `Duration` delay |
| `scheduleAt(correlationId, timerId, fireAt, data)` | `Mono<TimerEntry>` | Schedule a timer to fire at a specific `Instant` |
| `cancel(correlationId, timerId)` | `Mono<Boolean>` | Cancel a pending timer |
| `getReadyTimers(correlationId)` | `Flux<TimerEntry>` | Get all timers whose fire time has passed |
| `getPendingTimers(correlationId)` | `List<TimerEntry>` | List all pending (not yet fired) timers |
| `delay(duration)` | `Mono<Void>` | Simple delay without persistence |
| `cleanup(correlationId)` | `void` | Remove all timer state for a correlation ID |

### Combining Signals and Timers

A multi-step workflow that validates, waits for approval, pauses, then ships:

```java
@Workflow(name = "ApprovalWorkflow")
public class ApprovalWorkflow {

    @WorkflowStep(id = "validate")
    public Mono<Map<String, Object>> validate(@Input Map<String, Object> order) {
        return Mono.just(order);
    }

    @WorkflowStep(id = "awaitApproval", dependsOn = "validate")
    @WaitForSignal(value = "manager-approval", timeoutMs = 86400000)
    public Mono<String> awaitApproval() {
        return Mono.just("approved");
    }

    @WorkflowStep(id = "cooldown", dependsOn = "awaitApproval")
    @WaitForTimer(delayMs = 5000, timerId = "processing-delay")
    public Mono<Void> cooldown() {
        return Mono.empty();
    }

    @WorkflowStep(id = "ship", dependsOn = "cooldown")
    public Mono<String> ship(@FromStep("validate") Map<String, Object> order) {
        return shippingService.ship((String) order.get("orderId"));
    }
}
```

---

## 8. Workflow Builder DSL

For cases where annotation scanning is not practical — dynamically composed workflows, testing, or runtime configuration — use the programmatic `WorkflowBuilder`.

### WorkflowBuilder Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `new WorkflowBuilder(String name)` | `WorkflowBuilder` | Create a builder with the given workflow name |
| `.description(String)` | `WorkflowBuilder` | Set workflow description |
| `.version(String)` | `WorkflowBuilder` | Set version string (default: `"1.0"`) |
| `.triggerMode(TriggerMode)` | `WorkflowBuilder` | `SYNC` or `ASYNC` (default: `SYNC`) |
| `.timeout(long)` | `WorkflowBuilder` | Global timeout in milliseconds |
| `.retryPolicy(RetryPolicy)` | `WorkflowBuilder` | Default retry policy for all steps |
| `.publishEvents(boolean)` | `WorkflowBuilder` | Publish step completion events (default: `false`) |
| `.layerConcurrency(int)` | `WorkflowBuilder` | Max parallel steps per layer (`0` = unbounded) |
| `.triggerEventType(String)` | `WorkflowBuilder` | Event type that triggers this workflow via `EventGateway` |
| `.step(String stepId)` | `StepBuilder` | Begin defining a step (returns `StepBuilder`) |
| `.build()` | `WorkflowDefinition` | Build the immutable definition |

### StepBuilder Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `.name(String)` | `StepBuilder` | Display name |
| `.description(String)` | `StepBuilder` | Description |
| `.dependsOn(String...)` | `StepBuilder` | Step dependencies |
| `.order(int)` | `StepBuilder` | Explicit ordering hint within a layer |
| `.timeout(long)` | `StepBuilder` | Step timeout in milliseconds |
| `.retryPolicy(RetryPolicy)` | `StepBuilder` | Per-step retry policy |
| `.handler(Object bean, Method method)` | `StepBuilder` | Bean and method to invoke |
| `.outputEventType(String)` | `StepBuilder` | Event type published on step completion |
| `.condition(String)` | `StepBuilder` | SpEL condition for conditional execution |
| `.async(boolean)` | `StepBuilder` | Run step on bounded-elastic scheduler |
| `.compensatable(boolean, String)` | `StepBuilder` | Enable compensation with method name |
| `.waitForSignal(String)` | `StepBuilder` | Pause until a named signal is delivered |
| `.waitForSignal(String, long)` | `StepBuilder` | Pause with timeout |
| `.waitForTimer(long)` | `StepBuilder` | Pause for a fixed delay |
| `.waitForTimer(long, String)` | `StepBuilder` | Pause with a named timer ID |
| `.add()` | `WorkflowBuilder` | Finish step definition, return to `WorkflowBuilder` |

### Full Builder Example

```java
WorkflowDefinition def = new WorkflowBuilder("DynamicPipeline")
    .description("A pipeline built at runtime")
    .version("2.0")
    .triggerMode(TriggerMode.SYNC)
    .timeout(60_000L)
    .retryPolicy(RetryPolicy.DEFAULT)
    .triggerEventType("DataIngested")
    .publishEvents(true)
    .step("extract")
        .name("Extract Data")
        .handler(extractorBean, extractorBean.getClass()
            .getMethod("extract", Map.class))
        .timeout(30_000L)
        .add()
    .step("transform")
        .name("Transform Data")
        .dependsOn("extract")
        .handler(transformerBean, transformerBean.getClass()
            .getMethod("transform", Map.class))
        .condition("#{results['extract'] != null}")
        .add()
    .step("load")
        .name("Load Data")
        .dependsOn("transform")
        .handler(loaderBean, loaderBean.getClass()
            .getMethod("load", Map.class))
        .retryPolicy(new RetryPolicy(5, Duration.ofSeconds(2),
            Duration.ofMinutes(1), 2.0, 0.1, new String[]{}))
        .add()
    .build();

// Register and execute
workflowEngine.registerWorkflow(def);
workflowEngine.startWorkflow("DynamicPipeline", Map.of("source", "s3://data"))
    .subscribe(state -> log.info("Pipeline result: {}", state.status()));
```

---

## 9. WorkflowEngine API

`WorkflowEngine` is the primary entry point for workflow execution and lifecycle management.

### Method Reference

| Method | Return Type | Description |
|--------|-------------|-------------|
| `startWorkflow(String workflowId, Map<String, Object> input)` | `Mono<ExecutionState>` | Start with auto-generated correlation ID |
| `startWorkflow(String workflowId, Map<String, Object> input, String correlationId, String triggeredBy, boolean dryRun)` | `Mono<ExecutionState>` | Start with explicit correlation ID, audit trail, and optional dry-run mode |
| `suspendWorkflow(String correlationId, String reason)` | `Mono<ExecutionState>` | Suspend a running workflow with a reason |
| `suspendWorkflow(String correlationId)` | `Mono<ExecutionState>` | Suspend with default reason |
| `resumeWorkflow(String correlationId)` | `Mono<ExecutionState>` | Resume a suspended workflow from where it left off |
| `cancelWorkflow(String correlationId)` | `Mono<ExecutionState>` | Cancel a running workflow |
| `findByStatus(ExecutionStatus status)` | `Flux<ExecutionState>` | Query workflows by status |
| `findByCorrelationId(String correlationId)` | `Mono<Optional<ExecutionState>>` | Look up a specific execution |
| `registerWorkflow(WorkflowDefinition definition)` | `void` | Register a builder-created workflow definition |

### Starting a Workflow

```java
// Simple start (auto-generated correlation ID)
workflowEngine.startWorkflow("OrderProcessing", Map.of("orderId", "ORD-123"))
    .subscribe(state -> log.info("Started: {}", state.correlationId()));

// Full start with explicit options
workflowEngine.startWorkflow(
        "OrderProcessing",
        Map.of("orderId", "ORD-123"),
        "custom-correlation-id",    // explicit correlation ID
        "admin@example.com",        // triggeredBy (audit)
        false                       // dryRun
    )
    .subscribe(state -> log.info("Started: {}", state.correlationId()));
```

### Dry-Run Mode

When `dryRun = true`:
- The entire DAG is traversed
- All steps are marked `SKIPPED`
- No step handlers are invoked
- No results are stored
- Useful for validating topology and step configuration

```java
workflowEngine.startWorkflow("OrderProcessing", input, "dry-run-1", "test", true)
    .subscribe(state -> {
        state.stepStatuses().forEach((id, status) ->
            assertThat(status).isEqualTo(StepStatus.SKIPPED));
    });
```

### WorkflowExecutor

`WorkflowExecutor` is used internally by `WorkflowEngine`. It executes the step DAG layer-by-layer:

```java
Mono<ExecutionContext> execute(WorkflowDefinition definition, ExecutionContext ctx)
```

You typically don't interact with `WorkflowExecutor` directly.

---

## 10. Child Workflows

`ChildWorkflowService` allows a workflow step to spawn child workflow instances. Children are tracked by parent correlation ID and can be cancelled together.

### ChildWorkflowService API

| Method | Return Type | Description |
|--------|-------------|-------------|
| `spawn(String parentCorrelationId, String childWorkflowId, Map<String, Object> input)` | `Mono<ChildWorkflowResult>` | Start a child workflow linked to the parent |
| `getChildren(String parentCorrelationId)` | `List<String>` | List child correlation IDs for a parent |
| `cancelChildren(String parentCorrelationId)` | `Mono<Void>` | Cancel all children of a parent |
| `cleanup(String parentCorrelationId)` | `void` | Remove parent-child tracking state |

### Usage in a Workflow Step

```java
@WorkflowStep(id = "spawnChildren", dependsOn = "validate")
public Mono<List<String>> spawnChildren(
        @Input Map<String, Object> input,
        ExecutionContext ctx) {
    List<String> regions = (List<String>) input.get("regions");
    return Flux.fromIterable(regions)
        .flatMap(region -> childWorkflowService.spawn(
            ctx.getCorrelationId(),
            "RegionalProcessing",
            Map.of("region", region)))
        .map(ChildWorkflowResult::childCorrelationId)
        .collectList();
}
```

### Parent-Child Correlation

- When a parent is cancelled, `cancelChildren()` is called automatically to cancel all children.
- Child results are independent — the parent step receives the `ChildWorkflowResult` which contains the child's correlation ID.
- Each child runs as a fully independent workflow with its own `ExecutionState`.

---

## 11. Search Attributes & Queries

Workflow instances can be indexed by custom attributes for efficient searching, and queried for real-time state inspection.

### WorkflowSearchService

`WorkflowSearchService` manages an in-memory inverted index for custom search attributes.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `updateSearchAttribute(correlationId, key, value)` | `Mono<Void>` | Set or update a single attribute |
| `updateSearchAttributes(correlationId, attributes)` | `Mono<Void>` | Set or update multiple attributes |
| `getAttribute(correlationId, key)` | `Optional<Object>` | Get a single attribute value |
| `getAttributes(correlationId)` | `Map<String, Object>` | Get all attributes for an instance |
| `searchByAttribute(key, value)` | `Flux<ExecutionState>` | Find instances matching a single attribute |
| `searchByAttributes(criteria)` | `Flux<ExecutionState>` | Find instances matching multiple attributes (AND) |
| `removeIndex(correlationId)` | `void` | Remove all indexed attributes for an instance |

### Usage Example

```java
@Service
public class OrderWorkflowService {
    private final WorkflowEngine engine;
    private final WorkflowSearchService searchService;

    public Mono<ExecutionState> startOrder(String orderId, String customerId) {
        return engine.startWorkflow("OrderProcessing", Map.of("orderId", orderId))
            .flatMap(state -> searchService.updateSearchAttributes(
                state.correlationId(),
                Map.of("orderId", orderId, "customerId", customerId))
                .thenReturn(state));
    }

    public Flux<ExecutionState> findByCustomer(String customerId) {
        return searchService.searchByAttribute("customerId", customerId);
    }
}
```

### SearchAttributeProjection

`SearchAttributeProjection` is the in-memory read model that powers `WorkflowSearchService`. It maintains:
- **Forward index:** `correlationId → attribute key → value`
- **Inverted index:** `attribute key → value → set of correlationIds`

Both indexes use `ConcurrentHashMap` for thread safety.

### WorkflowQueryService

`WorkflowQueryService` provides read-only queries against running or completed workflow instances. All methods return `Mono<Optional<T>>` — the `Optional` is empty if the correlation ID is not found.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getStatus(correlationId)` | `Mono<Optional<ExecutionStatus>>` | Current execution status |
| `getStepStatuses(correlationId)` | `Mono<Optional<Map<String, StepStatus>>>` | Status of each step |
| `getCurrentSteps(correlationId)` | `Mono<Optional<List<String>>>` | Steps currently running |
| `getStepResults(correlationId)` | `Mono<Optional<Map<String, Object>>>` | Results of all completed steps |
| `getStepResult(correlationId, stepId)` | `Mono<Optional<Object>>` | Result of a specific step |
| `getVariables(correlationId)` | `Mono<Optional<Map<String, Object>>>` | All context variables |
| `getVariable(correlationId, key)` | `Mono<Optional<Object>>` | A specific context variable |
| `getTopologyLayers(correlationId)` | `Mono<Optional<List<List<String>>>>` | The computed DAG layers |
| `getHeaders(correlationId)` | `Mono<Optional<Map<String, String>>>` | Execution headers |
| `getFailureReason(correlationId)` | `Mono<Optional<String>>` | Failure reason (if failed) |

### Query Example

```java
// Poll for workflow progress
workflowQueryService.getStepStatuses(correlationId)
    .subscribe(opt -> opt.ifPresent(statuses -> {
        long completed = statuses.values().stream()
            .filter(StepStatus::isTerminal).count();
        log.info("Progress: {}/{} steps completed",
            completed, statuses.size());
    }));
```

---

[← Back to Index](README.md) | [Previous: Foundations](foundations.md) | [Next: Saga Pattern →](saga.md)

*Copyright 2024-2026 Firefly Software Solutions Inc. Licensed under the Apache License, Version 2.0.*
