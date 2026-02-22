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

package org.fireflyframework.orchestration.saga.composition;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for reusable saga composition templates. Templates can be registered
 * by name and retrieved for execution or composition reuse.
 */
@Slf4j
public class CompositionTemplateRegistry {

    /**
     * A named, reusable composition template with an optional description.
     */
    public record CompositionTemplate(String name, SagaComposition composition, String description) {}

    private final Map<String, CompositionTemplate> templates = new ConcurrentHashMap<>();

    /**
     * Registers a composition template. Throws if a template with the same name already exists.
     */
    public void register(CompositionTemplate template) {
        if (templates.putIfAbsent(template.name(), template) != null) {
            throw new IllegalStateException("Composition template '" + template.name() + "' already registered");
        }
        log.info("[saga-composition] Registered composition template '{}'", template.name());
    }

    /**
     * Retrieves a composition template by name.
     *
     * @return the template, or null if not found
     */
    public CompositionTemplate get(String name) {
        return templates.get(name);
    }

    /**
     * Returns all registered templates.
     */
    public Collection<CompositionTemplate> getAll() {
        return Collections.unmodifiableCollection(templates.values());
    }

    /**
     * Returns true if a template with the given name exists.
     */
    public boolean has(String name) {
        return templates.containsKey(name);
    }
}
