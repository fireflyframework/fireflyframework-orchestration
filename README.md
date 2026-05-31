# Firefly Framework - Orchestration

[![CI](https://github.com/fireflyframework/fireflyframework-orchestration/actions/workflows/ci.yml/badge.svg)](https://github.com/fireflyframework/fireflyframework-orchestration/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)

> Reactive orchestration engine for Spring Boot — Workflow, Saga, and TCC distributed-transaction patterns in one module, built on Project Reactor.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
  - [Saga (annotations)](#saga-annotations)
  - [Saga (builder DSL)](#saga-builder-dsl)
  - [TCC](#tcc)
  - [Workflow](#workflow)
- [Pattern Selection Guide](#pattern-selection-guide)
- [Event Integration](#event-integration)
- [Scheduling](#scheduling)
- [Configuration](#configuration)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [License](#license)

## Overview

`fireflyframework-orchestration` is the Firefly Framework's unified engine for coordinating multi-step,
distributed operations. It provides three coordination patterns — **Workflow**, **Saga**, and
**TCC (Try-Confirm-Cancel)** — in a single module, all built on Project Reactor and all sharing one
common core: execution context, argument injection, retry policies, persistence, observability,
event integration, scheduling, recovery, and dead-letter routing.

Distributed business processes rarely fit a single local transaction. Inventory lives in one service,
payments in another, shipping in a third. When a step fails partway through, you need a principled way
to roll back, retry, or compensate. This module gives you that, with a choice of consistency model:
**Workflow** for fire-and-forward pipelines (no rollback), **Saga** for eventual consistency with
automatic compensation, and **TCC** for strong consistency via soft-locked reservations and an explicit
confirm/cancel phase.

Within the framework this is the **domain-tier orchestration layer**: it sits above core-tier
microservices and composes their calls into resilient business transactions. Orchestrations are defined
either with annotations (`@Saga`, `@Workflow`, `@Tcc`) or a fluent builder DSL (`SagaBuilder`,
`WorkflowBuilder`, `TccBuilder`), and the engine handles DAG-based execution ordering (Kahn's algorithm
with parallel layers), automatic compensation on failure, per-step checkpointing, and recovery of stale
executions. It depends on `fireflyframework-kernel` (shared abstractions and exception hierarchy) and
`fireflyframework-observability` (metrics, tracing, health, structured logging), and optionally
integrates with `fireflyframework-eda` (event triggering and step-level event publishing),
`fireflyframework-cache` and `fireflyframework-eventsourcing` (durable persistence backends), and
Resilience4j (circuit breakers, rate limiters, bulkheads).

The engine is **reactive from top to bottom** — every method returns `Mono` or `Flux`, with no blocking
calls — and **zero-config to production-ready**: Spring Boot auto-configuration ships sensible
development defaults (in-memory persistence, SLF4J logging, DLQ enabled), and production hardening
(Redis/cache/event-sourced persistence, Micrometer metrics, distributed tracing, resilience) is enabled
purely through `firefly.orchestration.*` properties and Spring beans.

## Features

- **Three patterns, one core** — Workflow, Saga, and TCC share execution context, argument injection,
  retry policies, persistence, observability, and event integration. Learn the concepts once, apply them
  everywhere via `WorkflowEngine`, `SagaEngine`, and `TccEngine`.
- **DAG-based execution** — Kahn's algorithm produces a topological order with parallel layer support, so
  independent steps run concurrently.
- **Two definition styles** — annotation-driven (`@Saga`/`@SagaStep`, `@Workflow`/`@WorkflowStep`,
  `@Tcc`/`@TccParticipant`) and a programmatic builder DSL (`SagaBuilder`, `WorkflowBuilder`, `TccBuilder`).
- **Automatic compensation** — five compensation policies (`STRICT_SEQUENTIAL`, `GROUPED_PARALLEL`,
  `RETRY_WITH_BACKOFF`, `CIRCUIT_BREAKER`, `BEST_EFFORT_PARALLEL`) with per-step compensation handlers.
- **TCC try/confirm/cancel** — soft-locked reservations with strong consistency and an explicit
  two-phase confirm/cancel via `TccEngine`.
- **Retry with backoff** — per-step retry policies with exponential backoff and optional jitter.
- **Fan-out (ExpandEach)** — dynamically clone saga steps per input item.
- **12 argument-injection annotations** — `@Input`, `@FromStep`, `@FromTry`, `@Variable`, `@Header`,
  `@CorrelationId`, and more, resolved against the shared `ExecutionContext`.
- **Workflow lifecycle** — suspend, resume, and cancel running workflows; signal and timer gates
  (`@WaitForSignal`, `@WaitForTimer`); dry-run mode; SpEL step conditions; per-step compensation.
- **Durable workflow primitives** — child workflows, continue-as-new, query handlers, and search.
- **Event integration** — inbound event-driven triggering via `triggerEventType` + `EventGateway`, and
  outbound step-level publishing (`@StepEvent`, `@TccEvent`, `publishEvents`).
- **Lifecycle callbacks** — `@OnWorkflowComplete`/`@OnSagaComplete`/`@OnTccComplete` and error variants,
  with priority ordering, error-type filtering, error suppression, and result injection.
- **Pluggable persistence** — in-memory (default), Redis, cache, and event-sourced adapters selected by
  the `persistence.provider` property.
- **Cron scheduling** — `@ScheduledWorkflow`, `@ScheduledSaga`, `@ScheduledTcc` with `cron`, `fixedRate`,
  `fixedDelay`, `initialDelay`, `zone`, `enabled`, and `input`.
- **Backpressure & resilience** — adaptive backpressure strategy with a built-in circuit breaker, plus
  optional Resilience4j (circuit breaker, rate limiter, bulkhead, time limiter).
- **Recovery & DLQ** — automatic recovery of stale executions and a dead-letter queue for failed runs.
- **Full observability** — structured logging, Micrometer metrics, and distributed tracing via the
  Observation API, plus an optional REST API and Actuator health indicator.

## Requirements

- Java 21+ (Java 25 recommended)
- Spring Boot 3.x
- Maven 3.9+
- Project Reactor 3.x (provided transitively via `spring-boot-starter-webflux`)
- Optional runtime backends, depending on configuration:
  - A **Redis** server — when `persistence.provider: redis`
  - A `fireflyframework-cache` provider — when `persistence.provider: cache`
  - `fireflyframework-eventsourcing` (R2DBC datastore) — when `persistence.provider: event-sourced`
  - A `fireflyframework-eda` transport (e.g. Kafka, RabbitMQ) — for event triggering / publishing

## Installation

Add the dependency. The version is managed by the Firefly Framework parent/BOM, so you do not declare it
explicitly:

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-orchestration</artifactId>
    <!-- version managed by fireflyframework-parent / BOM -->
</dependency>
```

If your project inherits the Firefly parent POM, the version is supplied automatically:

```xml
<parent>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-parent</artifactId>
    <version>26.05.08</version>
</parent>
```

Optional backends are pulled in only when you add their dependency (Redis, cache, event sourcing,
EDA, Resilience4j) — the module declares them `optional`, so nothing ships unless you opt in.

## Quick Start

### Saga (annotations)

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

Execute it through the injected `SagaEngine`:

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

### Saga (builder DSL)

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

### TCC

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
        if (result.isConfirmed())   log.info("Transaction confirmed");
        else if (result.isCanceled()) log.warn("Transaction canceled");
    });
```

### Workflow

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

## Pattern Selection Guide

```
Need to undo on failure?
├─ No  ──▶  WORKFLOW
└─ Yes
   ├─ Need resource reservation / locking?  ──▶  TCC
   └─ Compensating actions sufficient?       ──▶  SAGA
```

| Aspect       | Workflow                          | Saga                    | TCC                     |
|--------------|-----------------------------------|-------------------------|-------------------------|
| Consistency  | Eventual                          | Eventual                | Strong                  |
| Isolation    | None                              | None                    | Soft-lock               |
| Rollback     | None (or per-step compensation)   | Automatic compensation  | Cancel phase            |
| Use case     | Pipelines, ETL, deployments       | Orders, transfers       | Reservations, holds     |

## Event Integration

**Inbound — event-driven triggering.** Any orchestration can be started by an external event using
`triggerEventType` (annotation or builder); route events through the `EventGateway`:

```java
@Saga(name = "OrderSaga", triggerEventType = "OrderCreated")              // annotation
SagaBuilder.saga("OrderSaga").triggerEventType("OrderCreated")            // builder

eventGateway.routeEvent("OrderCreated", Map.of("orderId", "ORD-123")).subscribe();
```

**Outbound — step-level events.**

- **Saga:** `@StepEvent(topic, type, key)` or builder `.stepEvent(topic, type, key)`
- **TCC:** `@TccEvent(topic, eventType, key)` or builder `.event(topic, eventType, key)`
- **Workflow:** `publishEvents = true` on `@Workflow` or builder `.publishEvents(true)`

Event integration is delivered over the `fireflyframework-eda` abstraction (optional dependency).

## Scheduling

All three patterns support scheduled execution with full parity:

```java
@Saga(name = "CleanupSaga")
@ScheduledSaga(cron = "0 0 2 * * *", zone = "America/New_York",
               enabled = true, input = "{\"daysOld\": 30}")
public class CleanupSaga { /* ... */ }

@Tcc(name = "ReconciliationTcc")
@ScheduledTcc(fixedRate = 60000, initialDelay = 5000)
public class ReconciliationTcc { /* ... */ }

@Workflow(id = "ReportWorkflow")
@ScheduledWorkflow(fixedDelay = 3600000, zone = "UTC")
public class ReportWorkflow { /* ... */ }
```

Every scheduling annotation supports `cron`, `fixedRate`, `fixedDelay`, `initialDelay`, `zone`,
`enabled`, `input`, and `description`.

## Configuration

All properties live under the `firefly.orchestration` prefix and are bound by `OrchestrationProperties`.
The defaults below are the real values from the `@ConfigurationProperties` class — every value shown is
the engine default, so a zero-config application runs in-memory out of the box.

```yaml
firefly:
  orchestration:
    workflow:
      enabled: true
    saga:
      enabled: true
      compensation-policy: STRICT_SEQUENTIAL   # STRICT_SEQUENTIAL | GROUPED_PARALLEL | RETRY_WITH_BACKOFF | CIRCUIT_BREAKER | BEST_EFFORT_PARALLEL
      default-timeout: 5m
      compensation-error-handler: default
    tcc:
      enabled: true
      default-timeout: 30s
      composition:
        enabled: true
        compensation-policy: STRICT_SEQUENTIAL
    persistence:
      provider: in-memory          # in-memory | redis | cache | event-sourced
      key-prefix: "orchestration:"
      key-ttl:                      # unset = no TTL
      retention-period: 7d
      cleanup-interval: 1h
    recovery:
      enabled: true
      stale-threshold: 1h
    scheduling:
      thread-pool-size: 4
    rest:
      enabled: true
    health:
      enabled: true
    metrics:
      enabled: true
    tracing:
      enabled: true
    dlq:
      enabled: true
    resilience:
      enabled: true
    backpressure:
      strategy: adaptive
      batch-size: 10
      circuit-breaker:
        failure-threshold: 5
        recovery-timeout: 30s
        half-open-max-calls: 3
    validation:
      enabled: true
      fail-on-warning: false
    eventsourcing:
      snapshot-interval: 100
      projection-poll-interval: 5s
```

Key properties:

| Property                                       | Default             | Description                                                            |
|------------------------------------------------|---------------------|------------------------------------------------------------------------|
| `persistence.provider`                         | `in-memory`         | Selects the persistence backend: `in-memory`, `redis`, `cache`, or `event-sourced`. |
| `persistence.retention-period`                 | `7d`                | How long completed execution records are kept before cleanup.          |
| `saga.compensation-policy`                     | `STRICT_SEQUENTIAL` | Default compensation strategy when a saga step fails.                   |
| `saga.default-timeout` / `tcc.default-timeout` | `5m` / `30s`        | Default per-execution timeout for saga / TCC runs.                     |
| `recovery.stale-threshold`                     | `1h`                | Age after which an in-flight execution is considered stale and recovered. |
| `scheduling.thread-pool-size`                  | `4`                 | Threads backing cron/fixed-rate scheduled orchestrations.              |
| `backpressure.strategy`                        | `adaptive`          | Backpressure strategy for high-throughput execution.                   |
| `metrics.enabled` / `tracing.enabled` / `dlq.enabled` | `true`       | Toggle observability and dead-letter capture.                          |

Optional features activate only when the matching dependency is present (e.g. Redis client for
`provider: redis`, Resilience4j for `resilience.enabled`, `fireflyframework-eventsourcing` for
`provider: event-sourced`).

## Documentation

Comprehensive guides live in the [`docs/`](docs/README.md) folder:

| Document | Description |
|----------|-------------|
| [Tutorial](docs/tutorial.md) | Step-by-step fintech payment-processing example |
| [Foundations](docs/foundations.md) | Introduction, architecture, pattern selection |
| [Workflow](docs/workflow.md) | Workflow annotations, builder, engine, signals/timers |
| [Saga](docs/saga.md) | Saga annotations, builder, compensation, fan-out |
| [TCC](docs/tcc.md) | TCC annotations, builder, try/confirm/cancel phases |
| [Core Infrastructure](docs/core-infrastructure.md) | ExecutionContext, retry, events, persistence, DLQ, observability |
| [Configuration](docs/configuration.md) | Properties, auto-configuration, Spring Boot integration |
| [Recipes & Production](docs/recipes-and-production.md) | Composition, testing, error handling, production checklist |

See the [Firefly Framework module catalog](https://github.com/fireflyframework) for the full list of
framework modules and how they fit together.

## Contributing

Contributions are welcome. Please read the [CONTRIBUTING.md](CONTRIBUTING.md) guide for details on our code of conduct, development process, and how to submit pull requests.

## License

Copyright 2024-2026 Firefly Software Foundation.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
