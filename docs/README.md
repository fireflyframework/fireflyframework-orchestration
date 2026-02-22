# Firefly Orchestration Documentation

> **Version:** 26.02.06 | **Java:** 25+ | **Spring Boot:** 3.x | **Reactor:** 3.x

Firefly Orchestration is a reactive framework for coordinating multi-step business processes in Spring Boot applications. It provides three complementary patterns — **Workflow**, **Saga**, and **TCC** — unified by a shared core layer for persistence, observability, retry, and event integration.

---

## Quick Start

New to Firefly Orchestration? Start with the **[Tutorial: Fintech Payment Processing](tutorial.md)** — a step-by-step guide that builds a complete fund transfer system using all three patterns.

---

## Reference Guide

| Document | Description |
|----------|-------------|
| [Part I: Foundations](foundations.md) | Introduction, architecture overview, pattern selection guide |
| [Part II: Workflow Pattern](workflow.md) | Workflow annotations, builder DSL, lifecycle management, signals & timers, execution reporting |
| [Part III: Saga Pattern](saga.md) | Saga annotations, builder DSL, compensation policies, fan-out, saga composition |
| [Part IV: TCC Pattern](tcc.md) | TCC annotations, builder DSL, try/confirm/cancel phases, TCC composition |
| [Part V: Core Infrastructure](core-infrastructure.md) | ExecutionContext, argument injection, retry, events, persistence, DLQ, observability, backpressure, validation, metrics endpoint, event sourcing |
| [Part VI: Configuration](configuration.md) | Configuration properties, auto-configuration chain, Spring Boot integration |
| [Part VII: Recipes & Production](recipes-and-production.md) | Pattern composition, testing, error handling, production checklist, validation & reporting, event sourcing |

---

## Table of Contents

### Part I: Foundations
- [§1 Introduction & Quick Start](foundations.md#1-introduction--quick-start)
- [§2 Architecture Overview](foundations.md#2-architecture-overview)
- [§3 Pattern Selection Guide](foundations.md#3-pattern-selection-guide)

### Part II: Workflow Pattern
- [§4 Workflow Annotation Reference](workflow.md#4-workflow-annotation-reference)
- [§5 Workflow Tutorial](workflow.md#5-workflow-tutorial)
- [§6 Workflow Lifecycle Management](workflow.md#6-workflow-lifecycle-management)
- [§7 Signals and Timers](workflow.md#7-signals-and-timers)
- [§8 Workflow Builder DSL](workflow.md#8-workflow-builder-dsl)
- [§9 WorkflowEngine API](workflow.md#9-workflowengine-api)
- [§10 Child Workflows](workflow.md#10-child-workflows)
- [§11 Search Attributes & Queries](workflow.md#11-search-attributes--queries)
- [§12 Execution Reporting for Workflows](workflow.md#12-execution-reporting-for-workflows)

### Part III: Saga Pattern
- [§12 Saga Annotation Reference](saga.md#12-saga-annotation-reference)
- [§13 Saga Tutorial](saga.md#13-saga-tutorial)
- [§14 Saga Compensation Deep Dive](saga.md#14-saga-compensation-deep-dive)
- [§15 ExpandEach (Fan-Out)](saga.md#15-expandeach-fan-out)
- [§16 Saga Builder DSL](saga.md#16-saga-builder-dsl)
- [§17 SagaEngine API](saga.md#17-sagaengine-api)
- [§18 SagaResult](saga.md#18-sagaresult)
- [§19 Saga Composition](saga.md#19-saga-composition)

### Part IV: TCC Pattern
- [§19 TCC Annotation Reference](tcc.md#19-tcc-annotation-reference)
- [§20 TCC Tutorial](tcc.md#20-tcc-tutorial)
- [§21 TCC Phases & Timeout/Retry](tcc.md#21-tcc-phases--timeoutretry)
- [§22 TCC Builder DSL](tcc.md#22-tcc-builder-dsl)
- [§23 TccEngine API](tcc.md#23-tccengine-api)
- [§24 TccResult](tcc.md#24-tccresult)
- [§25 TCC Composition](tcc.md#25-tcc-composition)

### Part V: Core Infrastructure
- [§25 ExecutionContext](core-infrastructure.md#25-executioncontext)
- [§26 Argument Injection (Parameter Resolution)](core-infrastructure.md#26-argument-injection-parameter-resolution)
- [§27 RetryPolicy](core-infrastructure.md#27-retrypolicy)
- [§28 Event Integration](core-infrastructure.md#28-event-integration)
- [§29 Scheduling](core-infrastructure.md#29-scheduling)
- [§30 Lifecycle Callbacks](core-infrastructure.md#30-lifecycle-callbacks)
- [§31 Persistence](core-infrastructure.md#31-persistence)
- [§32 Dead-Letter Queue](core-infrastructure.md#32-dead-letter-queue)
- [§33 Recovery Service](core-infrastructure.md#33-recovery-service)
- [§34 Observability: Events Interface](core-infrastructure.md#34-observability-events-interface)
- [§35 Observability: Metrics & Tracing](core-infrastructure.md#35-observability-metrics--tracing)
- [§36 Topology & DAG Execution](core-infrastructure.md#36-topology--dag-execution)
- [§37 REST API](core-infrastructure.md#37-rest-api)
- [§38 Backpressure Strategies](core-infrastructure.md#38-backpressure-strategies)
- [§39 Execution Reporting](core-infrastructure.md#39-execution-reporting)
- [§40 Validation Framework](core-infrastructure.md#40-validation-framework)
- [§41 Metrics Endpoint](core-infrastructure.md#41-metrics-endpoint)
- [§42 Event Sourcing](core-infrastructure.md#42-event-sourcing)

### Part VI: Configuration
- [§38 Configuration Properties](configuration.md#38-configuration-properties)
- [§39 Auto-Configuration Chain](configuration.md#39-auto-configuration-chain)
- [§40 Spring Boot Integration](configuration.md#40-spring-boot-integration)

### Part VII: Recipes & Production
- [§41 Recipe: Composing Patterns](recipes-and-production.md#41-recipe-composing-patterns)
- [§42 Recipe: Testing Orchestrations](recipes-and-production.md#42-recipe-testing-orchestrations)
- [§43 Recipe: Error Handling](recipes-and-production.md#43-recipe-error-handling)
- [§44 Recipe: Event-Driven Architecture](recipes-and-production.md#44-recipe-event-driven-architecture)
- [§45 Production Checklist](recipes-and-production.md#45-production-checklist)
- [§46 Resilience Patterns](recipes-and-production.md#46-resilience-patterns)
- [§47 Continue-as-New](recipes-and-production.md#47-continue-as-new)
- [§48 FAQ & Troubleshooting](recipes-and-production.md#48-faq--troubleshooting)
- [§49 Recipe: Validation & Reporting](recipes-and-production.md#49-recipe-validation--reporting)
- [§50 Recipe: Event Sourcing](recipes-and-production.md#50-recipe-event-sourcing)

---

*Copyright 2024-2026 Firefly Software Solutions Inc. Licensed under the Apache License, Version 2.0.*
