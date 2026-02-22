[← Back to Index](README.md) | [Next: Workflow Pattern →](workflow.md)

# Part I: Foundations

**Contents:**
- [§1 Introduction & Quick Start](#1-introduction--quick-start)
- [§2 Architecture Overview](#2-architecture-overview)
- [§3 Pattern Selection Guide](#3-pattern-selection-guide)

---

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
|   ExecutionContext    StepInvoker     ArgumentResolver               |
|   TopologyBuilder     RetryPolicy    OrchestrationEvents            |
|   ExecutionState      DeadLetterService    RecoveryService          |
|   EventGateway        OrchestrationScheduler                        |
|   OrchestrationValidator   BackpressureStrategy                     |
|   ExecutionReportBuilder                                            |
+---------+--------------------------------+-------------------+------+
          |                                |                   |
+---------v-----------------+   +----------v-----------+  +----v------+
|   Persistence Layer       |   | Observability Layer  |  |  Events   |
|  InMemory (default)       |   | LoggerEvents         |  |  Gateway  |
|  Redis / Cache / ES       |   | Metrics / Tracing    |  |  Publish  |
|                           |   | MetricsEndpoint      |  |           |
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
- `OrchestrationValidator` — validates definitions at registration time (empty steps, cycles, annotation conflicts)
- `ExecutionReportBuilder` — builds execution reports attached to results
- `BackpressureStrategyFactory` — registry of backpressure strategies for parallel execution control

**Infrastructure Layer** — Pluggable adapters:
- `ExecutionPersistenceProvider` — state storage (`InMemory`, `Redis`, `Cache`, `EventSourced`)
- `OrchestrationEvents` — lifecycle event callbacks (26 methods)
- `OrchestrationMetrics` — Micrometer counters and timers (8 metrics)
- `OrchestrationTracer` — Micrometer Observation API spans
- `OrchestrationEventPublisher` — domain event publishing (`NoOpEventPublisher` default)
- `OrchestrationMetricsEndpoint` — Actuator endpoint for orchestration metrics summary

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

[← Back to Index](README.md) | [Next: Workflow Pattern →](workflow.md)

*Copyright 2024-2026 Firefly Software Solutions Inc. Licensed under the Apache License, Version 2.0.*
