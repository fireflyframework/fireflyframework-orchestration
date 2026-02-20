/*
 * Copyright 2024-2026 Firefly Software Solutions Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fireflyframework.orchestration.tcc.engine;

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.context.TccPhase;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Immutable snapshot of a TCC transaction execution result.
 */
public final class TccResult {

    public enum Status { CONFIRMED, CANCELED, FAILED }

    private final String tccName;
    private final String correlationId;
    private final Status status;
    private final Instant startedAt;
    private final Instant completedAt;
    private final Throwable error;
    private final String failedParticipantId;
    private final TccPhase failedPhase;
    private final Map<String, ParticipantOutcome> participants;

    public record ParticipantOutcome(
            String participantId,
            Object tryResult,
            boolean trySucceeded,
            boolean confirmSucceeded,
            boolean cancelExecuted,
            Throwable error,
            int attempts,
            long latencyMs) {}

    private TccResult(String tccName, String correlationId, Status status,
                      Instant startedAt, Instant completedAt,
                      Throwable error, String failedParticipantId, TccPhase failedPhase,
                      Map<String, ParticipantOutcome> participants) {
        this.tccName = tccName;
        this.correlationId = correlationId;
        this.status = status;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.error = error;
        this.failedParticipantId = failedParticipantId;
        this.failedPhase = failedPhase;
        this.participants = participants;
    }

    public String tccName() { return tccName; }
    public String correlationId() { return correlationId; }
    public Status status() { return status; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public Duration duration() { return Duration.between(startedAt, completedAt); }
    public boolean isConfirmed() { return status == Status.CONFIRMED; }
    public boolean isCanceled() { return status == Status.CANCELED; }
    public boolean isFailed() { return status == Status.FAILED; }
    public Optional<Throwable> error() { return Optional.ofNullable(error); }
    public Optional<String> failedParticipantId() { return Optional.ofNullable(failedParticipantId); }
    public Optional<TccPhase> failedPhase() { return Optional.ofNullable(failedPhase); }
    public Map<String, ParticipantOutcome> participants() { return participants; }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> tryResultOf(String participantId, Class<T> type) {
        ParticipantOutcome outcome = participants.get(participantId);
        if (outcome == null || outcome.tryResult() == null) return Optional.empty();
        return type.isInstance(outcome.tryResult()) ? Optional.of((T) outcome.tryResult()) : Optional.empty();
    }

    public static TccResult confirmed(String tccName, ExecutionContext ctx,
                                       Map<String, ParticipantOutcome> participants) {
        return new TccResult(tccName, ctx.getCorrelationId(), Status.CONFIRMED,
                ctx.getStartedAt(), Instant.now(), null, null, null,
                Collections.unmodifiableMap(new LinkedHashMap<>(participants)));
    }

    public static TccResult canceled(String tccName, ExecutionContext ctx,
                                      String failedParticipantId, TccPhase failedPhase,
                                      Throwable error,
                                      Map<String, ParticipantOutcome> participants) {
        return new TccResult(tccName, ctx.getCorrelationId(), Status.CANCELED,
                ctx.getStartedAt(), Instant.now(), error, failedParticipantId, failedPhase,
                Collections.unmodifiableMap(new LinkedHashMap<>(participants)));
    }

    public static TccResult failed(String tccName, ExecutionContext ctx,
                                    String failedParticipantId, TccPhase failedPhase, Throwable error,
                                    Map<String, ParticipantOutcome> participants) {
        return new TccResult(tccName, ctx.getCorrelationId(), Status.FAILED,
                ctx.getStartedAt(), Instant.now(), error, failedParticipantId, failedPhase,
                Collections.unmodifiableMap(new LinkedHashMap<>(participants)));
    }
}
