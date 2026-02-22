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
- [§13 Saga Annotation Reference](saga.md#13-saga-annotation-reference)
- [§14 Saga Tutorial](saga.md#14-saga-tutorial)
- [§15 Saga Compensation Deep Dive](saga.md#15-saga-compensation-deep-dive)
- [§16 ExpandEach (Fan-Out)](saga.md#16-expandeach-fan-out)
- [§17 Saga Builder DSL](saga.md#17-saga-builder-dsl)
- [§18 SagaEngine API](saga.md#18-sagaengine-api)
- [§19 SagaResult](saga.md#19-sagaresult)
- [§20 Saga Composition](saga.md#20-saga-composition)

### Part IV: TCC Pattern
- [§21 TCC Annotation Reference](tcc.md#21-tcc-annotation-reference)
- [§22 TCC Tutorial](tcc.md#22-tcc-tutorial)
- [§23 TCC Phases & Timeout/Retry](tcc.md#23-tcc-phases--timeoutretry)
- [§24 TCC Builder DSL](tcc.md#24-tcc-builder-dsl)
- [§25 TccEngine API](tcc.md#25-tccengine-api)
- [§26 TccResult](tcc.md#26-tccresult)
- [§27 TCC Composition](tcc.md#27-tcc-composition)

### Part V: Core Infrastructure
- [§28 ExecutionContext](core-infrastructure.md#28-executioncontext)
- [§29 Argument Injection (Parameter Resolution)](core-infrastructure.md#29-argument-injection-parameter-resolution)
- [§30 RetryPolicy](core-infrastructure.md#30-retrypolicy)
- [§31 Event Integration](core-infrastructure.md#31-event-integration)
- [§32 Scheduling](core-infrastructure.md#32-scheduling)
- [§33 Lifecycle Callbacks](core-infrastructure.md#33-lifecycle-callbacks)
- [§34 Persistence](core-infrastructure.md#34-persistence)
- [§35 Dead-Letter Queue](core-infrastructure.md#35-dead-letter-queue)
- [§36 Recovery Service](core-infrastructure.md#36-recovery-service)
- [§37 Observability: Events Interface](core-infrastructure.md#37-observability-events-interface)
- [§38 Observability: Metrics & Tracing](core-infrastructure.md#38-observability-metrics--tracing)
- [§39 Topology & DAG Execution](core-infrastructure.md#39-topology--dag-execution)
- [§40 REST API](core-infrastructure.md#40-rest-api)
- [§41 Backpressure Strategies](core-infrastructure.md#41-backpressure-strategies)
- [§42 Execution Reporting](core-infrastructure.md#42-execution-reporting)
- [§43 Validation Framework](core-infrastructure.md#43-validation-framework)
- [§44 Metrics Endpoint](core-infrastructure.md#44-metrics-endpoint)
- [§45 Event Sourcing](core-infrastructure.md#45-event-sourcing)

### Part VI: Configuration
- [§46 Configuration Properties](configuration.md#46-configuration-properties)
- [§47 Auto-Configuration Chain](configuration.md#47-auto-configuration-chain)
- [§48 Spring Boot Integration](configuration.md#48-spring-boot-integration)

### Part VII: Recipes & Production
- [§49 Recipe: Composing Patterns](recipes-and-production.md#49-recipe-composing-patterns)
- [§50 Recipe: Testing Orchestrations](recipes-and-production.md#50-recipe-testing-orchestrations)
- [§51 Recipe: Error Handling](recipes-and-production.md#51-recipe-error-handling)
- [§52 Recipe: Event-Driven Architecture](recipes-and-production.md#52-recipe-event-driven-architecture)
- [§53 Production Checklist](recipes-and-production.md#53-production-checklist)
- [§54 Resilience Patterns](recipes-and-production.md#54-resilience-patterns)
- [§55 Continue-as-New](recipes-and-production.md#55-continue-as-new)
- [§56 FAQ & Troubleshooting](recipes-and-production.md#56-faq--troubleshooting)
- [§57 Recipe: Validation & Reporting](recipes-and-production.md#57-recipe-validation--reporting)
- [§58 Recipe: Event Sourcing](recipes-and-production.md#58-recipe-event-sourcing)

---

*Copyright 2024-2026 Firefly Software Solutions Inc. Licensed under the Apache License, Version 2.0.*
