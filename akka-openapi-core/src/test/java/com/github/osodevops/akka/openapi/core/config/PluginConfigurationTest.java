package com.github.osodevops.akka.openapi.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for PluginConfiguration.
 */
class PluginConfigurationTest {

    @Test
    void shouldBuildWithDefaults() {
        PluginConfiguration config = PluginConfiguration.builder().build();

        assertThat(config.getApiTitle()).isEqualTo("API");
        assertThat(config.getApiVersion()).isEqualTo("1.0.0");
        assertThat(config.getApiDescription()).isEmpty();
        assertThat(config.getServers()).isEmpty();
        assertThat(config.getScanPackages()).isEmpty();
        assertThat(config.isGenerateRequestSchemas()).isTrue();
        assertThat(config.isGenerateResponseSchemas()).isTrue();
        assertThat(config.isIncludeSecuritySchemes()).isTrue();
        assertThat(config.isFailOnValidationError()).isTrue();
        assertThat(config.getSecuritySchemes()).isEmpty();
    }

    @Test
    void shouldBuildWithCustomValues() {
        ServerConfig server = new ServerConfig("https://api.example.com", "Production");

        PluginConfiguration config = PluginConfiguration.builder()
            .apiTitle("My API")
            .apiVersion("2.0.0")
            .apiDescription("Test API")
            .termsOfService("https://example.com/terms")
            .contactName("Support")
            .contactEmail("support@example.com")
            .contactUrl("https://example.com/support")
            .licenseName("Apache 2.0")
            .licenseUrl("https://www.apache.org/licenses/LICENSE-2.0")
            .addServer(server)
            .addScanPackage("com.example")
            .generateRequestSchemas(false)
            .generateResponseSchemas(false)
            .includeSecuritySchemes(false)
            .failOnValidationError(false)
            .build();

        assertThat(config.getApiTitle()).isEqualTo("My API");
        assertThat(config.getApiVersion()).isEqualTo("2.0.0");
        assertThat(config.getApiDescription()).isEqualTo("Test API");
        assertThat(config.getTermsOfService()).isEqualTo("https://example.com/terms");
        assertThat(config.getContactName()).isEqualTo("Support");
        assertThat(config.getContactEmail()).isEqualTo("support@example.com");
        assertThat(config.getContactUrl()).isEqualTo("https://example.com/support");
        assertThat(config.getLicenseName()).isEqualTo("Apache 2.0");
        assertThat(config.getLicenseUrl()).isEqualTo("https://www.apache.org/licenses/LICENSE-2.0");
        assertThat(config.getServers()).containsExactly(server);
        assertThat(config.getScanPackages()).containsExactly("com.example");
        assertThat(config.isGenerateRequestSchemas()).isFalse();
        assertThat(config.isGenerateResponseSchemas()).isFalse();
        assertThat(config.isIncludeSecuritySchemes()).isFalse();
        assertThat(config.isFailOnValidationError()).isFalse();
    }

    @Test
    void shouldRejectNullApiTitle() {
        assertThatThrownBy(() -> PluginConfiguration.builder().apiTitle(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("apiTitle");
    }

    @Test
    void shouldRejectNullApiVersion() {
        assertThatThrownBy(() -> PluginConfiguration.builder().apiVersion(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("apiVersion");
    }

    @Test
    void serversListShouldBeImmutable() {
        PluginConfiguration config = PluginConfiguration.builder()
            .addServer(new ServerConfig("https://api.example.com", "Production"))
            .build();

        assertThatThrownBy(() -> config.getServers().add(new ServerConfig("https://test.com", "Test")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void scanPackagesListShouldBeImmutable() {
        PluginConfiguration config = PluginConfiguration.builder()
            .addScanPackage("com.example")
            .build();

        assertThatThrownBy(() -> config.getScanPackages().add("com.other"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldBuildWithSecuritySchemes() {
        SecuritySchemeConfig scheme1 = new SecuritySchemeConfig(
            "CustomAuthHeader", "apiKey", "header", "x-custom-auth", "Custom authentication header");
        SecuritySchemeConfig scheme2 = new SecuritySchemeConfig(
            "SecondaryAuthHeader", "apiKey", "header", "x-secondary-auth", "Secondary authentication header");

        PluginConfiguration config = PluginConfiguration.builder()
            .apiTitle("Test API")
            .apiVersion("1.0.0")
            .addSecurityScheme(scheme1)
            .addSecurityScheme(scheme2)
            .build();

        assertThat(config.getSecuritySchemes()).hasSize(2);
        assertThat(config.getSecuritySchemes().get(0).getSchemeName()).isEqualTo("CustomAuthHeader");
        assertThat(config.getSecuritySchemes().get(0).getType()).isEqualTo("apiKey");
        assertThat(config.getSecuritySchemes().get(0).getIn()).isEqualTo("header");
        assertThat(config.getSecuritySchemes().get(0).getName()).isEqualTo("x-custom-auth");
        assertThat(config.getSecuritySchemes().get(0).getDescription()).isEqualTo("Custom authentication header");
        assertThat(config.getSecuritySchemes().get(1).getSchemeName()).isEqualTo("SecondaryAuthHeader");
    }

    @Test
    void shouldBuildWithSecuritySchemesUsingBulkSetter() {
        SecuritySchemeConfig scheme = new SecuritySchemeConfig(
            "ApiKey", "apiKey", "header", "x-api-key", null);

        PluginConfiguration config = PluginConfiguration.builder()
            .apiTitle("Test API")
            .apiVersion("1.0.0")
            .securitySchemes(List.of(scheme))
            .build();

        assertThat(config.getSecuritySchemes()).hasSize(1);
        assertThat(config.getSecuritySchemes().get(0).getSchemeName()).isEqualTo("ApiKey");
    }

    @Test
    void securitySchemesListShouldBeImmutable() {
        PluginConfiguration config = PluginConfiguration.builder()
            .addSecurityScheme(new SecuritySchemeConfig(
                "ApiKey", "apiKey", "header", "x-api-key", null))
            .build();

        assertThatThrownBy(() -> config.getSecuritySchemes().add(
            new SecuritySchemeConfig("Other", "apiKey", "header", "x-other", null)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldHandleNullSecuritySchemesList() {
        PluginConfiguration config = PluginConfiguration.builder()
            .securitySchemes(null)
            .build();

        assertThat(config.getSecuritySchemes()).isEmpty();
    }
}
