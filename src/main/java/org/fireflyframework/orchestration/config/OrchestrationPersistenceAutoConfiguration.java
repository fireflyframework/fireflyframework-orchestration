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

package org.fireflyframework.orchestration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.persistence.cache.CachePersistenceProvider;
import org.fireflyframework.orchestration.persistence.eventsourced.EventSourcedPersistenceProvider;
import org.fireflyframework.orchestration.persistence.redis.RedisPersistenceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for persistence adapters.
 *
 * <p>Conditionally activates the appropriate persistence provider based on
 * available beans and classpath dependencies:
 * <ul>
 *   <li>Cache — when {@code CacheAdapter} bean is present</li>
 *   <li>Redis — when {@code ReactiveRedisTemplate} bean is present</li>
 *   <li>Event-sourced — when {@code EventStore} bean is present</li>
 * </ul>
 *
 * <p>If none of the above are present, the default {@code InMemoryPersistenceProvider}
 * from {@link OrchestrationAutoConfiguration} is used.
 */
@Slf4j
@AutoConfiguration(before = OrchestrationAutoConfiguration.class)
public class OrchestrationPersistenceAutoConfiguration {

    @Configuration
    @ConditionalOnClass(name = "org.fireflyframework.cache.core.CacheAdapter")
    @ConditionalOnBean(type = "org.fireflyframework.cache.core.CacheAdapter")
    @ConditionalOnProperty(name = "firefly.orchestration.persistence.provider", havingValue = "cache")
    static class CachePersistenceConfig {

        @Bean
        @ConditionalOnMissingBean(ExecutionPersistenceProvider.class)
        public ExecutionPersistenceProvider cachePersistenceProvider(
                org.fireflyframework.cache.core.CacheAdapter cacheAdapter,
                OrchestrationProperties properties) {
            log.info("[orchestration] Using cache-based persistence provider");
            return new CachePersistenceProvider(cacheAdapter, properties.getPersistence().getKeyTtl());
        }
    }

    @Configuration
    @ConditionalOnClass(name = "org.springframework.data.redis.core.ReactiveRedisTemplate")
    @ConditionalOnBean(type = "org.springframework.data.redis.core.ReactiveRedisTemplate")
    @ConditionalOnProperty(name = "firefly.orchestration.persistence.provider", havingValue = "redis")
    static class RedisPersistenceConfig {

        @Bean
        @ConditionalOnMissingBean(ExecutionPersistenceProvider.class)
        @SuppressWarnings("unchecked")
        public ExecutionPersistenceProvider redisPersistenceProvider(
                org.springframework.data.redis.core.ReactiveRedisTemplate<String, String> redisTemplate,
                ObjectMapper objectMapper,
                OrchestrationProperties properties) {
            log.info("[orchestration] Using Redis-based persistence provider");
            return new RedisPersistenceProvider(redisTemplate, objectMapper,
                    properties.getPersistence().getKeyPrefix(),
                    properties.getPersistence().getKeyTtl());
        }
    }

    @Configuration
    @ConditionalOnClass(name = "org.fireflyframework.eventsourcing.store.EventStore")
    @ConditionalOnBean(type = "org.fireflyframework.eventsourcing.store.EventStore")
    @ConditionalOnProperty(name = "firefly.orchestration.persistence.provider", havingValue = "event-sourced")
    static class EventSourcedPersistenceConfig {

        @Bean
        @ConditionalOnMissingBean(ExecutionPersistenceProvider.class)
        public ExecutionPersistenceProvider eventSourcedPersistenceProvider(
                org.fireflyframework.eventsourcing.store.EventStore eventStore,
                ObjectMapper objectMapper) {
            log.info("[orchestration] Using event-sourced persistence provider");
            return new EventSourcedPersistenceProvider(eventStore, objectMapper);
        }
    }
}
