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

package org.fireflyframework.orchestration.saga.compensation;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.fireflyframework.orchestration.saga.compensation.CompensationErrorHandler.CompensationErrorResult;

/**
 * Factory and registry for {@link CompensationErrorHandler} instances.
 * <p>
 * Pre-registers the following handler suppliers:
 * <ul>
 *     <li>{@code "default"} — {@link DefaultCompensationErrorHandler}</li>
 *     <li>{@code "fail-fast"} — {@link FailFastErrorHandler}</li>
 *     <li>{@code "log-and-continue"} — {@link LogAndContinueErrorHandler}</li>
 *     <li>{@code "retry"} — {@link RetryWithBackoffErrorHandler} with 3 retries</li>
 *     <li>{@code "robust"} — composite of retry (3 attempts) + log-and-continue</li>
 * </ul>
 * <p>
 * Each call to {@link #getHandler(String)} returns a fresh instance to avoid
 * shared mutable state between callers.
 */
public final class CompensationErrorHandlerFactory {

    private static final ConcurrentHashMap<String, Supplier<CompensationErrorHandler>> REGISTRY =
            new ConcurrentHashMap<>();

    static {
        registerDefaults();
    }

    private CompensationErrorHandlerFactory() {
        // Utility class — no instantiation
    }

    /**
     * Returns a fresh handler instance registered under the given name.
     *
     * @param name the handler name
     * @return an {@link Optional} containing a new handler instance, or empty if not found
     */
    public static Optional<CompensationErrorHandler> getHandler(String name) {
        Supplier<CompensationErrorHandler> supplier = REGISTRY.get(name);
        return supplier != null ? Optional.of(supplier.get()) : Optional.empty();
    }

    /**
     * Registers a handler supplier under the given name, replacing any previous registration.
     *
     * @param name     the handler name
     * @param supplier the handler supplier that creates fresh instances
     */
    public static void registerHandler(String name, Supplier<CompensationErrorHandler> supplier) {
        REGISTRY.put(name, supplier);
    }

    /**
     * Registers a handler instance under the given name.
     *
     * @param name    the handler name
     * @param handler the handler instance (returned directly on each lookup)
     */
    public static void registerHandler(String name, CompensationErrorHandler handler) {
        REGISTRY.put(name, () -> handler);
    }

    /**
     * Creates a {@link CompositeCompensationErrorHandler} from the given handlers.
     *
     * @param handlers the handlers to compose, evaluated in order
     * @return a composite handler
     */
    public static CompensationErrorHandler composite(CompensationErrorHandler... handlers) {
        return new CompositeCompensationErrorHandler(Arrays.asList(handlers));
    }

    /**
     * Resets the registry to its default state, removing any custom registrations.
     */
    public static void resetDefaults() {
        REGISTRY.clear();
        registerDefaults();
    }

    private static void registerDefaults() {
        REGISTRY.put("default", DefaultCompensationErrorHandler::new);
        REGISTRY.put("fail-fast", FailFastErrorHandler::new);
        REGISTRY.put("log-and-continue", LogAndContinueErrorHandler::new);
        REGISTRY.put("retry", () -> new RetryWithBackoffErrorHandler(3));
        REGISTRY.put("robust", () -> {
            var retryHandler = new RetryWithBackoffErrorHandler(3);
            // Wrap retry: convert FAIL_SAGA to CONTINUE so the composite falls through
            // to the log-and-continue handler when retries are exhausted
            CompensationErrorHandler retryOrDefer = (sagaName, stepId, error, attempt) -> {
                CompensationErrorResult result = retryHandler.handle(sagaName, stepId, error, attempt);
                return result == CompensationErrorResult.FAIL_SAGA
                        ? CompensationErrorResult.CONTINUE : result;
            };
            return new CompositeCompensationErrorHandler(List.of(
                    retryOrDefer,
                    new LogAndContinueErrorHandler()
            ));
        });
    }
}
