package com.github.osodevops.akka.openapi.core.fixtures;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Test fixture: polymorphic sealed interface with Jackson annotations.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "shapeType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Circle.class, name = "CIRCLE"),
    @JsonSubTypes.Type(value = Rectangle.class, name = "RECTANGLE"),
    @JsonSubTypes.Type(value = Triangle.class, name = "TRIANGLE")
})
public sealed interface Shape permits Circle, Rectangle, Triangle {
    double area();
}
