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

package org.fireflyframework.orchestration.workflow.annotation;

import org.fireflyframework.orchestration.core.model.TriggerMode;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Workflow {
    String id() default "";
    String name() default "";
    String description() default "";
    String version() default "1.0";
    TriggerMode triggerMode() default TriggerMode.SYNC;
    String triggerEventType() default "";
    long timeoutMs() default 30000;
    int maxRetries() default 3;
    long retryDelayMs() default 1000;
    boolean publishEvents() default false;
    int layerConcurrency() default 0;
}
