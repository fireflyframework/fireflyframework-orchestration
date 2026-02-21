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

package org.fireflyframework.orchestration.unit.core;

import org.fireflyframework.orchestration.core.argument.ArgumentResolver;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.tcc.annotation.FromTry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.*;

class FromTryArgumentResolverTest {

    private final ArgumentResolver resolver = new ArgumentResolver();

    @SuppressWarnings("unused")
    static class TestBean {
        public void confirmWithRawTryResult(@FromTry Object tryResult) {}
        public void confirmWithNamedTryResult(@FromTry("payment") Object paymentResult) {}
        public void cancelWithRawTryResult(@FromTry String tryResult) {}
        public void mixedParams(@FromTry Object tryResult, ExecutionContext ctx) {}
    }

    @Test
    void fromTry_resolves_rawTryResult() throws Exception {
        Method m = TestBean.class.getMethod("confirmWithRawTryResult", Object.class);
        var ctx = ExecutionContext.forTcc(null, "test");
        Object rawInput = "reservation-data-123";

        Object[] args = resolver.resolveArguments(m, rawInput, ctx);

        assertThat(args).hasSize(1);
        assertThat(args[0]).isEqualTo("reservation-data-123");
    }

    @Test
    void fromTry_resolves_namedParticipantResult() throws Exception {
        Method m = TestBean.class.getMethod("confirmWithNamedTryResult", Object.class);
        var ctx = ExecutionContext.forTcc(null, "test");
        ctx.putTryResult("payment", "payment-reserved-456");

        Object[] args = resolver.resolveArguments(m, "some-raw-input", ctx);

        assertThat(args).hasSize(1);
        assertThat(args[0]).isEqualTo("payment-reserved-456");
    }

    @Test
    void fromTry_namedParticipant_returnsNullWhenNotFound() throws Exception {
        Method m = TestBean.class.getMethod("confirmWithNamedTryResult", Object.class);
        var ctx = ExecutionContext.forTcc(null, "test");
        // No try result stored for "payment"

        Object[] args = resolver.resolveArguments(m, "raw-input", ctx);

        assertThat(args).hasSize(1);
        assertThat(args[0]).isNull();
    }

    @Test
    void fromTry_rawResult_worksWithTypedParameter() throws Exception {
        Method m = TestBean.class.getMethod("cancelWithRawTryResult", String.class);
        var ctx = ExecutionContext.forTcc(null, "test");

        Object[] args = resolver.resolveArguments(m, "typed-try-result", ctx);

        assertThat(args).hasSize(1);
        assertThat(args[0]).isEqualTo("typed-try-result");
    }

    @Test
    void fromTry_withExecutionContext_mixedParams() throws Exception {
        Method m = TestBean.class.getMethod("mixedParams", Object.class, ExecutionContext.class);
        var ctx = ExecutionContext.forTcc(null, "test");

        Object[] args = resolver.resolveArguments(m, "try-result", ctx);

        assertThat(args).hasSize(2);
        assertThat(args[0]).isEqualTo("try-result");
        assertThat(args[1]).isSameAs(ctx);
    }
}
