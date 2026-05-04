package com.github.osodevops.akka.openapi.core.config;

import java.util.Objects;

/**
 * Configuration for an OpenAPI security scheme entry.
 *
 * <p>Supports apiKey, http, oauth2, and openIdConnect security scheme types.
 * For apiKey schemes, {@code in} and {@code name} must be specified.</p>
 *
 * @since 1.0.0
 */
public class SecuritySchemeConfig {

    private String schemeName;
    private String type = "apiKey";
    private String in = "header";
    private String name;
    private String description;

    public SecuritySchemeConfig() {
    }

    public SecuritySchemeConfig(String schemeName, String type, String in, String name, String description) {
        this.schemeName = Objects.requireNonNull(schemeName, "schemeName must not be null");
        this.type = type != null ? type : "apiKey";
        this.in = in != null ? in : "header";
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = description;
    }

    public String getSchemeName() {
        return schemeName;
    }

    public void setSchemeName(String schemeName) {
        this.schemeName = schemeName;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getIn() {
        return in;
    }

    public void setIn(String in) {
        this.in = in;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SecuritySchemeConfig that = (SecuritySchemeConfig) o;
        return Objects.equals(schemeName, that.schemeName) &&
                Objects.equals(type, that.type) &&
                Objects.equals(in, that.in) &&
                Objects.equals(name, that.name) &&
                Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemeName, type, in, name, description);
    }

    @Override
    public String toString() {
        return "SecuritySchemeConfig{" +
                "schemeName='" + schemeName + '\'' +
                ", type='" + type + '\'' +
                ", in='" + in + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                '}';
    }
}

