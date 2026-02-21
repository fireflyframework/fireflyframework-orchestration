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

package org.fireflyframework.orchestration.tcc.builder;

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.tcc.registry.TccDefinition;
import org.fireflyframework.orchestration.tcc.registry.TccParticipantDefinition;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Fluent builder for constructing TCC definitions programmatically.
 *
 * <p>Usage:
 * <pre>{@code
 * TccDefinition def = TccBuilder.tcc("TransferFunds")
 *     .participant("debit")
 *         .tryHandler((input, ctx) -> debit(input))
 *         .confirmHandler((tryResult, ctx) -> commitDebit(tryResult))
 *         .cancelHandler((tryResult, ctx) -> rollbackDebit(tryResult))
 *         .add()
 *     .participant("credit")
 *         .tryHandler((input, ctx) -> credit(input))
 *         .confirmHandler((tryResult, ctx) -> commitCredit(tryResult))
 *         .cancelHandler((tryResult, ctx) -> rollbackCredit(tryResult))
 *         .add()
 *     .build();
 * }</pre>
 */
public class TccBuilder {

    private final TccDefinition tcc;
    private final AtomicInteger orderCounter = new AtomicInteger(0);

    private TccBuilder(String name, long timeoutMs, boolean retryEnabled, int maxRetries, long backoffMs) {
        this.tcc = new TccDefinition(name, null, null, timeoutMs, retryEnabled, maxRetries, backoffMs);
    }

    public static TccBuilder tcc(String name) {
        return new TccBuilder(name, 0L, false, 0, 0L);
    }

    public static TccBuilder named(String name) {
        return tcc(name);
    }

    public static TccBuilder tcc(String name, long timeoutMs) {
        return new TccBuilder(name, timeoutMs, false, 0, 0L);
    }

    public static TccBuilder tcc(String name, long timeoutMs, boolean retryEnabled, int maxRetries, long backoffMs) {
        return new TccBuilder(name, timeoutMs, retryEnabled, maxRetries, backoffMs);
    }

    public Participant participant(String id) {
        return new Participant(id);
    }

    public TccDefinition build() {
        if (tcc.participants.isEmpty()) {
            throw new IllegalStateException("TCC '" + tcc.name + "' must have at least one participant");
        }
        return tcc;
    }

    public class Participant {
        private final String id;
        private int order = -1;
        private long timeoutMs = -1;
        private boolean optional = false;
        private Method tryMethod;
        private Method confirmMethod;
        private Method cancelMethod;
        private long tryTimeoutMs = -1;
        private int tryRetry = -1;
        private long tryBackoffMs = -1;
        private long confirmTimeoutMs = -1;
        private int confirmRetry = -1;
        private long confirmBackoffMs = -1;
        private long cancelTimeoutMs = -1;
        private int cancelRetry = -1;
        private long cancelBackoffMs = -1;
        private boolean jitter = false;
        private double jitterFactor = 0.0;
        private Object handlerBean;

        private Participant(String id) {
            this.id = id;
        }

        public Participant order(int order) { this.order = order; return this; }
        public Participant timeoutMs(long ms) { this.timeoutMs = ms; return this; }
        public Participant optional(boolean optional) { this.optional = optional; return this; }
        public Participant tryTimeoutMs(long ms) { this.tryTimeoutMs = ms; return this; }
        public Participant tryRetry(int retry) { this.tryRetry = retry; return this; }
        public Participant tryBackoffMs(long ms) { this.tryBackoffMs = ms; return this; }
        public Participant confirmTimeoutMs(long ms) { this.confirmTimeoutMs = ms; return this; }
        public Participant confirmRetry(int retry) { this.confirmRetry = retry; return this; }
        public Participant confirmBackoffMs(long ms) { this.confirmBackoffMs = ms; return this; }
        public Participant cancelTimeoutMs(long ms) { this.cancelTimeoutMs = ms; return this; }
        public Participant cancelRetry(int retry) { this.cancelRetry = retry; return this; }
        public Participant cancelBackoffMs(long ms) { this.cancelBackoffMs = ms; return this; }
        public Participant jitter(boolean jitter) { this.jitter = jitter; return this; }
        public Participant jitterFactor(double jitterFactor) { this.jitterFactor = jitterFactor; return this; }

        /**
         * Set all three handlers using a single object with annotated methods.
         */
        public Participant handler(Object bean) {
            this.handlerBean = bean;
            return this;
        }

        /**
         * Set handlers using lambda functions. Creates a proxy object with methods
         * that delegate to the lambdas.
         */
        public Participant tryHandler(BiFunction<Object, ExecutionContext, Mono<?>> fn) {
            ensureHandlerBean();
            ((LambdaParticipantHandler) this.handlerBean).setTryFn(fn);
            return this;
        }

        public Participant confirmHandler(BiFunction<Object, ExecutionContext, Mono<?>> fn) {
            ensureHandlerBean();
            ((LambdaParticipantHandler) this.handlerBean).setConfirmFn(fn);
            return this;
        }

        public Participant cancelHandler(BiFunction<Object, ExecutionContext, Mono<?>> fn) {
            ensureHandlerBean();
            ((LambdaParticipantHandler) this.handlerBean).setCancelFn(fn);
            return this;
        }

        private void resolveConventionMethods(Object bean) {
            Class<?> clazz = bean.getClass();
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals("doTry") && tryMethod == null) tryMethod = m;
                else if (m.getName().equals("doConfirm") && confirmMethod == null) confirmMethod = m;
                else if (m.getName().equals("doCancel") && cancelMethod == null) cancelMethod = m;
            }
        }

        private void ensureHandlerBean() {
            if (this.handlerBean == null || !(this.handlerBean instanceof LambdaParticipantHandler)) {
                this.handlerBean = new LambdaParticipantHandler();
            }
        }

        public TccBuilder add() {
            if (handlerBean == null) {
                throw new IllegalStateException("Participant '" + id + "' must have a handler");
            }
            int effectiveOrder = order >= 0 ? order : orderCounter.getAndIncrement();

            if (handlerBean instanceof LambdaParticipantHandler lph) {
                if (lph.getTryFn() == null) throw new IllegalStateException("Participant '" + id + "' missing tryHandler");
                if (lph.getConfirmFn() == null) throw new IllegalStateException("Participant '" + id + "' missing confirmHandler");
                if (lph.getCancelFn() == null) throw new IllegalStateException("Participant '" + id + "' missing cancelHandler");

                try {
                    tryMethod = LambdaParticipantHandler.class.getDeclaredMethod("doTry", Object.class, ExecutionContext.class);
                    confirmMethod = LambdaParticipantHandler.class.getDeclaredMethod("doConfirm", Object.class, ExecutionContext.class);
                    cancelMethod = LambdaParticipantHandler.class.getDeclaredMethod("doCancel", Object.class, ExecutionContext.class);
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException("Internal error: handler methods not found", e);
                }
            } else {
                // Convention-based: resolve doTry/doConfirm/doCancel methods on the bean
                resolveConventionMethods(handlerBean);
            }

            if (tryMethod == null || confirmMethod == null || cancelMethod == null) {
                throw new IllegalStateException("Participant '" + id + "' must have try, confirm, and cancel methods " +
                        "(provide doTry/doConfirm/doCancel methods or use tryHandler/confirmHandler/cancelHandler)");
            }

            TccParticipantDefinition pd = new TccParticipantDefinition(
                    id, effectiveOrder, timeoutMs, optional,
                    handlerBean, handlerBean,
                    tryMethod, tryTimeoutMs, tryRetry, tryBackoffMs,
                    confirmMethod, confirmTimeoutMs, confirmRetry, confirmBackoffMs,
                    cancelMethod, cancelTimeoutMs, cancelRetry, cancelBackoffMs,
                    jitter, jitterFactor);

            if (tcc.participants.putIfAbsent(id, pd) != null) {
                throw new IllegalStateException("Duplicate participant id '" + id + "' in TCC '" + tcc.name + "'");
            }
            return TccBuilder.this;
        }
    }

    /**
     * Internal handler class that bridges lambda functions to reflective method invocation.
     */
    public static class LambdaParticipantHandler {
        private BiFunction<Object, ExecutionContext, Mono<?>> tryFn;
        private BiFunction<Object, ExecutionContext, Mono<?>> confirmFn;
        private BiFunction<Object, ExecutionContext, Mono<?>> cancelFn;

        public Mono<?> doTry(Object input, ExecutionContext ctx) {
            return tryFn.apply(input, ctx);
        }

        public Mono<?> doConfirm(Object input, ExecutionContext ctx) {
            return confirmFn.apply(input, ctx);
        }

        public Mono<?> doCancel(Object input, ExecutionContext ctx) {
            return cancelFn.apply(input, ctx);
        }

        void setTryFn(BiFunction<Object, ExecutionContext, Mono<?>> fn) { this.tryFn = fn; }
        void setConfirmFn(BiFunction<Object, ExecutionContext, Mono<?>> fn) { this.confirmFn = fn; }
        void setCancelFn(BiFunction<Object, ExecutionContext, Mono<?>> fn) { this.cancelFn = fn; }

        BiFunction<Object, ExecutionContext, Mono<?>> getTryFn() { return tryFn; }
        BiFunction<Object, ExecutionContext, Mono<?>> getConfirmFn() { return confirmFn; }
        BiFunction<Object, ExecutionContext, Mono<?>> getCancelFn() { return cancelFn; }
    }
}
