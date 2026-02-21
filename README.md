# Firefly Framework :: Orchestration

A unified, reactive orchestration engine for Spring Boot applications that brings three distributed transaction patterns — **Workflow**, **Saga**, and **TCC** (Try-Confirm-Cancel) — under a single, coherent API.

Modern microservice architectures break business operations into multiple service calls, and coordinating those calls reliably is one of the hardest problems in distributed systems. This module solves that problem by providing a reactive orchestration layer built on **Project Reactor** that handles execution ordering, failure recovery, automatic compensation, persistence, observability, event integration, and scheduling — so your application code focuses purely on business logic.

## Why Firefly Orchestration?

- **One module, three patterns.** Instead of adopting separate libraries for workflow orchestration, saga coordination, and TCC transactions, Firefly Orchestration provides all three patterns sharing a common core infrastructure. Learn one set of concepts (execution context, argument injection, retry policies, persistence) and apply them across all patterns.

- **Reactive from top to bottom.** Every method returns `Mono` or `Flux`. There are no blocking calls, no thread-pool exhaustion under load, and natural backpressure handling. Thousands of concurrent orchestrations run efficiently on a small thread pool.

- **Event-driven by design.** Orchestrations can be triggered by external events (`triggerEventType` + `EventGateway`) and can publish events when steps complete (`@StepEvent`, `@TccEvent`, `publishEvents`). This lets orchestrations participate in larger event-driven architectures without tight coupling.

- **Zero-config to production-ready.** Spring Boot auto-configuration provides sensible defaults for development (in-memory persistence, SLF4J logging, DLQ enabled). For production, plug in Redis persistence, Micrometer metrics, distributed tracing, and Resilience4j circuit breakers — all through configuration properties and Spring beans.

## Features

### Core Capabilities
- **DAG-based execution** — Kahn's algorithm for topological ordering with parallel layer support
- **Five compensation policies** — STRICT_SEQUENTIAL, GROUPED_PARALLEL, RETRY_WITH_BACKOFF, CIRCUIT_BREAKER, BEST_EFFORT_PARALLEL
- **Two definition styles** — annotation-driven (`@Saga`, `@Workflow`, `@Tcc`) and programmatic builder DSL
- **Retry with backoff** — per-step retry policies with exponential backoff and jitter
- **Fan-out (ExpandEach)** — dynamically clone saga steps per input item

### Event Integration
- **Event-driven triggering** — `triggerEventType` routes external events through the `EventGateway` to start executions
- **Event publishing** — `@StepEvent` (Saga), `@TccEvent` (TCC), `publishEvents` (Workflow) emit events to downstream systems
- **Lifecycle callbacks** — `@OnWorkflowComplete`, `@OnSagaComplete`, `@OnTccComplete` and error variants with priority ordering, error type filtering, and error suppression

### Workflow-Specific
- **Lifecycle management** — suspend, resume, and cancel running workflows
- **Signal and timer gates** — `@WaitForSignal` and `@WaitForTimer` for external event coordination and timed delays
- **Dry run mode** — traverse the DAG without executing step logic (all steps marked SKIPPED)
- **SpEL conditions** — conditional step execution based on runtime expressions
- **Compensatable steps** — `compensatable` and `compensationMethod` on `@WorkflowStep`

### Infrastructure
- **Pluggable persistence** — InMemory, Redis, Cache, and Event-Sourced adapters
- **Full observability** — structured logging, Micrometer metrics, distributed tracing
- **Dead-letter queue** — automatic DLQ capture for failed executions
- **Cron scheduling** — `@ScheduledWorkflow`, `@ScheduledSaga`, `@ScheduledTcc` with cron, fixedDelay, fixedRate
- **Spring Boot auto-configuration** — zero-config defaults, full customization via properties

## Quick Start

### 1. Add the Dependency

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-orchestration</artifactId>
    <version>26.02.06</version>
</dependency>
```

### 2. Define a Saga (Annotation-Driven)

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
}
```

### 3. Execute It

```java
@Service
public class OrderService {
    private final SagaEngine sagaEngine;

    public Mono<SagaResult> placeOrder(OrderRequest request) {
        return sagaEngine.execute("OrderSaga",
            StepInputs.of("reserve", request));
    }
}
```

### 4. Or Use the Builder DSL

```java
SagaDefinition def = SagaBuilder.saga("TransferFunds")
    .step("debit")
        .handler((input, ctx) -> accountService.debit(input))
        .compensation((result, ctx) -> accountService.credit(result))
        .add()
    .step("credit")
        .dependsOn("debit")
        .handlerInput(input -> accountService.credit(input))
        .add()
    .build();

sagaEngine.execute(def, StepInputs.of("debit", transferRequest));
```

## Documentation

For the complete reference guide covering all three patterns with step-by-step tutorials, configuration options, and architecture details, see **[docs/reference-guide.md](docs/reference-guide.md)**.

## Configuration

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
      provider: in-memory    # in-memory | redis | cache | event-sourced
    metrics:
      enabled: true
    tracing:
      enabled: true
    dlq:
      enabled: true
    scheduling:
      thread-pool-size: 4
    recovery:
      enabled: true
      stale-threshold: 1h
```

## Requirements

- Java 25+
- Spring Boot 3.x
- Project Reactor (included transitively)

## License

```
Copyright 2024-2026 Firefly Software Solutions Inc

Licensed under the Apache License, Version 2.0
```
