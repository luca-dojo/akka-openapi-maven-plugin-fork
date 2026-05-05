package com.github.osodevops.akka.openapi.core.fixtures;

/**
 * Test fixture: Rectangle subtype of Shape.
 */
public record Rectangle(double width, double height) implements Shape {
    @Override
    public double area() {
        return width * height;
    }
}

