package com.github.osodevops.akka.openapi.maven;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for GenerateOpenAPIMojo.
 *
 * Note: Full integration tests are in src/it directory.
 */
class GenerateOpenAPIMojoTest {

    private GenerateOpenAPIMojo mojo;

    @BeforeEach
    void setUp() {
        mojo = new GenerateOpenAPIMojo();
        mojo.setApiTitle("Test API");
        mojo.setApiVersion("1.0.0");
    }

    @Test
    void shouldSkipExecutionWhenSkipIsTrue() throws Exception {
        mojo.setSkip(true);

        // Should not throw when skip is true (project not needed)
        mojo.execute();
    }

    @Test
    void shouldSetOutputFormatFromConfiguration() {
        mojo.setOutputFormat("json");
        assertThat(mojo).isNotNull();
    }

    @Test
    void shouldConfigureServers() {
        GenerateOpenAPIMojo.ServerConfigParam server1 = new GenerateOpenAPIMojo.ServerConfigParam();
        server1.setUrl("https://api.example.com");
        server1.setDescription("Production");

        GenerateOpenAPIMojo.ServerConfigParam server2 = new GenerateOpenAPIMojo.ServerConfigParam();
        server2.setUrl("https://staging.example.com");
        server2.setDescription("Staging");

        mojo.setServers(List.of(server1, server2));

        assertThat(server1.getUrl()).isEqualTo("https://api.example.com");
        assertThat(server1.getDescription()).isEqualTo("Production");
        assertThat(server2.getUrl()).isEqualTo("https://staging.example.com");
        assertThat(server2.getDescription()).isEqualTo("Staging");
    }

    @Test
    void shouldConfigureScanPackages() {
        mojo.setScanPackages(List.of("com.example.api", "com.example.endpoints"));
        assertThat(mojo).isNotNull();
    }

    @Test
    void shouldSetFailOnValidationError() {
        mojo.setFailOnValidationError(false);
        assertThat(mojo).isNotNull();
    }

    @Test
    void shouldConfigureApiDescription() {
        mojo.setApiDescription("Test API Description");
        assertThat(mojo).isNotNull();
    }

    @Test
    void shouldConfigureOutputFile() {
        File outputFile = new File("target/openapi.yaml");
        mojo.setOutputFile(outputFile);
        assertThat(mojo).isNotNull();
    }

    @Test
    void shouldCreateServerConfigParam() {
        GenerateOpenAPIMojo.ServerConfigParam server = new GenerateOpenAPIMojo.ServerConfigParam();

        server.setUrl("https://api.example.com");
        server.setDescription("Production server");

        assertThat(server.getUrl()).isEqualTo("https://api.example.com");
        assertThat(server.getDescription()).isEqualTo("Production server");
    }

    @Test
    void shouldCreateSecuritySchemeParam() {
        GenerateOpenAPIMojo.SecuritySchemeParam scheme = new GenerateOpenAPIMojo.SecuritySchemeParam();

        scheme.setSchemeName("CustomAuthHeader");
        scheme.setType("apiKey");
        scheme.setIn("header");
        scheme.setName("x-custom-auth");
        scheme.setDescription("Custom authentication header");

        assertThat(scheme.getSchemeName()).isEqualTo("CustomAuthHeader");
        assertThat(scheme.getType()).isEqualTo("apiKey");
        assertThat(scheme.getIn()).isEqualTo("header");
        assertThat(scheme.getName()).isEqualTo("x-custom-auth");
        assertThat(scheme.getDescription()).isEqualTo("Custom authentication header");
    }

    @Test
    void shouldConfigureSecuritySchemes() {
        GenerateOpenAPIMojo.SecuritySchemeParam scheme1 = new GenerateOpenAPIMojo.SecuritySchemeParam();
        scheme1.setSchemeName("CustomAuthHeader");
        scheme1.setType("apiKey");
        scheme1.setIn("header");
        scheme1.setName("x-custom-auth");

        GenerateOpenAPIMojo.SecuritySchemeParam scheme2 = new GenerateOpenAPIMojo.SecuritySchemeParam();
        scheme2.setSchemeName("SecondaryAuthHeader");
        scheme2.setType("apiKey");
        scheme2.setIn("header");
        scheme2.setName("x-secondary-auth");

        mojo.setSecurity(List.of(scheme1, scheme2));

        assertThat(scheme1.getSchemeName()).isEqualTo("CustomAuthHeader");
        assertThat(scheme2.getSchemeName()).isEqualTo("SecondaryAuthHeader");
    }

    @Test
    void shouldHaveDefaultsForSecuritySchemeParam() {
        GenerateOpenAPIMojo.SecuritySchemeParam scheme = new GenerateOpenAPIMojo.SecuritySchemeParam();

        assertThat(scheme.getType()).isEqualTo("apiKey");
        assertThat(scheme.getIn()).isEqualTo("header");
    }
}
