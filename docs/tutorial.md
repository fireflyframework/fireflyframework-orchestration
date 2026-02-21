[← Back to Index](README.md)

# Tutorial: Fintech Payment Processing

Build a complete **inter-bank fund transfer system** using all three Firefly Orchestration patterns. By the end, you'll have a working system that:

- **Reserves funds** using TCC (try/confirm/cancel) for strong isolation
- **Orchestrates the transfer** using Saga with automatic compensation on failure
- **Tracks settlement** using Workflow with signals and timers for long-running lifecycle

> **Prerequisites:** Java 25+, Spring Boot 3.x, Maven, basic familiarity with Project Reactor (`Mono`/`Flux`).

---

## Table of Contents

- [1. Overview: What We're Building](#1-overview-what-were-building)
- [2. Project Setup](#2-project-setup)
- [3. Domain Model](#3-domain-model)
- [4. Building the Account Reservation TCC](#4-building-the-account-reservation-tcc)
- [5. Building the Fund Transfer Saga](#5-building-the-fund-transfer-saga)
- [6. Building the Settlement Tracking Workflow](#6-building-the-settlement-tracking-workflow)
- [7. Composing the Patterns](#7-composing-the-patterns)
- [8. Builder DSL Alternative](#8-builder-dsl-alternative)
- [9. Adding Observability](#9-adding-observability)
- [10. Production Configuration](#10-production-configuration)
- [11. Running the Complete System](#11-running-the-complete-system)

---

## 1. Overview: What We're Building

A fund transfer between two bank accounts involves three distinct coordination challenges:

```
Customer initiates transfer: $500 from Account A → Account B

1. RESERVE FUNDS (TCC)
   Try:     Place hold on $500 in Account A, prepare credit for Account B
   Confirm: Finalize debit + credit (all holds succeeded)
   Cancel:  Release holds (if any hold failed)

2. ORCHESTRATE TRANSFER (Saga)
   Step 1: Validate accounts & compliance (AML/KYC check)
   Step 2: Execute TCC reservation (triggers the TCC above)
   Step 3: Record ledger entries
   Step 4: Send notifications
   On failure: Compensate all completed steps automatically

3. TRACK SETTLEMENT (Workflow)
   Step 1: Create settlement record
   Step 2: Wait for clearing house confirmation (signal)
   Step 3: Wait for settlement window (timer — T+1 delay)
   Step 4: Reconcile and close
```

### Why Three Patterns?

| Pattern | Role | Why This Pattern? |
|---------|------|-------------------|
| **TCC** | Account reservation | Need strong isolation — funds must be soft-locked before commit to prevent double-spend |
| **Saga** | Transfer orchestration | Need compensating transactions — if ledger posting fails, TCC must be canceled and accounts restored |
| **Workflow** | Settlement tracking | Long-running process with external signals (clearing house) and timed delays (T+1 settlement) |

---

## 2. Project Setup

### Maven Dependency

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-orchestration</artifactId>
    <version>26.02.06</version>
</dependency>
```

### Application Properties

```yaml
firefly:
  orchestration:
    saga:
      enabled: true
      compensation-policy: RETRY_WITH_BACKOFF
    tcc:
      enabled: true
      default-timeout: 30s
    workflow:
      enabled: true
    persistence:
      provider: in-memory
    dlq:
      enabled: true
    metrics:
      enabled: true
    tracing:
      enabled: true
```

### Package Structure

```
com.example.transfer/
├── TransferApplication.java
├── domain/
│   ├── TransferRequest.java
│   ├── Account.java
│   ├── LedgerEntry.java
│   └── SettlementStatus.java
├── service/
│   ├── AccountService.java
│   ├── ComplianceService.java
│   ├── LedgerService.java
│   └── NotificationService.java
├── orchestration/
│   ├── AccountReservationTcc.java
│   ├── FundTransferSaga.java
│   └── SettlementTrackingWorkflow.java
└── api/
    └── TransferController.java
```

---

## 3. Domain Model

```java
// Transfer request — the input to the entire flow
public record TransferRequest(
    String fromAccountId,
    String toAccountId,
    BigDecimal amount,
    String currency,
    String reference
) {}

// Account snapshot
public record Account(
    String accountId,
    String accountHolder,
    BigDecimal balance,
    String currency,
    boolean active
) {}

// Ledger entry created after successful reservation
public record LedgerEntry(
    String entryId,
    String debitAccountId,
    String creditAccountId,
    BigDecimal amount,
    String currency,
    Instant timestamp
) {}

// Settlement lifecycle
public enum SettlementStatus {
    INITIATED, CLEARING, SETTLED, RECONCILED, CLOSED
}
```

### Service Interfaces

These are the downstream services our orchestrations will call. In a real system, these would be separate microservices or database operations.

```java
@Service
public class AccountService {
    public Mono<String> holdFunds(String accountId, BigDecimal amount) { /* ... */ }
    public Mono<Void> commitHold(String holdId) { /* ... */ }
    public Mono<Void> releaseHold(String holdId) { /* ... */ }
    public Mono<String> prepareCredit(String accountId, BigDecimal amount) { /* ... */ }
    public Mono<Void> commitCredit(String prepId) { /* ... */ }
    public Mono<Void> cancelCredit(String prepId) { /* ... */ }
}

@Service
public class ComplianceService {
    public Mono<Boolean> checkAmlKyc(String fromAccount, String toAccount, BigDecimal amount) { /* ... */ }
}

@Service
public class LedgerService {
    public Mono<LedgerEntry> postEntry(String debitAccount, String creditAccount,
                                        BigDecimal amount, String currency) { /* ... */ }
    public Mono<Void> reverseEntry(String entryId) { /* ... */ }
}

@Service
public class NotificationService {
    public Mono<Void> notifyTransferComplete(String reference, BigDecimal amount) { /* ... */ }
}
```

---

## 4. Building the Account Reservation TCC

### Why TCC for Account Reservations?

In fund transfers, we need **strong isolation** — when Account A's $500 is being transferred, no other transaction should be able to spend those same funds. TCC provides this through its Try phase: funds are soft-locked (held) during Try, committed during Confirm, or released during Cancel.

The key difference from a Saga: in a Saga, the debit would execute immediately (real money moves). In TCC, the Try phase only *reserves* — nothing is committed until all participants confirm.

### Step-by-Step Implementation

```java
@Tcc(name = "AccountReservation", timeoutMs = 30000,
     retryEnabled = true, maxRetries = 3, backoffMs = 500)
public class AccountReservationTcc {

    @TccParticipant(id = "debit", order = 0)
    public static class DebitParticipant {

        private final AccountService accountService;

        public DebitParticipant(AccountService accountService) {
            this.accountService = accountService;
        }

        @TryMethod(timeoutMs = 5000, retry = 2, backoffMs = 200)
        public Mono<String> tryHoldFunds(@Input TransferRequest request) {
            // Soft-lock the funds — no real debit yet
            return accountService.holdFunds(
                request.fromAccountId(), request.amount());
        }

        @ConfirmMethod(timeoutMs = 5000, retry = 3, backoffMs = 500)
        public Mono<Void> confirmDebit(@FromTry("debit") String holdId) {
            // All holds succeeded — finalize the debit
            return accountService.commitHold(holdId);
        }

        @CancelMethod(timeoutMs = 5000, retry = 3, backoffMs = 500)
        public Mono<Void> cancelDebit(@FromTry("debit") String holdId) {
            // Some hold failed — release the reserved funds
            return accountService.releaseHold(holdId);
        }
    }

    @TccParticipant(id = "credit", order = 1)
    @TccEvent(topic = "transfers", eventType = "CreditPrepared", key = "prepId")
    public static class CreditParticipant {

        private final AccountService accountService;

        public CreditParticipant(AccountService accountService) {
            this.accountService = accountService;
        }

        @TryMethod(timeoutMs = 5000)
        public Mono<String> tryPrepareCredit(@Input TransferRequest request) {
            // Prepare the credit — validate target account, check limits
            return accountService.prepareCredit(
                request.toAccountId(), request.amount());
        }

        @ConfirmMethod(timeoutMs = 5000, retry = 3)
        public Mono<Void> confirmCredit(@FromTry("credit") String prepId) {
            return accountService.commitCredit(prepId);
        }

        @CancelMethod(timeoutMs = 5000, retry = 3)
        public Mono<Void> cancelCredit(@FromTry("credit") String prepId) {
            return accountService.cancelCredit(prepId);
        }
    }

    @OnTccComplete
    public void onComplete(TccResult result) {
        log.info("Account reservation confirmed: {} (duration: {}ms)",
            result.correlationId(), result.duration().toMillis());
    }

    @OnTccError
    public void onError(Throwable error, ExecutionContext ctx) {
        log.error("Account reservation failed: {}", ctx.getCorrelationId(), error);
    }
}
```

### Key Design Decisions

1. **`order = 0` for debit, `order = 1` for credit** — Debit runs first. If the debit hold fails, we never even attempt the credit preparation.

2. **`@FromTry("debit")` in confirm/cancel** — The hold ID returned by the Try phase is automatically injected into Confirm and Cancel methods. No manual state passing needed.

3. **Per-phase retry configuration** — Try has 2 retries with 200ms backoff (fast fail); Confirm has 3 retries with 500ms backoff (more patient for finalization).

4. **`@TccEvent` on credit participant** — Publishes a `CreditPrepared` event when the credit's Confirm phase succeeds, for downstream consumers.

### Testing the TCC in Isolation

```java
@Test
void accountReservation_allHoldsSucceed_confirmed() {
    TccInputs inputs = TccInputs.builder()
        .forParticipant("debit", new TransferRequest("ACC-001", "ACC-002",
            BigDecimal.valueOf(500), "USD", "TXN-001"))
        .forParticipant("credit", new TransferRequest("ACC-001", "ACC-002",
            BigDecimal.valueOf(500), "USD", "TXN-001"))
        .build();

    StepVerifier.create(tccEngine.execute("AccountReservation", inputs))
        .assertNext(result -> {
            assertThat(result.isConfirmed()).isTrue();
            assertThat(result.tryResultOf("debit", String.class)).isPresent();
            assertThat(result.tryResultOf("credit", String.class)).isPresent();
        })
        .verifyComplete();
}

@Test
void accountReservation_debitFails_creditNeverTried() {
    // When debit Try fails, credit Try never runs (order = 1 > 0)
    // No Cancel needed — debit never succeeded
    when(accountService.holdFunds(any(), any()))
        .thenReturn(Mono.error(new InsufficientFundsException("Insufficient balance")));

    StepVerifier.create(tccEngine.execute("AccountReservation", inputs))
        .assertNext(result -> {
            assertThat(result.isCanceled()).isTrue();
            assertThat(result.failedParticipantId()).contains("debit");
            assertThat(result.failedPhase()).contains(TccPhase.TRY);
        })
        .verifyComplete();
}
```

---

## 5. Building the Fund Transfer Saga

### Why Saga for Transfer Orchestration?

The fund transfer involves multiple steps that must either all succeed or all be undone. Unlike TCC (which handles the reservation phase), the saga orchestrates the **entire transfer lifecycle** — validation, reservation, ledger posting, and notification. Each step has a compensating action:

```
validateAccounts   →  (no compensation — validation is idempotent)
reserveFunds       →  cancelReservation (triggers TCC cancel)
postLedger         →  reverseLedger
notifyCustomer     →  (no compensation — notification is best-effort)
```

### Step-by-Step Implementation

```java
@Saga(name = "FundTransfer", triggerEventType = "TransferRequested")
public class FundTransferSaga {

    private final ComplianceService complianceService;
    private final TccEngine tccEngine;
    private final LedgerService ledgerService;
    private final NotificationService notificationService;

    public FundTransferSaga(ComplianceService complianceService,
                             TccEngine tccEngine,
                             LedgerService ledgerService,
                             NotificationService notificationService) {
        this.complianceService = complianceService;
        this.tccEngine = tccEngine;
        this.ledgerService = ledgerService;
        this.notificationService = notificationService;
    }

    // ── Step 1: Validate accounts and compliance ──────────────────

    @SagaStep(id = "validate", timeoutMs = 10000, retry = 2, backoffMs = 500)
    public Mono<Boolean> validateAccounts(@Input TransferRequest request) {
        return complianceService.checkAmlKyc(
            request.fromAccountId(),
            request.toAccountId(),
            request.amount());
    }

    // ── Step 2: Reserve funds via TCC ─────────────────────────────

    @SagaStep(id = "reserve", dependsOn = "validate",
              compensate = "cancelReservation",
              timeoutMs = 35000, retry = 1)
    @StepEvent(topic = "transfers", type = "FundsReserved", key = "correlationId")
    public Mono<TccResult> reserveFunds(
            @Input TransferRequest request,
            @CorrelationId String correlationId) {
        TccInputs inputs = TccInputs.builder()
            .forParticipant("debit", request)
            .forParticipant("credit", request)
            .build();
        return tccEngine.execute("AccountReservation", inputs)
            .flatMap(result -> {
                if (result.isConfirmed()) {
                    return Mono.just(result);
                }
                return Mono.error(new RuntimeException(
                    "Account reservation failed: " +
                    result.failedParticipantId().orElse("unknown")));
            });
    }

    public Mono<Void> cancelReservation(@FromStep("reserve") TccResult tccResult) {
        // TCC already handles cancel internally — this compensation
        // is for cases where the saga needs to explicitly undo
        log.info("Reservation already canceled by TCC for correlation: {}",
            tccResult.correlationId());
        return Mono.empty();
    }

    // ── Step 3: Post ledger entries ───────────────────────────────

    @SagaStep(id = "ledger", dependsOn = "reserve",
              compensate = "reverseLedger",
              retry = 3, backoffMs = 1000, timeoutMs = 15000,
              jitter = true, jitterFactor = 0.3,
              compensationRetry = 5, compensationBackoffMs = 2000,
              compensationCritical = true)
    public Mono<LedgerEntry> postLedger(
            @Input TransferRequest request,
            @FromStep("reserve") TccResult reservation) {
        return ledgerService.postEntry(
            request.fromAccountId(),
            request.toAccountId(),
            request.amount(),
            request.currency());
    }

    public Mono<Void> reverseLedger(@FromStep("ledger") LedgerEntry entry) {
        return ledgerService.reverseEntry(entry.entryId());
    }

    // ── Step 4: Notify customer ───────────────────────────────────

    @SagaStep(id = "notify", dependsOn = "ledger",
              timeoutMs = 5000, retry = 2)
    public Mono<Void> notifyCustomer(@Input TransferRequest request) {
        return notificationService.notifyTransferComplete(
            request.reference(), request.amount());
    }

    // ── Lifecycle callbacks ───────────────────────────────────────

    @OnSagaComplete(priority = 0)
    public void onComplete(SagaResult result) {
        log.info("Fund transfer completed: {} (duration: {}ms, steps: {})",
            result.correlationId(),
            result.duration().toMillis(),
            result.steps().size());
    }

    @OnSagaError(priority = 0)
    public void onError(Throwable error, ExecutionContext ctx) {
        log.error("Fund transfer failed: {} at step {}",
            ctx.getCorrelationId(),
            ctx.getStepStatuses().entrySet().stream()
                .filter(e -> e.getValue() == StepStatus.FAILED)
                .map(Map.Entry::getKey)
                .findFirst().orElse("unknown"),
            error);
    }
}
```

### Key Design Decisions

1. **`triggerEventType = "TransferRequested"`** — The saga can be triggered by an external event via `EventGateway`, enabling event-driven architecture.

2. **`compensationCritical = true` on ledger step** — If ledger reversal fails, this is a critical data integrity issue. With `RETRY_WITH_BACKOFF` policy, the compensator will retry 5 times. If it still fails, the DLQ captures it for manual resolution.

3. **TCC nested inside Saga** — Step 2 calls `tccEngine.execute()` directly. The saga provides the compensation wrapper; the TCC provides the reservation semantics.

4. **`@StepEvent` on reserve step** — Publishes a `FundsReserved` event when reservation succeeds, which can trigger downstream workflows.

### Understanding the DAG

```
         +-----------+
         | validate  |   Layer 0
         +-----+-----+
               |
         +-----v-----+
         |  reserve   |   Layer 1
         +-----+-----+
               |
         +-----v-----+
         |   ledger   |   Layer 2
         +-----+-----+
               |
         +-----v-----+
         |   notify   |   Layer 3
         +-----------+
```

All steps are sequential here — each depends on the previous. If you had independent steps (e.g., sending notifications to both sender and receiver), they'd be in the same layer and run in parallel.

### Testing the Saga

```java
@Test
void fundTransfer_happyPath() {
    TransferRequest request = new TransferRequest(
        "ACC-001", "ACC-002", BigDecimal.valueOf(500), "USD", "TXN-001");

    StepVerifier.create(
        sagaEngine.execute("FundTransfer", StepInputs.of("validate", request)))
        .assertNext(result -> {
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.steps()).containsKeys("validate", "reserve", "ledger", "notify");

            // Verify ledger entry was created
            LedgerEntry entry = result.resultOf("ledger", LedgerEntry.class).orElseThrow();
            assertThat(entry.amount()).isEqualByComparingTo(BigDecimal.valueOf(500));
        })
        .verifyComplete();
}

@Test
void fundTransfer_ledgerFailure_compensatesReservation() {
    TransferRequest request = new TransferRequest(
        "ACC-001", "ACC-002", BigDecimal.valueOf(500), "USD", "TXN-002");

    when(ledgerService.postEntry(any(), any(), any(), any()))
        .thenReturn(Mono.error(new RuntimeException("Ledger unavailable")));

    StepVerifier.create(
        sagaEngine.execute("FundTransfer", StepInputs.of("validate", request)))
        .assertNext(result -> {
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.firstErrorStepId()).contains("ledger");
            assertThat(result.compensatedSteps()).contains("reserve");
        })
        .verifyComplete();
}
```

---

## 6. Building the Settlement Tracking Workflow

### Why Workflow for Settlement?

Settlement is a **long-running process** that spans hours or days. It needs:
- **Signal gates** — wait for the clearing house to confirm the transfer
- **Timer delays** — wait for the T+1 settlement window
- **Lifecycle management** — ability to suspend/resume/cancel

Neither Saga nor TCC support signals, timers, or lifecycle management. Workflow is the only pattern designed for these requirements.

### Step-by-Step Implementation

```java
@Workflow(id = "SettlementTracking", version = "1.0",
          publishEvents = true,
          triggerEventType = "FundsReserved")
public class SettlementTrackingWorkflow {

    private final SettlementService settlementService;
    private final ReconciliationService reconciliationService;

    public SettlementTrackingWorkflow(SettlementService settlementService,
                                       ReconciliationService reconciliationService) {
        this.settlementService = settlementService;
        this.reconciliationService = reconciliationService;
    }

    // ── Step 1: Create settlement record ──────────────────────────

    @WorkflowStep(id = "initSettlement", timeoutMs = 5000)
    public Mono<String> initSettlement(@Input Map<String, Object> input) {
        String transferRef = (String) input.get("reference");
        BigDecimal amount = new BigDecimal(input.get("amount").toString());
        return settlementService.createSettlement(transferRef, amount);
    }

    // ── Step 2: Submit to clearing house ──────────────────────────

    @WorkflowStep(id = "submitClearing", dependsOn = "initSettlement",
                  timeoutMs = 10000, maxRetries = 3, retryDelayMs = 2000)
    public Mono<String> submitToClearing(
            @FromStep("initSettlement") String settlementId) {
        return settlementService.submitToClearing(settlementId);
    }

    // ── Step 3: Wait for clearing confirmation (external signal) ──

    @WorkflowStep(id = "awaitClearing", dependsOn = "submitClearing")
    @WaitForSignal(value = "clearing-confirmed", timeoutMs = 86400000)  // 24h timeout
    public Mono<String> awaitClearingConfirmation(
            @FromStep("submitClearing") String clearingRef) {
        // This method executes AFTER the signal is delivered
        return Mono.just("cleared:" + clearingRef);
    }

    // ── Step 4: Wait for settlement window (T+1 timer) ────────────

    @WorkflowStep(id = "settlementWindow", dependsOn = "awaitClearing")
    @WaitForTimer(delayMs = 86400000, timerId = "t-plus-one")  // 24 hours
    public Mono<Void> waitForSettlementWindow() {
        // Timer fires after T+1 delay — settlement window opens
        return Mono.empty();
    }

    // ── Step 5: Reconcile and close ───────────────────────────────

    @WorkflowStep(id = "reconcile", dependsOn = "settlementWindow",
                  timeoutMs = 30000, maxRetries = 5, retryDelayMs = 5000)
    public Mono<String> reconcile(
            @FromStep("initSettlement") String settlementId,
            @CorrelationId String correlationId) {
        return reconciliationService.reconcile(settlementId)
            .map(result -> "Reconciled: " + result);
    }

    // ── Lifecycle callbacks ───────────────────────────────────────

    @OnWorkflowComplete
    public void onComplete(ExecutionContext ctx) {
        log.info("Settlement completed: {} (started: {})",
            ctx.getCorrelationId(), ctx.getStartedAt());
    }

    @OnWorkflowError(errorTypes = {TimeoutException.class})
    public void onTimeout(Throwable error, ExecutionContext ctx) {
        log.error("Settlement timed out — clearing confirmation not received: {}",
            ctx.getCorrelationId());
    }
}
```

### Key Design Decisions

1. **`triggerEventType = "FundsReserved"`** — The workflow starts automatically when the saga's reserve step publishes a `FundsReserved` event. No manual wiring needed.

2. **`@WaitForSignal("clearing-confirmed")` with 24h timeout** — The workflow pauses at step 3 until the clearing house sends a confirmation signal. If no signal arrives within 24 hours, the step times out.

3. **`@WaitForTimer(delayMs = 86400000, timerId = "t-plus-one")`** — After clearing confirmation, the workflow pauses for 24 hours (T+1 settlement delay). The named `timerId` allows external cancellation if needed.

4. **`publishEvents = true`** — Every step completion publishes an `OrchestrationEvent`, enabling monitoring dashboards.

### Sending the Clearing Signal

When the clearing house confirms the transfer, an external system delivers the signal:

```java
@Service
public class ClearingHouseAdapter {
    private final SignalService signalService;

    /**
     * Called when the clearing house sends a confirmation callback.
     */
    public Mono<SignalResult> onClearingConfirmed(
            String correlationId, String clearingRef) {
        return signalService.signal(
            correlationId,
            "clearing-confirmed",
            Map.of("clearingRef", clearingRef, "confirmedAt", Instant.now()));
    }
}
```

### Lifecycle Management

```java
@Service
public class SettlementOpsService {
    private final WorkflowEngine workflowEngine;

    // Suspend a settlement (e.g., fraud investigation)
    public Mono<ExecutionState> suspendSettlement(String correlationId) {
        return workflowEngine.suspendWorkflow(correlationId,
            "Fraud investigation in progress");
    }

    // Resume after investigation clears
    public Mono<ExecutionState> resumeSettlement(String correlationId) {
        return workflowEngine.resumeWorkflow(correlationId);
    }

    // Cancel a settlement
    public Mono<ExecutionState> cancelSettlement(String correlationId) {
        return workflowEngine.cancelWorkflow(correlationId);
    }

    // Query settlement progress
    public Mono<Optional<ExecutionState>> getSettlement(String correlationId) {
        return workflowEngine.findByCorrelationId(correlationId);
    }
}
```

### Testing the Workflow

```java
@Test
void settlement_happyPath_withSignalAndTimer() {
    Map<String, Object> input = Map.of(
        "reference", "TXN-001",
        "amount", "500.00");

    // Start the workflow
    ExecutionState state = workflowEngine
        .startWorkflow("SettlementTracking", input)
        .block();

    String correlationId = state.correlationId();

    // Workflow is now waiting for the clearing signal
    assertThat(state.stepStatuses().get("awaitClearing"))
        .isEqualTo(StepStatus.PENDING);

    // Deliver the clearing confirmation signal
    signalService.signal(correlationId, "clearing-confirmed",
        Map.of("clearingRef", "CLR-789"))
        .block();

    // Workflow proceeds to the timer step, then reconciliation
    // In tests, you can use TimerService to fire timers immediately
}
```

---

## 7. Composing the Patterns

The three patterns connect through **event-driven composition** — each pattern publishes events that trigger the next.

### End-to-End Flow

```
Customer Request
       │
       ▼
  ┌─────────────────────────────────┐
  │  EventGateway.routeEvent(       │
  │    "TransferRequested", payload) │
  └──────────────┬──────────────────┘
                 │  triggers
                 ▼
  ┌─────────────────────────────────┐
  │  FundTransferSaga               │
  │  ├── validate (AML/KYC)         │
  │  ├── reserve ──────┐            │
  │  │                 ▼            │
  │  │    ┌─────────────────────┐   │
  │  │    │ AccountReservation  │   │
  │  │    │ TCC (nested call)   │   │
  │  │    └─────────────────────┘   │
  │  ├── ledger                     │
  │  └── notify                     │
  └──────────────┬──────────────────┘
                 │  @StepEvent: "FundsReserved"
                 ▼
  ┌─────────────────────────────────┐
  │  SettlementTrackingWorkflow     │
  │  ├── initSettlement             │
  │  ├── submitClearing             │
  │  ├── awaitClearing (signal)     │
  │  ├── settlementWindow (timer)   │
  │  └── reconcile                  │
  └─────────────────────────────────┘
```

### Triggering the Transfer

```java
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final EventGateway eventGateway;
    private final SagaEngine sagaEngine;

    public TransferController(EventGateway eventGateway, SagaEngine sagaEngine) {
        this.eventGateway = eventGateway;
        this.sagaEngine = sagaEngine;
    }

    // Option 1: Direct saga execution
    @PostMapping("/direct")
    public Mono<SagaResult> transferDirect(@RequestBody TransferRequest request) {
        return sagaEngine.execute("FundTransfer",
            StepInputs.of("validate", request));
    }

    // Option 2: Event-driven (saga triggers via EventGateway)
    @PostMapping("/event")
    public Mono<Void> transferViaEvent(@RequestBody TransferRequest request) {
        return eventGateway.routeEvent("TransferRequested",
            Map.of(
                "fromAccountId", request.fromAccountId(),
                "toAccountId", request.toAccountId(),
                "amount", request.amount(),
                "currency", request.currency(),
                "reference", request.reference()));
    }
}
```

### Shared Context Composition

For tighter coupling, you can share an `ExecutionContext` across multiple pattern executions:

```java
@Service
public class TransferOrchestrator {
    private final SagaEngine sagaEngine;
    private final WorkflowEngine workflowEngine;

    public Mono<SagaResult> executeTransfer(TransferRequest request) {
        ExecutionContext ctx = ExecutionContext.forSaga(null, "FundTransfer");
        ctx.putVariable("reference", request.reference());
        ctx.putHeader("X-Transfer-Type", "DOMESTIC");

        return sagaEngine.execute("FundTransfer",
                StepInputs.of("validate", request), ctx)
            .flatMap(sagaResult -> {
                if (!sagaResult.isSuccess()) return Mono.just(sagaResult);

                // Start workflow with same context variables
                Map<String, Object> workflowInput = Map.of(
                    "reference", request.reference(),
                    "amount", request.amount().toString());

                return workflowEngine.startWorkflow(
                        "SettlementTracking", workflowInput)
                    .thenReturn(sagaResult);
            });
    }
}
```

---

## 8. Builder DSL Alternative

For dynamic or test-driven composition, rewrite the saga using the programmatic `SagaBuilder`:

```java
@Service
public class DynamicTransferSaga {
    private final ComplianceService complianceService;
    private final TccEngine tccEngine;
    private final LedgerService ledgerService;
    private final NotificationService notificationService;
    private final SagaEngine sagaEngine;

    public Mono<SagaResult> execute(TransferRequest request) {
        SagaDefinition def = SagaBuilder.saga("DynamicFundTransfer")
            .triggerEventType("TransferRequested")
            .step("validate")
                .retry(2)
                .backoffMs(500)
                .timeoutMs(10_000)
                .handlerInput(input -> {
                    TransferRequest req = (TransferRequest) input;
                    return complianceService.checkAmlKyc(
                        req.fromAccountId(), req.toAccountId(), req.amount());
                })
                .add()
            .step("reserve")
                .dependsOn("validate")
                .timeoutMs(35_000)
                .retry(1)
                .stepEvent("transfers", "FundsReserved", "correlationId")
                .handler((input, ctx) -> {
                    TransferRequest req = (TransferRequest) input;
                    TccInputs tccInputs = TccInputs.builder()
                        .forParticipant("debit", req)
                        .forParticipant("credit", req)
                        .build();
                    return tccEngine.execute("AccountReservation", tccInputs)
                        .flatMap(result -> result.isConfirmed()
                            ? Mono.just(result)
                            : Mono.error(new RuntimeException("Reservation failed")));
                })
                .compensation((result, ctx) -> {
                    log.info("Compensation: reservation already handled by TCC");
                    return Mono.empty();
                })
                .add()
            .step("ledger")
                .dependsOn("reserve")
                .retry(3)
                .backoffMs(1000)
                .jitter()
                .jitterFactor(0.3)
                .compensationRetry(5)
                .compensationBackoff(Duration.ofSeconds(2))
                .compensationCritical(true)
                .handler((input, ctx) -> {
                    TransferRequest req = (TransferRequest) input;
                    return ledgerService.postEntry(
                        req.fromAccountId(), req.toAccountId(),
                        req.amount(), req.currency());
                })
                .compensation((result, ctx) -> {
                    LedgerEntry entry = (LedgerEntry) result;
                    return ledgerService.reverseEntry(entry.entryId());
                })
                .add()
            .step("notify")
                .dependsOn("ledger")
                .retry(2)
                .timeoutMs(5_000)
                .handlerInput(input -> {
                    TransferRequest req = (TransferRequest) input;
                    return notificationService.notifyTransferComplete(
                        req.reference(), req.amount());
                })
                .add()
            .build();

        return sagaEngine.execute(def, StepInputs.of("validate", request));
    }
}
```

### When to Prefer Builder vs Annotations

| Use Case | Preferred Style |
|----------|----------------|
| Static, well-defined orchestrations | **Annotations** — clearer, more readable, Spring-scanned |
| Dynamic step composition at runtime | **Builder** — compose steps based on runtime conditions |
| Integration tests | **Builder** — no annotation scanning, full control, fast setup |
| Multi-tenant with different workflows per tenant | **Builder** — build definitions dynamically per tenant |

---

## 9. Adding Observability

### Custom Event Listener

Implement `OrchestrationEvents` to add logging, alerting, or metrics:

```java
@Component
public class TransferEventListener implements OrchestrationEvents {

    private final AlertService alertService;

    @Override
    public void onStart(String name, String correlationId, ExecutionPattern pattern) {
        log.info("[{}] {} started: {}", pattern, name, correlationId);
    }

    @Override
    public void onStepSuccess(String name, String correlationId,
                               String stepId, int attempts, long latencyMs) {
        log.info("[{}] Step {} completed in {}ms (attempts: {})",
            name, stepId, latencyMs, attempts);
    }

    @Override
    public void onStepFailed(String name, String correlationId,
                              String stepId, Throwable error, int attempts) {
        log.error("[{}] Step {} failed after {} attempts: {}",
            name, stepId, attempts, error.getMessage());
    }

    @Override
    public void onCompensationStarted(String name, String correlationId) {
        alertService.warn("Compensation started for " + name +
            " (" + correlationId + ")");
    }

    @Override
    public void onDeadLettered(String name, String correlationId,
                                String stepId, Throwable error) {
        alertService.critical("DLQ entry created: " + name +
            "/" + stepId + " (" + correlationId + ")");
    }

    @Override
    public void onCompleted(String name, String correlationId,
                             ExecutionPattern pattern, boolean success, long durationMs) {
        if (!success) {
            alertService.critical(name + " FAILED: " + correlationId);
        }
        log.info("[{}] {} completed: success={}, duration={}ms",
            pattern, name, success, durationMs);
    }
}
```

### Search Attributes for Transfer Queries

Use `WorkflowSearchService` to index settlement workflows by business attributes:

```java
@Service
public class SettlementSearchService {
    private final WorkflowEngine workflowEngine;
    private final WorkflowSearchService searchService;

    public Mono<ExecutionState> startTracked(TransferRequest request,
                                              Map<String, Object> workflowInput) {
        return workflowEngine.startWorkflow("SettlementTracking", workflowInput)
            .flatMap(state -> searchService.updateSearchAttributes(
                state.correlationId(),
                Map.of(
                    "reference", request.reference(),
                    "fromAccount", request.fromAccountId(),
                    "toAccount", request.toAccountId(),
                    "amount", request.amount().toString(),
                    "currency", request.currency()))
                .thenReturn(state));
    }

    public Flux<ExecutionState> findByAccount(String accountId) {
        return searchService.searchByAttribute("fromAccount", accountId);
    }

    public Flux<ExecutionState> findByReference(String reference) {
        return searchService.searchByAttribute("reference", reference);
    }
}
```

---

## 10. Production Configuration

### Full Production YAML

```yaml
firefly:
  orchestration:
    # Pattern configuration
    saga:
      enabled: true
      compensation-policy: CIRCUIT_BREAKER
      default-timeout: 2m
    tcc:
      enabled: true
      default-timeout: 30s
    workflow:
      enabled: true

    # Durable persistence
    persistence:
      provider: redis
      key-prefix: "prod:transfers:"
      key-ttl: 90d
      retention-period: 30d
      cleanup-interval: 30m

    # Recovery
    recovery:
      enabled: true
      stale-threshold: 15m

    # Scheduling
    scheduling:
      thread-pool-size: 8

    # Dead-letter queue
    dlq:
      enabled: true

    # Observability
    metrics:
      enabled: true
    tracing:
      enabled: true

    # REST API
    rest:
      enabled: true
    health:
      enabled: true

    # Resilience
    resilience:
      enabled: true
```

### Custom Persistence (Redis)

Add the Redis dependency:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
</dependency>
```

Configure Redis connection:

```yaml
spring:
  data:
    redis:
      host: redis.internal
      port: 6379
      password: ${REDIS_PASSWORD}
      ssl:
        enabled: true
```

The `RedisPersistenceProvider` activates automatically when:
- `spring-data-redis-reactive` is on the classpath
- `firefly.orchestration.persistence.provider=redis` is set

### DLQ Monitoring

```java
@Component
public class DlqMonitor {
    private final DeadLetterService dlqService;
    private final AlertService alertService;

    @Scheduled(fixedDelay = 60000)
    public void checkDlq() {
        dlqService.count()
            .filter(count -> count > 0)
            .subscribe(count ->
                alertService.warn("Transfer DLQ has " + count + " entries"));
    }
}
```

### Health Check

With `health.enabled=true`, the `/actuator/health` endpoint includes:

```json
{
  "status": "UP",
  "components": {
    "orchestration": {
      "status": "UP",
      "details": {
        "persistence": "healthy"
      }
    }
  }
}
```

---

## 11. Running the Complete System

### Application Class

```java
@SpringBootApplication
public class TransferApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransferApplication.class, args);
    }
}
```

### Triggering a Transfer

```bash
# Direct saga execution
curl -X POST http://localhost:8080/api/transfers/direct \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccountId": "ACC-001",
    "toAccountId": "ACC-002",
    "amount": 500.00,
    "currency": "USD",
    "reference": "TXN-20260221-001"
  }'

# Event-driven execution
curl -X POST http://localhost:8080/api/transfers/event \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccountId": "ACC-001",
    "toAccountId": "ACC-002",
    "amount": 500.00,
    "currency": "USD",
    "reference": "TXN-20260221-002"
  }'
```

### Observing the Execution Flow

Check running workflows:

```bash
curl http://localhost:8080/api/orchestration/executions?status=RUNNING
```

Check a specific settlement workflow:

```bash
curl http://localhost:8080/api/orchestration/workflows/instances/{correlationId}
```

List pending timers:

```bash
curl http://localhost:8080/api/orchestration/workflows/instances/{correlationId}/timers
```

Deliver clearing house signal:

```bash
curl -X POST http://localhost:8080/api/orchestration/workflows/instances/{correlationId}/signal/clearing-confirmed \
  -H "Content-Type: application/json" \
  -d '{"clearingRef": "CLR-20260222-001"}'
```

Check DLQ:

```bash
curl http://localhost:8080/api/orchestration/dlq
curl http://localhost:8080/api/orchestration/dlq/count
```

### Simulating Failure

Test compensation by injecting a failure in the ledger service:

```java
// In your test configuration
@Bean
@Primary
public LedgerService failingLedgerService() {
    return new LedgerService() {
        @Override
        public Mono<LedgerEntry> postEntry(String debit, String credit,
                                            BigDecimal amount, String currency) {
            return Mono.error(new RuntimeException("Ledger unavailable"));
        }
        @Override
        public Mono<Void> reverseEntry(String entryId) {
            return Mono.empty();
        }
    };
}
```

When the ledger step fails:
1. The saga marks `ledger` as `FAILED`
2. Compensation runs in reverse: `cancelReservation` for the `reserve` step
3. The TCC's Cancel phase releases all holds
4. `@OnSagaError` fires with the error details
5. A DLQ entry is created for the failed step

The complete execution flow is visible in the logs via `OrchestrationLoggerEvents` and in metrics via `firefly.orchestration.executions.completed{success=false}`.

---

## Next Steps

- **[Foundations](foundations.md)** — Architecture deep dive and pattern selection guide
- **[Saga Reference](saga.md)** — Complete annotation and builder API reference
- **[TCC Reference](tcc.md)** — Per-phase timeout/retry configuration details
- **[Workflow Reference](workflow.md)** — Signals, timers, child workflows, search attributes
- **[Core Infrastructure](core-infrastructure.md)** — ExecutionContext, argument injection, persistence providers
- **[Recipes & Production](recipes-and-production.md)** — Testing strategies, error handling patterns, production checklist

---

[← Back to Index](README.md)

*Copyright 2024-2026 Firefly Software Solutions Inc. Licensed under the Apache License, Version 2.0.*
