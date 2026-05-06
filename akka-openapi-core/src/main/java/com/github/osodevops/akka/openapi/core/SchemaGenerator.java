package com.github.osodevops.akka.openapi.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import io.swagger.v3.oas.models.media.*;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Generates OpenAPI Schema objects from Java types.
 *
 * <p>This class uses the jsonschema-generator library to convert Java POJOs
 * to JSON Schema, then transforms them into OpenAPI 3.1 compatible Schema objects.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Primitive and wrapper type support</li>
 *   <li>Collection (List, Set) and array support</li>
 *   <li>Map type support</li>
 *   <li>Optional type unwrapping</li>
 *   <li>Enum support with @JsonValue</li>
 *   <li>Circular reference detection via $ref</li>
 *   <li>Jackson annotation support (@JsonProperty, @JsonIgnore)</li>
 *   <li>Validation annotation support (@NotNull, @Size, @Pattern)</li>
 * </ul>
 */
public class SchemaGenerator {

    private final com.github.victools.jsonschema.generator.SchemaGenerator jsonSchemaGenerator;
    private final ObjectMapper objectMapper;
    private final Map<String, Schema<?>> generatedSchemas;
    private final Map<String, String> schemaNameAliases;
    private final Consumer<String> logger;
    private final Set<String> processingTypes;

    /**
     * Creates a new SchemaGenerator with default settings.
     */
    public SchemaGenerator() {
        this(msg -> {});
    }

    /**
     * Creates a new SchemaGenerator with a custom logger.
     *
     * @param logger consumer for log messages
     */
    public SchemaGenerator(Consumer<String> logger) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.objectMapper = new ObjectMapper();
        this.generatedSchemas = new ConcurrentHashMap<>();
        this.schemaNameAliases = new ConcurrentHashMap<>();
        this.processingTypes = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.jsonSchemaGenerator = createJsonSchemaGenerator();
    }

    private com.github.victools.jsonschema.generator.SchemaGenerator createJsonSchemaGenerator() {
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(
            SchemaVersion.DRAFT_2020_12,
            OptionPreset.PLAIN_JSON
        );

        // Add Jackson module for @JsonProperty, @JsonIgnore, etc.
        JacksonModule jacksonModule = new JacksonModule(
            JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
            JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE
        );
        configBuilder.with(jacksonModule);

        // Add Jakarta Validation module for @NotNull, @Size, @Pattern, etc.
        JakartaValidationModule validationModule = new JakartaValidationModule(
            JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
            JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS
        );
        configBuilder.with(validationModule);

        // Configure options
        configBuilder.with(Option.DEFINITIONS_FOR_ALL_OBJECTS);
        configBuilder.with(Option.SCHEMA_VERSION_INDICATOR);

        // Handle date/time types
        configBuilder.forTypesInGeneral()
            .withStringFormatResolver(target -> {
                Class<?> erasedType = target.getType().getErasedType();
                if (LocalDate.class.isAssignableFrom(erasedType)) {
                    return "date";
                } else if (LocalDateTime.class.isAssignableFrom(erasedType) ||
                           ZonedDateTime.class.isAssignableFrom(erasedType) ||
                           OffsetDateTime.class.isAssignableFrom(erasedType) ||
                           Instant.class.isAssignableFrom(erasedType)) {
                    return "date-time";
                } else if (LocalTime.class.isAssignableFrom(erasedType) ||
                           OffsetTime.class.isAssignableFrom(erasedType)) {
                    return "time";
                } else if (Duration.class.isAssignableFrom(erasedType) ||
                           Period.class.isAssignableFrom(erasedType)) {
                    return "duration";
                } else if (UUID.class.isAssignableFrom(erasedType)) {
                    return "uuid";
                }
                return null;
            });

        return new com.github.victools.jsonschema.generator.SchemaGenerator(configBuilder.build());
    }

    /**
     * Generates an OpenAPI Schema for the given Java type.
     *
     * @param javaType the Java type to generate a schema for
     * @return the generated OpenAPI Schema
     * @throws SchemaGenerationException if schema generation fails
     */
    public Schema<?> generateSchema(Type javaType) {
        Objects.requireNonNull(javaType, "javaType must not be null");

        Class<?> rawClass = getRawClass(javaType);
        if (rawClass == null) {
            throw new SchemaGenerationException("Cannot determine raw class for type: " + javaType);
        }

        // Handle void types explicitly
        if (rawClass == Void.class || rawClass == void.class) {
            return null;
        }

        // Check for simple types first
        Schema<?> simpleSchema = trySimpleTypeSchema(rawClass);
        if (simpleSchema != null) {
            return simpleSchema;
        }

        Schema<?> containerSchema = tryContainerSchema(javaType, rawClass);
        if (containerSchema != null) {
            return containerSchema;
        }

        String typeName = getTypeName(rawClass);

        // Check for circular reference
        if (processingTypes.contains(typeName)) {
            logger.accept("Circular reference detected for: " + typeName);
            return createReference(typeName);
        }

        // Prefer explicit Jackson polymorphism over cached schemas that may have
        // been discovered as generic $defs from another type.
        Schema<?> polymorphicSchema = tryPolymorphicSchema(rawClass, typeName);
        if (polymorphicSchema != null) {
            generatedSchemas.put(typeName, polymorphicSchema);
            return polymorphicSchema;
        }

        // For complex types, check if already generated
        if (generatedSchemas.containsKey(typeName)) {
            return createReference(typeName);
        }

        try {
            processingTypes.add(typeName);
            return generateComplexSchema(javaType, rawClass, typeName);
        } finally {
            processingTypes.remove(typeName);
        }
    }

    /**
     * Generates an OpenAPI Schema for the given Java class.
     *
     * @param javaClass the Java class to generate a schema for
     * @return the generated OpenAPI Schema
     * @throws SchemaGenerationException if schema generation fails
     */
    public Schema<?> generateSchema(Class<?> javaClass) {
        return generateSchema((Type) javaClass);
    }

    private Schema<?> tryContainerSchema(Type javaType, Class<?> rawClass) {
        if (rawClass.isArray()) {
            ArraySchema arraySchema = new ArraySchema();
            arraySchema.setItems(resolveNestedSchema(rawClass.getComponentType()));
            return arraySchema;
        }

        if (Collection.class.isAssignableFrom(rawClass)) {
            ArraySchema arraySchema = new ArraySchema();
            Type itemType = Object.class;
            if (javaType instanceof java.lang.reflect.ParameterizedType parameterizedType
                    && parameterizedType.getActualTypeArguments().length > 0) {
                itemType = parameterizedType.getActualTypeArguments()[0];
            }
            arraySchema.setItems(resolveNestedSchema(itemType));
            return arraySchema;
        }

        return null;
    }

    private Schema<?> resolveNestedSchema(Type nestedType) {
        Schema<?> schema = generateSchema(nestedType);
        if (schema == null) {
            return new ObjectSchema();
        }

        Class<?> nestedRawClass = getRawClass(nestedType);
        if (nestedRawClass != null) {
            String nestedTypeName = getTypeName(nestedRawClass);
            if (generatedSchemas.containsKey(nestedTypeName)) {
                return createReference(nestedTypeName);
            }
        }
        return schema;
    }

    private Schema<?> generateComplexSchema(Type javaType, Class<?> rawClass, String typeName) {
        try {
            logger.accept("Generating schema for: " + typeName);

            // Generate JSON Schema using victools
            ObjectNode jsonSchema = jsonSchemaGenerator.generateSchema(javaType);

            // Convert to OpenAPI Schema
            Schema<?> openApiSchema = convertJsonSchemaToOpenApi(jsonSchema, typeName);

            // Store in generated schemas if it's a named type
            if (openApiSchema != null && !(openApiSchema instanceof ArraySchema)) {
                generatedSchemas.put(typeName, openApiSchema);
            }

            return openApiSchema;

        } catch (Exception e) {
            throw new SchemaGenerationException("Failed to generate schema for: " + typeName, e);
        }
    }

    /**
     * Attempts to generate a polymorphic schema for types annotated with
     * {@code @JsonTypeInfo} and {@code @JsonSubTypes}.
     *
     * @param rawClass the class to inspect
     * @param typeName the schema name for this type
     * @return a composed schema with oneOf/discriminator, or null if not polymorphic
     */
    private Schema<?> tryPolymorphicSchema(Class<?> rawClass, String typeName) {
        // Look up annotations by class identity first, then by name for cross-classloader support
        JsonTypeInfo typeInfo = rawClass.getAnnotation(JsonTypeInfo.class);
        JsonSubTypes subTypes = rawClass.getAnnotation(JsonSubTypes.class);

        // If direct annotation lookup fails, try by name (cross-classloader scenario)
        if (typeInfo == null || subTypes == null) {
            return tryPolymorphicSchemaByReflection(rawClass, typeName);
        }

        if (subTypes.value().length == 0) {
            return null;
        }

        List<PolymorphicSubtype> polymorphicSubtypes = new ArrayList<>();
        for (JsonSubTypes.Type subType : subTypes.value()) {
            Class<?> subClass = subType.value();
            polymorphicSubtypes.add(new PolymorphicSubtype(
                subClass, resolveDiscriminatorValue(subType.name(), subClass)));
        }

        return buildPolymorphicSchema(typeName, typeInfo.property(), polymorphicSubtypes, false);
    }

    /**
     * Fallback polymorphic schema generation using reflection to find annotations by name.
     * This handles the cross-classloader scenario where annotation classes loaded by the
     * plugin classloader differ from those loaded by the project classloader.
     */
    private Schema<?> tryPolymorphicSchemaByReflection(Class<?> rawClass, String typeName) {
        try {
            java.lang.annotation.Annotation typeInfoAnn = null;
            java.lang.annotation.Annotation subTypesAnn = null;

            for (java.lang.annotation.Annotation ann : rawClass.getAnnotations()) {
                String annName = ann.annotationType().getName();
                if ("com.fasterxml.jackson.annotation.JsonTypeInfo".equals(annName)) {
                    typeInfoAnn = ann;
                } else if ("com.fasterxml.jackson.annotation.JsonSubTypes".equals(annName)) {
                    subTypesAnn = ann;
                }
            }

            if (typeInfoAnn == null || subTypesAnn == null) {
                return null;
            }

            // Extract property from @JsonTypeInfo
            java.lang.reflect.Method propertyMethod = typeInfoAnn.annotationType().getMethod("property");
            String discriminatorProperty = (String) propertyMethod.invoke(typeInfoAnn);

            // Extract subtypes from @JsonSubTypes
            java.lang.reflect.Method valueMethod = subTypesAnn.annotationType().getMethod("value");
            Object[] subTypeArray = (Object[]) valueMethod.invoke(subTypesAnn);

            if (subTypeArray == null || subTypeArray.length == 0) {
                return null;
            }

            logger.accept("Detected polymorphic type (via reflection): " + typeName +
                " with " + subTypeArray.length + " subtypes");

            List<PolymorphicSubtype> polymorphicSubtypes = new ArrayList<>();

            for (Object subTypeObj : subTypeArray) {
                // Each element is a @JsonSubTypes.Type annotation
                java.lang.reflect.Method subValueMethod = subTypeObj.getClass().getMethod("value");
                java.lang.reflect.Method subNameMethod = subTypeObj.getClass().getMethod("name");

                Class<?> subClass = (Class<?>) subValueMethod.invoke(subTypeObj);
                String declaredName = (String) subNameMethod.invoke(subTypeObj);
                polymorphicSubtypes.add(new PolymorphicSubtype(
                    subClass, resolveDiscriminatorValue(declaredName, subClass)));
            }

            return buildPolymorphicSchema(typeName, discriminatorProperty, polymorphicSubtypes, true);

        } catch (Exception e) {
            logger.accept("Warning: reflection-based polymorphic detection failed for " +
                typeName + ": " + e.getMessage());
            return null;
        }
    }

    private Schema<?> buildPolymorphicSchema(
            String typeName,
            String discriminatorProperty,
            List<PolymorphicSubtype> subTypes,
            boolean detectedByReflection) {
        if (discriminatorProperty == null || discriminatorProperty.isBlank() || subTypes.isEmpty()) {
            return null;
        }

        String detectionMode = detectedByReflection ? " (via reflection)" : "";
        logger.accept("Detected polymorphic type" + detectionMode + ": " + typeName
            + " with " + subTypes.size() + " subtypes");

        List<Schema> oneOfSchemas = new ArrayList<>();
        Map<String, String> discriminatorMapping = new LinkedHashMap<>();

        for (PolymorphicSubtype subType : subTypes) {
            String subTypeName = getTypeName(subType.subClass());
            Schema<?> subSchema = generatedSchemas.get(subTypeName);
            if (subSchema == null) {
                subSchema = generateSubtypeSchema(
                    subType.subClass(), subTypeName, discriminatorProperty, subType.discriminatorValue());
            } else {
                ensureDiscriminatorProperty(subSchema, discriminatorProperty, subType.discriminatorValue());
            }

            Schema<?> ref = new Schema<>();
            ref.set$ref("#/components/schemas/" + subTypeName);
            oneOfSchemas.add(ref);
            discriminatorMapping.put(
                subType.discriminatorValue(), "#/components/schemas/" + subTypeName);
        }

        ComposedSchema composedSchema = new ComposedSchema();
        composedSchema.setOneOf(oneOfSchemas);

        Discriminator discriminator = new Discriminator();
        discriminator.setPropertyName(discriminatorProperty);
        discriminator.setMapping(discriminatorMapping);
        composedSchema.setDiscriminator(discriminator);

        return composedSchema;
    }

    private Schema<?> generateSubtypeSchema(
            Class<?> subClass,
            String subTypeName,
            String discriminatorProperty,
            String discriminatorValue) {
        Schema<?> subSchema;
        try {
            ObjectNode subJsonSchema = jsonSchemaGenerator.generateSchema(subClass);
            subSchema = convertJsonSchemaToOpenApi(subJsonSchema, subTypeName);
            if (isEmptyObjectSchema(subSchema) || subSchema.get$ref() != null) {
                Schema<?> reflectionSchema = generateSchemaByReflection(subClass, subTypeName);
                if (reflectionSchema != null) {
                    subSchema = reflectionSchema;
                }
            }
        } catch (Exception e) {
            logger.accept("Warning: could not generate schema for subtype " +
                subTypeName + " via victools, trying reflection: " + e.getMessage());
            subSchema = generateSchemaByReflection(subClass, subTypeName);
        }

        if (subSchema == null) {
            subSchema = new ObjectSchema();
        }
        ensureDiscriminatorProperty(subSchema, discriminatorProperty, discriminatorValue);
        generatedSchemas.put(subTypeName, subSchema);
        return subSchema;
    }

    private boolean isEmptyObjectSchema(Schema<?> schema) {
        return schema instanceof ObjectSchema
            && (schema.getProperties() == null || schema.getProperties().isEmpty());
    }

    private void ensureDiscriminatorProperty(
            Schema<?> schema, String discriminatorProperty, String discriminatorValue) {
        if (schema == null || schema.get$ref() != null
                || discriminatorProperty == null || discriminatorProperty.isBlank()) {
            return;
        }

        Schema<?> discriminatorSchema = schema.getProperties() != null
            ? schema.getProperties().get(discriminatorProperty)
            : null;
        if (discriminatorSchema == null) {
            discriminatorSchema = new StringSchema();
            schema.addProperty(discriminatorProperty, discriminatorSchema);
        }

        setSingleAllowedValue(discriminatorSchema, discriminatorValue);
        List<String> required = schema.getRequired() != null
            ? new ArrayList<>(schema.getRequired())
            : new ArrayList<>();
        if (!required.contains(discriminatorProperty)) {
            required.add(discriminatorProperty);
            schema.setRequired(required);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void setSingleAllowedValue(Schema<?> schema, String value) {
        schema.setConst(value);
        ((Schema) schema).setEnum(Collections.singletonList(value));
    }

    private String resolveDiscriminatorValue(String declaredName, Class<?> subClass) {
        return declaredName != null && !declaredName.isBlank()
            ? declaredName
            : getTypeName(subClass);
    }

    private record PolymorphicSubtype(Class<?> subClass, String discriminatorValue) {
    }

    /**
     * Generates a schema for a class using pure Java reflection.
     *
     * <p>This is used as a fallback for types that victools cannot introspect
     * correctly (e.g., records implementing sealed interfaces loaded via a
     * different classloader). It inspects record components first, then
     * falls back to public getter methods.</p>
     *
     * @param clazz the class to inspect
     * @param typeName the schema name to use
     * @return an ObjectSchema with the discovered properties, or null if none found
     */
    private Schema<?> generateSchemaByReflection(Class<?> clazz, String typeName) {
        try {
            logger.accept("Generating schema via reflection for: " + typeName);
            ObjectSchema objectSchema = new ObjectSchema();

            // Java records expose components via Class.getRecordComponents()
            java.lang.reflect.RecordComponent[] components = clazz.getRecordComponents();
            if (components != null && components.length > 0) {
                for (java.lang.reflect.RecordComponent component : components) {
                    java.lang.reflect.Method accessor = component.getAccessor();
                    if (hasJsonIgnore(component, accessor)) {
                        continue;
                    }
                    String propName = getJsonPropertyName(component.getName(), component, accessor);
                    Class<?> propType = component.getType();
                    Schema<?> propSchema = mapJavaTypeToSchema(propType, component.getGenericType());
                    if (propSchema != null) {
                        objectSchema.addProperty(propName, propSchema);
                    }
                }
                return objectSchema;
            }

            // For non-records, inspect public getter methods
            for (java.lang.reflect.Method method : clazz.getMethods()) {
                String methodName = method.getName();
                if (method.getParameterCount() != 0 || method.getDeclaringClass() == Object.class) {
                    continue;
                }
                String propName = null;
                if (methodName.startsWith("get") && methodName.length() > 3) {
                    propName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
                } else if (methodName.startsWith("is") && methodName.length() > 2) {
                    propName = Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
                }
                if (propName != null && !propName.isBlank()) {
                    if (hasJsonIgnore(method)) {
                        continue;
                    }
                    propName = getJsonPropertyName(propName, method);
                    Schema<?> propSchema = mapJavaTypeToSchema(
                        method.getReturnType(), method.getGenericReturnType());
                    if (propSchema != null) {
                        objectSchema.addProperty(propName, propSchema);
                    }
                }
            }

            return objectSchema.getProperties() != null && !objectSchema.getProperties().isEmpty()
                ? objectSchema : null;

        } catch (Exception e) {
            logger.accept("Warning: reflection-based schema generation failed for " +
                typeName + ": " + e.getMessage());
            return null;
        }
    }

    private boolean hasJsonIgnore(AnnotatedElement... elements) {
        for (AnnotatedElement element : elements) {
            if (element != null && element.getAnnotation(JsonIgnore.class) != null) {
                return true;
            }
        }
        return false;
    }

    private String getJsonPropertyName(String defaultName, AnnotatedElement... elements) {
        for (AnnotatedElement element : elements) {
            if (element == null) {
                continue;
            }
            JsonProperty jsonProperty = element.getAnnotation(JsonProperty.class);
            if (jsonProperty != null && jsonProperty.value() != null && !jsonProperty.value().isBlank()) {
                return jsonProperty.value();
            }
        }
        return defaultName;
    }

    /**
     * Maps a Java type to an OpenAPI schema using simple type matching.
     * Used by the reflection-based schema generator.
     */
    private Schema<?> mapJavaTypeToSchema(Class<?> type, java.lang.reflect.Type genericType) {
        // Unwrap Optional<T>
        if (Optional.class.isAssignableFrom(type) && genericType instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.Type inner =
                ((java.lang.reflect.ParameterizedType) genericType).getActualTypeArguments()[0];
            if (inner instanceof Class<?>) {
                return mapJavaTypeToSchema((Class<?>) inner, inner);
            }
        }

        // Simple types
        Schema<?> simple = trySimpleTypeSchema(type);
        if (simple != null) {
            return simple;
        }

        // Collections: List, Set
        if (List.class.isAssignableFrom(type) || Set.class.isAssignableFrom(type)) {
            ArraySchema array = new ArraySchema();
            if (genericType instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.Type inner =
                    ((java.lang.reflect.ParameterizedType) genericType).getActualTypeArguments()[0];
                if (inner instanceof Class<?>) {
                    array.setItems(mapJavaTypeToSchema((Class<?>) inner, inner));
                }
            }
            return array;
        }

        // Enum
        if (type.isEnum()) {
            StringSchema enumSchema = new StringSchema();
            for (Object constant : type.getEnumConstants()) {
                enumSchema.addEnumItem(constant.toString());
            }
            return enumSchema;
        }

        // Complex object - use $ref if already generated, otherwise ObjectSchema placeholder
        String refTypeName = type.getSimpleName();
        if (generatedSchemas.containsKey(refTypeName)) {
            return createReference(refTypeName);
        }
        return new ObjectSchema();
    }

    private Schema<?> convertJsonSchemaToOpenApi(ObjectNode jsonSchema, String rootTypeName) {
        // Extract definitions first (they may be under $defs in draft 2020-12)
        JsonNode defs = getDefinitions(jsonSchema);
        Set<String> rootAliases = new HashSet<>();
        JsonNode rootSchema = resolveRootSchemaNode(jsonSchema, defs, rootAliases);
        rootAliases.forEach(alias -> schemaNameAliases.put(alias, rootTypeName));
        registerExistingSchemaAliases(defs);

        if (defs != null && defs.isObject()) {
            defs.fields().forEachRemaining(entry -> {
                String defName = sanitizeSchemaName(entry.getKey());
                if (!rootAliases.contains(defName)
                        && !schemaNameAliases.containsKey(defName)
                        && !generatedSchemas.containsKey(defName)) {
                    Schema<?> defSchema = convertJsonNodeToSchema(entry.getValue(), defName);
                    if (defSchema != null) {
                        generatedSchemas.put(defName, defSchema);
                    }
                }
            });
        }

        // Convert the main schema
        return convertJsonNodeToSchema(rootSchema, rootTypeName);
    }

    private JsonNode getDefinitions(JsonNode jsonSchema) {
        JsonNode defs = jsonSchema.get("$defs");
        return defs != null ? defs : jsonSchema.get("definitions");
    }

    private void registerExistingSchemaAliases(JsonNode defs) {
        if (defs == null || !defs.isObject()) {
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = defs.fields();
        while (fields.hasNext()) {
            String defName = sanitizeSchemaName(fields.next().getKey());
            String existingSchemaName = findExistingSchemaName(defName);
            if (existingSchemaName != null && !existingSchemaName.equals(defName)) {
                schemaNameAliases.put(defName, existingSchemaName);
            }
        }
    }

    private String findExistingSchemaName(String schemaName) {
        String alias = schemaNameAliases.get(schemaName);
        if (alias != null || generatedSchemas.containsKey(schemaName)) {
            return alias != null ? alias : schemaName;
        }

        String baseName = stripNumericSuffix(schemaName);
        return baseName != null && generatedSchemas.containsKey(baseName) ? baseName : null;
    }

    private String stripNumericSuffix(String schemaName) {
        int dashIndex = schemaName.lastIndexOf('-');
        if (dashIndex <= 0 || dashIndex == schemaName.length() - 1) {
            return null;
        }
        for (int i = dashIndex + 1; i < schemaName.length(); i++) {
            if (!Character.isDigit(schemaName.charAt(i))) {
                return null;
            }
        }
        return schemaName.substring(0, dashIndex);
    }

    private JsonNode resolveRootSchemaNode(JsonNode jsonSchema, JsonNode defs, Set<String> rootAliases) {
        JsonNode current = jsonSchema;
        Set<String> visitedRefs = new HashSet<>();

        while (current != null && current.has("$ref")) {
            String refName = extractRawRefName(current.get("$ref").asText());
            if (refName == null || !visitedRefs.add(refName)) {
                break;
            }

            JsonNode referenced = findDefinition(defs, refName);
            if (referenced == null) {
                break;
            }

            rootAliases.add(sanitizeSchemaName(refName));
            current = referenced;
        }

        return current != null ? current : jsonSchema;
    }

    private JsonNode findDefinition(JsonNode defs, String refName) {
        if (defs == null || !defs.isObject() || refName == null) {
            return null;
        }
        if (defs.has(refName)) {
            return defs.get(refName);
        }

        String sanitizedRefName = sanitizeSchemaName(refName);
        Iterator<Map.Entry<String, JsonNode>> fields = defs.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (sanitizedRefName.equals(sanitizeSchemaName(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String extractRawRefName(String ref) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }
        int lastSlash = ref.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < ref.length() - 1) {
            return ref.substring(lastSlash + 1);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Schema<?> convertJsonNodeToSchema(JsonNode node, String currentTypeName) {
        if (node == null || node.isNull()) {
            return new ObjectSchema();
        }

        // Handle $ref
        if (node.has("$ref")) {
            String ref = node.get("$ref").asText();
            // Extract the type name from the reference
            String refName = extractRefName(ref, currentTypeName);
            if (refName != null && !refName.isEmpty()) {
                return createReference(refName);
            }
            // If we can't extract a proper ref name, return an object schema
            return new ObjectSchema();
        }

        String type = node.has("type") ? node.get("type").asText() : null;

        // Handle arrays with multiple types (JSON Schema 2020-12 style)
        if (node.has("type") && node.get("type").isArray()) {
            ArrayNode typeArray = (ArrayNode) node.get("type");
            // Find the non-null type
            for (JsonNode typeNode : typeArray) {
                String t = typeNode.asText();
                if (!"null".equals(t)) {
                    type = t;
                    break;
                }
            }
        }

        Schema<?> schema;

        if ("array".equals(type)) {
            ArraySchema arraySchema = new ArraySchema();
            if (node.has("items")) {
                arraySchema.setItems(convertJsonNodeToSchema(node.get("items"), currentTypeName));
            }
            schema = arraySchema;
        } else if ("object".equals(type) || (type == null && node.has("properties"))) {
            ObjectSchema objectSchema = new ObjectSchema();

            if (node.has("properties")) {
                JsonNode properties = node.get("properties");
                final String typeName = currentTypeName;
                properties.fields().forEachRemaining(entry -> {
                    Schema<?> propSchema = convertJsonNodeToSchema(entry.getValue(), typeName);
                    objectSchema.addProperty(entry.getKey(), propSchema);
                });
            }

            if (node.has("required") && node.get("required").isArray()) {
                List<String> required = new ArrayList<>();
                node.get("required").forEach(n -> required.add(n.asText()));
                objectSchema.setRequired(required);
            }

            if (node.has("additionalProperties")) {
                JsonNode addProps = node.get("additionalProperties");
                if (addProps.isBoolean()) {
                    // For Map types, allow additional properties
                    if (addProps.asBoolean()) {
                        objectSchema.setAdditionalProperties(new ObjectSchema());
                    }
                } else {
                    objectSchema.setAdditionalProperties(convertJsonNodeToSchema(addProps, currentTypeName));
                }
            }

            schema = objectSchema;
        } else if ("string".equals(type)) {
            StringSchema stringSchema = new StringSchema();
            if (node.has("format")) {
                stringSchema.setFormat(node.get("format").asText());
            }
            if (node.has("pattern")) {
                stringSchema.setPattern(node.get("pattern").asText());
            }
            if (node.has("minLength")) {
                stringSchema.setMinLength(node.get("minLength").asInt());
            }
            if (node.has("maxLength")) {
                stringSchema.setMaxLength(node.get("maxLength").asInt());
            }
            if (node.has("enum")) {
                List<String> enumValues = new ArrayList<>();
                node.get("enum").forEach(n -> enumValues.add(n.asText()));
                stringSchema.setEnum(enumValues);
            }
            schema = stringSchema;
        } else if ("integer".equals(type)) {
            IntegerSchema integerSchema = new IntegerSchema();
            if (node.has("format")) {
                integerSchema.setFormat(node.get("format").asText());
            }
            if (node.has("minimum")) {
                integerSchema.setMinimum(BigDecimal.valueOf(node.get("minimum").asLong()));
            }
            if (node.has("maximum")) {
                integerSchema.setMaximum(BigDecimal.valueOf(node.get("maximum").asLong()));
            }
            schema = integerSchema;
        } else if ("number".equals(type)) {
            NumberSchema numberSchema = new NumberSchema();
            if (node.has("format")) {
                numberSchema.setFormat(node.get("format").asText());
            }
            if (node.has("minimum")) {
                numberSchema.setMinimum(BigDecimal.valueOf(node.get("minimum").asDouble()));
            }
            if (node.has("maximum")) {
                numberSchema.setMaximum(BigDecimal.valueOf(node.get("maximum").asDouble()));
            }
            schema = numberSchema;
        } else if ("boolean".equals(type)) {
            schema = new BooleanSchema();
        } else {
            // Default to object schema
            schema = new ObjectSchema();
        }

        // Set common properties
        if (node.has("description")) {
            schema.setDescription(node.get("description").asText());
        }
        if (node.has("title")) {
            schema.setTitle(node.get("title").asText());
        }
        if (node.has("default")) {
            schema.setDefault(convertJsonNodeToValue(node.get("default")));
        }
        if (node.has("example")) {
            schema.setExample(convertJsonNodeToValue(node.get("example")));
        }
        if (node.has("nullable") && node.get("nullable").asBoolean()) {
            schema.setNullable(true);
        }

        return schema;
    }

    private Object convertJsonNodeToValue(JsonNode node) {
        if (node.isNull()) {
            return null;
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isInt()) {
            return node.asInt();
        } else if (node.isLong()) {
            return node.asLong();
        } else if (node.isDouble()) {
            return node.asDouble();
        } else if (node.isTextual()) {
            return node.asText();
        } else if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(n -> list.add(convertJsonNodeToValue(n)));
            return list;
        } else if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry ->
                map.put(entry.getKey(), convertJsonNodeToValue(entry.getValue())));
            return map;
        }
        return node.toString();
    }

    private String extractRefName(String ref, String currentTypeName) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }

        String refName = null;

        // Handle JSON Schema 2020-12 $defs
        if (ref.startsWith("#/$defs/")) {
            refName = ref.substring("#/$defs/".length());
        }
        // Handle older JSON Schema definitions
        else if (ref.startsWith("#/definitions/")) {
            refName = ref.substring("#/definitions/".length());
        }
        // Handle existing OpenAPI component refs
        else if (ref.startsWith("#/components/schemas/")) {
            refName = ref.substring("#/components/schemas/".length());
        }
        // If it's just a pointer to root (#), use the current type name (circular reference)
        else if ("#".equals(ref)) {
            logger.accept("Circular reference detected for: " + currentTypeName);
            return sanitizeSchemaName(currentTypeName);
        }
        // Otherwise, try to extract the last segment
        else {
            int lastSlash = ref.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < ref.length() - 1) {
                refName = ref.substring(lastSlash + 1);
            }
        }

        return refName != null ? canonicalSchemaName(refName) : null;
    }

    private String canonicalSchemaName(String name) {
        String sanitizedName = sanitizeSchemaName(name);
        return schemaNameAliases.getOrDefault(sanitizedName, sanitizedName);
    }

    /**
     * Sanitizes a schema name to ensure it's valid for OpenAPI.
     * Replaces invalid characters with underscores.
     *
     * @param name the raw schema name
     * @return the sanitized schema name
     */
    private String sanitizeSchemaName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        // Replace characters that are not valid in OpenAPI schema names
        // Valid characters: letters, digits, underscores, hyphens
        // Common problematic characters: parentheses, commas, spaces, angle brackets
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private Schema<?> trySimpleTypeSchema(Class<?> type) {
        // Primitives and wrappers
        if (type == String.class || type == CharSequence.class) {
            return new StringSchema();
        } else if (type == int.class || type == Integer.class) {
            return new IntegerSchema().format("int32");
        } else if (type == long.class || type == Long.class) {
            return new IntegerSchema().format("int64");
        } else if (type == short.class || type == Short.class) {
            return new IntegerSchema().format("int32");
        } else if (type == byte.class || type == Byte.class) {
            return new IntegerSchema().format("int32");
        } else if (type == float.class || type == Float.class) {
            return new NumberSchema().format("float");
        } else if (type == double.class || type == Double.class) {
            return new NumberSchema().format("double");
        } else if (type == boolean.class || type == Boolean.class) {
            return new BooleanSchema();
        } else if (type == char.class || type == Character.class) {
            return new StringSchema().minLength(1).maxLength(1);
        } else if (type == BigDecimal.class) {
            return new NumberSchema();
        } else if (type == BigInteger.class) {
            return new IntegerSchema();
        } else if (type == UUID.class) {
            return new UUIDSchema();
        } else if (type == LocalDate.class) {
            return new DateSchema();
        } else if (type == LocalDateTime.class || type == ZonedDateTime.class ||
                   type == OffsetDateTime.class || type == Instant.class) {
            return new DateTimeSchema();
        } else if (type == LocalTime.class || type == OffsetTime.class) {
            return new StringSchema().format("time");
        } else if (type == Duration.class || type == Period.class) {
            return new StringSchema().format("duration");
        } else if (type == byte[].class) {
            return new BinarySchema();
        } else if (type == Object.class) {
            return new ObjectSchema();
        } else if (type == Void.class || type == void.class) {
            return null;
        }

        return null;
    }

    private Schema<?> createReference(String typeName) {
        Schema<?> schema = new Schema<>();
        schema.set$ref("#/components/schemas/" + typeName);
        return schema;
    }

    private Class<?> getRawClass(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        } else if (type instanceof java.lang.reflect.ParameterizedType) {
            Type rawType = ((java.lang.reflect.ParameterizedType) type).getRawType();
            if (rawType instanceof Class<?>) {
                return (Class<?>) rawType;
            }
        }
        return null;
    }

    private String getTypeName(Class<?> type) {
        return type.getSimpleName();
    }

    /**
     * Returns all schemas generated so far.
     *
     * <p>These schemas should be added to the OpenAPI components/schemas section.</p>
     *
     * @return map of schema names to schemas
     */
    public Map<String, Schema<?>> getGeneratedSchemas() {
        return Collections.unmodifiableMap(generatedSchemas);
    }

    /**
     * Clears all generated schemas.
     */
    public void clearSchemas() {
        generatedSchemas.clear();
        schemaNameAliases.clear();
    }

    /**
     * Checks if a schema has already been generated for the given type.
     *
     * @param typeName the type name to check
     * @return true if a schema exists for the type
     */
    public boolean hasSchema(String typeName) {
        return generatedSchemas.containsKey(typeName);
    }

    /**
     * Exception thrown when schema generation fails.
     */
    public static class SchemaGenerationException extends RuntimeException {
        public SchemaGenerationException(String message) {
            super(message);
        }

        public SchemaGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
