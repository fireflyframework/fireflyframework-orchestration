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

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Utilities for extracting {@link Method} from Java method references ({@code Class::method})
 * via serializable lambdas.
 *
 * <p>Provides serializable functional interfaces (Fn1..Fn4) that capture
 * method references for reflective inspection.
 *
 * <pre>{@code
 * Method m = MethodRefs.methodOf((MethodRefs.Fn1<OrderService, Mono<?>>) OrderService::process);
 * }</pre>
 */
public final class MethodRefs {

    private MethodRefs() {}

    public interface Fn1<A, R> extends Serializable { R apply(A a); }
    public interface Fn2<A, B, R> extends Serializable { R apply(A a, B b); }
    public interface Fn3<A, B, C, R> extends Serializable { R apply(A a, B b, C c); }
    public interface Fn4<A, B, C, D, R> extends Serializable { R apply(A a, B b, C c, D d); }

    /**
     * Extract the {@link Method} from a serializable lambda created from a method reference.
     */
    public static Method methodOf(Serializable lambda) {
        SerializedLambda sl = serializedLambda(lambda);
        String implClass = sl.getImplClass().replace('/', '.');
        String methodName = sl.getImplMethodName();
        String descriptor = sl.getImplMethodSignature();
        Class<?> targetClass = classForName(implClass);
        Class<?>[] paramTypes = parseDescriptorParams(descriptor, targetClass.getClassLoader());
        try {
            Method m = targetClass.getDeclaredMethod(methodName, paramTypes);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            // Fallback: search by name and compatible parameter count
            for (Method m : targetClass.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == paramTypes.length
                        && isCompatible(paramTypes, m.getParameterTypes())) {
                    m.setAccessible(true);
                    return m;
                }
            }
            throw new IllegalArgumentException(
                    "Could not resolve method reference: " + implClass + "::" + methodName + descriptor);
        }
    }

    private static boolean isCompatible(Class<?>[] wanted, Class<?>[] actual) {
        if (wanted.length != actual.length) return false;
        for (int i = 0; i < wanted.length; i++) {
            if (!wrap(actual[i]).isAssignableFrom(wrap(wanted[i]))) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        if (c == short.class) return Short.class;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        return c;
    }

    private static SerializedLambda serializedLambda(Serializable lambda) {
        try {
            Method writeReplace = lambda.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            Object replacement = writeReplace.invoke(lambda);
            if (replacement instanceof SerializedLambda sl) {
                return sl;
            }
            throw new IllegalArgumentException("Not a SerializedLambda: " + replacement);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Failed to extract SerializedLambda", e);
        }
    }

    private static Class<?> classForName(String name) {
        try {
            return Class.forName(name, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ex) {
                throw new IllegalArgumentException("Class not found: " + name, ex);
            }
        }
    }

    private static Class<?>[] parseDescriptorParams(String desc, ClassLoader cl) {
        int start = desc.indexOf('(');
        int end = desc.indexOf(')');
        if (start < 0 || end < 0 || end < start) {
            throw new IllegalArgumentException("Bad method descriptor: " + desc);
        }
        String params = desc.substring(start + 1, end);
        List<Class<?>> types = new ArrayList<>();
        int i = 0;
        while (i < params.length()) {
            char c = params.charAt(i);
            int arrayDims = 0;
            while (c == '[') { arrayDims++; i++; c = params.charAt(i); }
            Class<?> t = switch (c) {
                case 'B' -> { i++; yield byte.class; }
                case 'C' -> { i++; yield char.class; }
                case 'D' -> { i++; yield double.class; }
                case 'F' -> { i++; yield float.class; }
                case 'I' -> { i++; yield int.class; }
                case 'J' -> { i++; yield long.class; }
                case 'S' -> { i++; yield short.class; }
                case 'Z' -> { i++; yield boolean.class; }
                case 'V' -> { i++; yield void.class; }
                case 'L' -> {
                    int semi = params.indexOf(';', i);
                    String cname = params.substring(i + 1, semi).replace('/', '.');
                    i = semi + 1;
                    yield forName(cname, cl);
                }
                default -> throw new IllegalArgumentException(
                        "Unknown descriptor token '" + c + "' in " + desc);
            };
            if (arrayDims > 0) {
                t = arrayClass(t, arrayDims);
            }
            types.add(t);
        }
        return types.toArray(new Class<?>[0]);
    }

    private static Class<?> arrayClass(Class<?> component, int dims) {
        StringBuilder sb = new StringBuilder();
        for (int d = 0; d < dims; d++) sb.append('[');
        if (component.isPrimitive()) {
            sb.append(primitiveDescriptor(component));
        } else {
            sb.append('L').append(component.getName()).append(';');
        }
        try {
            return Class.forName(sb.toString());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Array class not found: " + sb, e);
        }
    }

    private static char primitiveDescriptor(Class<?> c) {
        if (c == byte.class) return 'B';
        if (c == char.class) return 'C';
        if (c == double.class) return 'D';
        if (c == float.class) return 'F';
        if (c == int.class) return 'I';
        if (c == long.class) return 'J';
        if (c == short.class) return 'S';
        if (c == boolean.class) return 'Z';
        return 'V';
    }

    private static Class<?> forName(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ex) {
                throw new IllegalArgumentException("Class not found: " + name, ex);
            }
        }
    }
}
