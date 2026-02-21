[← Back to Index](README.md) | [Previous: Configuration](configuration.md)

# Part VII: Recipes & Production

**Contents:**
- [§41 Recipe: Composing Patterns](#41-recipe-composing-patterns)
- [§42 Recipe: Testing Orchestrations](#42-recipe-testing-orchestrations)
- [§43 Recipe: Error Handling](#43-recipe-error-handling)
- [§44 Recipe: Event-Driven Architecture](#44-recipe-event-driven-architecture)
- [§45 Production Checklist](#45-production-checklist)
- [§46 Resilience Patterns](#46-resilience-patterns)
- [§47 Continue-as-New](#47-continue-as-new)
- [§48 FAQ & Troubleshooting](#48-faq--troubleshooting)

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

[← Back to Index](README.md)

---

*Copyright 2024-2026 Firefly Software Solutions Inc. Licensed under the Apache License, Version 2.0.*
