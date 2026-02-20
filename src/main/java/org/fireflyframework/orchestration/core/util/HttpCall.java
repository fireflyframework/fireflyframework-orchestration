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

package org.fireflyframework.orchestration.core.util;

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Helper for propagating correlation and headers into WebClient calls
 * within orchestration step handlers.
 *
 * <pre>{@code
 * HttpCall.propagate(client.post().uri("/api").bodyValue(body), ctx)
 *         .retrieve().bodyToMono(Response.class);
 *
 * HttpCall.exchangeOrError(
 *     client.post().uri("/api").bodyValue(req), ctx,
 *     SuccessDto.class, ErrorDto.class,
 *     (status, err) -> new DownstreamException(status, err.message()));
 * }</pre>
 */
public final class HttpCall {

    public static final String CORRELATION_HEADER = "X-Orchestration-Id";

    private HttpCall() {}

    /**
     * Propagate correlation ID and context headers into a WebClient request.
     */
    public static WebClient.RequestHeadersSpec<?> propagate(WebClient.RequestHeadersSpec<?> spec,
                                                             ExecutionContext ctx) {
        if (ctx == null) return spec;
        WebClient.RequestHeadersSpec<?> s = spec.header(CORRELATION_HEADER, ctx.getCorrelationId());
        for (Map.Entry<String, String> e : ctx.getHeaders().entrySet()) {
            s = s.header(e.getKey(), e.getValue());
        }
        return s;
    }

    /**
     * Propagate correlation + headers and also add extra headers for this call only.
     */
    public static WebClient.RequestHeadersSpec<?> propagate(WebClient.RequestHeadersSpec<?> spec,
                                                             ExecutionContext ctx,
                                                             Map<String, String> extraHeaders) {
        WebClient.RequestHeadersSpec<?> s = propagate(spec, ctx);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    s = s.header(e.getKey(), e.getValue());
                }
            }
        }
        return s;
    }

    /**
     * Build Spring HttpHeaders with correlation and all custom headers from context.
     * Useful for clients other than WebClient (RestTemplate, Feign, OkHttp, etc.).
     */
    public static HttpHeaders buildHeaders(ExecutionContext ctx) {
        HttpHeaders h = new HttpHeaders();
        if (ctx == null) return h;
        h.add(CORRELATION_HEADER, ctx.getCorrelationId());
        ctx.getHeaders().forEach((k, v) -> { if (k != null && v != null) h.add(k, v); });
        return h;
    }

    /**
     * Executes the request, returning the success body on 2xx. For error statuses,
     * deserializes the body to {@code errorType} and converts to an exception via
     * {@code errorMapper}, causing the step to fail (and compensate).
     */
    public static <T, E> Mono<T> exchangeOrError(
            WebClient.RequestHeadersSpec<?> spec,
            ExecutionContext ctx,
            Class<T> successType,
            Class<E> errorType,
            BiFunction<Integer, E, ? extends Throwable> errorMapper) {
        Objects.requireNonNull(successType, "successType");
        Objects.requireNonNull(errorType, "errorType");
        Objects.requireNonNull(errorMapper, "errorMapper");
        return propagate(spec, ctx)
                .exchangeToMono(resp -> handleResponse(resp, successType, errorType, errorMapper));
    }

    /**
     * Overload that ignores HTTP status in the mapper.
     */
    public static <T, E> Mono<T> exchangeOrError(
            WebClient.RequestHeadersSpec<?> spec,
            ExecutionContext ctx,
            Class<T> successType,
            Class<E> errorType,
            Function<E, ? extends Throwable> errorMapper) {
        return exchangeOrError(spec, ctx, successType, errorType, (status, e) -> errorMapper.apply(e));
    }

    /**
     * Convenience for calls that do not return a body (204/empty).
     * On non-2xx, maps the error body.
     */
    public static <E> Mono<Void> exchangeVoidOrError(
            WebClient.RequestHeadersSpec<?> spec,
            ExecutionContext ctx,
            Class<E> errorType,
            BiFunction<Integer, E, ? extends Throwable> errorMapper) {
        return exchangeOrError(spec, ctx, Void.class, errorType, errorMapper).then();
    }

    private static <T, E> Mono<T> handleResponse(
            ClientResponse resp,
            Class<T> successType,
            Class<E> errorType,
            BiFunction<Integer, E, ? extends Throwable> errorMapper) {
        if (resp.statusCode().is2xxSuccessful()) {
            return resp.bodyToMono(successType);
        }
        int status = resp.statusCode().value();
        return resp.bodyToMono(errorType)
                .map(Optional::of)
                .onErrorResume(ex -> Mono.just(Optional.empty()))
                .defaultIfEmpty(Optional.empty())
                .flatMap(opt -> Mono.error(errorMapper.apply(status, opt.orElse(null))));
    }
}
