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

import org.fireflyframework.orchestration.core.argument.*;
import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ArgumentResolverTest {

    private final ArgumentResolver resolver = new ArgumentResolver();

    // Test helper classes
    @SuppressWarnings("unused")
    static class TestBean {
        public void contextParam(ExecutionContext ctx) {}
        public void inputParam(@Input String value) {}
        public void inputMapParam(@Input("key") String value) {}
        public void fromStepParam(@FromStep("step1") Object result) {}
        public void headerParam(@Header("X-Custom") String header) {}
        public void headersParam(@Headers Map<String, String> headers) {}
        public void variableParam(@Variable("count") Integer count) {}
        public void variablesParam(@Variables Map<String, Object> vars) {}
        public void requiredParam(@Input @Required String value) {}
        public void implicitParam(String value) {}
        public void noParams() {}
    }

    @Test
    void resolves_executionContext_byType() throws Exception {
        Method m = TestBean.class.getMethod("contextParam", ExecutionContext.class);
        var ctx = ExecutionContext.forSaga(null, "test");
        Object[] args = resolver.resolveArguments(m, null, ctx);
        assertThat(args).hasSize(1);
        assertThat(args[0]).isSameAs(ctx);
    }

    @Test
    void resolves_input_annotatedParameter() throws Exception {
        Method m = TestBean.class.getMethod("inputParam", String.class);
        var ctx = ExecutionContext.forSaga(null, "test");
        Object[] args = resolver.resolveArguments(m, "hello", ctx);
        assertThat(args[0]).isEqualTo("hello");
    }

    @Test
    void resolves_input_fromMapByKey() throws Exception {
        Method m = TestBean.class.getMethod("inputMapParam", String.class);
        var ctx = ExecutionContext.forSaga(null, "test");
        Object[] args = resolver.resolveArguments(m, Map.of("key", "value"), ctx);
        assertThat(args[0]).isEqualTo("value");
    }

    @Test
    void resolves_fromStep_result() throws Exception {
        Method m = TestBean.class.getMethod("fromStepParam", Object.class);
        var ctx = ExecutionContext.forSaga(null, "test");
        ctx.putResult("step1", "result1");
        Object[] args = resolver.resolveArguments(m, null, ctx);
        assertThat(args[0]).isEqualTo("result1");
    }

    @Test
    void resolves_header_parameter() throws Exception {
        Method m = TestBean.class.getMethod("headerParam", String.class);
        var ctx = ExecutionContext.forSaga(null, "test");
        ctx.putHeader("X-Custom", "custom-value");
        Object[] args = resolver.resolveArguments(m, null, ctx);
        assertThat(args[0]).isEqualTo("custom-value");
    }

    @Test
    void resolves_headers_asFullMap() throws Exception {
        Method m = TestBean.class.getMethod("headersParam", Map.class);
        var ctx = ExecutionContext.forSaga(null, "test");
        ctx.putHeader("key1", "val1");
        Object[] args = resolver.resolveArguments(m, null, ctx);
        assertThat(args[0]).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) args[0];
        assertThat(headers).containsEntry("key1", "val1");
    }

    @Test
    void resolves_variable_parameter() throws Exception {
        Method m = TestBean.class.getMethod("variableParam", Integer.class);
        var ctx = ExecutionContext.forSaga(null, "test");
        ctx.putVariable("count", 42);
        Object[] args = resolver.resolveArguments(m, null, ctx);
        assertThat(args[0]).isEqualTo(42);
    }

    @Test
    void resolves_variables_asFullMap() throws Exception {
        Method m = TestBean.class.getMethod("variablesParam", Map.class);
        var ctx = ExecutionContext.forSaga(null, "test");
        ctx.putVariable("a", 1);
        Object[] args = resolver.resolveArguments(m, null, ctx);
        assertThat(args[0]).isInstanceOf(Map.class);
    }

    @Test
    void required_throwsOnNull() throws Exception {
        Method m = TestBean.class.getMethod("requiredParam", String.class);
        var ctx = ExecutionContext.forSaga(null, "test");
        assertThatThrownBy(() -> resolver.resolveArguments(m, null, ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Required parameter");
    }

    @Test
    void implicit_input_worksForFirstUnannotated() throws Exception {
        Method m = TestBean.class.getMethod("implicitParam", String.class);
        var ctx = ExecutionContext.forSaga(null, "test");
        Object[] args = resolver.resolveArguments(m, "implicit-value", ctx);
        assertThat(args[0]).isEqualTo("implicit-value");
    }

    @Test
    void noParams_returnsEmptyArray() throws Exception {
        Method m = TestBean.class.getMethod("noParams");
        var ctx = ExecutionContext.forSaga(null, "test");
        Object[] args = resolver.resolveArguments(m, null, ctx);
        assertThat(args).isEmpty();
    }

    @Test
    void caches_compiledResolvers() throws Exception {
        Method m = TestBean.class.getMethod("inputParam", String.class);
        var ctx = ExecutionContext.forSaga(null, "test");
        // Call twice to verify caching works
        resolver.resolveArguments(m, "first", ctx);
        Object[] args = resolver.resolveArguments(m, "second", ctx);
        assertThat(args[0]).isEqualTo("second");
    }
}
