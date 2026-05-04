package com.github.osodevops.akka.openapi.core;

import java.lang.reflect.Type;
import java.util.Set;

/**
 * Utility class to detect Akka / Pekko low-level HTTP response types.
 *
 * <p>When an endpoint method declares a return type of {@code HttpResponse}, the
 * Java signature does not expose the response body payload type. Callers should
 * use {@code @OpenAPIResponseSchema} to explicitly declare that payload type.</p>
 *
 * @since 1.0.2
 */
public final class HttpResponseUnwrapper {

    /**
     * Well-known class names that represent low-level HTTP response types.
     *
     * Raw class names are used so that the Akka SDK does not need to be on the
     * compile-time classpath of the plugin itself.
     */
    private static final Set<String> HTTP_RESPONSE_CLASS_NAMES = Set.of(
        "akka.http.javadsl.model.HttpResponse",
        "akka.http.scaladsl.model.HttpResponse",
        "org.apache.pekko.http.javadsl.model.HttpResponse",
        "org.apache.pekko.http.scaladsl.model.HttpResponse"
    );

    private HttpResponseUnwrapper() {
        // utility class
    }

    /**
     * Returns {@code true} if the given type is (or wraps) an Akka/Pekko
     * low-level {@code HttpResponse} type.
     *
     * @param type the Java type to test
     * @return {@code true} when the type is a low-level HTTP response
     */
    public static boolean isHttpResponseType(Type type) {
        if (type == null) {
            return false;
        }
        Class<?> rawClass = getRawClass(type);
        return rawClass != null && isHttpResponseClass(rawClass);
    }

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
        if (type instanceof java.lang.reflect.ParameterizedType) {
            Type raw = ((java.lang.reflect.ParameterizedType) type).getRawType();
            if (raw instanceof Class<?>) {
                return (Class<?>) raw;
            }
        }
        return null;
    }
}
