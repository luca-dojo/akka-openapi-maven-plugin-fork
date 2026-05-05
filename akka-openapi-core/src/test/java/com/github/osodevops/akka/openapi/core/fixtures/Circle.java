package com.github.osodevops.akka.openapi.core.fixtures;

/**
 * Test fixture: Circle subtype of Shape.
 */
public record Circle(double radius) implements Shape {
    @Override
    public double area() {
        return Math.PI * radius * radius;
    }
}

