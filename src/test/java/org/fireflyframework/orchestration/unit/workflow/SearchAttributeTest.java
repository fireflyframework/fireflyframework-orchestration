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

import org.fireflyframework.orchestration.workflow.search.SearchAttributeProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SearchAttributeTest {

    private SearchAttributeProjection projection;

    @BeforeEach
    void setUp() {
        projection = new SearchAttributeProjection();
    }

    @Test
    void upsert_and_get() {
        projection.upsert("wf-1", "customer", "acme");
        assertThat(projection.get("wf-1", "customer")).contains("acme");
    }

    @Test
    void findByAttribute_returnsMatchingInstances() {
        projection.upsert("wf-1", "status", "active");
        projection.upsert("wf-2", "status", "active");
        projection.upsert("wf-3", "status", "inactive");

        assertThat(projection.findByAttribute("status", "active"))
                .containsExactlyInAnyOrder("wf-1", "wf-2");
    }

    @Test
    void findByAttributes_intersectsResults() {
        projection.upsert("wf-1", "region", "us");
        projection.upsert("wf-1", "tier", "premium");
        projection.upsert("wf-2", "region", "us");
        projection.upsert("wf-2", "tier", "standard");
        projection.upsert("wf-3", "region", "eu");
        projection.upsert("wf-3", "tier", "premium");

        assertThat(projection.findByAttributes(Map.of("region", "us", "tier", "premium")))
                .containsExactly("wf-1");
    }

    @Test
    void upsert_updatesInvertedIndex() {
        projection.upsert("wf-1", "status", "active");
        assertThat(projection.findByAttribute("status", "active")).contains("wf-1");

        projection.upsert("wf-1", "status", "completed");
        assertThat(projection.findByAttribute("status", "active")).doesNotContain("wf-1");
        assertThat(projection.findByAttribute("status", "completed")).contains("wf-1");
    }

    @Test
    void remove_cleansUpAllIndexes() {
        projection.upsert("wf-1", "a", "1");
        projection.upsert("wf-1", "b", "2");

        projection.remove("wf-1");
        assertThat(projection.get("wf-1", "a")).isEmpty();
        assertThat(projection.findByAttribute("a", "1")).isEmpty();
        assertThat(projection.findByAttribute("b", "2")).isEmpty();
    }

    @Test
    void getAll_returnsAllAttributes() {
        projection.upsert("wf-1", "a", "1");
        projection.upsert("wf-1", "b", "2");

        assertThat(projection.getAll("wf-1")).containsExactlyInAnyOrderEntriesOf(Map.of("a", "1", "b", "2"));
    }
}
