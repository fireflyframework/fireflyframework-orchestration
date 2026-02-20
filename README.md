# Firefly Framework :: Orchestration

A unified, reactive orchestration engine for Spring Boot applications supporting three distributed transaction patterns: **Workflow**, **Saga**, and **TCC** (Try-Confirm-Cancel).

Built on **Project Reactor** for non-blocking execution, with DAG-based topological step ordering, automatic compensation, persistence, observability, and dead-letter queue support.

## Features

- **Three orchestration patterns** in one module — Workflow, Saga, TCC
- **Reactive from top to bottom** — built on Reactor `Mono`/`Flux`
- **DAG-based execution** — Kahn's algorithm for topological ordering with parallel layer support
- **Five compensation policies** — STRICT_SEQUENTIAL, GROUPED_PARALLEL, RETRY_WITH_BACKOFF, CIRCUIT_BREAKER, BEST_EFFORT_PARALLEL
- **Two definition styles** — annotation-driven (`@Saga`, `@Workflow`, `@Tcc`) and programmatic builder DSL
- **Pluggable persistence** — InMemory, Redis, Cache, Event-Sourced adapters
- **Full observability** — structured logging, Micrometer metrics, distributed tracing
- **Dead-letter queue** — automatic DLQ for failed executions
- **Workflow lifecycle** — suspend, resume, cancel running workflows
- **Retry with backoff** — per-step retry policies with exponential backoff and jitter
- **Spring Boot auto-configuration** — zero-config defaults, full customization via properties
- **Fan-out (ExpandEach)** — dynamically clone saga steps per input item

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
    tcc:
      enabled: true
    persistence:
      provider: in-memory    # in-memory | redis | cache | event-sourced
    metrics:
      enabled: true
    tracing:
      enabled: true
```

## Requirements

- Java 21+
- Spring Boot 3.x
- Project Reactor (included transitively)

## License

```
Copyright 2024-2026 Firefly Software Solutions Inc

Licensed under the Apache License, Version 2.0
```
