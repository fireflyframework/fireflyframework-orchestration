# Firefly Orchestration — Complete Reference Guide

> **Version:** 26.02.06 | **Java:** 21+ | **Spring Boot:** 3.x | **Reactor:** 3.x

This guide covers every aspect of the Firefly Orchestration module — from first principles through advanced patterns. It serves as both a learning resource and an API reference.

---

## Table of Contents

- [Part I: Foundations](#part-i-foundations)
  - [1. What Is Orchestration?](#1-what-is-orchestration)
  - [2. Architecture Overview](#2-architecture-overview)
  - [3. When to Use Which Pattern](#3-when-to-use-which-pattern)
  - [4. Installation & Setup](#4-installation--setup)
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
  - [22. Retry Policies & Backoff](#22-retry-policies--backoff)
  - [23. Persistence Providers](#23-persistence-providers)
  - [24. Dead-Letter Queue](#24-dead-letter-queue)
  - [25. Observability (Events, Metrics, Tracing)](#25-observability-events-metrics-tracing)
  - [26. REST API](#26-rest-api)
  - [27. Recovery Service](#27-recovery-service)
  - [28. Exception Hierarchy](#28-exception-hierarchy)
- [Part VI: Configuration Reference](#part-vi-configuration-reference)
  - [29. All Configuration Properties](#29-all-configuration-properties)
  - [30. Auto-Configuration Chain](#30-auto-configuration-chain)
- [Part VII: Recipes & Patterns](#part-vii-recipes--patterns)
  - [31. Cross-Pattern Composition](#31-cross-pattern-composition)
  - [32. Testing Your Orchestrations](#32-testing-your-orchestrations)
  - [33. Production Checklist](#33-production-checklist)

---

# Part I: Foundations

## 1. What Is Orchestration?

Orchestration coordinates multi-step business processes where each step may:
- Call a remote service
- Write to a database
- Publish an event
- Invoke another orchestration

The challenge is that any step can fail, and partial completion leaves the system in an inconsistent state. This module provides three patterns to handle this:

| Pattern | Consistency | Isolation | Use Case |
|---------|------------|-----------|----------|
| **Workflow** | Eventual | None | Multi-step processes with dependency ordering |
| **Saga** | Eventual | None | Distributed transactions with compensating actions |
| **TCC** | Strong | Soft-lock | Distributed transactions requiring resource reservation |

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    Your Application                          │
│  @Saga, @Workflow, @Tcc   OR   SagaBuilder, WorkflowBuilder │
└──────────────┬──────────────────────────────┬───────────────┘
               │ register                      │ execute
┌──────────────▼──────────────────────────────▼───────────────┐
│                 Engine Layer                                  │
│  WorkflowEngine    SagaEngine    TccEngine                   │
│       │                │              │                      │
│  WorkflowExecutor  SagaExecutionOrchestrator  TccExecOrch.  │
│                    SagaCompensator                            │
└──────┬────────────────┬───────────────┬─────────────────────┘
       │                │               │
┌──────▼────────────────▼───────────────▼─────────────────────┐
│                   Core Layer                                  │
│  ExecutionContext   StepInvoker   ArgumentResolver            │
│  TopologyBuilder    RetryPolicy   OrchestrationEvents         │
│  ExecutionState     DeadLetterService   RecoveryService       │
└──────┬────────────────────────────────────┬─────────────────┘
       │                                    │
┌──────▼────────────────┐  ┌───────────────▼──────────────────┐
│   Persistence Layer    │  │        Observability Layer        │
│  InMemory (default)    │  │  Logger / Metrics / Tracing      │
│  Redis / Cache / ES    │  │  CompositeOrchestrationEvents    │
└────────────────────────┘  └──────────────────────────────────┘
```

**Key design decisions:**
- **Reactive-first:** Every I/O operation returns `Mono` or `Flux`. No blocking calls.
- **Thread-safe:** `ExecutionContext` uses `ConcurrentHashMap` for all mutable state.
- **Pluggable:** Persistence, observability, and event publishing are port interfaces with swappable adapters.
- **Convention over configuration:** Zero-config defaults with Spring Boot auto-configuration.

## 3. When to Use Which Pattern

### Use **Workflow** when:
- Steps have dependency ordering (DAG) but no rollback needed
- You need suspend/resume/cancel lifecycle management
- Steps are idempotent and can be retried safely
- Example: ETL pipelines, report generation, multi-stage deployments

### Use **Saga** when:
- Each step has a compensating action (undo)
- All-or-nothing semantics are needed across services
- Steps can fail independently and you need automatic rollback
- Example: Order processing, fund transfers, booking systems

### Use **TCC** when:
- You need resource reservation before committing
- Stronger isolation is required (soft-locking)
- Two-phase commitment with explicit try/confirm/cancel
- Example: Seat reservations, inventory holds, account debits

### Decision Flowchart

```
Need to undo on failure?
├── No → WORKFLOW
└── Yes
    ├── Need resource reservation/locking? → TCC
    └── Compensating actions sufficient? → SAGA
```

## 4. Installation & Setup

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

---

# Part II: Workflow Pattern

## 5. Workflow Concepts

A **Workflow** is a directed acyclic graph (DAG) of steps. Steps declare their dependencies, and the engine executes them in topological order — steps in the same layer run in parallel.

```
           ┌──────────┐
           │ validate  │  Layer 0 (runs first)
           └────┬──────┘
           ┌────┴──────┐
     ┌─────▼───┐ ┌─────▼───┐
     │ charge  │ │ reserve │  Layer 1 (run in parallel)
     └────┬────┘ └────┬────┘
          └─────┬─────┘
          ┌─────▼─────┐
          │   ship    │  Layer 2
          └───────────┘
```

**Key concepts:**
- **Step:** A single unit of work — a method that returns `Mono<T>`
- **Dependency:** `dependsOn` declares that a step cannot run until its dependencies complete
- **Layer:** A group of steps with all dependencies satisfied, executed in parallel
- **Topology:** The DAG structure computed by Kahn's algorithm
- **ExecutionState:** Persisted snapshot of the workflow's progress

## 6. Tutorial: Your First Workflow

### Step 1: Create the Workflow Class

```java
import org.fireflyframework.orchestration.workflow.annotation.*;
import org.fireflyframework.orchestration.core.annotation.*;
import reactor.core.publisher.Mono;
import java.util.Map;

@Workflow(name = "OrderProcessing", version = "1.0",
          description = "Processes a customer order end-to-end")
public class OrderProcessingWorkflow {

    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final ShippingService shippingService;

    // Constructor injection — Spring manages this bean
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
        // Validation logic here
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
    public void onError(@Input Throwable error) {
        log.error("Order processing failed", error);
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
        System.out.println("Status: " + state.status());           // COMPLETED or FAILED
        System.out.println("Correlation: " + state.correlationId());
        System.out.println("Duration: " + Duration.between(
            state.startedAt(), state.updatedAt()).toMillis() + "ms");
        state.stepResults().forEach((stepId, result) ->
            System.out.println("  " + stepId + " → " + result));
    });
```

## 7. Workflow Builder DSL

For cases where annotation scanning is not practical (e.g., dynamically composed workflows, testing):

```java
import org.fireflyframework.orchestration.workflow.builder.WorkflowBuilder;

WorkflowDefinition def = new WorkflowBuilder("DynamicPipeline")
    .description("A pipeline built at runtime")
    .version("2.0")
    .triggerMode(TriggerMode.SYNC)
    .timeout(60_000L)
    .retryPolicy(RetryPolicy.DEFAULT)
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
        .add()
    .step("load")
        .name("Load Data")
        .dependsOn("transform")
        .handler(loaderBean, loaderBean.getClass()
            .getMethod("load", Map.class))
        .retryPolicy(new RetryPolicy(5, Duration.ofSeconds(2),
            Duration.ofMinutes(1), 2.0, 0.1))
        .add()
    .build();

// Register it with the engine
workflowEngine.registerWorkflow(def);

// Execute it
workflowEngine.startWorkflow("DynamicPipeline", Map.of("source", "s3://data"))
    .subscribe(state -> log.info("Pipeline result: {}", state.status()));
```

## 8. Workflow Engine API

### `WorkflowEngine` — Full Method Reference

| Method | Description |
|--------|-------------|
| `startWorkflow(workflowId, input)` | Start a workflow with auto-generated correlation ID |
| `startWorkflow(workflowId, input, correlationId, triggeredBy, dryRun)` | Start with explicit options |
| `cancelWorkflow(correlationId)` | Cancel a running workflow (rejects if already terminal) |
| `suspendWorkflow(correlationId, reason)` | Suspend a running workflow with a reason |
| `suspendWorkflow(correlationId)` | Suspend with default reason ("User requested") |
| `resumeWorkflow(correlationId)` | Resume a suspended workflow from where it left off |
| `findByStatus(status)` | Query workflows by status |
| `findByCorrelationId(correlationId)` | Look up a specific execution |
| `registerWorkflow(definition)` | Register a builder-created definition |

### Return Types

All methods return reactive types:
- `Mono<ExecutionState>` — single execution state snapshot
- `Flux<ExecutionState>` — stream of execution states
- `Mono<Optional<ExecutionState>>` — optional lookup

## 9. Workflow Lifecycle (Suspend/Resume/Cancel)

Workflows support full lifecycle management:

```
PENDING → RUNNING → COMPLETED
                  → FAILED
          RUNNING → SUSPENDED → RUNNING (resume)
          RUNNING → CANCELLED
```

### Suspend and Resume

```java
// Start a long-running workflow
Mono<ExecutionState> workflow = workflowEngine.startWorkflow("ETLPipeline", input);

// Suspend it (e.g., during maintenance)
workflowEngine.suspendWorkflow(correlationId, "Scheduled maintenance")
    .subscribe(state -> log.info("Suspended: {}", state.status()));

// Resume it later — picks up from where it left off
// Already-completed steps are skipped
workflowEngine.resumeWorkflow(correlationId)
    .subscribe(state -> log.info("Resumed and completed: {}", state.status()));
```

### Cancel

```java
workflowEngine.cancelWorkflow(correlationId)
    .subscribe(state -> log.info("Cancelled: {}", state.status()));
    // Throws IllegalStateException if already COMPLETED/FAILED/CANCELLED
```

---

# Part III: Saga Pattern

## 10. Saga Concepts

A **Saga** is a sequence of local transactions where each step has a **compensating action**. If any step fails, the saga engine automatically runs compensations for all completed steps in reverse order.

```
Step 1: Reserve Inventory  →  Compensation: Cancel Reservation
Step 2: Charge Payment     →  Compensation: Refund Payment
Step 3: Ship Order         →  (no compensation — final step)

If Step 2 fails:
  1. Run compensation for Step 1 (Cancel Reservation)
  2. Report failure with compensation results
```

**Key concepts:**
- **Step:** A forward action that may succeed or fail
- **Compensation:** An undo action that reverses a completed step
- **Compensation Policy:** Strategy for running compensations (sequential, parallel, circuit breaker)
- **StepInputs:** Per-step input data (static or lazy-resolved)
- **ExpandEach:** Fan-out — clone a step once per item in a collection

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

    // Compensation method — name matches @SagaStep.compensate
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

    // No compensation for the credit step — it's the final step
    // If credit fails, debit is automatically compensated
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
            // All steps completed successfully
            DebitResult debit = result.resultOf("debit", DebitResult.class)
                .orElseThrow();
            CreditResult credit = result.resultOf("credit", CreditResult.class)
                .orElseThrow();
            log.info("Transfer complete: {} → {}", debit, credit);
        } else {
            // Some step failed — compensations have already run
            log.error("Transfer failed: {}", result.error().orElse(null));
            log.info("Failed steps: {}", result.failedSteps());
            log.info("Compensated steps: {}", result.compensatedSteps());
        }
    });
```

## 12. Saga Builder DSL

The programmatic builder provides a fluent API for constructing sagas without annotations:

```java
SagaDefinition def = SagaBuilder.saga("OrderSaga")
    .step("reserve")
        .retry(3)
        .backoffMs(500)
        .timeoutMs(10_000)
        .jitter()
        .jitterFactor(0.2)
        .compensationRetry(2)
        .compensationTimeout(Duration.ofSeconds(5))
        .compensationCritical(true)
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
        // No compensation — notifications can't be unsent
        .add()
    .build();
```

### Handler Variants

| Method | Signature | Use When |
|--------|-----------|----------|
| `handler(StepHandler)` | `(I, ExecutionContext) → Mono<O>` | Full interface implementation |
| `handler(BiFunction)` | `(I, ExecutionContext) → Mono<O>` | Lambda with input + context |
| `handlerInput(Function)` | `(I) → Mono<O>` | Lambda needing only the input |
| `handlerCtx(Function)` | `(ExecutionContext) → Mono<O>` | Lambda needing only the context |
| `handler(Supplier)` | `() → Mono<O>` | Lambda needing no arguments |

### Compensation Variants

| Method | Signature |
|--------|-----------|
| `compensation(BiFunction)` | `(Object, ExecutionContext) → Mono<Void>` |
| `compensationCtx(Function)` | `(ExecutionContext) → Mono<Void>` |
| `compensation(Supplier)` | `() → Mono<Void>` |

## 13. Compensation Policies

When a saga step fails, the engine runs compensations for all completed steps. The **compensation policy** determines how those compensations are executed.

Set the policy globally:

```yaml
firefly:
  orchestration:
    saga:
      compensation-policy: STRICT_SEQUENTIAL   # default
```

### STRICT_SEQUENTIAL (Default)

Compensations run one at a time in **reverse completion order** (last completed → first completed). If compensation A fails, compensation B still runs.

```
Step 1 ✓ → Step 2 ✓ → Step 3 ✗ (failed)
Compensate Step 2 → Compensate Step 1
```

**Best for:** Most applications. Predictable, debuggable, safe.

### GROUPED_PARALLEL

Steps in the same DAG layer are compensated in parallel; layers are compensated in reverse order.

```
Layer 2: [Step3] ✓     → Compensate Step3
Layer 1: [Step2a, Step2b] ✓  → Compensate Step2a || Step2b  (parallel)
Layer 0: [Step1] ✓     → Compensate Step1
```

**Best for:** Sagas with many independent steps in the same layer. Faster compensation.

### RETRY_WITH_BACKOFF

Like STRICT_SEQUENTIAL but each compensation is retried with exponential backoff. Failed compensations are logged and skipped (best-effort).

```
Compensate Step 2 (attempt 1: fail, attempt 2: fail, attempt 3: success)
Compensate Step 1 (attempt 1: success)
```

**Best for:** Compensations that may fail transiently (network issues, service restarts).

### CIRCUIT_BREAKER

Like RETRY_WITH_BACKOFF, but tracks compensation failures. If a step marked `compensationCritical = true` fails compensation, the **circuit opens** and all remaining compensations are skipped.

```java
.step("critical")
    .compensationCritical(true)   // triggers circuit breaker on failure
    .handler(...)
    .compensation(...)
    .add()
```

**Best for:** Sagas where some compensations are critical for data integrity and subsequent compensations shouldn't run if a critical one fails.

### BEST_EFFORT_PARALLEL

All compensations run in parallel with no ordering guarantee. Failures are logged but don't stop other compensations.

```
Compensate Step 1 || Step 2 || Step 3  (all parallel, all best-effort)
```

**Best for:** Performance-critical sagas where compensation ordering doesn't matter and individual failures are acceptable.

## 14. Saga Fan-Out (ExpandEach)

`ExpandEach` dynamically clones a saga step for each item in a collection. This is useful for processing a batch of items where each needs its own saga step instance.

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
Original: reserve → charge → ship
Expanded: reserve:SKU-001, reserve:SKU-002, reserve:SKU-003 → charge → ship
```

Each cloned step runs independently. Downstream steps (`charge`) wait for all expanded steps to complete.

You can provide a custom ID suffix function:
```java
ExpandEach.of(items)                    // uses #0, #1, #2 suffixes
ExpandEach.of(items, item -> item.id()) // uses :id suffix
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
result.error();                 // Optional<Throwable> — primary error if failed
result.headers();               // Map<String, String> — execution headers

// Per-step inspection
result.steps();                 // Map<String, StepOutcome> — all step outcomes
result.failedSteps();           // List<String> — IDs of failed steps
result.compensatedSteps();      // List<String> — IDs of compensated steps
result.firstErrorStepId();      // Optional<String> — first step that failed

// Type-safe result extraction
Optional<DebitResult> debit = result.resultOf("debit", DebitResult.class);

// StepOutcome fields
StepOutcome outcome = result.steps().get("debit");
outcome.status();               // StepStatus (DONE, FAILED, COMPENSATED, etc.)
outcome.attempts();             // int — number of execution attempts
outcome.latencyMs();            // long — execution duration in ms
outcome.result();               // Object — step return value
outcome.error();                // Throwable — step error (null if succeeded)
outcome.compensated();          // boolean — was compensation run?
outcome.compensationResult();   // Object — compensation return value
outcome.compensationError();    // Throwable — compensation error (null if succeeded)
```

---

# Part IV: TCC Pattern

## 16. TCC Concepts

**TCC (Try-Confirm-Cancel)** is a two-phase distributed transaction protocol. Each participant implements three operations:

| Phase | Purpose | When It Runs |
|-------|---------|--------------|
| **Try** | Reserve resources, validate preconditions | Always runs first |
| **Confirm** | Commit the reservation | Only if ALL tries succeed |
| **Cancel** | Release the reservation | Only if ANY try fails |

```
Participant A: Try ✓  Participant B: Try ✓  → Confirm A, Confirm B
Participant A: Try ✓  Participant B: Try ✗  → Cancel A (B was never reserved)
```

**Key difference from Saga:** In a Saga, the forward action is committed immediately and must be compensated on failure. In TCC, the Try phase only *reserves* resources — nothing is committed until the Confirm phase.

**Key concepts:**
- **Participant:** A service that implements Try/Confirm/Cancel for a resource
- **Order:** Participants execute their Try phase in defined order
- **Optional:** An optional participant's Try failure doesn't trigger Cancel for others
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
            // Reserve the funds (soft-lock, not committed)
            return accountService.holdFunds(
                request.fromAccount(), request.amount());
        }

        @ConfirmMethod(timeoutMs = 5000, retry = 3)
        public Mono<Void> confirmDebit(@FromTry("debit") String holdId) {
            // Commit the hold — funds are now debited
            return accountService.commitHold(holdId);
        }

        @CancelMethod(timeoutMs = 5000, retry = 3)
        public Mono<Void> cancelDebit(@FromTry("debit") String holdId) {
            // Release the hold — funds are available again
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
                // All participants confirmed — transaction committed
                log.info("Transfer confirmed: {}", result.correlationId());
                Optional<String> holdId = result.tryResultOf("debit", String.class);
            }
            case CANCELED -> {
                // A try failed — all successful tries were canceled
                log.warn("Transfer canceled: participant={}, error={}",
                    result.failedParticipantId().orElse("unknown"),
                    result.error().map(Throwable::getMessage).orElse("none"));
            }
            case FAILED -> {
                // Confirm or cancel phase failed — needs manual intervention
                log.error("Transfer failed: phase={}, participant={}",
                    result.failedPhase().orElse(null),
                    result.failedParticipantId().orElse("unknown"));
            }
        }
    });
```

## 18. TCC Builder DSL

```java
TccDefinition def = TccBuilder.tcc("TransferFunds", 30_000L, true, 3, 500L)
    .participant("debit")
        .order(0)
        .tryTimeoutMs(5_000L)
        .tryRetry(2)
        .tryBackoffMs(200L)
        .confirmTimeoutMs(5_000L)
        .confirmRetry(3)
        .cancelTimeoutMs(5_000L)
        .cancelRetry(3)
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
        .optional(false)
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

### Convention-Based Handler

You can also pass a bean with `doTry`, `doConfirm`, `doCancel` methods:

```java
public class DebitHandler {
    public Mono<String> doTry(Object input, ExecutionContext ctx) { ... }
    public Mono<Void> doConfirm(Object tryResult, ExecutionContext ctx) { ... }
    public Mono<Void> doCancel(Object tryResult, ExecutionContext ctx) { ... }
}

TccBuilder.tcc("Transfer")
    .participant("debit")
        .handler(new DebitHandler())    // methods resolved by convention
        .add()
    .build();
```

### Optional Participants

An optional participant's Try failure does not trigger Cancel for other participants. The saga continues as if the optional participant was not present.

```java
.participant("loyalty-points")
    .optional(true)    // failure here won't cancel debit/credit
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
result.failedPhase();               // Optional<TccPhase> — TRY, CONFIRM, or CANCEL

// Per-participant outcomes
result.participants();              // Map<String, ParticipantOutcome>
result.tryResultOf("debit", String.class);   // Optional<T>

// ParticipantOutcome fields
ParticipantOutcome outcome = result.participants().get("debit");
outcome.participantId();            // "debit"
outcome.tryResult();                // Object — Try phase return value
outcome.trySucceeded();             // boolean
outcome.confirmSucceeded();         // boolean
outcome.cancelExecuted();           // boolean
outcome.error();                    // Throwable (null if no error)
outcome.attempts();                 // int — total attempts
outcome.latencyMs();                // long
```

---

# Part V: Core Infrastructure

## 20. ExecutionContext

`ExecutionContext` is the thread-safe state carrier shared across all steps in an execution. It holds variables, headers, step results, step statuses, and pattern-specific state.

### Creating Contexts

```java
// Typically the engine creates these, but you can create them manually:
ExecutionContext ctx = ExecutionContext.forWorkflow("correlation-123", "MyWorkflow");
ExecutionContext ctx = ExecutionContext.forWorkflow("correlation-123", "MyWorkflow", true); // dry run
ExecutionContext ctx = ExecutionContext.forSaga(null, "MySaga");       // null = auto-generate ID
ExecutionContext ctx = ExecutionContext.forTcc(null, "MyTcc");
```

### Variables

Variables are execution-wide key-value pairs. They survive across steps and are persisted with the execution state.

```java
ctx.putVariable("orderId", "ORD-123");
String orderId = ctx.getVariableAs("orderId", String.class);
ctx.removeVariable("tempData");
Map<String, Object> all = ctx.getVariables();   // unmodifiable view
```

### Headers

Headers are string key-value metadata (like HTTP headers):

```java
ctx.putHeader("X-Tenant-Id", "acme");
String tenant = ctx.getHeader("X-Tenant-Id");
Map<String, String> all = ctx.getHeaders();     // unmodifiable view
```

### Step Results

Each step's return value is stored by step ID:

```java
ctx.putResult("validate", validationResult);
Object result = ctx.getResult("validate");
Map<String, Object> all = ctx.getStepResults(); // unmodifiable view
```

### Step Status Tracking

```java
ctx.setStepStatus("validate", StepStatus.RUNNING);
StepStatus status = ctx.getStepStatus("validate"); // PENDING if not set
int attempts = ctx.incrementAttempts("validate");   // returns new count
ctx.setStepLatency("validate", 150L);               // milliseconds
```

### Idempotency Keys

Prevent duplicate step execution:

```java
boolean isNew = ctx.addIdempotencyKey("debit:ORD-123");
if (ctx.hasIdempotencyKey("debit:ORD-123")) {
    // Already processed
}
```

## 21. Parameter Injection Annotations

The `ArgumentResolver` automatically injects values into step method parameters based on annotations:

| Annotation | What It Injects | Example |
|------------|----------------|---------|
| `@Input` | The step's input object | `@Input OrderRequest req` |
| `@Input("key")` | A specific key from a Map input | `@Input("orderId") String id` |
| `@FromStep("stepId")` | The result of a completed step | `@FromStep("validate") Map result` |
| `@FromCompensationResult("stepId")` | Compensation result of a step | `@FromCompensationResult("debit") Object r` |
| `@CompensationError("stepId")` | Compensation error of a step | `@CompensationError("debit") Throwable err` |
| `@Header("name")` | A single header value | `@Header("X-Tenant") String tenant` |
| `@Headers` | The full headers map | `@Headers Map<String,String> headers` |
| `@Variable("name")` | A single context variable | `@Variable("orderId") String id` |
| `@Variables` | The full variables map | `@Variables Map<String,Object> vars` |
| `@Required` | Enforces non-null | `@Required @FromStep("x") Object r` |
| `@FromTry("participantId")` | TCC Try-phase result | `@FromTry("debit") String holdId` |

### `@SetVariable` (Method-Level)

Store the method's return value as a named context variable:

```java
@SagaStep(id = "lookup")
@SetVariable("customerId")
public Mono<String> lookupCustomer(@Input OrderRequest req) {
    return customerService.findId(req.email());
}

// Later steps can access it:
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

## 22. Retry Policies & Backoff

### RetryPolicy Record

```java
public record RetryPolicy(
    int maxAttempts,           // total attempts (1 = no retry)
    Duration initialDelay,     // delay before first retry
    Duration maxDelay,         // cap on exponential growth
    double multiplier,         // backoff multiplier (2.0 = doubling)
    double jitterFactor        // 0.0-1.0, randomizes delay
) {
    // Predefined policies
    static final RetryPolicy DEFAULT = new RetryPolicy(3, 1s, 5m, 2.0, 0.1);
    static final RetryPolicy NO_RETRY = new RetryPolicy(1, 0, 0, 1.0, 0.0);
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
Jittered delay = 2s ± (2s * 0.1) = between 1.8s and 2.2s
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

## 23. Persistence Providers

### Provider Selection

```yaml
firefly:
  orchestration:
    persistence:
      provider: in-memory    # default
      # provider: redis      # requires ReactiveRedisTemplate
      # provider: cache      # requires fireflyframework-cache CacheAdapter
      # provider: event-sourced  # requires fireflyframework-eventsourcing EventStore
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

| Feature | InMemory | Redis | Cache | EventSourced |
|---------|----------|-------|-------|--------------|
| Persistence | Process lifetime | Durable | Adapter-dependent | Durable |
| Query support | Full | Full | Full | Limited |
| Cleanup | Supported | Supported | Supported | No-op |
| Clustering | No | Yes | Adapter-dependent | Yes |
| Performance | Fastest | Fast | Varies | Write-optimized |

## 24. Dead-Letter Queue

Failed executions are automatically sent to the DLQ for later inspection and retry.

### Configuration

```yaml
firefly:
  orchestration:
    dlq:
      enabled: true    # default
```

### DeadLetterEntry Record

```java
record DeadLetterEntry(
    String id,                    // unique DLQ entry ID
    String executionName,         // saga/workflow/tcc name
    String correlationId,         // execution correlation ID
    ExecutionPattern pattern,     // WORKFLOW, SAGA, or TCC
    String failedStepId,          // which step failed
    ExecutionStatus status,       // FAILED
    String errorMessage,          // error description
    String errorType,             // exception class name
    Map<String, Object> metadata, // additional context
    int retryCount,               // number of retry attempts
    Instant createdAt,
    Instant updatedAt
)
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
    Mono<Void> delete(String id);
    Flux<DeadLetterEntry> findAll();
    Flux<DeadLetterEntry> findByExecutionName(String name);
    Mono<Long> count();
    Mono<Void> markRetried(String id);
}
```

## 25. Observability (Events, Metrics, Tracing)

### OrchestrationEvents Interface

All orchestration lifecycle events flow through this interface. The default `CompositeOrchestrationEvents` fans out to all registered delegates.

**Lifecycle events (all patterns):**

| Event | When It Fires |
|-------|---------------|
| `onStart` | Execution begins |
| `onStepStarted` | A step begins execution |
| `onStepSuccess` | A step completes successfully |
| `onStepFailed` | A step fails after all retries |
| `onStepSkipped` | A step is skipped (condition not met) |
| `onCompleted` | Execution completes (success or failure) |

**Saga-specific:**

| Event | When It Fires |
|-------|---------------|
| `onCompensationStarted` | Compensation phase begins |
| `onStepCompensated` | A step's compensation succeeds |
| `onStepCompensationFailed` | A step's compensation fails |

**TCC-specific:**

| Event | When It Fires |
|-------|---------------|
| `onPhaseStarted` | TRY/CONFIRM/CANCEL phase begins |
| `onPhaseCompleted` | A phase completes successfully |
| `onPhaseFailed` | A phase fails |
| `onParticipantStarted` | A participant begins a phase |
| `onParticipantSuccess` | A participant completes a phase |
| `onParticipantFailed` | A participant fails a phase |

**Workflow-specific:**

| Event | When It Fires |
|-------|---------------|
| `onWorkflowSuspended` | A workflow is suspended |
| `onWorkflowResumed` | A workflow is resumed |

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

The `CompositeOrchestrationEvents` automatically picks up all `OrchestrationEvents` beans and fans out to each one with per-delegate error isolation.

### Metrics (Micrometer)

Enabled automatically when a `MeterRegistry` is on the classpath:

```yaml
firefly:
  orchestration:
    metrics:
      enabled: true   # default when MeterRegistry present
```

Metrics published:

| Metric | Type | Tags |
|--------|------|------|
| `orchestration.execution.started` | Counter | name, pattern |
| `orchestration.execution.completed` | Counter | name, pattern, success |
| `orchestration.execution.duration` | Timer | name, pattern |
| `orchestration.step.started` | Counter | name, stepId |
| `orchestration.step.completed` | Counter | name, stepId, success |
| `orchestration.step.duration` | Timer | name, stepId |
| `orchestration.compensation.started` | Counter | name |
| `orchestration.compensation.completed` | Counter | name, stepId |

### Tracing (Micrometer Observation)

Enabled automatically when an `ObservationRegistry` is on the classpath:

```yaml
firefly:
  orchestration:
    tracing:
      enabled: true   # default when ObservationRegistry present
```

Creates spans for:
- Overall execution
- Each step execution
- Compensation phases
- TCC phase transitions

## 26. REST API

### Endpoints

Enabled by default when running a reactive web application:

```yaml
firefly:
  orchestration:
    rest:
      enabled: true    # default
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
      enabled: true    # default
```

Reports `UP` when persistence is healthy, `DOWN` otherwise. Visible at `/actuator/health`.

## 27. Recovery Service

Detects and reports stale executions (started but never completed):

```yaml
firefly:
  orchestration:
    recovery:
      enabled: true
      stale-threshold: 1h    # executions older than this are considered stale
    persistence:
      retention-period: 7d   # completed executions older than this are cleaned up
      cleanup-interval: 1h   # how often cleanup runs
```

### Programmatic Access

```java
@Autowired RecoveryService recoveryService;

// Find stale executions
recoveryService.findStaleExecutions()
    .subscribe(state -> log.warn("Stale execution: {}", state.correlationId()));

// Clean up old completed executions
recoveryService.cleanupCompletedExecutions(Duration.ofDays(30))
    .subscribe(count -> log.info("Cleaned up {} executions", count));
```

## 28. Exception Hierarchy

All orchestration exceptions extend the sealed `OrchestrationException`:

```
OrchestrationException (sealed)
├── ExecutionNotFoundException       — workflow/saga/tcc ID not found
├── StepExecutionException           — step method invocation failed
│     └── getStepId()                — which step failed
├── TopologyValidationException      — DAG has cycles, missing deps, etc.
├── DuplicateDefinitionException     — two definitions share the same name
├── ExecutionTimeoutException        — execution or step timed out
├── CompensationException            — saga compensation step failed
└── TccPhaseException                — TCC Try/Confirm/Cancel phase error
      └── getPhase()                 — which phase (TRY, CONFIRM, CANCEL)
```

Each exception carries an error code (e.g., `ORCHESTRATION_STEP_EXECUTION_ERROR`) for programmatic error handling.

---

# Part VI: Configuration Reference

## 29. All Configuration Properties

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
| `firefly.orchestration.persistence.key-ttl` | Duration | — | Optional Redis key TTL |
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

## 30. Auto-Configuration Chain

The module's auto-configurations load in a specific order to ensure dependencies are available:

### Phase 1: Persistence (before everything else)

`OrchestrationPersistenceAutoConfiguration` creates the persistence provider based on classpath and properties. If no specific provider matches, the fallback `InMemoryPersistenceProvider` is created in Phase 2.

### Phase 2: Core Infrastructure

`OrchestrationAutoConfiguration` creates:
1. `ArgumentResolver` — parameter injection
2. `StepInvoker` — reflective method invocation with retry
3. `OrchestrationLoggerEvents` — SLF4J event logging
4. `OrchestrationEvents` — composite fan-out (wraps all event delegates)
5. `ExecutionPersistenceProvider` — InMemory fallback if none exists
6. `DeadLetterStore` + `DeadLetterService` — DLQ
7. `OrchestrationEventPublisher` — domain event publishing (no-op default)
8. `OrchestrationScheduler` — scheduled task executor
9. `RecoveryService` — stale execution detection

### Phase 3: Pattern Engines (after core)

Each enabled pattern creates its registry, orchestrator, and engine:
- `WorkflowAutoConfiguration` → `WorkflowRegistry`, `WorkflowExecutor`, `WorkflowEngine`
- `SagaAutoConfiguration` → `SagaRegistry`, `SagaExecutionOrchestrator`, `SagaCompensator`, `SagaEngine`
- `TccAutoConfiguration` → `TccRegistry`, `TccExecutionOrchestrator`, `TccEngine`

### Phase 4: Extensions (after core)

- `OrchestrationMetricsAutoConfiguration` — if `MeterRegistry` on classpath
- `OrchestrationTracingAutoConfiguration` — if `ObservationRegistry` on classpath
- `OrchestrationResilienceAutoConfiguration` — if `CircuitBreakerRegistry` on classpath
- `OrchestrationRestAutoConfiguration` — if reactive web application

---

# Part VII: Recipes & Patterns

## 31. Cross-Pattern Composition

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

                // If saga succeeded, run TCC for payment
                return tccEngine.execute("PaymentTcc",
                    TccInputs.of("charge", order))
                    .map(tccResult -> {
                        if (tccResult.isConfirmed()) {
                            return sagaResult; // both succeeded
                        }
                        // TCC failed — you may want to compensate the saga
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

## 32. Testing Your Orchestrations

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

### Integration Testing with Real Engine

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

    var registry = new SagaRegistry();
    registry.register(def);
    var events = new OrchestrationEvents() {};
    var invoker = new StepInvoker(new ArgumentResolver());
    var orchestrator = new SagaExecutionOrchestrator(invoker, events);
    var compensator = new SagaCompensator(events, CompensationPolicy.STRICT_SEQUENTIAL, invoker);
    var engine = new SagaEngine(registry, events, CompensationPolicy.STRICT_SEQUENTIAL,
        orchestrator, null, null, compensator);

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

    // ... create engine as above ...

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

## 33. Production Checklist

Before going to production, verify:

### Persistence
- [ ] Switch from `in-memory` to `redis` or another durable provider
- [ ] Configure `retention-period` and `cleanup-interval`
- [ ] Test persistence failover behavior

### Observability
- [ ] Enable metrics (`firefly.orchestration.metrics.enabled=true`)
- [ ] Enable tracing (`firefly.orchestration.tracing.enabled=true`)
- [ ] Add custom `OrchestrationEvents` listener for alerting
- [ ] Set up dashboards for `orchestration.execution.*` metrics

### Resilience
- [ ] Configure per-step timeouts (don't rely on global defaults)
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
