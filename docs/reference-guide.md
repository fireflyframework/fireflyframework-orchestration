# Firefly Orchestration — Reference Guide

> **Version:** 26.02.06 | **Java:** 25+ | **Spring Boot:** 3.x | **Reactor:** 3.x

This is the definitive reference for `fireflyframework-orchestration`. It covers every annotation, builder method, engine API, configuration property, and infrastructure component. Every code snippet, attribute table, and method signature is verified against the source code.

---

## Table of Contents

- [Part I: Foundations](#part-i-foundations)
  - [§1 Introduction & Quick Start](#1-introduction--quick-start)
  - [§2 Architecture Overview](#2-architecture-overview)
  - [§3 Pattern Selection Guide](#3-pattern-selection-guide)
- [Part II: Workflow Pattern](#part-ii-workflow-pattern)
  - [§4 Workflow Annotation Reference](#4-workflow-annotation-reference)
  - [§5 Workflow Tutorial](#5-workflow-tutorial)
  - [§6 Workflow Lifecycle Management](#6-workflow-lifecycle-management)
  - [§7 Signals and Timers](#7-signals-and-timers)
  - [§8 Workflow Builder DSL](#8-workflow-builder-dsl)
  - [§9 WorkflowEngine API](#9-workflowengine-api)
  - [§10 Child Workflows](#10-child-workflows)
  - [§11 Search Attributes & Queries](#11-search-attributes--queries)
- [Part III: Saga Pattern](#part-iii-saga-pattern)
  - [§12 Saga Annotation Reference](#12-saga-annotation-reference)
  - [§13 Saga Tutorial](#13-saga-tutorial)
  - [§14 Saga Compensation Deep Dive](#14-saga-compensation-deep-dive)
  - [§15 ExpandEach (Fan-Out)](#15-expandeach-fan-out)
  - [§16 Saga Builder DSL](#16-saga-builder-dsl)
  - [§17 SagaEngine API](#17-sagaengine-api)
  - [§18 SagaResult](#18-sagaresult)
- [Part IV: TCC Pattern](#part-iv-tcc-pattern)
  - [§19 TCC Annotation Reference](#19-tcc-annotation-reference)
  - [§20 TCC Tutorial](#20-tcc-tutorial)
  - [§21 TCC Phases & Timeout/Retry](#21-tcc-phases--timeoutretry)
  - [§22 TCC Builder DSL](#22-tcc-builder-dsl)
  - [§23 TccEngine API](#23-tccengine-api)
  - [§24 TccResult](#24-tccresult)
- [Part V: Core Infrastructure](#part-v-core-infrastructure)
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
- [Part VI: Configuration](#part-vi-configuration)
  - [§38 Configuration Properties](#38-configuration-properties)
  - [§39 Auto-Configuration Chain](#39-auto-configuration-chain)
  - [§40 Spring Boot Integration](#40-spring-boot-integration)
- [Part VII: Recipes & Production](#part-vii-recipes--production)
  - [§41 Recipe: Composing Patterns](#41-recipe-composing-patterns)
  - [§42 Recipe: Testing Orchestrations](#42-recipe-testing-orchestrations)
  - [§43 Recipe: Error Handling](#43-recipe-error-handling)
  - [§44 Recipe: Event-Driven Architecture](#44-recipe-event-driven-architecture)
  - [§45 Production Checklist](#45-production-checklist)
  - [§46 Resilience Patterns](#46-resilience-patterns)
  - [§47 Continue-as-New](#47-continue-as-new)
  - [§48 FAQ & Troubleshooting](#48-faq--troubleshooting)

---

# Part I: Foundations

## 1. Introduction & Quick Start

### What Is Firefly Orchestration?

Firefly Orchestration is a reactive, annotation-driven framework for coordinating multi-step business processes in Spring Boot applications. It provides three complementary execution patterns — **Workflow**, **Saga**, and **TCC** — unified by a shared core layer for persistence, observability, retry, and event integration.

Each pattern addresses a different class of distributed coordination problem:

| Pattern      | Consistency | Isolation | Rollback Mechanism     | Primary Use Case                            |
|--------------|-------------|-----------|------------------------|---------------------------------------------|
| **Workflow** | Eventual    | None      | None (or compensatable)| Multi-step processes with dependency ordering|
| **Saga**     | Eventual    | None      | Compensating actions   | Distributed transactions with undo semantics|
| **TCC**      | Strong      | Soft-lock | Cancel (release)       | Resource reservation before commit          |

### Key Characteristics

- **Reactive-first:** Every I/O operation returns `Mono<T>` or `Flux<T>`. No blocking calls anywhere in the execution path.
- **Thread-safe:** `ExecutionContext` uses `ConcurrentHashMap` for all mutable state.
- **Annotation-driven or programmatic:** Define orchestrations with `@Saga`, `@Workflow`, `@Tcc` annotations, or use fluent builder DSLs (`SagaBuilder`, `WorkflowBuilder`, `TccBuilder`).
- **Pluggable infrastructure:** Persistence, event publishing, and observability are port interfaces with swappable adapters.
- **Convention over configuration:** Zero-config defaults via Spring Boot auto-configuration. All three patterns are enabled out of the box.

### Requirements

- Java 25+
- Spring Boot 3.x
- Project Reactor 3.x

### Maven Dependency

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-orchestration</artifactId>
    <version>26.02.06</version>
</dependency>
```

### Quick Start: Hello World Saga

Define a saga with a single step:

```java
@Saga(name = "HelloSaga")
public class HelloSaga {

    @SagaStep(id = "greet")
    public Mono<String> greet(@Input String name) {
        return Mono.just("Hello, " + name + "!");
    }

    @OnSagaComplete
    public void done(SagaResult result) {
        result.resultOf("greet", String.class)
            .ifPresent(msg -> System.out.println(msg));
    }
}
```

Execute it:

```java
sagaEngine.execute("HelloSaga", StepInputs.of("greet", "World"))
    .subscribe(result -> {
        assert result.isSuccess();
        // prints: Hello, World!
    });
```

### Defaults Out of the Box

No configuration is required for development. The module starts with:

- All three patterns enabled (Workflow, Saga, TCC)
- `InMemoryPersistenceProvider` for execution state
- SLF4J structured logging via `OrchestrationLoggerEvents`
- Dead-letter queue enabled with `InMemoryDeadLetterStore`
- Scheduler with 4 threads
- No-op event publisher (replace with Kafka/RabbitMQ adapter for production)
- Recovery service enabled with 1-hour stale threshold

---

## 2. Architecture Overview

The module is organized into four layers. Your application defines orchestrations using annotations or builders. The engine layer routes execution through pattern-specific orchestrators. The core layer provides shared infrastructure. The persistence and observability layers are pluggable via Spring beans.

```
+---------------------------------------------------------------------+
|                         Your Application                            |
|  @Saga  @Workflow  @Tcc     OR     SagaBuilder  WorkflowBuilder     |
|                                    TccBuilder                       |
+-----------------+---------------------------------+-----------------+
                  |  register                       |  execute
+-----------------v---------------------------------v-----------------+
|                          Engine Layer                               |
|   WorkflowEngine         SagaEngine           TccEngine             |
|   WorkflowExecutor       SagaCompensator                            |
|   SagaExecutionOrchestrator   TccExecutionOrchestrator              |
+---------+-----------------------------+--------------------+--------+
          |                             |                    |
+---------v-----------------------------v--------------------v--------+
|                           Core Layer                                |
|   ExecutionContext    StepInvoker     ArgumentResolver              |
|   TopologyBuilder     RetryPolicy    OrchestrationEvents            |
|   ExecutionState      DeadLetterService    RecoveryService          |
|   EventGateway        OrchestrationScheduler                        |
+---------+--------------------------------+-------------------+------+
          |                                |                   |
+---------v-----------------+   +----------v-----------+  +----v------+
|   Persistence Layer       |   | Observability Layer  |  |  Events   |
|  InMemory (default)       |   | LoggerEvents         |  |  Gateway  |
|  Redis / Cache / ES       |   | Metrics / Tracing    |  |  Publish  |
+---------------------------+   +----------------------+  +-----------+
```

### Layer Components

**Application Layer** — Your code:
- `@Saga`, `@SagaStep`, `@CompensationSagaStep` — annotated saga classes
- `@Workflow`, `@WorkflowStep`, `@WaitForSignal`, `@WaitForTimer` — annotated workflow classes
- `@Tcc`, `@TccParticipant`, `@TryMethod`, `@ConfirmMethod`, `@CancelMethod` — annotated TCC classes
- `SagaBuilder`, `WorkflowBuilder`, `TccBuilder` — programmatic builders

**Engine Layer** — Pattern-specific execution:
- `WorkflowEngine` — start, suspend, resume, cancel workflows
- `WorkflowExecutor` — executes steps layer-by-layer with signal/timer support
- `SagaEngine` — execute sagas by name or definition
- `SagaExecutionOrchestrator` — orchestrates step execution across topology layers
- `SagaCompensator` — runs compensations per configured `CompensationPolicy`
- `TccEngine` — execute TCC transactions by name or definition
- `TccExecutionOrchestrator` — orchestrates try/confirm/cancel phases

**Core Layer** — Shared infrastructure:
- `ExecutionContext` — thread-safe per-execution state (variables, headers, results, statuses)
- `StepInvoker` — invokes step methods with retry, backoff, jitter, timeout
- `ArgumentResolver` — resolves method parameters from annotations (`@Input`, `@FromStep`, etc.)
- `TopologyBuilder` — computes DAG layers using Kahn's algorithm
- `RetryPolicy` — configurable retry with exponential backoff and jitter
- `EventGateway` — routes inbound events to orchestration executors
- `OrchestrationScheduler` — cron, fixed-rate, and fixed-delay scheduling
- `DeadLetterService` — captures failed executions for later retry
- `RecoveryService` — detects and cleans up stale executions

**Infrastructure Layer** — Pluggable adapters:
- `ExecutionPersistenceProvider` — state storage (`InMemory`, `Redis`, `Cache`, `EventSourced`)
- `OrchestrationEvents` — lifecycle event callbacks (26 methods)
- `OrchestrationMetrics` — Micrometer counters and timers (8 metrics)
- `OrchestrationTracer` — Micrometer Observation API spans
- `OrchestrationEventPublisher` — domain event publishing (`NoOpEventPublisher` default)

### Data Flow

```
1. Definition:     @Saga / SagaBuilder.saga("name")
                          ↓
2. Registration:   SagaRegistry scans beans at startup
                          ↓
3. Execution:      sagaEngine.execute("name", inputs)
                          ↓
4. Orchestration:  SagaExecutionOrchestrator builds topology, invokes steps
                          ↓
5. Step Invocation: StepInvoker resolves args, calls method, handles retry
                          ↓
6. Persistence:    ExecutionState saved after each step
                          ↓
7. Observability:  OrchestrationEvents → Metrics, Tracing, Logging
                          ↓
8. Result:         SagaResult returned to caller
```

---

## 3. Pattern Selection Guide

### Decision Flowchart

```
Need to coordinate multiple steps across services?
│
├── Need to undo completed work on failure?
│   │
│   ├── Need resource reservation / soft-locking before commit?
│   │   └── YES → TCC
│   │
│   └── Compensating actions sufficient (no reservation needed)?
│       └── YES → SAGA
│
└── No rollback needed? (Steps are idempotent, or failure is acceptable)
    └── YES → WORKFLOW
```

### Comparison Table

| Characteristic         | Workflow                    | Saga                         | TCC                              |
|------------------------|-----------------------------|------------------------------|----------------------------------|
| **Consistency**        | Eventual                    | Eventual                     | Strong (two-phase)               |
| **Isolation**          | None                        | None                         | Soft-lock during Try phase       |
| **Rollback**           | Optional (`compensatable`)  | Automatic (compensations)    | Cancel (release reservations)    |
| **Step ordering**      | DAG (dependency graph)      | DAG (dependency graph)       | Sequential by `order`            |
| **Parallel execution** | Yes (within layers)         | Yes (within layers)          | Try phase sequential             |
| **Lifecycle control**  | Suspend / Resume / Cancel   | Execute-to-completion        | Execute-to-completion            |
| **Signal/Timer gates** | Yes                         | No                           | No                               |
| **Child executions**   | Yes (`ChildWorkflowService`)| No                           | No                               |
| **Search attributes**  | Yes (via `WorkflowSearchService`) | No                      | No                               |
| **Continue-as-new**    | Yes (`ContinueAsNewService`)| No                           | No                               |
| **Result type**        | `ExecutionState`            | `SagaResult`                 | `TccResult`                      |
| **Builder**            | `WorkflowBuilder`           | `SagaBuilder`                | `TccBuilder`                     |
| **Engine**             | `WorkflowEngine`            | `SagaEngine`                 | `TccEngine`                      |

### When to Use Each Pattern

**Use Workflow when:**

- Steps have dependency ordering (DAG) but no rollback is needed
- You need suspend/resume/cancel lifecycle management
- You need signal gates (wait for human approval) or timer delays
- You need child workflow spawning or continue-as-new for long-running processes
- You need searchable execution attributes
- Steps are idempotent and can be retried safely
- **Examples:** ETL pipelines, report generation, multi-stage deployments, approval workflows, batch processing

**Use Saga when:**

- Each step has a compensating action (undo)
- All-or-nothing semantics are needed across services
- Steps can fail independently and you need automatic rollback
- You need fine-grained compensation policies (sequential, parallel, circuit breaker)
- You need fan-out processing (`ExpandEach`)
- **Examples:** Order processing, fund transfers, booking systems, inventory management

**Use TCC when:**

- You need resource reservation before committing
- Stronger isolation is required (soft-locking)
- Two-phase commitment with explicit try/confirm/cancel
- Each participant manages its own try/confirm/cancel lifecycle
- Per-phase timeout and retry configuration is needed
- **Examples:** Seat reservations, inventory holds, account debits, ticket booking

---

# Part II: Workflow Pattern

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

# Part III: Saga Pattern

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

# Part IV: TCC Pattern

## 19. TCC Annotation Reference

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

## 20. TCC Tutorial

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

## 21. TCC Phases & Timeout/Retry

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

## 22. TCC Builder DSL

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

## 23. TccEngine API

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

## 24. TccResult

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

# Part V: Core Infrastructure

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

# Part VI: Configuration

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

### Example: Production Configuration

```yaml
firefly:
  orchestration:
    saga:
      compensation-policy: RETRY_WITH_BACKOFF
      default-timeout: 2m
    tcc:
      default-timeout: 15s
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
| `CompensationErrorHandler` | `DefaultCompensationErrorHandler` |
| `SagaCompensator` | `SagaCompensator` |
| `SagaEngine` | `SagaEngine` |

**`TccAutoConfiguration`** (when `tcc.enabled=true`):

| Bean | Type |
|------|------|
| `TccRegistry` | `TccRegistry` |
| `TccExecutionOrchestrator` | `TccExecutionOrchestrator` |
| `TccEngine` | `TccEngine` |

### Phase 3: Extensions (after core)

| Auto-Configuration | Bean | Classpath Condition |
|--------------------|------|---------------------|
| `OrchestrationMetricsAutoConfiguration` | `OrchestrationMetrics` | `MeterRegistry` on classpath |
| `OrchestrationTracingAutoConfiguration` | `OrchestrationTracer` | `ObservationRegistry` on classpath |
| `OrchestrationResilienceAutoConfiguration` | `ResilienceDecorator` | `CircuitBreakerRegistry` on classpath |
| `OrchestrationRestAutoConfiguration` | `OrchestrationController`, `DeadLetterController`, `OrchestrationHealthIndicator` | Reactive web application |
| `EventGatewayAutoConfiguration` | `EventGateway` + initializer | Always (after pattern engines) |

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

# Part VII: Recipes & Production

## 41. Recipe: Composing Patterns

You can compose patterns by chaining them — a saga step can trigger a TCC, a workflow step can start a saga, or you can share context across multiple executions.

### Sequential Composition

A saga that triggers a TCC for payment processing:

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
                    TccInputs.of(Map.of("charge", order)))
                    .map(tccResult -> {
                        if (tccResult.isConfirmed()) return sagaResult;
                        throw new RuntimeException("Payment TCC failed");
                    });
            });
    }
}
```

### Shared Context Composition

Share an `ExecutionContext` across multiple executions to pass variables:

```java
ExecutionContext ctx = ExecutionContext.forSaga(null, "composed");
ctx.putVariable("orderId", orderId);
ctx.putHeader("X-Tenant-Id", tenantId);

sagaEngine.execute("ValidateOrder", StepInputs.empty(), ctx)
    .flatMap(r1 -> tccEngine.execute("ChargeFunds", TccInputs.empty(), ctx))
    .flatMap(r2 -> sagaEngine.execute("FulfillOrder", StepInputs.empty(), ctx));
```

Each execution sees variables and headers set by previous executions in the chain.

### Event-Driven Composition

Use `triggerEventType` to compose patterns loosely via the `EventGateway`:

```java
@Saga(name = "OrderSaga", triggerEventType = "OrderCreated")
public class OrderSaga {
    @SagaStep(id = "validate")
    @StepEvent(topic = "payments", type = "PaymentRequested", key = "orderId")
    public Mono<String> validate(@Input OrderRequest req) { ... }
}

@Tcc(name = "PaymentTcc", triggerEventType = "PaymentRequested")
public class PaymentTcc { ... }
```

When `OrderSaga.validate` completes, it publishes `PaymentRequested`, which automatically triggers `PaymentTcc` via the `EventGateway`.

---

## 42. Recipe: Testing Orchestrations

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

The builder DSL is ideal for integration tests — no annotation scanning, full control:

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
    var compensator = new SagaCompensator(events,
        CompensationPolicy.STRICT_SEQUENTIAL, invoker);
    var engine = new SagaEngine(null, events, orchestrator,
        null, null, compensator, noOpPublisher);

    StepVerifier.create(engine.execute(def, StepInputs.empty()))
        .assertNext(result -> {
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.steps()).containsKeys("step1", "step2");
        })
        .verifyComplete();
}
```

### Testing Compensation Paths

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
            .cancelHandler((r, ctx) -> {
                canceled.set(true);
                return Mono.empty();
            })
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

### Testing Dry-Run Mode (Workflow)

```java
@Test
void workflow_dryRun_skipsAllSteps() {
    workflowEngine.startWorkflow("MyWorkflow", Map.of(), "dry-1", "test", true)
        .as(StepVerifier::create)
        .assertNext(state -> {
            assertThat(state.status()).isEqualTo(ExecutionStatus.COMPLETED);
            state.stepStatuses().values()
                .forEach(s -> assertThat(s).isEqualTo(StepStatus.SKIPPED));
        })
        .verifyComplete();
}
```

---

## 43. Recipe: Error Handling

### Step-Level: Retry + Timeout

Configure per-step resilience:

```java
@SagaStep(id = "charge",
          retry = 5,           // 5 attempts
          backoffMs = 1000,    // 1s initial backoff
          timeoutMs = 10000,   // 10s timeout per attempt
          jitter = true,       // randomize delays
          jitterFactor = 0.3)  // ±30%
public Mono<String> charge(@Input PaymentRequest req) {
    return paymentService.charge(req);
}
```

### Saga Compensation Error Handling

When compensation fails, the behavior depends on the `CompensationPolicy`:

- `STRICT_SEQUENTIAL` / `GROUPED_PARALLEL`: Compensation failure is logged; remaining compensations still run
- `RETRY_WITH_BACKOFF`: Compensation is retried with backoff; failures are logged and skipped
- `CIRCUIT_BREAKER`: If a `compensationCritical` step fails, remaining compensations are skipped
- `BEST_EFFORT_PARALLEL`: All compensations run in parallel; failures are logged

For critical compensations, use the DLQ as a safety net:

```java
@SagaStep(id = "debit", compensate = "refund",
          compensationCritical = true, compensationRetry = 10)
```

### TCC Cancel-Phase Error Handling

If Cancel fails, the result is `FAILED` and a DLQ entry is created. This is a signal for manual intervention.

### Dead-Letter Queue for Unrecoverable Failures

Monitor the DLQ and alert on growth:

```java
@Scheduled(fixedDelay = 60000)
public void monitorDlq() {
    dlqService.count()
        .filter(count -> count > 0)
        .subscribe(count -> alertService.warn("DLQ has " + count + " entries"));
}
```

### Lifecycle Callbacks for Error Notifications

```java
@OnSagaError(priority = 0)
public void alertOnFailure(Throwable error, ExecutionContext ctx) {
    alertService.critical("Saga " + ctx.getExecutionName() + " failed: " + error.getMessage());
}

@OnSagaError(errorTypes = TimeoutException.class, suppressError = true)
public void handleTimeout(ExecutionContext ctx) {
    // Convert timeout to success — use for non-critical sagas
    log.warn("Saga {} timed out, treating as success", ctx.getExecutionName());
}
```

---

## 44. Recipe: Event-Driven Architecture

### Event-Driven Triggering

Define orchestrations that react to domain events:

```java
@Saga(name = "OrderFulfillment", triggerEventType = "OrderPlaced")
public class OrderFulfillmentSaga { ... }
```

Route events from your message consumer:

```java
@KafkaListener(topics = "orders")
public void onOrderEvent(OrderEvent event) {
    eventGateway.routeEvent(event.type(), event.payload())
        .subscribe();
}
```

### Publishing Step Events for Downstream Consumers

```java
@SagaStep(id = "reserve")
@StepEvent(topic = "inventory", type = "InventoryReserved", key = "orderId")
public Mono<ReservationResult> reserve(@Input OrderRequest req) { ... }
```

Implement `OrchestrationEventPublisher` to forward to your message broker:

```java
@Component
public class KafkaEventPublisher implements OrchestrationEventPublisher {
    private final KafkaTemplate<String, OrchestrationEvent> kafka;

    @Override
    public Mono<Void> publish(OrchestrationEvent event) {
        if (event.topic() == null) return Mono.empty(); // skip non-topic events
        return Mono.fromFuture(kafka.send(event.topic(), event.key(), event))
            .then();
    }
}
```

### Event Flow Diagram

```
External Event → EventGateway → SagaEngine.execute()
                                    ↓
                              Step executes
                                    ↓
                          @StepEvent published
                                    ↓
                     OrchestrationEventPublisher → Kafka/RabbitMQ
                                    ↓
                          Downstream consumers
```

---

## 45. Production Checklist

### Persistence

- [ ] Switch from `in-memory` to `redis` or another durable provider
- [ ] Configure `retention-period` and `cleanup-interval`
- [ ] Test persistence failover behavior
- [ ] Set `key-ttl` for Redis to prevent unbounded key growth

### Observability

- [ ] Enable metrics (`firefly.orchestration.metrics.enabled=true`)
- [ ] Enable tracing (`firefly.orchestration.tracing.enabled=true`)
- [ ] Add custom `OrchestrationEvents` listener for alerting
- [ ] Set up dashboards for `firefly.orchestration.executions.*` metrics
- [ ] Configure span sampling rate for tracing

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

### Scheduling

- [ ] Configure `thread-pool-size` based on scheduled task count
- [ ] Verify cron expressions with appropriate timezones
- [ ] Test scheduled task behavior across DST transitions

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

## 46. Resilience Patterns

### Resilience4j Integration

When `firefly.orchestration.resilience.enabled=true` and `CircuitBreakerRegistry` is on the classpath, the `ResilienceDecorator` bean is created automatically.

**Condition:**

```yaml
firefly:
  orchestration:
    resilience:
      enabled: true
```

**Classpath:** `io.github.resilience4j:resilience4j-circuitbreaker`

The `ResilienceDecorator` wraps step execution with circuit breaker patterns. It is primarily used by the `CIRCUIT_BREAKER` compensation policy for saga compensations.

### Circuit Breakers for Compensation

When using `CompensationPolicy.CIRCUIT_BREAKER`:

1. Each step's compensation is wrapped in a circuit breaker
2. Steps marked `compensationCritical = true` are monitored
3. If a critical compensation fails repeatedly, the circuit opens
4. Open circuit halts remaining compensations to prevent cascading failures

### Rate Limiting Considerations

For high-throughput environments:

- Use `layerConcurrency` to limit parallel step execution within a layer
- Configure `scheduling.thread-pool-size` to control concurrent scheduled executions
- Use `cpuBound = true` on CPU-intensive steps to schedule them on bounded-elastic

---

## 47. Continue-as-New

`ContinueAsNewService` implements the continue-as-new pattern for long-running workflows that accumulate state.

### When to Use

- Workflows that run for days/weeks/months (e.g., subscription management)
- Execution state grows unbounded (many steps, large variables)
- You want to "reset" the workflow with fresh state while maintaining continuity

### How It Works

1. Completes the current workflow execution
2. Starts a fresh execution with the same workflow definition
3. Assigns a new correlation ID
4. Migrates pending signals and timers to the new instance
5. Fires `OrchestrationEvents.onContinueAsNew(name, oldCorrId, newCorrId)`

### API

```java
Mono<ContinueAsNewResult> continueAsNew(
    String currentCorrelationId,
    Map<String, Object> newInput
)
```

### Usage

```java
@WorkflowStep(id = "checkRenewal", dependsOn = "processMonth")
public Mono<String> checkRenewal(ExecutionContext ctx) {
    if (shouldContinue()) {
        return continueAsNewService.continueAsNew(
            ctx.getCorrelationId(),
            Map.of("month", nextMonth(), "customerId", ctx.getVariable("customerId")))
            .map(result -> "Continued as " + result.newCorrelationId());
    }
    return Mono.just("Completed — no renewal needed");
}
```

### ContinueAsNewResult

Contains:
- `newCorrelationId` — the correlation ID of the new execution
- `oldCorrelationId` — the correlation ID of the completed execution

---

## 48. FAQ & Troubleshooting

### "My compensation didn't run"

**Check:** Does the `@SagaStep` have `compensate = "methodName"` set? The compensation method must:
- Be on the same bean (or use `@CompensationSagaStep` for external beans)
- Be accessible (public or package-private)
- Return `Mono<Void>` or `Mono<?>`

### "Steps run sequentially instead of in parallel"

**Check:** Steps only run in parallel if they're in the same DAG layer. Steps in the same layer have **no dependency on each other**. If `charge` depends on `validate` and `reserve` depends on `validate`, then `charge` and `reserve` are in the same layer and run in parallel.

```java
// Parallel: both depend only on "validate"
@SagaStep(id = "charge", dependsOn = "validate")
@SagaStep(id = "reserve", dependsOn = "validate")

// Sequential: "reserve" depends on "charge"
@SagaStep(id = "charge", dependsOn = "validate")
@SagaStep(id = "reserve", dependsOn = "charge")
```

### "Events not publishing"

**Check:**
1. Is `publishEvents = true` on the `@Workflow`? (or `@StepEvent` on the saga step?)
2. Is there a custom `OrchestrationEventPublisher` bean? The default is `NoOpEventPublisher`.
3. Is the `EventGateway` initialized? Check startup logs for `[event-gateway] Scan complete`.

### "TCC Cancel runs for participants that didn't Try"

**By design**, Cancel only runs for participants whose Try **succeeded**. If participant A's Try succeeded and participant B's Try failed, only A's Cancel runs. B never reserved resources, so no Cancel is needed.

### "Workflow doesn't resume after suspend"

**Check:**
1. Is the workflow in `SUSPENDED` state? Use `workflowEngine.findByCorrelationId(corrId)`.
2. Are you calling `resumeWorkflow(correlationId)`?
3. Resume is rejected if the workflow is in a terminal state (`COMPLETED`, `FAILED`, `CANCELLED`).

### "Scheduled saga doesn't fire"

**Check:**
1. Is `@ScheduledSaga(enabled = true)` set? (default is `true`)
2. Is the `OrchestrationScheduler` bean present? (auto-configured by default)
3. Is the cron expression valid? Use 6-field format: `second minute hour day month weekday`
4. Check logs for scheduling registration at startup

### "Idempotency key not preventing duplicates"

**Check:** Idempotency keys are scoped to a single execution. They prevent duplicate step invocation **within the same execution** (e.g., on retry). They do not prevent duplicate executions across separate `sagaEngine.execute()` calls.

### "TopologyValidationException: Cycle detected"

Your step dependencies form a cycle. Common causes:
- Step A depends on Step B, and Step B depends on Step A
- Transitive cycle: A → B → C → A

Use the topology REST endpoint to visualize: `GET /api/orchestration/workflows/{id}/topology`

### Model Reference: ExecutionStatus

```java
enum ExecutionStatus {
    PENDING, RUNNING, WAITING, SUSPENDED, COMPLETED, FAILED, CANCELLED, TIMED_OUT,
    TRYING, CONFIRMING, CONFIRMED, CANCELING, CANCELED, COMPENSATING
}
```

Helper methods: `isTerminal()`, `isActive()`, `canSuspend()`, `canResume()`

### Model Reference: StepStatus

```java
enum StepStatus {
    PENDING, RUNNING, DONE, FAILED, SKIPPED, TIMED_OUT, RETRYING,
    TRIED, TRY_FAILED, CONFIRMING, CONFIRMED, CONFIRM_FAILED,
    CANCELING, CANCELED, CANCEL_FAILED,
    COMPENSATED, COMPENSATION_FAILED
}
```

Helper methods: `isTerminal()`, `isActive()`, `isSuccessful()`

---

*Copyright 2024-2026 Firefly Software Solutions Inc. Licensed under the Apache License, Version 2.0.*
