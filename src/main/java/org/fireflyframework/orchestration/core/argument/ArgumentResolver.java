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

package org.fireflyframework.orchestration.core.argument;

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.tcc.annotation.FromTry;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ArgumentResolver {

    @FunctionalInterface
    public interface ArgResolver {
        Object resolve(Object input, ExecutionContext ctx);
    }

    private final Map<Method, ArgResolver[]> cache = new ConcurrentHashMap<>();

    public Object[] resolveArguments(Method method, Object input, ExecutionContext ctx) {
        ArgResolver[] resolvers = cache.computeIfAbsent(method, this::compile);
        Object[] args = new Object[resolvers.length];
        for (int i = 0; i < resolvers.length; i++) {
            args[i] = resolvers[i].resolve(input, ctx);
        }
        return args;
    }

    private ArgResolver[] compile(Method method) {
        Parameter[] params = method.getParameters();
        if (params.length == 0) return new ArgResolver[0];

        ArgResolver[] resolvers = new ArgResolver[params.length];
        boolean implicitUsed = false;

        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];

            // 1. ExecutionContext by type
            if (ExecutionContext.class.isAssignableFrom(p.getType())) {
                resolvers[i] = wrapRequired(p, method, (in, ctx) -> ctx);
                continue;
            }

            // 2. @Input
            var inputAnn = p.getAnnotation(Input.class);
            if (inputAnn != null) {
                String key = inputAnn.value();
                if (key == null || key.isBlank()) {
                    resolvers[i] = wrapRequired(p, method, (in, ctx) -> in);
                } else {
                    resolvers[i] = wrapRequired(p, method, (in, ctx) ->
                            (in instanceof Map<?, ?> m) ? m.get(key) : null);
                }
                continue;
            }

            // 3. @FromStep
            var fromStepAnn = p.getAnnotation(FromStep.class);
            if (fromStepAnn != null) {
                String ref = fromStepAnn.value();
                resolvers[i] = wrapRequired(p, method, (in, ctx) -> ctx.getResult(ref));
                continue;
            }

            // 4. @FromCompensationResult
            var fromCompAnn = p.getAnnotation(FromCompensationResult.class);
            if (fromCompAnn != null) {
                String ref = fromCompAnn.value();
                resolvers[i] = wrapRequired(p, method, (in, ctx) -> ctx.getCompensationResult(ref));
                continue;
            }

            // 5. @CompensationError
            var compErrAnn = p.getAnnotation(CompensationError.class);
            if (compErrAnn != null) {
                String ref = compErrAnn.value();
                resolvers[i] = wrapRequired(p, method, (in, ctx) -> ctx.getCompensationError(ref));
                continue;
            }

            // 5b. @FromTry — inject try-phase result for TCC confirm/cancel methods
            var fromTryAnn = p.getAnnotation(FromTry.class);
            if (fromTryAnn != null) {
                String ref = fromTryAnn.value();
                if (ref == null || ref.isBlank()) {
                    // No specific participant referenced — use raw input (the try result)
                    resolvers[i] = wrapRequired(p, method, (in, ctx) -> in);
                } else {
                    // Named participant — look up try result from context
                    resolvers[i] = wrapRequired(p, method, (in, ctx) -> ctx.getTryResult(ref));
                }
                continue;
            }

            // 6. @Header
            var headerAnn = p.getAnnotation(Header.class);
            if (headerAnn != null) {
                String name = headerAnn.value();
                resolvers[i] = wrapRequired(p, method, (in, ctx) -> ctx.getHeader(name));
                continue;
            }

            // 7. @Headers
            if (p.getAnnotation(Headers.class) != null) {
                resolvers[i] = wrapRequired(p, method, (in, ctx) -> ctx.getHeaders());
                continue;
            }

            // 8. @Variable
            var varAnn = p.getAnnotation(Variable.class);
            if (varAnn != null) {
                String name = varAnn.value();
                resolvers[i] = wrapRequired(p, method, (in, ctx) -> ctx.getVariable(name));
                continue;
            }

            // 9. @Variables
            if (p.getAnnotation(Variables.class) != null) {
                resolvers[i] = wrapRequired(p, method, (in, ctx) -> ctx.getVariables());
                continue;
            }

            // 10. @CorrelationId
            var corrIdAnn = p.getAnnotation(CorrelationId.class);
            if (corrIdAnn != null) {
                resolvers[i] = wrapRequired(p, method, (in, ctx) -> ctx.getCorrelationId());
                continue;
            }

            // 11. Implicit input (first unannotated non-context parameter)
            if (!implicitUsed) {
                resolvers[i] = wrapRequired(p, method, (in, ctx) -> in);
                implicitUsed = true;
            } else {
                throw new IllegalStateException(
                        "Unresolvable parameter '" + p.getName() + "' at position " + i +
                        " in method " + method.getName() +
                        ". Use @Input/@FromStep/@FromTry/@FromCompensationResult/@CompensationError/@Header/@Headers/@Variable/@Variables/@CorrelationId or ExecutionContext.");
            }
        }
        return resolvers;
    }

    private ArgResolver wrapRequired(Parameter p, Method method, ArgResolver base) {
        if (p.getAnnotation(Required.class) == null) return base;
        String paramName = p.getName();
        return (in, ctx) -> {
            Object v = base.resolve(in, ctx);
            if (v == null) {
                throw new IllegalStateException(
                        "Required parameter '" + paramName + "' in method " + method.getName() + " resolved to null");
            }
            return v;
        };
    }
}
