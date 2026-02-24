# Firefly Framework Orchestration

**A reactive orchestration engine for distributed transactions in Spring Boot applications.**

Firefly Orchestration provides three coordination patterns -- Workflow, Saga, and TCC (Try-Confirm-Cancel) -- in a single module built on Project Reactor. All three share a common core: execution context, argument injection, retry policies, persistence, observability, event integration, scheduling, and dead-letter routing.

Define orchestrations using annotations (`@Saga`, `@Workflow`, `@Tcc`) or a fluent builder DSL. The engine handles DAG-based execution ordering, automatic compensation on failure, per-step checkpointing, and recovery of stale executions.

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-orchestration</artifactId>
    <version>26.02.07</version>
</dependency>
```

> Requires **Java 25+**, **Spring Boot 3.x**, and **Project Reactor 3.x**. Licensed under **Apache 2.0**.

---

## Table of Contents

- [Why Firefly Orchestration](#why-firefly-orchestration)
- [Features](#features)
- [Getting Started](#getting-started)
  - [Saga Example (Annotations)](#saga-example-annotations)
  - [Saga Example (Builder DSL)](#saga-example-builder-dsl)
  - [TCC Example](#tcc-example)
  - [Workflow Example](#workflow-example)
- [Architecture](#architecture)
- [Pattern Selection Guide](#pattern-selection-guide)
- [Event Integration](#event-integration)
- [Scheduling](#scheduling)
- [Configuration Reference](#configuration-reference)
- [Documentation](#documentation)
- [License](#license)

---

## Why Firefly Orchestration

**One module, three patterns.** Instead of adopting separate libraries for workflow orchestration, saga coordination, and TCC transactions, Firefly Orchestration provides all three sharing a common core. Learn one set of concepts -- execution context, argument injection, retry policies, persistence -- and apply them across all patterns.

**Reactive from top to bottom.** Every engine method returns `Mono` or `Flux`. There are no blocking calls, no thread-pool exhaustion under load, and natural backpressure handling. Thousands of concurrent orchestrations run efficiently on a small thread pool.

**Event-driven by design.** Orchestrations can be triggered by external events via `triggerEventType` and `EventGateway`, and can publish events when steps complete via `@StepEvent`, `@TccEvent`, and `publishEvents`. This lets orchestrations participate in larger event-driven architectures without tight coupling.

**Zero-config to production-ready.** Spring Boot auto-configuration provides sensible defaults for development (in-memory persistence, SLF4J logging, DLQ enabled). For production, plug in Redis persistence, Micrometer metrics, distributed tracing, and Resilience4j circuit breakers through configuration properties and Spring beans.

---

## Features

### Core Capabilities

- **DAG-based execution** -- Kahn's algorithm for topological ordering with parallel layer support
- **Five compensation policies** -- STRICT_SEQUENTIAL, GROUPED_PARALLEL, RETRY_WITH_BACKOFF, CIRCUIT_BREAKER, BEST_EFFORT_PARALLEL
- **Two definition styles** -- annotation-driven (`@Saga`, `@Workflow`, `@Tcc`) and programmatic builder DSL (`SagaBuilder`, `WorkflowBuilder`, `TccBuilder`)
- **Retry with backoff** -- per-step retry policies with exponential backoff and jitter
- **Fan-out (ExpandEach)** -- dynamically clone saga steps per input item

### Event Integration

- **Event-driven triggering** -- `triggerEventType` routes external events through `EventGateway` to start executions (annotation and builder)
- **Step-level event publishing** -- `@StepEvent` (Saga), `@TccEvent` (TCC), `publishEvents` (Workflow)
- **Lifecycle callbacks** -- `@OnWorkflowComplete`, `@OnSagaComplete`, `@OnTccComplete` and error variants with priority ordering, error type filtering, error suppression, and result injection

### Workflow-Specific

- **Lifecycle management** -- suspend, resume, and cancel running workflows
- **Signal and timer gates** -- `@WaitForSignal` and `@WaitForTimer` for external event coordination and timed delays
- **Dry-run mode** -- traverse the DAG without executing step logic (all steps marked SKIPPED)
- **SpEL conditions** -- conditional step execution based on runtime expressions
- **Compensatable steps** -- per-step compensation with `compensatable` and `compensationMethod`

### Infrastructure

- **Pluggable persistence** -- InMemory (default), Redis, Cache, and Event-Sourced adapters
- **Full observability** -- structured logging, 8 Micrometer metrics, distributed tracing via Observation API
- **Dead-letter queue** -- automatic DLQ capture for failed executions with retry tracking
- **Cron scheduling** -- `@ScheduledWorkflow`, `@ScheduledSaga`, `@ScheduledTcc` with cron, fixedDelay, fixedRate, zone, initialDelay, and input
- **12 argument injection annotations** -- `@Input`, `@FromStep`, `@FromTry`, `@Variable`, `@Header`, `@CorrelationId`, and more
- **Spring Boot auto-configuration** -- zero-config defaults, full customization via properties

---

## Getting Started

### Saga Example (Annotations)

```java
@Saga(name = "OrderSaga")
public class OrderSaga {

    @SagaStep(id = "reserve", compensate = "cancelReservation")
    public Mono<String> reserveInventory(@Input OrderRequest request) {
        return inventoryService.reserve(request.items());
    }

    public Mono<Void> cancelReservation(@Input String reservationId) {
        return inventoryService.cancel(reservationId);
    }

    @SagaStep(id = "charge", dependsOn = "reserve", compensate = "refund")
    public Mono<String> chargePayment(@FromStep("reserve") String reservationId) {
        return paymentService.charge(reservationId);
    }

    public Mono<Void> refund(@Input String chargeId) {
        return paymentService.refund(chargeId);
    }

    @OnSagaComplete
    public void onComplete(SagaResult result) {
        log.info("Order saga completed: {}", result.correlationId());
    }
}
```

Execute it:

```java
sagaEngine.execute("OrderSaga", StepInputs.of("reserve", orderRequest))
    .subscribe(result -> {
        if (result.isSuccess()) {
            log.info("All steps completed in {}ms", result.duration().toMillis());
        } else {
            log.error("Failed at step: {}", result.firstErrorStepId().orElse("unknown"));
        }
    });
```

### Saga Example (Builder DSL)

```java
SagaDefinition def = SagaBuilder.saga("TransferFunds")
    .triggerEventType("TransferRequested")
    .step("debit")
        .handler((input, ctx) -> accountService.debit(input))
        .compensation((result, ctx) -> accountService.credit(result))
        .retry(3).backoffMs(500).jitter()
        .stepEvent("transfers", "DebitCompleted", "accountId")
        .add()
    .step("credit")
        .dependsOn("debit")
        .handlerInput(input -> accountService.credit(input))
        .add()
    .build();

sagaEngine.execute(def, StepInputs.of("debit", transferRequest));
```

### TCC Example

```java
TccDefinition def = TccBuilder.tcc("PaymentTransaction")
    .triggerEventType("PaymentRequested")
    .participant("debit")
        .tryHandler((input, ctx) -> accountService.holdFunds(input))
        .confirmHandler((tryResult, ctx) -> accountService.commitHold(tryResult))
        .cancelHandler((tryResult, ctx) -> accountService.releaseHold(tryResult))
        .event("payments", "DebitConfirmed", "holdId")
        .add()
    .participant("credit")
        .tryHandler((input, ctx) -> accountService.prepareCredit(input))
        .confirmHandler((tryResult, ctx) -> accountService.commitCredit(tryResult))
        .cancelHandler((tryResult, ctx) -> accountService.cancelCredit(tryResult))
        .add()
    .build();

tccEngine.execute(def, TccInputs.of(Map.of("debit", request, "credit", request)))
    .subscribe(result -> {
        if (result.isConfirmed()) log.info("Transaction confirmed");
        else if (result.isCanceled()) log.warn("Transaction canceled");
    });
```

### Workflow Example

```java
@Workflow(id = "OrderProcessing", version = "1.0",
          publishEvents = true, triggerEventType = "OrderReceived")
public class OrderProcessingWorkflow {

    @WorkflowStep(id = "validate", timeoutMs = 5000)
    public Mono<Map<String, Object>> validate(@Input Map<String, Object> input) {
        return Mono.just(Map.of("orderId", input.get("orderId"), "validated", true));
    }

    @WorkflowStep(id = "charge", dependsOn = "validate",
                  maxRetries = 3, retryDelayMs = 1000)
    public Mono<String> charge(@FromStep("validate") Map<String, Object> validated) {
        return paymentService.charge((String) validated.get("orderId"));
    }

    @WorkflowStep(id = "ship", dependsOn = "charge")
    public Mono<String> ship(@FromStep("charge") String chargeId) {
        return shippingService.ship(chargeId);
    }
}
```

---

## Architecture

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

---

## Pattern Selection Guide

```
Need to undo on failure?
+-- No  -->  WORKFLOW
+-- Yes
    +-- Need resource reservation/locking?  -->  TCC
    +-- Compensating actions sufficient?    -->  SAGA
```

| Aspect | Workflow | Saga | TCC |
|--------|----------|------|-----|
| Consistency | Eventual | Eventual | Strong |
| Isolation | None | None | Soft-lock |
| Rollback | None (or per-step compensation) | Automatic compensation | Cancel phase |
| Use case | Pipelines, ETL, deployments | Orders, transfers, bookings | Reservations, holds, debits |

---

## Event Integration

### Inbound: Event-Driven Triggering

Any orchestration can be triggered by an external event using `triggerEventType`:

```java
// Annotation
@Saga(name = "OrderSaga", triggerEventType = "OrderCreated")

// Builder
SagaBuilder.saga("OrderSaga").triggerEventType("OrderCreated")
TccBuilder.tcc("PaymentTcc").triggerEventType("PaymentRequested")
new WorkflowBuilder("Pipeline").triggerEventType("DataIngested")
```

Route events through the `EventGateway`:

```java
eventGateway.routeEvent("OrderCreated", Map.of("orderId", "ORD-123"))
    .subscribe();
```

### Outbound: Step-Level Events

- **Saga:** `@StepEvent(topic, type, key)` or builder `.stepEvent(topic, type, key)`
- **TCC:** `@TccEvent(topic, eventType, key)` or builder `.event(topic, eventType, key)`
- **Workflow:** `publishEvents = true` on `@Workflow` or builder `.publishEvents(true)`

---

## Scheduling

All three patterns support scheduled execution with full parity:

```java
@Saga(name = "CleanupSaga")
@ScheduledSaga(cron = "0 0 2 * * *", zone = "America/New_York",
               enabled = true, input = "{\"daysOld\": 30}")
public class CleanupSaga { ... }

@Tcc(name = "ReconciliationTcc")
@ScheduledTcc(fixedRate = 60000, initialDelay = 5000)
public class ReconciliationTcc { ... }

@Workflow(id = "ReportWorkflow")
@ScheduledWorkflow(fixedDelay = 3600000, zone = "UTC")
public class ReportWorkflow { ... }
```

All scheduling annotations support: `cron`, `fixedRate`, `fixedDelay`, `initialDelay`, `zone`, `enabled`, `input`, and `description`.

---

## Configuration Reference

All properties use the `firefly.orchestration` prefix:

```yaml
firefly:
  orchestration:
    workflow:
      enabled: true
    saga:
      enabled: true
      compensation-policy: STRICT_SEQUENTIAL
      default-timeout: 5m
    tcc:
      enabled: true
      default-timeout: 30s
    persistence:
      provider: in-memory          # in-memory | redis | cache | event-sourced
      key-prefix: "orchestration:"
      retention-period: 7d
      cleanup-interval: 1h
    scheduling:
      thread-pool-size: 4
    recovery:
      enabled: true
      stale-threshold: 1h
    metrics:
      enabled: true
    tracing:
      enabled: true
    dlq:
      enabled: true
    rest:
      enabled: true
    health:
      enabled: true
    resilience:
      enabled: true
```

---

## Documentation

Full documentation lives in the [`docs/`](docs/README.md) folder:

| Document | Description |
|----------|-------------|
| [Tutorial](docs/tutorial.md) | Step-by-step fintech payment processing example |
| [Foundations](docs/foundations.md) | Introduction, architecture, pattern selection |
| [Workflow](docs/workflow.md) | Workflow annotations, builder, engine, signals/timers |
| [Saga](docs/saga.md) | Saga annotations, builder, compensation, fan-out |
| [TCC](docs/tcc.md) | TCC annotations, builder, try/confirm/cancel phases |
| [Core Infrastructure](docs/core-infrastructure.md) | ExecutionContext, retry, events, persistence, DLQ, observability |
| [Configuration](docs/configuration.md) | Properties, auto-configuration, Spring Boot integration |
| [Recipes & Production](docs/recipes-and-production.md) | Composition, testing, error handling, production checklist |

---

## License

```
Copyright 2024-2026 Firefly Software Solutions Inc

Licensed under the Apache License, Version 2.0
```
