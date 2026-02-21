# Firefly Orchestration -- Complete Reference Guide

> **Version:** 26.02.06 | **Java:** 25+ | **Spring Boot:** 3.x | **Reactor:** 3.x

This guide covers every aspect of the Firefly Orchestration module -- from first principles through advanced patterns. It serves as both a learning resource and an API reference.

---

## Table of Contents

- [Part I: Foundations](#part-i-foundations)
  - [1. What Is Orchestration?](#1-what-is-orchestration)
  - [2. Architecture Overview](#2-architecture-overview)
  - [3. When to Use Which Pattern](#3-when-to-use-which-pattern)
  - [4. Installation and Setup](#4-installation-and-setup)
- [Part II: Workflow Pattern](#part-ii-workflow-pattern)
  - [5. Workflow Concepts](#5-workflow-concepts)
  - [6. Tutorial: Your First Workflow](#6-tutorial-your-first-workflow)
  - [7. Workflow Builder DSL](#7-workflow-builder-dsl)
  - [8. Workflow Engine API](#8-workflow-engine-api)
  - [9. Workflow Lifecycle (Suspend/Resume/Cancel)](#9-workflow-lifecycle-suspendresumecancel)
- [Part III: Saga Pattern](#part-iii-saga-pattern)
  - [10. Saga Concepts](#10-saga-concepts)
  - [11. Tutorial: Your First Saga](#11-tutorial-your-first-saga)
  - [12. Saga Builder DSL](#12-saga-builder-dsl)
  - [13. Compensation Policies](#13-compensation-policies)
  - [14. Saga Fan-Out (ExpandEach)](#14-saga-fan-out-expandeach)
  - [15. Saga Result API](#15-saga-result-api)
- [Part IV: TCC Pattern](#part-iv-tcc-pattern)
  - [16. TCC Concepts](#16-tcc-concepts)
  - [17. Tutorial: Your First TCC Transaction](#17-tutorial-your-first-tcc-transaction)
  - [18. TCC Builder DSL](#18-tcc-builder-dsl)
  - [19. TCC Result API](#19-tcc-result-api)
- [Part V: Core Infrastructure](#part-v-core-infrastructure)
  - [20. ExecutionContext](#20-executioncontext)
  - [21. Parameter Injection Annotations](#21-parameter-injection-annotations)
  - [22. Retry Policies and Backoff](#22-retry-policies-and-backoff)
  - [23. Event Integration](#23-event-integration)
  - [24. Scheduling](#24-scheduling)
  - [25. Persistence Providers](#25-persistence-providers)
  - [26. Dead-Letter Queue](#26-dead-letter-queue)
  - [27. Observability (Events, Metrics, Tracing)](#27-observability-events-metrics-tracing)
  - [28. Lifecycle Callbacks](#28-lifecycle-callbacks)
  - [29. REST API](#29-rest-api)
  - [30. Recovery Service](#30-recovery-service)
  - [31. Exception Hierarchy](#31-exception-hierarchy)
- [Part VI: Configuration Reference](#part-vi-configuration-reference)
  - [32. All Configuration Properties](#32-all-configuration-properties)
  - [33. Auto-Configuration Chain](#33-auto-configuration-chain)
- [Part VII: Recipes and Patterns](#part-vii-recipes-and-patterns)
  - [34. Cross-Pattern Composition](#34-cross-pattern-composition)
  - [35. Testing Your Orchestrations](#35-testing-your-orchestrations)
  - [36. Production Checklist](#36-production-checklist)

---

# Part I: Foundations

## 1. What Is Orchestration?

Orchestration coordinates multi-step business processes where each step may call a remote service, write to a database, publish an event, or invoke another orchestration. The challenge is that any step can fail, and partial completion leaves the system in an inconsistent state. This module provides three patterns to handle this:

| Pattern      | Consistency | Isolation | Use Case                                                    |
|--------------|-------------|-----------|-------------------------------------------------------------|
| **Workflow** | Eventual    | None      | Multi-step processes with dependency ordering               |
| **Saga**     | Eventual    | None      | Distributed transactions with compensating actions          |
| **TCC**      | Strong      | Soft-lock | Distributed transactions requiring resource reservation     |

## 2. Architecture Overview

The module is organized into four layers. Your application defines orchestrations using annotations or builders. The engine layer routes execution through pattern-specific orchestrators. The core layer provides shared infrastructure -- context management, step invocation, argument resolution, retry logic, topology computation. The persistence and observability layers are pluggable via Spring beans.

```
+--------------------------------------------------------------------+
|                        Your Application                            |
|  @Saga  @Workflow  @Tcc    OR    SagaBuilder  WorkflowBuilder      |
+----------------+----------------------------------+----------------+
                 |  register                         |  execute
+----------------v----------------------------------v----------------+
|                         Engine Layer                               |
|   WorkflowEngine       SagaEngine           TccEngine              |
|   WorkflowExecutor     SagaExecOrchestrator TccExecOrchestrator    |
|                         SagaCompensator                            |
+--------+----------------------------+-------------------+----------+
         |                            |                   |
+--------v----------------------------v-------------------v----------+
|                          Core Layer                                |
|   ExecutionContext    StepInvoker    ArgumentResolver               |
|   TopologyBuilder     RetryPolicy    OrchestrationEvents           |
|   ExecutionState      DeadLetterService    RecoveryService         |
+--------+------------------------------------------+---------+-----+
         |                                          |         |
+--------v-----------------+   +--------------------v-+  +----v----+
|    Persistence Layer     |   |  Observability Layer  |  |  Events |
|  InMemory (default)      |   |  Logger / Metrics     |  |  Gateway|
|  Redis / Cache / ES      |   |  Tracing              |  |  Publish|
+--------------------------+   +-----------------------+  +---------+
```

**Key design decisions:**

- **Reactive-first:** Every I/O operation returns `Mono` or `Flux`. No blocking calls anywhere in the execution path.
- **Thread-safe:** `ExecutionContext` uses `ConcurrentHashMap` for all mutable state.
- **Pluggable:** Persistence, observability, and event publishing are port interfaces with swappable adapters.
- **Convention over configuration:** Zero-config defaults with Spring Boot auto-configuration.

## 3. When to Use Which Pattern

### Use Workflow when:

- Steps have dependency ordering (DAG) but no rollback is needed
- You need suspend/resume/cancel lifecycle management
- Steps are idempotent and can be retried safely
- Example: ETL pipelines, report generation, multi-stage deployments

### Use Saga when:

- Each step has a compensating action (undo)
- All-or-nothing semantics are needed across services
- Steps can fail independently and you need automatic rollback
- Example: Order processing, fund transfers, booking systems

### Use TCC when:

- You need resource reservation before committing
- Stronger isolation is required (soft-locking)
- Two-phase commitment with explicit try/confirm/cancel
- Example: Seat reservations, inventory holds, account debits

### Decision Flowchart

```
Need to undo on failure?
+-- No  -->  WORKFLOW
+-- Yes
    +-- Need resource reservation/locking?  -->  TCC
    +-- Compensating actions sufficient?    -->  SAGA
```

## 4. Installation and Setup

### Maven

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-orchestration</artifactId>
    <version>26.02.06</version>
</dependency>
```

### Auto-Configuration

The module auto-configures itself via Spring Boot's `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` file. All three patterns are enabled by default.

### Minimal Configuration

No configuration is needed for development. The module starts with:

- All patterns enabled
- InMemory persistence
- SLF4J structured logging
- DLQ enabled
- Scheduler with 4 threads

---

# Part II: Workflow Pattern

## 5. Workflow Concepts

A **Workflow** is a directed acyclic graph (DAG) of steps. Steps declare their dependencies, and the engine executes them in topological order -- steps in the same layer run in parallel.

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

The `TopologyBuilder` uses Kahn's algorithm to compute layers from the dependency graph. It validates that the graph is acyclic and that all dependencies exist. Within each layer, steps execute concurrently. The optional `layerConcurrency` setting limits how many steps in a single layer can run at the same time.

**Key concepts:**

- **Step:** A single unit of work -- a method that returns `Mono<T>`
- **Dependency:** `dependsOn` declares that a step cannot run until its dependencies complete
- **Layer:** A group of steps with all dependencies satisfied, executed in parallel
- **Topology:** The DAG structure computed by Kahn's algorithm
- **ExecutionState:** Persisted snapshot of the workflow's progress

## 6. Tutorial: Your First Workflow

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
                  order = 0, timeoutMs = 5000)
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
    public void onComplete() {
        log.info("Order processing completed");
    }

    @OnWorkflowError(errorTypes = RuntimeException.class)
    public void onError(Throwable error, ExecutionContext ctx) {
        log.error("Order processing failed: {}", ctx.getCorrelationId(), error);
    }
}
```

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

## 7. Workflow Builder DSL

For cases where annotation scanning is not practical (dynamically composed workflows, testing, or runtime configuration):

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

workflowEngine.registerWorkflow(def);

workflowEngine.startWorkflow("DynamicPipeline", Map.of("source", "s3://data"))
    .subscribe(state -> log.info("Pipeline result: {}", state.status()));
```

### WorkflowBuilder API

| Method | Description |
|--------|-------------|
| `description(String)` | Workflow description |
| `version(String)` | Version string (default: "1.0") |
| `triggerMode(TriggerMode)` | SYNC or ASYNC (default: SYNC) |
| `timeout(long)` | Global timeout in milliseconds (default: 30000) |
| `retryPolicy(RetryPolicy)` | Default retry policy for steps |
| `publishEvents(boolean)` | Publish step completion events (default: false) |
| `layerConcurrency(int)` | Max parallel steps per layer (0 = unbounded) |
| `triggerEventType(String)` | Event type that triggers this workflow via EventGateway |
| `step(String)` | Begin defining a step (returns StepBuilder) |

### StepBuilder API

| Method | Description |
|--------|-------------|
| `name(String)` | Display name |
| `description(String)` | Description |
| `dependsOn(String...)` | Step dependencies |
| `order(int)` | Explicit ordering hint |
| `timeout(long)` | Step timeout in milliseconds |
| `retryPolicy(RetryPolicy)` | Per-step retry policy |
| `handler(Object, Method)` | Bean and method to invoke |
| `outputEventType(String)` | Event type published on step completion |
| `condition(String)` | SpEL condition for conditional execution |
| `async(boolean)` | Run step asynchronously |
| `compensatable(boolean, String)` | Enable compensation with method name |
| `waitForSignal(String)` | Pause until a named signal is delivered |
| `waitForSignal(String, long)` | Pause with timeout |
| `waitForTimer(long)` | Pause for a fixed delay |
| `waitForTimer(long, String)` | Pause with a named timer ID |
| `add()` | Finish step definition, return to WorkflowBuilder |

## 8. Workflow Engine API

### WorkflowEngine Method Reference

| Method | Return Type | Description |
|--------|-------------|-------------|
| `startWorkflow(workflowId, input)` | `Mono<ExecutionState>` | Start with auto-generated correlation ID |
| `startWorkflow(workflowId, input, correlationId, triggeredBy, dryRun)` | `Mono<ExecutionState>` | Start with explicit options |
| `cancelWorkflow(correlationId)` | `Mono<ExecutionState>` | Cancel a running workflow |
| `suspendWorkflow(correlationId, reason)` | `Mono<ExecutionState>` | Suspend with a reason |
| `suspendWorkflow(correlationId)` | `Mono<ExecutionState>` | Suspend with default reason |
| `resumeWorkflow(correlationId)` | `Mono<ExecutionState>` | Resume a suspended workflow |
| `findByStatus(status)` | `Flux<ExecutionState>` | Query workflows by status |
| `findByCorrelationId(correlationId)` | `Mono<Optional<ExecutionState>>` | Look up a specific execution |
| `registerWorkflow(definition)` | `void` | Register a builder-created definition |

## 9. Workflow Lifecycle (Suspend/Resume/Cancel)

Workflows support full lifecycle management:

```
PENDING --> RUNNING --> COMPLETED
                   --> FAILED
            RUNNING --> SUSPENDED --> RUNNING (resume)
            RUNNING --> CANCELLED
```

### Suspend and Resume

```java
workflowEngine.suspendWorkflow(correlationId, "Scheduled maintenance")
    .subscribe(state -> log.info("Suspended: {}", state.status()));

workflowEngine.resumeWorkflow(correlationId)
    .subscribe(state -> log.info("Resumed and completed: {}", state.status()));
```

When a workflow resumes, already-completed steps are skipped. Execution picks up from where it left off.

### Cancel

```java
workflowEngine.cancelWorkflow(correlationId)
    .subscribe(state -> log.info("Cancelled: {}", state.status()));
```

Cancellation is rejected if the workflow is already in a terminal state (COMPLETED, FAILED, CANCELLED).

### Dry-Run Mode

Dry-run mode traverses the entire DAG without executing any step logic. All steps are marked SKIPPED and no results are stored. This is useful for validating topology and step configuration before real execution.

```java
workflowEngine.startWorkflow("OrderProcessing", input, "corr-id", "test", true)
    .subscribe(state -> {
        // All steps will have status SKIPPED
        state.stepStatuses().forEach((id, status) ->
            assertThat(status).isEqualTo(StepStatus.SKIPPED));
    });
```

---

# Part III: Saga Pattern

## 10. Saga Concepts

A **Saga** is a sequence of local transactions where each step has a **compensating action**. If any step fails, the saga engine automatically runs compensations for all completed steps in reverse order.

```
Step 1: Reserve Inventory   -->  Compensation: Cancel Reservation
Step 2: Charge Payment      -->  Compensation: Refund Payment
Step 3: Ship Order           -->  (no compensation -- final step)

If Step 2 fails:
  1. Run compensation for Step 1 (Cancel Reservation)
  2. Report failure with compensation results
```

**Key concepts:**

- **Step:** A forward action that may succeed or fail
- **Compensation:** An undo action that reverses a completed step
- **Compensation Policy:** Strategy for running compensations (sequential, parallel, circuit breaker)
- **StepInputs:** Per-step input data (static or lazy-resolved)
- **ExpandEach:** Fan-out -- clone a step once per item in a collection
- **StepEvent:** Optional event published when a step completes

## 11. Tutorial: Your First Saga

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
}
```

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

### SagaEngine Method Reference

| Method | Return Type | Description |
|--------|-------------|-------------|
| `execute(sagaName, StepInputs)` | `Mono<SagaResult>` | Execute by name with inputs |
| `execute(sagaName, StepInputs, ExecutionContext)` | `Mono<SagaResult>` | Execute with explicit context |
| `execute(sagaName, Map<String, Object>)` | `Mono<SagaResult>` | Execute with raw input map |
| `execute(SagaDefinition, StepInputs)` | `Mono<SagaResult>` | Execute a builder-created definition |
| `execute(SagaDefinition, StepInputs, ExecutionContext)` | `Mono<SagaResult>` | Execute with definition and context |

## 12. Saga Builder DSL

The programmatic builder provides a fluent API for constructing sagas without annotations:

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

### Handler Variants

| Method | Signature | Use When |
|--------|-----------|----------|
| `handler(StepHandler)` | `(I, ExecutionContext) -> Mono<O>` | Full interface implementation |
| `handler(BiFunction)` | `(I, ExecutionContext) -> Mono<O>` | Lambda with input + context |
| `handlerInput(Function)` | `(I) -> Mono<O>` | Lambda needing only the input |
| `handlerCtx(Function)` | `(ExecutionContext) -> Mono<O>` | Lambda needing only the context |
| `handler(Supplier)` | `() -> Mono<O>` | Lambda needing no arguments |

### Compensation Variants

| Method | Signature |
|--------|-----------|
| `compensation(BiFunction)` | `(Object, ExecutionContext) -> Mono<Void>` |
| `compensationCtx(Function)` | `(ExecutionContext) -> Mono<Void>` |
| `compensation(Supplier)` | `() -> Mono<Void>` |

### Step Configuration

| Method | Description |
|--------|-------------|
| `dependsOn(String...)` | Step dependencies |
| `retry(int)` | Max retry attempts |
| `backoff(Duration)` / `backoffMs(long)` | Backoff between retries |
| `timeout(Duration)` / `timeoutMs(long)` | Step timeout |
| `idempotencyKey(String)` | Literal key for deduplication |
| `jitter()` / `jitter(boolean)` | Enable jitter on backoff |
| `jitterFactor(double)` | Jitter factor (0.0 - 1.0, default 0.5) |
| `cpuBound(boolean)` | Schedule on bounded-elastic scheduler |
| `stepEvent(String, String, String)` | Per-step event publishing (topic, type, key) |
| `compensationRetry(int)` | Retry attempts for compensation |
| `compensationBackoff(Duration)` | Backoff for compensation retries |
| `compensationTimeout(Duration)` | Timeout for compensation |
| `compensationCritical(boolean)` | Circuit breaker flag for compensation |

## 13. Compensation Policies

When a saga step fails, the engine runs compensations for all completed steps. The **compensation policy** determines how those compensations execute.

Set the policy globally:

```yaml
firefly:
  orchestration:
    saga:
      compensation-policy: STRICT_SEQUENTIAL
```

### STRICT_SEQUENTIAL (Default)

Compensations run one at a time in **reverse completion order** (last completed to first completed). If compensation A fails, compensation B still runs.

```
Step 1 ok --> Step 2 ok --> Step 3 FAIL
Compensate Step 2 --> Compensate Step 1
```

Best for most applications. Predictable, debuggable, safe.

### GROUPED_PARALLEL

Steps in the same DAG layer are compensated in parallel; layers are compensated in reverse order.

```
Layer 2: [Step3] ok          --> Compensate Step3
Layer 1: [Step2a, Step2b] ok --> Compensate Step2a || Step2b  (parallel)
Layer 0: [Step1] ok          --> Compensate Step1
```

Best for sagas with many independent steps in the same layer.

### RETRY_WITH_BACKOFF

Like STRICT_SEQUENTIAL but each compensation is retried with exponential backoff. Failed compensations are logged and skipped (best-effort).

```
Compensate Step 2 (attempt 1: fail, attempt 2: fail, attempt 3: success)
Compensate Step 1 (attempt 1: success)
```

Best for compensations that may fail transiently.

### CIRCUIT_BREAKER

Like RETRY_WITH_BACKOFF, but tracks compensation failures. If a step marked `compensationCritical = true` fails compensation, the circuit opens and all remaining compensations are skipped.

```java
.step("critical")
    .compensationCritical(true)
    .handler(...)
    .compensation(...)
    .add()
```

Best for sagas where some compensations are critical for data integrity.

### BEST_EFFORT_PARALLEL

All compensations run in parallel with no ordering guarantee. Failures are logged but do not stop other compensations.

```
Compensate Step 1 || Step 2 || Step 3  (all parallel, all best-effort)
```

Best for performance-critical sagas where compensation ordering does not matter.

## 14. Saga Fan-Out (ExpandEach)

`ExpandEach` dynamically clones a saga step for each item in a collection. This is useful for processing a batch of items where each needs its own step instance.

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

This expands the saga at runtime:

```
Original:  reserve         --> charge --> ship
Expanded:  reserve:SKU-001 \
           reserve:SKU-002  |--> charge --> ship
           reserve:SKU-003 /
```

Each cloned step runs independently. Downstream steps wait for all expanded steps to complete.

```java
ExpandEach.of(items)                     // uses #0, #1, #2 suffixes
ExpandEach.of(items, item -> item.id())  // uses :id suffix
```

## 15. Saga Result API

`SagaResult` provides a rich API for inspecting saga execution results:

```java
SagaResult result = ...;

// Top-level
result.sagaName();              // "TransferFunds"
result.correlationId();         // "550e8400-e29b-..."
result.isSuccess();             // true or false
result.duration();              // Duration between start and completion
result.error();                 // Optional<Throwable> -- primary error if failed
result.headers();               // Map<String, String> -- execution headers
result.startedAt();             // Instant
result.completedAt();           // Instant

// Per-step inspection
result.steps();                 // Map<String, StepOutcome> -- all step outcomes
result.failedSteps();           // List<String> -- IDs of failed steps
result.compensatedSteps();      // List<String> -- IDs of compensated steps
result.firstErrorStepId();      // Optional<String> -- first step that failed
result.stepResults();           // Map<String, Object> -- raw step results

// Type-safe result extraction
Optional<DebitResult> debit = result.resultOf("debit", DebitResult.class);

// StepOutcome fields
StepOutcome outcome = result.steps().get("debit");
outcome.status();               // StepStatus (DONE, FAILED, COMPENSATED, etc.)
outcome.attempts();             // int -- number of execution attempts
outcome.latencyMs();            // long -- execution duration in ms
outcome.result();               // Object -- step return value
outcome.error();                // Throwable -- step error (null if succeeded)
outcome.compensated();          // boolean -- was compensation run?
outcome.startedAt();            // Instant -- when step started
outcome.compensationResult();   // Object -- compensation return value
outcome.compensationError();    // Throwable -- compensation error (null if succeeded)
```

### Factory Methods

| Method | Description |
|--------|-------------|
| `SagaResult.from(sagaName, ctx, compensatedFlags, stepErrors, allStepIds)` | Build from execution context |
| `SagaResult.failed(sagaName, correlationId, failedStepId, error, steps)` | Build a pre-failed result |

---

# Part IV: TCC Pattern

## 16. TCC Concepts

**TCC (Try-Confirm-Cancel)** is a two-phase distributed transaction protocol. Each participant implements three operations:

| Phase       | Purpose                              | When It Runs                   |
|-------------|--------------------------------------|--------------------------------|
| **Try**     | Reserve resources, validate          | Always runs first              |
| **Confirm** | Commit the reservation               | Only if ALL tries succeed      |
| **Cancel**  | Release the reservation              | Only if ANY try fails          |

```
Participant A: Try ok   Participant B: Try ok   --> Confirm A, Confirm B
Participant A: Try ok   Participant B: Try FAIL --> Cancel A (B was never reserved)
Participant A: Try ok   Confirm A FAIL          --> Cancel A (confirm failure triggers cancel)
```

**Key difference from Saga:** In a Saga, the forward action is committed immediately and must be compensated on failure. In TCC, the Try phase only *reserves* resources -- nothing is committed until the Confirm phase.

**Confirm failure behavior:** If the Confirm phase fails for any participant, the framework automatically chains into the Cancel phase to restore consistency. The result status will be `CANCELED` (if cancel succeeds) or `FAILED` (if cancel also fails, which routes to the DLQ).

**Key concepts:**

- **Participant:** A service that implements Try/Confirm/Cancel for a resource
- **Order:** Participants execute their Try phase in defined order
- **Optional:** An optional participant's Try failure does not trigger Cancel for others
- **TccInputs:** Per-participant input data for the Try phase

## 17. Tutorial: Your First TCC Transaction

### Step 1: Define the TCC Coordinator (Annotations)

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

### TccEngine Method Reference

| Method | Return Type | Description |
|--------|-------------|-------------|
| `execute(tccName, TccInputs)` | `Mono<TccResult>` | Execute by name with inputs |
| `execute(tccName, TccInputs, ExecutionContext)` | `Mono<TccResult>` | Execute with explicit context |
| `execute(tccName, Map<String, Object>)` | `Mono<TccResult>` | Execute with raw participant inputs |
| `execute(TccDefinition, TccInputs)` | `Mono<TccResult>` | Execute a builder-created definition |
| `execute(TccDefinition, TccInputs, ExecutionContext)` | `Mono<TccResult>` | Execute with definition and context |

## 18. TCC Builder DSL

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
            accountService.prepareCredit(((TransferRequest) input).toAccount(),
                ((TransferRequest) input).amount()))
        .confirmHandler((tryResult, ctx) ->
            accountService.commitCredit((String) tryResult))
        .cancelHandler((tryResult, ctx) ->
            accountService.cancelCredit((String) tryResult))
        .add()
    .build();

tccEngine.execute(def, TccInputs.of(Map.of(
    "debit", transferRequest,
    "credit", transferRequest
)));
```

### Builder Defaults

`TccBuilder.tcc(name)` creates a definition with defaults matching the `@Tcc` annotation: `timeoutMs = -1`, `retryEnabled = true`, `maxRetries = 3`, `backoffMs = 1000`.

For retry-disabled definitions, use `TccBuilder.tccNoRetry(name)` which sets `retryEnabled = false`, `maxRetries = 0`, `backoffMs = 0`.

### Convention-Based Handler

You can pass a bean with `doTry`, `doConfirm`, `doCancel` methods instead of lambda functions:

```java
public class DebitHandler {
    public Mono<String> doTry(Object input, ExecutionContext ctx) { ... }
    public Mono<Void> doConfirm(Object tryResult, ExecutionContext ctx) { ... }
    public Mono<Void> doCancel(Object tryResult, ExecutionContext ctx) { ... }
}

TccBuilder.tcc("Transfer")
    .participant("debit")
        .handler(new DebitHandler())
        .add()
    .build();
```

### Optional Participants

An optional participant's Try failure does not trigger Cancel for other participants:

```java
.participant("loyalty-points")
    .optional(true)
    .tryHandler(...)
    .confirmHandler(...)
    .cancelHandler(...)
    .add()
```

## 19. TCC Result API

```java
TccResult result = ...;

// Status
result.status();                    // CONFIRMED, CANCELED, or FAILED
result.isConfirmed();               // true if all phases succeeded
result.isCanceled();                // true if Try failed and Cancel ran
result.isFailed();                  // true if Confirm or Cancel phase failed

// Metadata
result.tccName();                   // "TransferFunds"
result.correlationId();             // UUID
result.duration();                  // Duration
result.startedAt();                 // Instant
result.completedAt();               // Instant

// Error info
result.error();                     // Optional<Throwable>
result.failedParticipantId();       // Optional<String>
result.failedPhase();               // Optional<TccPhase> -- TRY, CONFIRM, or CANCEL

// Per-participant outcomes
result.participants();              // Map<String, ParticipantOutcome>
result.tryResultOf("debit", String.class);   // Optional<T>

// ParticipantOutcome fields
ParticipantOutcome outcome = result.participants().get("debit");
outcome.participantId();            // "debit"
outcome.tryResult();                // Object -- Try phase return value
outcome.trySucceeded();             // boolean
outcome.confirmSucceeded();         // boolean
outcome.cancelExecuted();           // boolean
outcome.error();                    // Throwable (null if no error)
outcome.attempts();                 // int -- total attempts
outcome.latencyMs();                // long
```

### Factory Methods

| Method | Description |
|--------|-------------|
| `TccResult.confirmed(tccName, ctx, participants)` | All phases succeeded |
| `TccResult.canceled(tccName, ctx, failedId, failedPhase, error, participants)` | Try failed, cancel ran |
| `TccResult.failed(tccName, ctx, failedId, failedPhase, error, participants)` | Confirm/Cancel failed |

---

# Part V: Core Infrastructure

## 20. ExecutionContext

`ExecutionContext` is the thread-safe state carrier shared across all steps in an execution. It holds variables, headers, step results, step statuses, and pattern-specific state. All internal maps use `ConcurrentHashMap`.

### Creating Contexts

```java
ExecutionContext ctx = ExecutionContext.forWorkflow("correlation-123", "MyWorkflow");
ExecutionContext ctx = ExecutionContext.forWorkflow("correlation-123", "MyWorkflow", true);  // dry run
ExecutionContext ctx = ExecutionContext.forSaga(null, "MySaga");       // null = auto-generate ID
ExecutionContext ctx = ExecutionContext.forTcc(null, "MyTcc");
```

### Identity

```java
ctx.getCorrelationId();     // unique execution ID
ctx.getExecutionName();     // saga/workflow/tcc name
ctx.getPattern();           // ExecutionPattern (WORKFLOW, SAGA, TCC)
ctx.getStartedAt();         // Instant when execution began
ctx.isDryRun();             // true if dry-run mode (workflow only)
```

### Variables

Variables are execution-wide key-value pairs that persist across steps:

```java
ctx.putVariable("orderId", "ORD-123");
String orderId = ctx.getVariableAs("orderId", String.class);
ctx.removeVariable("tempData");
Map<String, Object> all = ctx.getVariables();
```

### Headers

String key-value metadata (similar to HTTP headers):

```java
ctx.putHeader("X-Tenant-Id", "acme");
String tenant = ctx.getHeader("X-Tenant-Id");
Map<String, String> all = ctx.getHeaders();
```

### Step Results

Each step's return value is stored by step ID:

```java
ctx.putResult("validate", validationResult);
Object result = ctx.getResult("validate");
<T> T typed = ctx.getResult("validate", Map.class);
Map<String, Object> all = ctx.getStepResults();
```

### Step Status Tracking

```java
ctx.setStepStatus("validate", StepStatus.RUNNING);
StepStatus status = ctx.getStepStatus("validate");   // PENDING if not set
int attempts = ctx.incrementAttempts("validate");     // returns new count
ctx.setStepLatency("validate", 150L);                 // milliseconds
ctx.markStepStarted("validate");                      // records Instant
Instant started = ctx.getStepStartedAt("validate");
```

### Idempotency Keys

Prevent duplicate step execution:

```java
boolean isNew = ctx.addIdempotencyKey("debit:ORD-123");
if (ctx.hasIdempotencyKey("debit:ORD-123")) {
    // Already processed
}
Set<String> keys = ctx.getIdempotencyKeys();
```

### Saga-Specific: Compensation State

```java
ctx.putCompensationResult("debit", refundResult);
ctx.putCompensationError("debit", error);
Object result = ctx.getCompensationResult("debit");
Throwable error = ctx.getCompensationError("debit");
```

### TCC-Specific: Try Results and Phase Tracking

```java
ctx.setCurrentPhase(TccPhase.TRY);
TccPhase phase = ctx.getCurrentPhase();
ctx.putTryResult("debit", holdId);
Object tryResult = ctx.getTryResult("debit");
Map<String, Object> allTryResults = ctx.getTryResults();
```

### Workflow-Specific: Compensatable Step Tracking

```java
ctx.addCompensatableStep("charge");
List<String> compensatable = ctx.getCompletedCompensatableSteps();
```

### Topology

```java
ctx.setTopologyLayers(layers);
List<List<String>> layers = ctx.getTopologyLayers();
```

## 21. Parameter Injection Annotations

The `ArgumentResolver` automatically injects values into step method parameters based on annotations. This works across all three patterns.

| Annotation | What It Injects | Example |
|------------|----------------|---------|
| `@Input` | The step's input object | `@Input OrderRequest req` |
| `@Input("key")` | A specific key from a Map input | `@Input("orderId") String id` |
| `@FromStep("stepId")` | The result of a completed step | `@FromStep("validate") Map result` |
| `@FromCompensationResult("stepId")` | Compensation result of a step | `@FromCompensationResult("debit") Object r` |
| `@CompensationError("stepId")` | Compensation error of a step | `@CompensationError("debit") Throwable err` |
| `@FromTry("participantId")` | TCC Try-phase result | `@FromTry("debit") String holdId` |
| `@Header("name")` | A single header value | `@Header("X-Tenant") String tenant` |
| `@Headers` | The full headers map | `@Headers Map<String,String> headers` |
| `@Variable("name")` | A single context variable | `@Variable("orderId") String id` |
| `@Variables` | The full variables map | `@Variables Map<String,Object> vars` |
| `@CorrelationId` | The execution's correlation ID | `@CorrelationId String corrId` |
| `@Required` | Enforces non-null (modifier) | `@Required @FromStep("x") Object r` |

### @SetVariable (Method-Level)

Store the method's return value as a named context variable:

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

### Unannotated Parameters

If a parameter has no annotation, the resolver attempts to inject:

1. The `ExecutionContext` if the parameter type matches
2. The step input if the parameter type is assignable
3. `null` otherwise

## 22. Retry Policies and Backoff

### RetryPolicy Record

```java
public record RetryPolicy(
    int maxAttempts,              // total attempts (1 = no retry)
    Duration initialDelay,        // delay before first retry
    Duration maxDelay,            // cap on exponential growth
    double multiplier,            // backoff multiplier (2.0 = doubling)
    double jitterFactor,          // 0.0-1.0, randomizes delay
    String[] retryableExceptions  // only retry these exception types (empty = all)
) {
    static final RetryPolicy DEFAULT = new RetryPolicy(3, 1s, 5m, 2.0, 0.0, {});
    static final RetryPolicy NO_RETRY = new RetryPolicy(1, 0, 0, 1.0, 0.0, {});
}
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
Jittered delay = 2s +/- (2s * 0.1) = between 1.8s and 2.2s
```

### Per-Step Retry (Annotation)

```java
@SagaStep(id = "charge", retry = 5, backoffMs = 1000,
          timeoutMs = 10000, jitter = true, jitterFactor = 0.2)
```

### Per-Step Retry (Builder)

```java
.step("charge")
    .retry(5)
    .backoffMs(1000)
    .timeoutMs(10_000)
    .jitter()
    .jitterFactor(0.2)
    .add()
```

### Workflow Retry Fallback

When a workflow step has `RetryPolicy.NO_RETRY`, the engine falls back to the workflow-level retry policy. If the step has its own retry policy, the step-level policy takes precedence.

## 23. Event Integration

The event system has two directions: **inbound** (triggering orchestrations) and **outbound** (publishing events when steps complete).

### Inbound: Event-Driven Triggering

Any orchestration can be triggered by an external event using `triggerEventType`. This works identically across annotations and builders for all three patterns.

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

**Routing:**

The `EventGateway` maintains a registry of event type to orchestration mappings. When an event arrives, it looks up the registered executor and invokes it:

```java
eventGateway.routeEvent("OrderCreated", Map.of("orderId", "ORD-123"))
    .subscribe();
```

```java
// Query the gateway
eventGateway.hasRegistration("OrderCreated");   // boolean
eventGateway.registrationCount();                // int
eventGateway.registeredEventTypes();             // Set<String>
```

### Outbound: Step-Level Event Publishing

**Saga -- @StepEvent:**

Publishes an event each time an individual step completes successfully, regardless of the overall saga outcome:

```java
@SagaStep(id = "reserve")
@StepEvent(topic = "inventory", type = "InventoryReserved", key = "orderId")
public Mono<String> reserve(@Input OrderRequest req) { ... }
```

Builder equivalent:

```java
.step("reserve")
    .stepEvent("inventory", "InventoryReserved", "orderId")
    .handler(...)
    .add()
```

**TCC -- @TccEvent:**

Publishes an event when a TCC participant completes its Confirm phase:

```java
@TccEvent(topic = "payments", eventType = "PaymentConfirmed", key = "txId")
@TccParticipant(id = "debit", order = 0)
public static class DebitParticipant { ... }
```

Builder equivalent:

```java
.participant("debit")
    .event("payments", "PaymentConfirmed", "txId")
    .tryHandler(...)
    .confirmHandler(...)
    .cancelHandler(...)
    .add()
```

**Workflow -- publishEvents:**

When `publishEvents = true` on a workflow, the engine publishes `OrchestrationEvent.stepCompleted` for each step that finishes:

```java
@Workflow(id = "Pipeline", publishEvents = true)
```

Builder equivalent:

```java
new WorkflowBuilder("Pipeline").publishEvents(true)
```

Individual steps can also specify `outputEventType` for custom event types:

```java
@WorkflowStep(id = "extract", outputEventType = "DataExtracted")
```

### OrchestrationEvent

Events are represented as `OrchestrationEvent` records:

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

Events are published through the `OrchestrationEventPublisher` interface. The default implementation is a no-op. Provide a custom bean to integrate with your messaging infrastructure (Kafka, RabbitMQ, etc.):

```java
@Component
public class KafkaEventPublisher implements OrchestrationEventPublisher {
    @Override
    public Mono<Void> publish(OrchestrationEvent event) {
        return kafkaTemplate.send(event.topic(), event.key(), event)
            .then();
    }
}
```

## 24. Scheduling

All three patterns support scheduled execution with full attribute parity.

### Scheduling Annotations

**@ScheduledSaga:**

```java
@Saga(name = "CleanupSaga")
@ScheduledSaga(cron = "0 0 2 * * *", zone = "America/New_York",
               enabled = true, input = "{\"daysOld\": 30}",
               description = "Nightly cleanup")
public class CleanupSaga { ... }
```

**@ScheduledTcc:**

```java
@Tcc(name = "ReconciliationTcc")
@ScheduledTcc(fixedRate = 60000, initialDelay = 5000,
              input = "{\"batchSize\": 100}")
public class ReconciliationTcc { ... }
```

**@ScheduledWorkflow:**

```java
@Workflow(id = "ReportWorkflow")
@ScheduledWorkflow(fixedDelay = 3600000, zone = "UTC")
public class ReportWorkflow { ... }
```

### Annotation Attributes (All Three)

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `cron` | String | `""` | Cron expression (6 fields: second minute hour day month weekday) |
| `zone` | String | `""` | Timezone for cron (e.g., "America/New_York") |
| `fixedRate` | long | `-1` | Fixed rate in milliseconds |
| `fixedDelay` | long | `-1` | Fixed delay in milliseconds |
| `initialDelay` | long | `0` | Initial delay before first execution |
| `enabled` | boolean | `true` | Whether this schedule is active |
| `input` | String | `"{}"` | JSON input passed to the engine |
| `description` | String | `""` | Human-readable description |

### Multiple Schedules

All scheduling annotations are `@Repeatable`, so you can apply multiple to a single class:

```java
@Saga(name = "multi-schedule")
@ScheduledSaga(fixedRate = 2000)
@ScheduledSaga(cron = "0 0 * * * *")
public class MultiScheduleSaga { ... }
```

### OrchestrationScheduler

The `OrchestrationScheduler` manages the underlying `ScheduledExecutorService`:

```java
scheduler.scheduleAtFixedRate(taskId, task, initialDelay, period);
scheduler.scheduleWithFixedDelay(taskId, task, initialDelay, delay);
scheduler.scheduleWithCron(taskId, task, cronExpression);
scheduler.scheduleWithCron(taskId, task, cronExpression, zone);
scheduler.cancel(taskId);
scheduler.activeTaskCount();
scheduler.shutdown();
```

Thread pool size is configured via:

```yaml
firefly:
  orchestration:
    scheduling:
      thread-pool-size: 4
```

## 25. Persistence Providers

### Provider Selection

```yaml
firefly:
  orchestration:
    persistence:
      provider: in-memory
      # provider: redis
      # provider: cache
      # provider: event-sourced
```

### ExecutionPersistenceProvider Interface

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

```java
record ExecutionState(
    String correlationId,
    String executionName,
    ExecutionPattern pattern,
    ExecutionStatus status,
    Map<String, Object> stepResults,
    Map<String, StepStatus> stepStatuses,
    Map<String, Integer> stepAttempts,
    Map<String, Long> stepLatenciesMs,
    Map<String, Object> variables,
    Map<String, String> headers,
    Set<String> idempotencyKeys,
    List<List<String>> topologyLayers,
    String failureReason,
    Instant startedAt,
    Instant updatedAt
) {
    ExecutionState withStatus(ExecutionStatus newStatus);
    ExecutionState withFailure(String reason);
    boolean isTerminal();
}
```

### Custom Persistence Provider

Implement the interface and register as a Spring bean:

```java
@Component
public class JdbcPersistenceProvider implements ExecutionPersistenceProvider {
    // Your JDBC-based implementation
}
```

The auto-configuration uses `@ConditionalOnMissingBean`, so your custom bean takes precedence.

### Provider Comparison

| Feature       | InMemory          | Redis    | Cache            | EventSourced     |
|---------------|-------------------|----------|------------------|------------------|
| Persistence   | Process lifetime  | Durable  | Adapter-dependent| Durable          |
| Query support | Full              | Full     | Full             | Limited          |
| Cleanup       | Supported         | Supported| Supported        | No-op            |
| Clustering    | No                | Yes      | Adapter-dependent| Yes              |
| Performance   | Fastest           | Fast     | Varies           | Write-optimized  |

## 26. Dead-Letter Queue

Failed executions are automatically sent to the DLQ for later inspection and retry.

### Configuration

```yaml
firefly:
  orchestration:
    dlq:
      enabled: true
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

### DeadLetterService

```java
public class DeadLetterService {
    Mono<Void> deadLetter(DeadLetterEntry entry);
    Flux<DeadLetterEntry> getAllEntries();
    Mono<Optional<DeadLetterEntry>> getEntry(String id);
    Flux<DeadLetterEntry> getByExecutionName(String name);
    Flux<DeadLetterEntry> getByCorrelationId(String correlationId);
    Mono<Void> deleteEntry(String id);
    Mono<Long> count();
    Mono<DeadLetterEntry> markRetried(String id);
}
```

### DLQ REST Endpoints

```
GET    /api/orchestration/dlq          # list all entries
GET    /api/orchestration/dlq/count    # count entries
DELETE /api/orchestration/dlq/{id}     # remove an entry
```

### Custom DLQ Store

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

### When Entries Are Created

- **Saga:** One DLQ entry per failed step (each with its own `stepId`)
- **TCC:** One DLQ entry for the failed transaction (only for `FAILED` status, not `CANCELED`)
- **Workflow:** One DLQ entry per failed step when the step exhausts all retries

## 27. Observability (Events, Metrics, Tracing)

### OrchestrationEvents Interface

All orchestration lifecycle events flow through this interface. Every method has a default no-op implementation, so you only override what you need. The `CompositeOrchestrationEvents` auto-configuration collects all `OrchestrationEvents` beans and fans out to each one with per-delegate error isolation.

**Lifecycle events (all patterns):**

| Method | When It Fires |
|--------|---------------|
| `onStart(name, correlationId, pattern)` | Execution begins |
| `onStepStarted(name, correlationId, stepId)` | A step begins execution |
| `onStepSuccess(name, correlationId, stepId, attempts, latencyMs)` | A step completes successfully |
| `onStepFailed(name, correlationId, stepId, error, attempts)` | A step fails after all retries |
| `onStepSkipped(name, correlationId, stepId)` | A step is skipped (condition not met or dry-run) |
| `onStepSkippedIdempotent(name, correlationId, stepId)` | A step is skipped due to idempotency key |
| `onCompleted(name, correlationId, pattern, success, durationMs)` | Execution completes |
| `onDeadLettered(name, correlationId, stepId, error)` | An entry is added to the DLQ |

**Saga-specific:**

| Method | When It Fires |
|--------|---------------|
| `onCompensationStarted(name, correlationId)` | Compensation phase begins |
| `onStepCompensated(name, correlationId, stepId)` | A step's compensation succeeds |
| `onStepCompensationFailed(name, correlationId, stepId, error)` | A step's compensation fails |

**TCC-specific:**

| Method | When It Fires |
|--------|---------------|
| `onPhaseStarted(name, correlationId, phase)` | TRY/CONFIRM/CANCEL phase begins |
| `onPhaseCompleted(name, correlationId, phase, durationMs)` | A phase completes successfully |
| `onPhaseFailed(name, correlationId, phase, error)` | A phase fails |
| `onParticipantStarted(name, correlationId, participantId, phase)` | A participant begins a phase |
| `onParticipantSuccess(name, correlationId, participantId, phase)` | A participant completes a phase |
| `onParticipantFailed(name, correlationId, participantId, phase, error)` | A participant fails a phase |

**Workflow-specific:**

| Method | When It Fires |
|--------|---------------|
| `onWorkflowSuspended(name, correlationId, reason)` | A workflow is suspended |
| `onWorkflowResumed(name, correlationId)` | A workflow is resumed |
| `onSignalDelivered(name, correlationId, signalName)` | A signal is delivered |
| `onTimerFired(name, correlationId, timerId)` | A timer fires |
| `onChildWorkflowStarted(parentName, parentCorrId, childWorkflowId, childCorrId)` | A child workflow starts |
| `onChildWorkflowCompleted(parentName, parentCorrId, childCorrId, success)` | A child workflow completes |
| `onContinueAsNew(name, oldCorrId, newCorrId)` | Workflow continues as new execution |

**Composition:**

| Method | When It Fires |
|--------|---------------|
| `onCompositionStarted(compositionName, correlationId)` | Cross-pattern composition begins |
| `onCompositionCompleted(compositionName, correlationId, success)` | Composition completes |

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

### Metrics (Micrometer)

Enabled automatically when a `MeterRegistry` is on the classpath. All metrics use the `firefly.orchestration` prefix.

```yaml
firefly:
  orchestration:
    metrics:
      enabled: true
```

| Metric | Type | Tags |
|--------|------|------|
| `firefly.orchestration.executions.started` | Counter | name, pattern |
| `firefly.orchestration.executions.completed` | Counter | name, pattern, success |
| `firefly.orchestration.executions.duration` | Timer | name, pattern |
| `firefly.orchestration.steps.completed` | Counter | name, stepId, success |
| `firefly.orchestration.steps.duration` | Timer | name, stepId |
| `firefly.orchestration.steps.retries` | Counter | name, stepId |
| `firefly.orchestration.compensations.started` | Counter | name |
| `firefly.orchestration.dlq.entries` | Counter | name |

### Tracing (Micrometer Observation)

Enabled automatically when an `ObservationRegistry` is on the classpath.

```yaml
firefly:
  orchestration:
    tracing:
      enabled: true
```

The `OrchestrationTracer` creates spans for:

- **Execution-level:** Observation name `orchestration.execution` with tags `orchestration.name`, `orchestration.pattern`, `orchestration.correlationId`
- **Step-level:** Observation name `orchestration.step` with tags `orchestration.name`, `orchestration.step`, `orchestration.correlationId`

```java
tracer.traceExecution(name, pattern, correlationId, mono);
tracer.traceStep(executionName, stepId, correlationId, mono);
```

## 28. Lifecycle Callbacks

All three patterns support lifecycle callbacks for success and error handling.

### Saga Callbacks

```java
@OnSagaComplete(async = false, priority = 0)
public void onComplete(ExecutionContext ctx, SagaResult result) { ... }

@OnSagaError(errorTypes = {TimeoutException.class}, suppressError = false, async = false)
public void onError(Throwable error, ExecutionContext ctx) { ... }
```

**@OnSagaComplete** receives both `ExecutionContext` and `SagaResult` via automatic argument resolution. Either parameter is optional -- you can declare just one or both in any order.

**@OnSagaError** receives `Throwable` and `ExecutionContext`. The `errorTypes` attribute filters which exceptions trigger the callback. When `suppressError = true`, the saga result is converted to a success result (useful for fallback behavior).

### TCC Callbacks

```java
@OnTccComplete(async = false, priority = 0)
public void onComplete(ExecutionContext ctx, TccResult result) { ... }

@OnTccError(errorTypes = {}, suppressError = false, async = false)
public void onError(Throwable error, ExecutionContext ctx) { ... }
```

Same semantics as saga callbacks. `@OnTccComplete` receives `TccResult` when declared as a parameter.

### Workflow Callbacks

```java
@OnWorkflowComplete(async = false, priority = 0)
public void onComplete(ExecutionContext ctx) { ... }

@OnWorkflowError(errorTypes = {}, stepIds = {}, suppressError = false, async = false)
public void onError(Throwable error, ExecutionContext ctx) { ... }
```

`@OnWorkflowError` has an additional `stepIds` attribute to filter by which steps caused the error.

### Common Attributes

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `async` | boolean | `false` | Run callback on bounded-elastic scheduler |
| `priority` | int | `0` | Ordering (lower = earlier) |
| `errorTypes` | Class[] | `{}` | Filter by exception type (error callbacks only) |
| `suppressError` | boolean | `false` | Convert failure to success (error callbacks only) |

## 29. REST API

### Endpoints

Enabled by default when running a reactive web application:

```yaml
firefly:
  orchestration:
    rest:
      enabled: true
```

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/orchestration/executions` | List executions (optional `?status=` filter) |
| `GET` | `/api/orchestration/executions/{id}` | Get execution by correlation ID |
| `GET` | `/api/orchestration/dlq` | List DLQ entries |
| `GET` | `/api/orchestration/dlq/count` | Count DLQ entries |
| `DELETE` | `/api/orchestration/dlq/{id}` | Delete a DLQ entry |

### Health Indicator

```yaml
firefly:
  orchestration:
    health:
      enabled: true
```

Reports `UP` when persistence is healthy, `DOWN` otherwise. Visible at `/actuator/health`.

## 30. Recovery Service

Detects and reports stale executions (started but never completed):

```yaml
firefly:
  orchestration:
    recovery:
      enabled: true
      stale-threshold: 1h
    persistence:
      retention-period: 7d
      cleanup-interval: 1h
```

### Programmatic Access

```java
@Autowired RecoveryService recoveryService;

recoveryService.findStaleExecutions()
    .subscribe(state -> log.warn("Stale execution: {}", state.correlationId()));

recoveryService.cleanupCompletedExecutions(Duration.ofDays(30))
    .subscribe(count -> log.info("Cleaned up {} executions", count));
```

## 31. Exception Hierarchy

All orchestration exceptions extend the sealed `OrchestrationException`:

```
OrchestrationException (sealed)
+-- ExecutionNotFoundException       -- workflow/saga/tcc ID not found
+-- StepExecutionException           -- step method invocation failed
|     getStepId()                    -- which step failed
+-- TopologyValidationException      -- DAG has cycles, missing deps, etc.
+-- DuplicateDefinitionException     -- two definitions share the same name
+-- ExecutionTimeoutException        -- execution or step timed out
+-- CompensationException            -- saga compensation step failed
+-- TccPhaseException                -- TCC Try/Confirm/Cancel phase error
      getPhase()                     -- which phase (TRY, CONFIRM, CANCEL)
```

Each exception carries an error code (e.g., `ORCHESTRATION_STEP_EXECUTION_ERROR`) for programmatic error handling.

---

# Part VI: Configuration Reference

## 32. All Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `firefly.orchestration.workflow.enabled` | boolean | `true` | Enable workflow pattern |
| `firefly.orchestration.saga.enabled` | boolean | `true` | Enable saga pattern |
| `firefly.orchestration.saga.compensation-policy` | enum | `STRICT_SEQUENTIAL` | Default compensation strategy |
| `firefly.orchestration.saga.default-timeout` | Duration | `5m` | Default saga timeout |
| `firefly.orchestration.tcc.enabled` | boolean | `true` | Enable TCC pattern |
| `firefly.orchestration.tcc.default-timeout` | Duration | `30s` | Default TCC timeout |
| `firefly.orchestration.persistence.provider` | string | `in-memory` | Persistence adapter |
| `firefly.orchestration.persistence.key-prefix` | string | `orchestration:` | Redis/cache key prefix |
| `firefly.orchestration.persistence.key-ttl` | Duration | -- | Optional Redis key TTL |
| `firefly.orchestration.persistence.retention-period` | Duration | `7d` | Completed execution retention |
| `firefly.orchestration.persistence.cleanup-interval` | Duration | `1h` | Cleanup task interval |
| `firefly.orchestration.recovery.enabled` | boolean | `true` | Enable stale execution detection |
| `firefly.orchestration.recovery.stale-threshold` | Duration | `1h` | Age before execution is stale |
| `firefly.orchestration.scheduling.thread-pool-size` | int | `4` | Scheduler thread pool size |
| `firefly.orchestration.rest.enabled` | boolean | `true` | Expose REST endpoints |
| `firefly.orchestration.health.enabled` | boolean | `true` | Expose health indicator |
| `firefly.orchestration.metrics.enabled` | boolean | `true` | Enable Micrometer metrics |
| `firefly.orchestration.tracing.enabled` | boolean | `true` | Enable distributed tracing |
| `firefly.orchestration.dlq.enabled` | boolean | `true` | Enable dead-letter queue |
| `firefly.orchestration.resilience.enabled` | boolean | `true` | Enable Resilience4j integration |

## 33. Auto-Configuration Chain

The module's auto-configurations load in a specific order to ensure dependencies are available:

### Phase 1: Persistence (before everything else)

`OrchestrationPersistenceAutoConfiguration` creates the persistence provider based on classpath and properties. If no specific provider matches, the fallback `InMemoryPersistenceProvider` is created in Phase 2.

### Phase 2: Core Infrastructure

`OrchestrationAutoConfiguration` creates:

1. `ArgumentResolver` -- parameter injection
2. `StepInvoker` -- reflective method invocation with retry
3. `OrchestrationLoggerEvents` -- SLF4J event logging
4. `OrchestrationEvents` -- composite fan-out (wraps all event delegates)
5. `ExecutionPersistenceProvider` -- InMemory fallback if none exists
6. `DeadLetterStore` + `DeadLetterService` -- DLQ
7. `OrchestrationEventPublisher` -- domain event publishing (no-op default)
8. `OrchestrationScheduler` -- scheduled task executor
9. `RecoveryService` -- stale execution detection

### Phase 3: Pattern Engines (after core)

Each enabled pattern creates its registry, orchestrator, and engine:

- `WorkflowAutoConfiguration` creates `WorkflowRegistry`, `WorkflowExecutor`, `WorkflowEngine`
- `SagaAutoConfiguration` creates `SagaRegistry`, `SagaExecutionOrchestrator`, `SagaCompensator`, `SagaEngine`
- `TccAutoConfiguration` creates `TccRegistry`, `TccExecutionOrchestrator`, `TccEngine`

### Phase 4: Extensions (after core)

- `OrchestrationMetricsAutoConfiguration` -- if `MeterRegistry` on classpath
- `OrchestrationTracingAutoConfiguration` -- if `ObservationRegistry` on classpath
- `OrchestrationResilienceAutoConfiguration` -- if `CircuitBreakerRegistry` on classpath
- `OrchestrationRestAutoConfiguration` -- if reactive web application

---

# Part VII: Recipes and Patterns

## 34. Cross-Pattern Composition

You can compose patterns by chaining them. For example, a Saga that triggers a TCC for a specific step:

```java
@Service
public class OrderService {
    private final SagaEngine sagaEngine;
    private final TccEngine tccEngine;

    public Mono<SagaResult> processOrder(OrderRequest order) {
        return sagaEngine.execute("OrderSaga",
            StepInputs.of("validate", order))
            .flatMap(sagaResult -> {
                if (!sagaResult.isSuccess()) return Mono.just(sagaResult);

                return tccEngine.execute("PaymentTcc",
                    TccInputs.of("charge", order))
                    .map(tccResult -> {
                        if (tccResult.isConfirmed()) return sagaResult;
                        throw new RuntimeException("Payment TCC failed");
                    });
            });
    }
}
```

### Sequential Composition (Same Context)

Share an `ExecutionContext` across multiple executions:

```java
ExecutionContext ctx = ExecutionContext.forSaga(null, "composed");
ctx.putVariable("orderId", orderId);

sagaEngine.execute("ValidateOrder", StepInputs.empty(), ctx)
    .flatMap(r1 -> tccEngine.execute("ChargeFunds", TccInputs.empty(), ctx))
    .flatMap(r2 -> sagaEngine.execute("FulfillOrder", StepInputs.empty(), ctx));
```

## 35. Testing Your Orchestrations

### Unit Testing Steps

Test step methods directly without the engine:

```java
@Test
void debitAccount_succeeds() {
    var saga = new TransferFundsSaga(mockAccountService);
    when(mockAccountService.debit("ACC-1", 100.0))
        .thenReturn(Mono.just(new DebitResult("ACC-1", 100.0)));

    StepVerifier.create(saga.debitAccount(new TransferRequest("ACC-1", "ACC-2", 100.0)))
        .assertNext(result -> {
            assertThat(result.accountId()).isEqualTo("ACC-1");
            assertThat(result.amount()).isEqualTo(100.0);
        })
        .verifyComplete();
}
```

### Integration Testing with Builder DSL

```java
@Test
void saga_fullExecution_success() {
    SagaDefinition def = SagaBuilder.saga("TestSaga")
        .step("step1")
            .handlerInput(input -> Mono.just("result1"))
            .compensation((r, ctx) -> Mono.empty())
            .add()
        .step("step2")
            .dependsOn("step1")
            .handlerInput(input -> Mono.just("result2"))
            .add()
        .build();

    var events = new OrchestrationEvents() {};
    var invoker = new StepInvoker(new ArgumentResolver());
    var noOpPublisher = new NoOpEventPublisher();
    var orchestrator = new SagaExecutionOrchestrator(invoker, events, noOpPublisher);
    var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, invoker);
    var engine = new SagaEngine(null, events, orchestrator, null, null, compensator, noOpPublisher);

    StepVerifier.create(engine.execute(def, StepInputs.empty()))
        .assertNext(result -> {
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.steps()).containsKeys("step1", "step2");
        })
        .verifyComplete();
}
```

### Testing Compensation

```java
@Test
void saga_stepFailure_triggersCompensation() {
    AtomicBoolean compensated = new AtomicBoolean(false);

    SagaDefinition def = SagaBuilder.saga("CompTest")
        .step("step1")
            .handlerInput(input -> Mono.just("done"))
            .compensation((r, ctx) -> {
                compensated.set(true);
                return Mono.empty();
            })
            .add()
        .step("step2")
            .dependsOn("step1")
            .handlerInput(input -> Mono.error(new RuntimeException("boom")))
            .add()
        .build();

    // ... create engine ...

    StepVerifier.create(engine.execute(def, StepInputs.empty()))
        .assertNext(result -> {
            assertThat(result.isSuccess()).isFalse();
            assertThat(compensated.get()).isTrue();
            assertThat(result.compensatedSteps()).contains("step1");
        })
        .verifyComplete();
}
```

### Testing TCC Cancel

```java
@Test
void tcc_tryFailure_triggersCancel() {
    AtomicBoolean canceled = new AtomicBoolean(false);

    TccDefinition def = TccBuilder.tcc("CancelTest")
        .participant("p1")
            .tryHandler((input, ctx) -> Mono.just("reserved"))
            .confirmHandler((r, ctx) -> Mono.empty())
            .cancelHandler((r, ctx) -> { canceled.set(true); return Mono.empty(); })
            .add()
        .participant("p2")
            .tryHandler((input, ctx) -> Mono.error(new RuntimeException("fail")))
            .confirmHandler((r, ctx) -> Mono.empty())
            .cancelHandler((r, ctx) -> Mono.empty())
            .add()
        .build();

    // ... create engine ...

    StepVerifier.create(engine.execute(def, TccInputs.empty()))
        .assertNext(result -> {
            assertThat(result.isCanceled()).isTrue();
            assertThat(canceled.get()).isTrue();
        })
        .verifyComplete();
}
```

## 36. Production Checklist

Before going to production, verify:

### Persistence

- [ ] Switch from `in-memory` to `redis` or another durable provider
- [ ] Configure `retention-period` and `cleanup-interval`
- [ ] Test persistence failover behavior

### Observability

- [ ] Enable metrics (`firefly.orchestration.metrics.enabled=true`)
- [ ] Enable tracing (`firefly.orchestration.tracing.enabled=true`)
- [ ] Add custom `OrchestrationEvents` listener for alerting
- [ ] Set up dashboards for `firefly.orchestration.executions.*` metrics

### Resilience

- [ ] Configure per-step timeouts (do not rely on global defaults)
- [ ] Set appropriate retry counts and backoff delays
- [ ] Mark critical compensations with `compensationCritical = true`
- [ ] Choose the right compensation policy for your use case
- [ ] Test failure scenarios: step failure, timeout, partial completion

### Dead-Letter Queue

- [ ] Monitor DLQ entry count (`/api/orchestration/dlq/count`)
- [ ] Set up alerts for DLQ growth
- [ ] Implement DLQ entry retry/resolution process
- [ ] Consider a durable `DeadLetterStore` implementation

### Security

- [ ] Restrict REST endpoints with Spring Security
- [ ] Audit who triggers orchestrations (`triggeredBy` parameter)
- [ ] Sanitize step inputs/outputs (no sensitive data in persistence/DLQ)

### Testing

- [ ] Unit test each step method in isolation
- [ ] Integration test full saga/workflow/TCC flows
- [ ] Test compensation flows (verify rollback works)
- [ ] Test resume after failure (workflow suspend/resume)
- [ ] Load test with concurrent executions

---

*Copyright 2024-2026 Firefly Software Solutions Inc. Licensed under the Apache License, Version 2.0.*
