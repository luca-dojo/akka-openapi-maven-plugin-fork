package com.github.osodevops.akka.openapi.core;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Utility class to detect and unwrap Akka / Pekko HTTP wrapper types.
 *
 * <p>When an endpoint method declares a return type of {@code HttpResponse} (raw or
 * parameterized), the plugin needs to extract the inner payload type so that the
 * generated OpenAPI response schema reflects the actual domain object rather than
 * the HTTP-level wrapper.</p>
 *
 * <p>Supported scenarios:</p>
 * <ol>
 *   <li><b>Parameterized wrapper</b> – {@code HttpResponse<CustomerResponse>}: the first
 *       type argument ({@code CustomerResponse}) is returned as the payload type.</li>
 *   <li><b>Raw wrapper</b> – {@code HttpResponse}: no inner type can be inferred; callers
 *       should fall back to the {@code @OpenAPIResponseSchema} annotation.</li>
 * </ol>
 *
 * @since 1.0.0
 */
public final class HttpResponseUnwrapper {

    /**
     * Well-known class names that represent HTTP response wrapper types.
     * Raw class names are used so that the Akka SDK does not need to be on the
     * compile-time classpath of the plugin itself.
     */
    private static final Set<String> DEFAULT_HTTP_RESPONSE_CLASS_NAMES = Set.of(
        "akka.http.javadsl.model.HttpResponse",
        "akka.http.scaladsl.model.HttpResponse",
        "org.apache.pekko.http.javadsl.model.HttpResponse",
        "org.apache.pekko.http.scaladsl.model.HttpResponse",
        // Akka SDK convenience type (if ever generic)
        "akka.javasdk.http.HttpResponse"
    );

    /** Mutable set that allows additional class names to be registered (e.g. in tests). */
    private static final Set<String> HTTP_RESPONSE_CLASS_NAMES =
        Collections.synchronizedSet(new HashSet<>(DEFAULT_HTTP_RESPONSE_CLASS_NAMES));

    private HttpResponseUnwrapper() {
        // utility class
    }

    /**
     * Registers an additional class name to be treated as an HTTP response wrapper.
     *
     * <p>This is primarily intended for testing purposes, allowing test-specific stub
     * classes to be recognised by the unwrapper without requiring the real Akka SDK on
     * the test classpath.</p>
     *
     * @param className fully-qualified class name to register
     */
    static void registerHttpResponseClassName(String className) {
        HTTP_RESPONSE_CLASS_NAMES.add(className);
    }

    /**
     * Resets the set of known HTTP response class names to the defaults.
     * Intended for use in tests only.
     */
    static void resetToDefaults() {
        HTTP_RESPONSE_CLASS_NAMES.clear();
        HTTP_RESPONSE_CLASS_NAMES.addAll(DEFAULT_HTTP_RESPONSE_CLASS_NAMES);
    }

    /**
     * Returns {@code true} if the given type is (or wraps) an Akka/Pekko
     * {@code HttpResponse} type.
     *
     * @param type the Java type to test
     * @return {@code true} when the type is an HTTP wrapper
     */
    public static boolean isHttpResponseType(Type type) {
        if (type == null) {
            return false;
        }
        Class<?> rawClass = getRawClass(type);
        return rawClass != null && isHttpResponseClass(rawClass);
    }

    /**
     * Attempts to resolve the inner payload type from an {@code HttpResponse<T>}
     * parameterized type.
     *
     * @param type the parameterized type (e.g. {@code HttpResponse<CustomerResponse>})
     * @return the first type argument, or {@code null} if the type is not a parameterized
     *         HTTP response wrapper (i.e. it is a raw {@code HttpResponse})
     */
    public static Type unwrapParameterizedHttpResponse(Type type) {
        if (!(type instanceof ParameterizedType)) {
            return null;
        }
        ParameterizedType parameterized = (ParameterizedType) type;
        Class<?> rawClass = getRawClass(parameterized.getRawType());
        if (rawClass == null || !isHttpResponseClass(rawClass)) {
            return null;
        }
        Type[] typeArgs = parameterized.getActualTypeArguments();
        if (typeArgs.length > 0) {
            return typeArgs[0];
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static boolean isHttpResponseClass(Class<?> clazz) {
        // Check the class itself and all super-classes / interfaces
        if (HTTP_RESPONSE_CLASS_NAMES.contains(clazz.getName())) {
            return true;
        }
        // Walk the hierarchy so that sub-types are also recognised
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && isHttpResponseClass(superClass)) {
            return true;
        }
        for (Class<?> iface : clazz.getInterfaces()) {
            if (isHttpResponseClass(iface)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> getRawClass(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            Type raw = ((ParameterizedType) type).getRawType();
            if (raw instanceof Class<?>) {
                return (Class<?>) raw;
            }
        }
        return null;
    }
}


