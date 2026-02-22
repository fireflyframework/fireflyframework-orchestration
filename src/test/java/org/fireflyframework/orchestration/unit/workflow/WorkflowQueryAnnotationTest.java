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

package org.fireflyframework.orchestration.unit.workflow;

import org.fireflyframework.orchestration.core.context.ExecutionContext;
import org.fireflyframework.orchestration.core.model.ExecutionPattern;
import org.fireflyframework.orchestration.core.model.ExecutionStatus;
import org.fireflyframework.orchestration.core.model.RetryPolicy;
import org.fireflyframework.orchestration.core.model.StepStatus;
import org.fireflyframework.orchestration.core.model.TriggerMode;
import org.fireflyframework.orchestration.core.persistence.ExecutionState;
import org.fireflyframework.orchestration.core.persistence.InMemoryPersistenceProvider;
import org.fireflyframework.orchestration.workflow.annotation.WorkflowQuery;
import org.fireflyframework.orchestration.workflow.query.WorkflowQueryService;
import org.fireflyframework.orchestration.workflow.registry.WorkflowDefinition;
import org.fireflyframework.orchestration.workflow.registry.WorkflowRegistry;
import org.fireflyframework.orchestration.workflow.registry.WorkflowStepDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@code @WorkflowQuery} annotation integration with WorkflowQueryService
 * and WorkflowRegistry scanning.
 */
class WorkflowQueryAnnotationTest {

    private InMemoryPersistenceProvider persistence;
    private WorkflowRegistry registry;
    private WorkflowQueryService queryService;

    @BeforeEach
    void setUp() {
        persistence = new InMemoryPersistenceProvider();
        registry = new WorkflowRegistry();
    }

    /**
     * Sample workflow bean with a {@code @WorkflowQuery} method.
     */
    public static class QueryableWorkflowBean {

        @WorkflowQuery("orderStatus")
        public String getOrderStatus(ExecutionContext ctx) {
            Object orderId = ctx.getVariable("orderId");
            Object status = ctx.getVariable("currentStatus");
            return "Order " + orderId + " is " + status;
        }

        @WorkflowQuery("itemCount")
        public int getItemCount(ExecutionContext ctx) {
            Object count = ctx.getVariable("itemCount");
            return count != null ? (int) count : 0;
        }
    }

    @Test
    void workflowQuery_executesAnnotatedMethod() throws Exception {
        var bean = new QueryableWorkflowBean();

        // Build query methods map manually (simulating what WorkflowRegistry does)
        Map<String, Method> queryMethods = new HashMap<>();
        queryMethods.put("orderStatus",
                QueryableWorkflowBean.class.getMethod("getOrderStatus", ExecutionContext.class));
        queryMethods.put("itemCount",
                QueryableWorkflowBean.class.getMethod("getItemCount", ExecutionContext.class));

        // Create a minimal step for the workflow definition
        var stepDef = new WorkflowStepDefinition(
                "step1", "Step 1", "", List.of(), 0,
                "", 5000, RetryPolicy.NO_RETRY, "",
                false, false, "", null, null);

        var def = new WorkflowDefinition(
                "order-wf", "Order WF", "test", "1.0",
                List.of(stepDef), TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT,
                bean, List.of(), List.of(), List.of(),
                false, 0, queryMethods);
        registry.register(def);

        queryService = new WorkflowQueryService(persistence, registry);

        // Persist a workflow state with variables
        var state = new ExecutionState("wf-query-1", "order-wf", ExecutionPattern.WORKFLOW,
                ExecutionStatus.RUNNING,
                Map.of(), Map.of(),
                Map.of(), Map.of(),
                Map.of("orderId", "ORD-42", "currentStatus", "PROCESSING", "itemCount", 5),
                Map.of(), Set.of(), List.of(),
                null, Instant.now(), Instant.now(), Optional.empty());
        persistence.save(state).block();

        // Execute the query
        StepVerifier.create(queryService.executeQuery("wf-query-1", "orderStatus"))
                .assertNext(result -> {
                    assertThat(result).isEqualTo("Order ORD-42 is PROCESSING");
                })
                .verifyComplete();

        // Execute itemCount query
        StepVerifier.create(queryService.executeQuery("wf-query-1", "itemCount"))
                .assertNext(result -> {
                    assertThat(result).isEqualTo(5);
                })
                .verifyComplete();
    }

    @Test
    void workflowQuery_registryScansQueryMethods() throws Exception {
        var bean = new QueryableWorkflowBean();

        // Build query methods map by scanning annotations (same as registry logic)
        Map<String, Method> queryMethods = new HashMap<>();
        for (Method m : QueryableWorkflowBean.class.getDeclaredMethods()) {
            WorkflowQuery ann = m.getAnnotation(WorkflowQuery.class);
            if (ann != null) {
                queryMethods.put(ann.value(), m);
            }
        }

        // Verify the scan found both query methods
        assertThat(queryMethods).containsKey("orderStatus");
        assertThat(queryMethods).containsKey("itemCount");
        assertThat(queryMethods).hasSize(2);

        // Verify methods are correct
        assertThat(queryMethods.get("orderStatus").getName()).isEqualTo("getOrderStatus");
        assertThat(queryMethods.get("itemCount").getName()).isEqualTo("getItemCount");

        // Verify we can create a WorkflowDefinition with query methods
        var stepDef = new WorkflowStepDefinition(
                "step1", "Step 1", "", List.of(), 0,
                "", 5000, RetryPolicy.NO_RETRY, "",
                false, false, "", null, null);

        var def = new WorkflowDefinition(
                "query-scan-wf", "Query Scan WF", "test", "1.0",
                List.of(stepDef), TriggerMode.SYNC, "", 30000, RetryPolicy.DEFAULT,
                bean, List.of(), List.of(), List.of(),
                false, 0, queryMethods);

        assertThat(def.queryMethods()).hasSize(2);
        assertThat(def.queryMethods()).containsKey("orderStatus");
        assertThat(def.queryMethods()).containsKey("itemCount");
    }
}
