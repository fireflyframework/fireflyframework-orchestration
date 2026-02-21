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

package org.fireflyframework.orchestration.core.scheduling;

import org.fireflyframework.orchestration.saga.annotation.Saga;
import org.fireflyframework.orchestration.saga.annotation.ScheduledSaga;
import org.fireflyframework.orchestration.saga.engine.SagaEngine;
import org.fireflyframework.orchestration.saga.engine.StepInputs;
import org.fireflyframework.orchestration.tcc.annotation.ScheduledTcc;
import org.fireflyframework.orchestration.tcc.annotation.Tcc;
import org.fireflyframework.orchestration.tcc.engine.TccEngine;
import org.fireflyframework.orchestration.tcc.engine.TccInputs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;

import java.util.Map;

/**
 * Scans for beans annotated with {@link ScheduledSaga} and {@link ScheduledTcc},
 * and registers the corresponding scheduled tasks with the {@link OrchestrationScheduler}.
 */
@Slf4j
public class SchedulingPostProcessor implements SmartInitializingSingleton {

    private final ApplicationContext applicationContext;
    private final OrchestrationScheduler scheduler;
    private final SagaEngine sagaEngine;
    private final TccEngine tccEngine;

    public SchedulingPostProcessor(ApplicationContext applicationContext,
                                   OrchestrationScheduler scheduler,
                                   SagaEngine sagaEngine,
                                   TccEngine tccEngine) {
        this.applicationContext = applicationContext;
        this.scheduler = scheduler;
        this.sagaEngine = sagaEngine;
        this.tccEngine = tccEngine;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (sagaEngine != null) {
            scanScheduledSagas();
        }
        if (tccEngine != null) {
            scanScheduledTccs();
        }
    }

    private void scanScheduledSagas() {
        Map<String, Object> sagaBeans = applicationContext.getBeansWithAnnotation(Saga.class);
        for (var entry : sagaBeans.entrySet()) {
            Class<?> cls = AopUtils.getTargetClass(entry.getValue());
            Saga sagaAnn = cls.getAnnotation(Saga.class);
            if (sagaAnn == null) continue;
            String sagaName = sagaAnn.name();

            ScheduledSaga[] schedAnns = cls.getAnnotationsByType(ScheduledSaga.class);
            if (schedAnns.length == 0) continue;

            for (ScheduledSaga schedAnn : schedAnns) {
                registerSchedule(sagaName, "saga", schedAnn.cron(), schedAnn.fixedDelay(),
                        schedAnn.fixedRate(),
                        () -> sagaEngine.execute(sagaName, StepInputs.empty()).subscribe());
            }
        }
    }

    private void scanScheduledTccs() {
        Map<String, Object> tccBeans = applicationContext.getBeansWithAnnotation(Tcc.class);
        for (var entry : tccBeans.entrySet()) {
            Class<?> cls = AopUtils.getTargetClass(entry.getValue());
            Tcc tccAnn = cls.getAnnotation(Tcc.class);
            if (tccAnn == null) continue;
            String tccName = tccAnn.name();

            ScheduledTcc[] schedAnns = cls.getAnnotationsByType(ScheduledTcc.class);
            if (schedAnns.length == 0) continue;

            for (ScheduledTcc schedAnn : schedAnns) {
                registerSchedule(tccName, "tcc", schedAnn.cron(), schedAnn.fixedDelay(),
                        schedAnn.fixedRate(),
                        () -> tccEngine.execute(tccName, TccInputs.empty()).subscribe());
            }
        }
    }

    private void registerSchedule(String name, String type, String cron, long fixedDelay,
                                  long fixedRate, Runnable task) {
        String taskId = type + ":" + name;
        if (cron != null && !cron.isBlank()) {
            scheduler.scheduleWithCron(taskId + ":cron", task, cron);
            log.info("[scheduling] Registered cron schedule for {} '{}'", type, name);
        }
        if (fixedRate > 0) {
            scheduler.scheduleAtFixedRate(taskId + ":rate", task, fixedRate, fixedRate);
            log.info("[scheduling] Registered fixed-rate schedule for {} '{}' every {}ms", type, name, fixedRate);
        }
        if (fixedDelay > 0) {
            scheduler.scheduleAtFixedRate(taskId + ":delay", task, fixedDelay, fixedDelay);
            log.info("[scheduling] Registered fixed-delay schedule for {} '{}' every {}ms", type, name, fixedDelay);
        }
    }
}
