package com.github.osodevops.akka.openapi.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests covering the three issues reported in
 * <a href="https://github.com/osodevops/akka-openapi-maven-plugin/issues/59">Issue #59</a>.
 */
class Issue59FixTest {

    private SchemaGenerator generator;

    @BeforeEach
    void setUp() {
        List<String> logs = new ArrayList<>();
        generator = new SchemaGenerator(logs::add);
    }

    // ====================================================================
    // Bug 1: @JsonValue wrappers should retain Jakarta Validation constraints
    // ====================================================================

    @Nested
    class JsonValueConstraintsAreRetained {

        @Test
        void decimalMinMaxOnDoubleRecord() {
            generator.generateSchema(Ratio.class);

            Schema<?> schema = generator.getGeneratedSchemas().get("Ratio");
            assertThat(schema).isInstanceOf(NumberSchema.class);
            assertThat(schema.getMinimum()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(schema.getMaximum()).isEqualByComparingTo(BigDecimal.ONE);
        }

        @Test
        void decimalMinExclusiveBecomesExclusiveMinimum() {
            generator.generateSchema(ExclusiveLowerBound.class);

            Schema<?> schema = generator.getGeneratedSchemas().get("ExclusiveLowerBound");
            assertThat(schema).isInstanceOf(NumberSchema.class);
            assertThat(schema.getExclusiveMinimumValue())
                .as("@DecimalMin(inclusive=false) -> exclusiveMinimum")
                .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(schema.getMinimum()).isNull();
        }

        @Test
        void minMaxOnIntRecord() {
            generator.generateSchema(BoundedCount.class);

            Schema<?> schema = generator.getGeneratedSchemas().get("BoundedCount");
            assertThat(schema).isInstanceOf(IntegerSchema.class);
            assertThat(schema.getMinimum()).isEqualByComparingTo(BigDecimal.valueOf(1));
            assertThat(schema.getMaximum()).isEqualByComparingTo(BigDecimal.valueOf(100));
        }

        @Test
        void sizeOnStringWrapper() {
            generator.generateSchema(BoundedString.class);

            Schema<?> schema = generator.getGeneratedSchemas().get("BoundedString");
            assertThat(schema).isInstanceOf(StringSchema.class);
            assertThat(schema.getMinLength()).isEqualTo(3);
            assertThat(schema.getMaxLength()).isEqualTo(10);
        }

        @Test
        void patternOnStringWrapper() {
            generator.generateSchema(PatternedString.class);

            Schema<?> schema = generator.getGeneratedSchemas().get("PatternedString");
            assertThat(schema).isInstanceOf(StringSchema.class);
            assertThat(schema.getPattern()).isEqualTo("^[A-Z]{3}$");
        }

        @Test
        void positiveOnIntWrapper() {
            generator.generateSchema(PositiveCount.class);

            Schema<?> schema = generator.getGeneratedSchemas().get("PositiveCount");
            assertThat(schema).isInstanceOf(IntegerSchema.class);
            assertThat(schema.getExclusiveMinimumValue())
                .as("@Positive -> exclusiveMinimum 0")
                .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        void positiveOrZeroOnIntWrapper() {
            generator.generateSchema(NonNegativeCount.class);

            Schema<?> schema = generator.getGeneratedSchemas().get("NonNegativeCount");
            assertThat(schema).isInstanceOf(IntegerSchema.class);
            assertThat(schema.getMinimum()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        void notBlankAndNotEmptyOnStringWrapper() {
            generator.generateSchema(NonEmptyString.class);

            Schema<?> schema = generator.getGeneratedSchemas().get("NonEmptyString");
            assertThat(schema).isInstanceOf(StringSchema.class);
            assertThat(schema.getMinLength()).isEqualTo(1);
        }

        @Test
        void constraintsOnReferencedWrapperPropagateThroughComponentRef() {
            // Mirrors the user's example: Request wraps Ratio
            generator.generateSchema(RequestWithWrapper.class);

            Map<String, Schema<?>> schemas = generator.getGeneratedSchemas();
            // The Ratio component schema should be present and constrained
            Schema<?> ratio = schemas.get("Ratio");
            assertThat(ratio).isInstanceOf(NumberSchema.class);
            assertThat(ratio.getMinimum()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ratio.getMaximum()).isEqualByComparingTo(BigDecimal.ONE);

            // The Request schema should reference Ratio (not inline a bare scalar)
            Schema<?> request = schemas.get("RequestWithWrapper");
            assertThat(request.getProperties()).containsKey("ratio");
        }

        // Non-record @JsonValue with constraints on the getter
        @Test
        void constraintsOnJsonValueGetterOfPojo() {
            generator.generateSchema(JsonValuePojo.class);

            Schema<?> schema = generator.getGeneratedSchemas().get("JsonValuePojo");
            assertThat(schema).isInstanceOf(StringSchema.class);
            assertThat(schema.getMinLength()).isEqualTo(1);
            assertThat(schema.getMaxLength()).isEqualTo(20);
        }
    }

    // ====================================================================
    // Bug 2: Polymorphic oneOf variants should carry a title
    // ====================================================================

    @Nested
    class PolymorphicSubtypesHaveTitles {

        @Test
        void everySubtypeComponentHasATitle() {
            generator.generateSchema(Response.class);

            Map<String, Schema<?>> schemas = generator.getGeneratedSchemas();
            assertThat(schemas).containsKeys("Response", "Success", "Rejected", "Failed");

            for (String name : List.of("Success", "Rejected", "Failed")) {
                Schema<?> s = schemas.get(name);
                assertThat(s.getTitle())
                    .as("subtype %s should have a title", name)
                    .isEqualTo(name);
            }
        }

        @Test
        void rootComposedSchemaIsValidAndOneOfStillHasRefs() {
            generator.generateSchema(Response.class);

            Schema<?> root = generator.getGeneratedSchemas().get("Response");
            assertThat(root).isInstanceOf(ComposedSchema.class);
            ComposedSchema cs = (ComposedSchema) root;
            assertThat(cs.getOneOf()).hasSize(3);
            // Variants stay as plain $ref entries — title lives on the referenced schema
            for (Schema<?> variant : cs.getOneOf()) {
                assertThat(variant.get$ref()).startsWith("#/components/schemas/");
            }
        }
    }

    // ====================================================================
    // Bug 3: Map<K, ComplexValue> should expose properties of the value type
    // (Should already pass on main thanks to commit 16c71f7 — locked in as a
    // regression test.)
    // ====================================================================

    @Nested
    class MapValueRegressions {

        @Test
        void mapValueTypeIsRegisteredWithItsProperties() {
            generator.generateSchema(MapRequest.class);

            Map<String, Schema<?>> schemas = generator.getGeneratedSchemas();
            assertThat(schemas).containsKey("Params");

            Schema<?> params = schemas.get("Params");
            assertThat(params.getProperties()).isNotNull();
            assertThat(params.getProperties().keySet())
                .contains("instructionA", "instructionB");
        }

        @Test
        void mapFieldReferencesValueTypeViaAdditionalProperties() {
            generator.generateSchema(MapRequest.class);

            Schema<?> request = generator.getGeneratedSchemas().get("MapRequest");
            Schema<?> mapField = request.getProperties().get("mapSchemas");
            assertThat(mapField).isInstanceOf(ObjectSchema.class);
            Object addProps = mapField.getAdditionalProperties();
            assertThat(addProps).isInstanceOf(Schema.class);
            assertThat(((Schema<?>) addProps).get$ref())
                .isEqualTo("#/components/schemas/Params");
        }
    }

    // ----- Test fixtures for Bug 1 ---------------------------------------

    private record Ratio(@JsonValue @DecimalMin("0") @DecimalMax("1") double value) {}

    private record ExclusiveLowerBound(
        @JsonValue @DecimalMin(value = "0", inclusive = false) double value
    ) {}

    private record BoundedCount(@JsonValue @Min(1) @Max(100) int value) {}

    private record BoundedString(@JsonValue @Size(min = 3, max = 10) String value) {}

    private record PatternedString(@JsonValue @Pattern(regexp = "^[A-Z]{3}$") String value) {}

    private record PositiveCount(@JsonValue @Positive int value) {}

    private record NonNegativeCount(@JsonValue @PositiveOrZero int value) {}

    private record NonEmptyString(@JsonValue @NotBlank @NotEmpty String value) {}

    private record RequestWithWrapper(Ratio ratio) {}

    /** Non-record POJO with @JsonValue on the getter and Jakarta annotations on the getter. */
    public static final class JsonValuePojo {
        private final String code;

        public JsonValuePojo(String code) {
            this.code = code;
        }

        @JsonValue
        @NotEmpty
        @Size(max = 20)
        public String getCode() {
            return code;
        }
    }

    // ----- Test fixtures for Bug 2 ---------------------------------------

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "status")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Response.Success.class, name = "success"),
        @JsonSubTypes.Type(value = Response.Rejected.class, name = "rejected"),
        @JsonSubTypes.Type(value = Response.Failed.class, name = "failed")
    })
    sealed interface Response permits Response.Success, Response.Rejected, Response.Failed {
        record Success(String text) implements Response {}
        record Rejected(String reason) implements Response {}
        record Failed(String reason) implements Response {}
    }

    // ----- Test fixtures for Bug 3 ---------------------------------------

    private record Params(String instructionA, String instructionB) {}

    private record MapRequest(String rootParam, Map<String, Params> mapSchemas) {}
}
