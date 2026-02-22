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
- [§49 Recipe: Validation & Reporting](#49-recipe-validation--reporting)
- [§50 Recipe: Event Sourcing](#50-recipe-event-sourcing)

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

### Saga Composition

Use `SagaCompositionBuilder` to compose multiple sagas into a single execution graph with automatic dependency resolution, parallel execution, and data flow:

```java
SagaComposition composition = SagaCompositionBuilder.create("order-fulfillment")
        .saga("validate")
            .sagaName("OrderValidationSaga")
            .add()
        .saga("payment")
            .sagaName("PaymentSaga")
            .dependsOn("validate")
            .add()
        .saga("shipping")
            .sagaName("ShippingSaga")
            .dependsOn("payment")
            .optional(true)
            .add()
        .saga("notification")
            .sagaName("NotificationSaga")
            .dependsOn("payment")
            .add()
        .dataFlow("validate", "orderId", "payment", "orderId")
        .dataFlow("payment", "paymentId", "shipping", "paymentRef")
        .build();
```

Sagas in the same DAG layer (no dependency between them) run in parallel. In this example, `shipping` and `notification` both depend on `payment` so they execute in parallel once `payment` completes. Setting `optional(true)` on `shipping` means its failure does not fail the composition — useful for best-effort side effects like delivery tracking. Data flows use `DataMapping` to pipe result fields from one saga into the inputs of another.

Key classes:

- `SagaComposition` — the composed graph of sagas
- `SagaCompositionBuilder` — fluent builder for constructing compositions
- `CompositionContext` — shared context across all sagas in a composition
- `CompositionDataFlowManager` — manages `DataMapping` pipelines between sagas
- `CompositionCompensationManager` — coordinates compensation across composed sagas
- `CompositionValidator` — validates the composition graph (cycles, missing refs)
- `CompositionVisualizationService` — generates DOT and Mermaid graph output
- `CompositionTemplateRegistry` — stores reusable composition templates

### TCC Composition

Use `TccCompositionBuilder` to compose multiple TCC transactions into a layered execution graph:

```java
TccComposition composition = TccCompositionBuilder.create("distributed-payment")
        .tcc("reserve-inventory")
            .tccName("InventoryReserveTcc")
            .add()
        .tcc("charge-payment")
            .tccName("PaymentChargeTcc")
            .add()
        .tcc("update-ledger")
            .tccName("LedgerUpdateTcc")
            .dependsOn("reserve-inventory", "charge-payment")
            .add()
        .dataMapping("reserve-inventory", "reservationId", "update-ledger", "reservationRef")
        .dataMapping("charge-payment", "transactionId", "update-ledger", "paymentRef")
        .build();
```

In this example, `reserve-inventory` and `charge-payment` have no dependencies on each other, so they run in parallel. `update-ledger` waits for both to complete. On failure, `TccCompositionCompensationManager` compensates confirmed TCCs in reverse topological order. `TccCompositor` handles layer-by-layer execution of the TCC graph.

Key classes:

- `TccComposition` — the composed graph of TCC transactions
- `TccCompositionBuilder` — fluent builder for constructing TCC compositions
- `TccCompositor` — executes the TCC composition layer by layer
- `TccCompositionResult` — result of the composed TCC execution
- `TccCompositionContext` — shared context across all TCCs in a composition
- `TccCompositionCompensationManager` — compensates confirmed TCCs on failure
- `TccCompositionDataFlowManager` — manages data mappings between TCCs
- `TccCompositionValidator` — validates the TCC composition graph
- `TccCompositionVisualizationService` — generates DOT and Mermaid graph output

### Composition Templates

Use `CompositionTemplateRegistry` to register reusable composition templates that can be retrieved and executed by name:

```java
@Component
public class MyCompositionTemplates {
    @Autowired
    private CompositionTemplateRegistry templateRegistry;

    @PostConstruct
    void registerTemplates() {
        templateRegistry.register("order-fulfillment", composition);
    }
}
// Later: retrieve and execute
SagaComposition composition = templateRegistry.get("order-fulfillment");
```

This is useful for compositions that are defined once and executed many times with different inputs, such as standard business processes that are shared across services.

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

### Compensation Error Handler Selection

The framework provides several compensation error handler strategies. Select the handler that matches your reliability requirements:

| Handler | Behavior | Use When |
|---------|----------|----------|
| `"default"` (`DefaultCompensationErrorHandler`) | Retry with backoff | General-purpose default |
| `"fail-fast"` (`FailFastErrorHandler`) | Always returns `FAIL_SAGA` | Critical compensations that must not be retried |
| `"log-and-continue"` (`LogAndContinueErrorHandler`) | Logs WARN, returns `CONTINUE` | Best-effort compensations |
| `"retry"` (`RetryWithBackoffErrorHandler`) | Configurable retry with backoff | Transient failures expected |
| `"robust"` (`CompositeCompensationErrorHandler`) | Chains retry then log-and-continue | Production systems |

Configure globally via application properties:

```yaml
firefly:
  orchestration:
    saga:
      compensation-error-handler: robust
```

Or resolve programmatically:

```java
CompensationErrorHandler handler = CompensationErrorHandlerFactory.getHandler("robust");
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

### Backpressure Strategies

The framework provides pluggable backpressure strategies to protect downstream services:

| Strategy | Behavior | Use When |
|----------|----------|----------|
| `adaptive` | Dynamic concurrency based on error rate | General-purpose, unknown load |
| `batched` | Fixed-size batch processing | Rate-limited APIs, bulk operations |
| `circuit-breaker` | Three-state circuit breaker | Fragile downstream dependencies |
| `circuit-breaker-aggressive` | Low threshold (2 failures), long recovery (60s) | Very fragile dependencies |
| `circuit-breaker-conservative` | High threshold (10 failures), short recovery (15s) | Stable dependencies, occasional blips |

Configuration example:

```yaml
firefly:
  orchestration:
    backpressure:
      strategy: circuit-breaker
      circuit-breaker:
        failure-threshold: 3
        recovery-timeout: 45s
        half-open-max-calls: 2
```

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

## 49. Recipe: Validation & Reporting

### Validating Orchestration Definitions

Use `OrchestrationValidator` to validate saga, TCC, and workflow definitions before execution. Validation catches configuration errors early — duplicate step IDs, missing compensations, cycle detection, and more.

```java
OrchestrationValidator validator = new OrchestrationValidator();
List<ValidationIssue> issues = validator.validateSaga(sagaDef);
validator.validateAndThrow(issues); // throws if ERROR-level issues exist
```

Example validation output:

```
[validation] WARNING: Step has no compensation method defined at saga.order-processing.step.notify
[validation] ERROR: Duplicate step ID 'validate' at saga.order-processing.step.validate
```

WARNING-level issues are informational and do not prevent execution. ERROR-level issues cause `validateAndThrow()` to throw an exception.

Enable automatic validation on startup via configuration:

```yaml
firefly:
  orchestration:
    validation:
      enabled: true
```

### Execution Reporting

Every orchestration result includes an optional `ExecutionReport` that provides metrics and per-step details:

```java
SagaResult result = sagaEngine.execute("OrderSaga", inputs).block();
ExecutionReport report = result.report().orElseThrow();

// Top-level metrics
report.isSuccess();           // true/false
report.stepCount();           // total steps
report.failedStepCount();     // failed steps
report.completedStepCount();  // successful steps
report.totalRetries();        // total retry attempts

// Per-step details
StepReport stepReport = report.stepReports().get("validate");
stepReport.status();    // StepStatus.DONE
stepReport.attempts();  // 1
stepReport.latency();   // Duration
```

Execution reporting works the same across all patterns:

- **Saga:** `sagaResult.report()`
- **TCC:** `tccResult.report()`
- **Workflow:** `executionState.report()`

---

## 50. Recipe: Event Sourcing

### Setup

Configure the `EventSourcedPersistenceProvider` to store execution state as an append-only event log instead of mutable snapshots:

```java
@Bean
public ExecutionPersistenceProvider persistenceProvider(EventStore eventStore, ObjectMapper mapper) {
    return new EventSourcedPersistenceProvider(eventStore, mapper);
}
```

Or enable via configuration:

```yaml
firefly:
  orchestration:
    persistence:
      provider: event-sourced
```

### Querying Execution History

The event-sourced provider includes an `OrchestrationProjection` for querying materialized views of execution history:

```java
EventSourcedPersistenceProvider provider = ...;
OrchestrationProjection projection = provider.getProjection();

// Find all failed executions
List<ExecutionSummary> failed = projection.findByStatus(ExecutionStatus.FAILED);

// Find all saga executions
List<ExecutionSummary> sagas = projection.findByPattern(ExecutionPattern.SAGA);

// Get specific execution summary
Optional<ExecutionSummary> summary = projection.getExecutionSummary(correlationId);
```

### Snapshot Configuration

Snapshots reduce projection rebuild time by periodically capturing the current state:

```yaml
firefly:
  orchestration:
    eventsourcing:
      snapshot-interval: 100     # events between snapshots
      projection-poll-interval: 5s
    persistence:
      provider: event-sourced
```

**Important notes:**

- Events are immutable — `cleanup()` is a no-op in event-sourced mode.
- `findInFlight()` and `findStale()` are not efficiently supported in event-sourced mode. Use the projection queries instead.

---

[← Back to Index](README.md)

---

*Copyright 2024-2026 Firefly Software Solutions Inc. Licensed under the Apache License, Version 2.0.*
