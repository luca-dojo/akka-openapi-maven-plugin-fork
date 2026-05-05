package com.github.osodevops.akka.openapi.core.fixtures;

/**
 * Test fixture: Triangle subtype of Shape.
 */
public record Triangle(double base, double height) implements Shape {
    @Override
    public double area() {
        return 0.5 * base * height;
    }
}

