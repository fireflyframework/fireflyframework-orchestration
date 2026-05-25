/*
 * Copyright 2024-2026 Firefly Software Foundation
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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fireflyframework.orchestration.core.persistence.ExecutionPersistenceProvider;
import org.fireflyframework.orchestration.persistence.cache.CachePersistenceProvider;
import org.fireflyframework.orchestration.persistence.eventsourced.EventSourcedPersistenceProvider;
import org.fireflyframework.orchestration.persistence.redis.RedisPersistenceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
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
// IMPORTANT: `after = RedisReactiveAutoConfiguration.class` is required so that
// the @ConditionalOnBean(ReactiveRedisTemplate.class) on RedisPersistenceConfig
// is evaluated AFTER Spring Boot has registered the ReactiveRedisTemplate bean.
// Without it, the condition silently fails and the in-memory fallback wins even
// when firefly.orchestration.persistence.provider=redis is set.
@AutoConfiguration(
        before = OrchestrationAutoConfiguration.class,
        after = RedisReactiveAutoConfiguration.class)
public class OrchestrationPersistenceAutoConfiguration {

    /**
     * Dedicated ObjectMapper for orchestration state persistence (Redis, event-sourced).
     *
     * <p>{@link org.fireflyframework.orchestration.core.persistence.ExecutionState#report()}
     * is an {@link java.util.Optional}, which requires {@code jackson-datatype-jdk8} to
     * serialize. We cannot rely on the shared application {@code ObjectMapper} bean
     * because other framework modules (e.g. {@code fireflyframework-eda}) declare a
     * {@code @Bean @ConditionalOnMissingBean ObjectMapper} that registers only
     * {@code JavaTimeModule}, and may win the auto-configuration race over Spring Boot's
     * own {@code Jackson2ObjectMapperBuilder}, leaving the persistence path without
     * {@code Jdk8Module}.
     *
     * <p>This dedicated mapper invokes {@code findAndRegisterModules()} to auto-discover
     * every Jackson module on the classpath (Jdk8, JavaTime, Parameter Names, etc.),
     * matching the pattern already used by {@code SagaPersistenceAutoConfiguration} and
     * {@code EventSourcingAutoConfiguration} elsewhere in the framework.
     */
    @Bean
    @ConditionalOnMissingBean(name = "orchestrationPersistenceObjectMapper")
    public ObjectMapper orchestrationPersistenceObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        // ExecutionState is a record exposing derived getters (isTerminal, isSuccess,
        // isFailed) which Jackson serializes as fields but cannot map back to the
        // canonical constructor on read. Disabling FAIL_ON_UNKNOWN_PROPERTIES makes
        // findById() round-trips work without polluting the record with @JsonIgnore
        // annotations on every derived accessor.
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

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
                @Qualifier("orchestrationPersistenceObjectMapper") ObjectMapper objectMapper,
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
                @Qualifier("orchestrationPersistenceObjectMapper") ObjectMapper objectMapper) {
            log.info("[orchestration] Using event-sourced persistence provider");
            return new EventSourcedPersistenceProvider(eventStore, objectMapper);
        }
    }
}
