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
    private final Map<String, String> subtypeToParentMap;
    private final Consumer<String> logger;
    private final Set<String> processingTypes;
    /** Registry mapping simple class name → Class<?> for enrichRequiredFields post-processing on defs. */
    private final Map<String, Class<?>> classRegistry;

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
        this.subtypeToParentMap = new ConcurrentHashMap<>();
        this.processingTypes = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.classRegistry = new ConcurrentHashMap<>();
        this.jsonSchemaGenerator = createJsonSchemaGenerator();
    }

    /**
     * Recursively registers a class and all types reachable from it into the classRegistry:
     * - Declared nested/inner classes
     * - Types of record components
     * - Types of declared fields
     * - Generic type arguments (e.g. List&lt;Foo&gt; → Foo)
     *
     * This ensures that any type victools may emit as a $def can be found later
     * for {@link #enrichRequiredFields} post-processing, regardless of the nesting depth.
     */
    private void registerClassHierarchy(Class<?> clazz) {
        registerClassHierarchy(clazz, new HashSet<>());
    }

    private void registerClassHierarchy(Class<?> clazz, Set<Class<?>> visited) {
        if (clazz == null || visited.contains(clazz)) return;
        if (clazz.isPrimitive() || clazz.isArray() || clazz.isEnum()
                || clazz.getPackageName().startsWith("java.")
                || clazz.getPackageName().startsWith("javax.")
                || clazz.getPackageName().startsWith("scala.")
                || clazz.getPackageName().startsWith("akka.")) {
            return;
        }
        visited.add(clazz);
        classRegistry.putIfAbsent(clazz.getSimpleName(), clazz);

        for (Class<?> nested : clazz.getDeclaredClasses()) {
            registerClassHierarchy(nested, visited);
        }

        JsonSubTypes jsonSubTypes = clazz.getAnnotation(JsonSubTypes.class);
        if (jsonSubTypes != null) {
            for (JsonSubTypes.Type subType : jsonSubTypes.value()) {
                registerClassHierarchy(subType.value(), visited);
            }
        }

        if (clazz.isSealed()) {
            for (Class<?> permitted : clazz.getPermittedSubclasses()) {
                registerClassHierarchy(permitted, visited);
            }
        }

        java.lang.reflect.RecordComponent[] components = clazz.getRecordComponents();
        if (components != null) {
            for (java.lang.reflect.RecordComponent component : components) {
                registerTypeAndArguments(component.getGenericType(), visited);
            }
        }

        for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                registerTypeAndArguments(field.getGenericType(), visited);
            }
        }

        for (Class<?> iface : clazz.getInterfaces()) {
            registerClassHierarchy(iface, visited);
        }
    }

    /**
     * Resolves a generic type to its raw class and any type arguments, registering each.
     */
    private void registerTypeAndArguments(java.lang.reflect.Type type, Set<Class<?>> visited) {
        if (type instanceof Class<?> raw) {
            registerClassHierarchy(raw, visited);
        } else if (type instanceof java.lang.reflect.ParameterizedType pt) {
            registerTypeAndArguments(pt.getRawType(), visited);
            for (java.lang.reflect.Type arg : pt.getActualTypeArguments()) {
                registerTypeAndArguments(arg, visited);
            }
        } else if (type instanceof java.lang.reflect.WildcardType wt) {
            for (java.lang.reflect.Type bound : wt.getUpperBounds()) {
                registerTypeAndArguments(bound, visited);
            }
        }
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

        // Unwrap Optional<T> fields and method return types to their inner type T.
        // Without this, victools treats Optional as an opaque object.
        configBuilder.forFields()
            .withTargetTypeOverridesResolver(field -> {
                com.github.victools.jsonschema.generator.TypeContext ctx =
                    field.getContext();
                com.fasterxml.classmate.ResolvedType resolvedType = field.getType();
                if (Optional.class.isAssignableFrom(resolvedType.getErasedType())
                    && resolvedType.getTypeParameters().size() == 1) {
                    return List.of(ctx.resolve(resolvedType.getTypeParameters().get(0)));
                }
                return null;
            });
        configBuilder.forMethods()
            .withTargetTypeOverridesResolver(method -> {
                com.github.victools.jsonschema.generator.TypeContext ctx =
                    method.getContext();
                com.fasterxml.classmate.ResolvedType resolvedType = method.getType();
                if (Optional.class.isAssignableFrom(resolvedType.getErasedType())
                    && resolvedType.getTypeParameters().size() == 1) {
                    return List.of(ctx.resolve(resolvedType.getTypeParameters().get(0)));
                }
                return null;
            });

        // Handle @JsonValue: types with a single @JsonValue-annotated member should
        // be represented as the type of that member (e.g., record SKU(@JsonValue String value) -> string)
        configBuilder.forTypesInGeneral()
            .withCustomDefinitionProvider((javaType, context) -> {
                Class<?> erasedType = javaType.getErasedType();
                if (erasedType.isEnum()) {
                    return null;
                }
                Type valueType = findJsonValueType(erasedType);
                if (valueType != null) {
                    ObjectNode schema = createJsonValueDefinition(valueType, context);
                    return new CustomDefinition(schema);
                }
                return null;
            });

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

        // Suppress framework-internal Akka/Scala types — treat as opaque object
        String earlyTypeName = getTypeName(rawClass);
        if (isInternalSchemaName(earlyTypeName)) {
            logger.accept("Suppressing internal framework type: " + earlyTypeName);
            return new ObjectSchema();
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

        // For parameterized non-container types (e.g. PagedResponse<Customer>),
        // generate a specialized schema with a unique name incorporating the type argument.
        String typeName;
        if (javaType instanceof java.lang.reflect.ParameterizedType parameterizedType
                && !Collection.class.isAssignableFrom(rawClass)
                && !Map.class.isAssignableFrom(rawClass)) {
            typeName = getSpecializedTypeName(rawClass, parameterizedType);
        } else {
            typeName = getTypeName(rawClass);
        }

        // Also check the full specialized type name (e.g. Source_Object_NotUsed_)
        if (isInternalSchemaName(typeName)) {
            logger.accept("Suppressing internal framework type: " + typeName);
            return new ObjectSchema();
        }

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
        if (Optional.class.isAssignableFrom(rawClass)) {
            Type innerType = Object.class;
            if (javaType instanceof java.lang.reflect.ParameterizedType parameterizedType
                    && parameterizedType.getActualTypeArguments().length > 0) {
                innerType = parameterizedType.getActualTypeArguments()[0];
            }
            return resolveNestedSchema(innerType);
        }

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

        // Handle Map<K, V> types as object with additionalProperties
        if (Map.class.isAssignableFrom(rawClass)) {
            ObjectSchema mapSchema = new ObjectSchema();
            if (javaType instanceof java.lang.reflect.ParameterizedType parameterizedType
                    && parameterizedType.getActualTypeArguments().length == 2) {
                Type valueType = parameterizedType.getActualTypeArguments()[1];
                Schema<?> valueSchema = resolveNestedSchema(valueType);
                mapSchema.setAdditionalProperties(valueSchema);
            } else {
                mapSchema.setAdditionalProperties(new ObjectSchema());
            }
            return mapSchema;
        }

        return null;
    }

    /**
     * Pre-scans a class's fields/record components for polymorphic types and Map value types,
     * generating their schemas first so they are available during victools processing.
     */
    private void preGeneratePolymorphicFields(Class<?> rawClass) {
        // Check record components
        java.lang.reflect.RecordComponent[] components = rawClass.getRecordComponents();
        if (components != null) {
            for (java.lang.reflect.RecordComponent component : components) {
                preGenerateFieldType(component.getType(), component.getGenericType());
            }
        }
        // Check declared fields (for non-record classes)
        for (java.lang.reflect.Field field : rawClass.getDeclaredFields()) {
            preGenerateFieldType(field.getType(), field.getGenericType());
        }
    }

    private void preGenerateFieldType(Class<?> fieldType, java.lang.reflect.Type genericType) {
        // Pre-generate polymorphic types
        if (isPolymorphicType(fieldType) && !generatedSchemas.containsKey(getTypeName(fieldType))) {
            generateSchema(fieldType);
            return;
        }
        // Pre-generate Map value types (so they exist when we inline Map refs)
        if (Map.class.isAssignableFrom(fieldType)
                && genericType instanceof java.lang.reflect.ParameterizedType pt
                && pt.getActualTypeArguments().length == 2) {
            java.lang.reflect.Type valueType = pt.getActualTypeArguments()[1];
            Class<?> valueClass = getRawClass(valueType);
            if (valueClass != null && trySimpleTypeSchema(valueClass) == null
                    && !generatedSchemas.containsKey(getTypeName(valueClass))) {
                generateSchema(valueType);
            }
        }
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

            // Register class and all its nested types so defs post-processing can look them up.
            registerClassHierarchy(rawClass);

            // Pre-scan fields for polymorphic types and generate those first,
            // so that subtypeToParentMap is populated before victools processes this type.
            preGeneratePolymorphicFields(rawClass);

            // Generate JSON Schema using victools
            ObjectNode jsonSchema = jsonSchemaGenerator.generateSchema(javaType);

            // Convert to OpenAPI Schema
            Schema<?> openApiSchema = convertJsonSchemaToOpenApi(jsonSchema, typeName);

            // Post-process: add required fields based on Optional vs non-Optional
            if (openApiSchema instanceof ObjectSchema) {
                enrichRequiredFields(rawClass, (ObjectSchema) openApiSchema);
            }

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
     * Checks if a type is a polymorphic type annotated with @JsonTypeInfo (or equivalent by name).
     */
    private boolean isPolymorphicType(Class<?> type) {
        for (java.lang.annotation.Annotation ann : type.getAnnotations()) {
            String annName = ann.annotationType().getName();
            if ("com.fasterxml.jackson.annotation.JsonTypeInfo".equals(annName)) {
                return true;
            }
        }
        return false;
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
            // Register subtype→parent mapping for ref resolution
            subtypeToParentMap.put(subTypeName, typeName);
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
            && (schema.getProperties() == null || schema.getProperties().isEmpty())
            && ((ObjectSchema) schema).getAdditionalProperties() == null;
    }

    /**
     * Extracts the value type name from a Map def name like "Map_String_ComponentHealth_".
     * Returns the last type segment (e.g. "ComponentHealth"), or null if unparseable.
     */
    private String extractMapValueType(String defName) {
        // Pattern: Map_KeyType_ValueType_ (underscores replacing angle brackets/commas)
        if (!defName.startsWith("Map_")) {
            return null;
        }
        String rest = defName.substring("Map_".length());
        // Remove trailing underscore
        if (rest.endsWith("_")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        // Split by underscore — first segment is key type, rest is value type
        int firstUnderscore = rest.indexOf('_');
        if (firstUnderscore < 0) {
            return null;
        }
        String valueType = rest.substring(firstUnderscore + 1);
        // Remove trailing underscore if present
        if (valueType.endsWith("_")) {
            valueType = valueType.substring(0, valueType.length() - 1);
        }
        return valueType.isEmpty() ? null : valueType;
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

            // Check for @JsonValue annotation — the type serializes as the value type directly
            Schema<?> jsonValueSchema = tryJsonValueSchema(clazz);
            if (jsonValueSchema != null) {
                return jsonValueSchema;
            }

            ObjectSchema objectSchema = new ObjectSchema();
            List<String> requiredFields = new ArrayList<>();

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
                    // Non-Optional fields are required
                    if (!Optional.class.isAssignableFrom(propType) && !isNullableAnnotated(component, accessor)) {
                        requiredFields.add(propName);
                    }
                }
                if (!requiredFields.isEmpty()) {
                    objectSchema.setRequired(requiredFields);
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
                    Class<?> returnType = method.getReturnType();
                    Schema<?> propSchema = mapJavaTypeToSchema(returnType, method.getGenericReturnType());
                    if (propSchema != null) {
                        objectSchema.addProperty(propName, propSchema);
                    }
                    // Non-Optional return types are required
                    if (!Optional.class.isAssignableFrom(returnType)
                            && !isNullableAnnotated(method)
                            && !returnType.isPrimitive()) {
                        requiredFields.add(propName);
                    }
                }
            }

            if (!requiredFields.isEmpty()) {
                objectSchema.setRequired(requiredFields);
            }
            return objectSchema.getProperties() != null && !objectSchema.getProperties().isEmpty()
                ? objectSchema : null;

        } catch (Exception e) {
            logger.accept("Warning: reflection-based schema generation failed for " +
                typeName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Checks if the class has a {@code @JsonValue} annotation on any field, method, or
     * record component, and if so returns the schema for the value type directly.
     * This handles value-wrapper types that serialize as their inner value.
     */
    private Schema<?> tryJsonValueSchema(Class<?> clazz) {
        if (clazz.isEnum()) {
            return null;
        }

        // Check record components for @JsonValue
        java.lang.reflect.RecordComponent[] components = clazz.getRecordComponents();
        if (components != null) {
            for (java.lang.reflect.RecordComponent component : components) {
                if (hasJsonValueAnnotation(component.getAccessor()) ||
                    hasJsonValueAnnotation(component)) {
                    return mapJavaTypeToSchema(component.getType(), component.getGenericType());
                }
            }
        }

        // Check methods for @JsonValue
        for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && hasJsonValueAnnotation(method)) {
                return mapJavaTypeToSchema(method.getReturnType(), method.getGenericReturnType());
            }
        }

        // Check fields for @JsonValue
        for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
            if (hasJsonValueAnnotation(field)) {
                return mapJavaTypeToSchema(field.getType(), field.getGenericType());
            }
        }

        return null;
    }

    /**
     * Checks if an annotated element has a {@code @JsonValue} annotation,
     * handling cross-classloader scenarios by checking annotation type name.
     */
    private boolean hasJsonValueAnnotation(java.lang.reflect.AnnotatedElement element) {
        for (java.lang.annotation.Annotation ann : element.getAnnotations()) {
            String annName = ann.annotationType().getName();
            if ("com.fasterxml.jackson.annotation.JsonValue".equals(annName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the type of the @JsonValue-annotated member in a class, or null if not found.
     */
    private Type findJsonValueType(Class<?> clazz) {
        // Check record components
        java.lang.reflect.RecordComponent[] components = clazz.getRecordComponents();
        if (components != null) {
            for (java.lang.reflect.RecordComponent component : components) {
                if (hasJsonValueAnnotation(component.getAccessor()) ||
                    hasJsonValueAnnotation(component)) {
                    return component.getGenericType();
                }
            }
        }
        // Check methods
        for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && hasJsonValueAnnotation(method)) {
                return method.getGenericReturnType();
            }
        }
        // Check fields
        for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
            if (hasJsonValueAnnotation(field)) {
                return field.getGenericType();
            }
        }
        return null;
    }

    private ObjectNode createJsonValueDefinition(Type valueType, SchemaGenerationContext context) {
        Class<?> rawClass = getRawClass(valueType);
        ObjectNode simpleSchema = rawClass != null ? createSimpleJsonSchema(rawClass) : null;
        if (simpleSchema != null) {
            return simpleSchema;
        }
        return context.createDefinition(context.getTypeContext().resolve(valueType));
    }

    private ObjectNode createSimpleJsonSchema(Class<?> type) {
        ObjectNode schema = objectMapper.createObjectNode();
        if (type == String.class || type == CharSequence.class) {
            schema.put("type", "string");
        } else if (type == char.class || type == Character.class) {
            schema.put("type", "string");
            schema.put("minLength", 1);
            schema.put("maxLength", 1);
        } else if (type == int.class || type == Integer.class ||
                   type == short.class || type == Short.class ||
                   type == byte.class || type == Byte.class) {
            schema.put("type", "integer");
            schema.put("format", "int32");
        } else if (type == long.class || type == Long.class) {
            schema.put("type", "integer");
            schema.put("format", "int64");
        } else if (type == BigInteger.class) {
            schema.put("type", "integer");
        } else if (type == float.class || type == Float.class) {
            schema.put("type", "number");
            schema.put("format", "float");
        } else if (type == double.class || type == Double.class) {
            schema.put("type", "number");
            schema.put("format", "double");
        } else if (type == BigDecimal.class) {
            schema.put("type", "number");
        } else if (type == boolean.class || type == Boolean.class) {
            schema.put("type", "boolean");
        } else if (type == UUID.class) {
            schema.put("type", "string");
            schema.put("format", "uuid");
        } else if (type == LocalDate.class) {
            schema.put("type", "string");
            schema.put("format", "date");
        } else if (type == LocalDateTime.class || type == ZonedDateTime.class ||
                   type == OffsetDateTime.class || type == Instant.class) {
            schema.put("type", "string");
            schema.put("format", "date-time");
        } else if (type == LocalTime.class || type == OffsetTime.class) {
            schema.put("type", "string");
            schema.put("format", "time");
        } else if (type == Duration.class || type == Period.class) {
            schema.put("type", "string");
            schema.put("format", "duration");
        } else if (type == byte[].class) {
            schema.put("type", "string");
            schema.put("format", "binary");
        } else if (type == Object.class) {
            schema.put("type", "object");
        } else {
            return null;
        }
        return schema;
    }

    /**
     * Post-processes a victools-generated ObjectSchema to add required fields
     * based on Optional vs non-Optional type declarations in the source class.
     * This supplements what victools detects via @NotNull/@NotBlank.
     */
    private void enrichRequiredFields(Class<?> rawClass, ObjectSchema schema) {
        if (schema.getProperties() == null) return;
        Set<String> existingRequired = schema.getRequired() != null
                ? new HashSet<>(schema.getRequired()) : new HashSet<>();
        List<String> toAdd = new ArrayList<>();

        // Check record components
        java.lang.reflect.RecordComponent[] components = rawClass.getRecordComponents();
        if (components != null) {
            for (java.lang.reflect.RecordComponent component : components) {
                String propName = getJsonPropertyName(component.getName(), component, component.getAccessor());
                if (schema.getProperties().containsKey(propName)
                        && !Optional.class.isAssignableFrom(component.getType())
                        && !existingRequired.contains(propName)
                        && !isNullableAnnotated(component, component.getAccessor())) {
                    toAdd.add(propName);
                }
            }
        } else {
            // Check declared fields for non-record classes
            for (java.lang.reflect.Field field : rawClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                String propName = getJsonPropertyName(field.getName(), field);
                if (schema.getProperties().containsKey(propName)
                        && !Optional.class.isAssignableFrom(field.getType())
                        && !existingRequired.contains(propName)
                        && !isNullableAnnotated(field)) {
                    toAdd.add(propName);
                }
            }
        }

        if (!toAdd.isEmpty()) {
            List<String> required = new ArrayList<>(existingRequired);
            required.addAll(toAdd);
            schema.setRequired(required);
        }
    }

    /**
     * Final pass: for every ObjectSchema stored in generatedSchemas, if its name maps to a known
     * class in the classRegistry, call enrichRequiredFields on it. This catches all schemas that
     * were stored from victools $defs processing and never individually enriched.
     */
    private void enrichAllGeneratedSchemas() {
        for (Map.Entry<String, Schema<?>> entry : generatedSchemas.entrySet()) {
            if (!(entry.getValue() instanceof ObjectSchema objectSchema)) continue;
            if (objectSchema.getProperties() == null) continue;
            Class<?> clazz = classRegistry.get(entry.getKey());
            if (clazz != null) {
                enrichRequiredFields(clazz, objectSchema);
            }
        }
    }

    /**
     * Returns true if any of the given annotated elements has a nullable annotation     * (e.g. @Nullable from any package, or @NotNull absent indicates nullable).
     * We use the simpler heuristic: Optional type = nullable, otherwise required.
     * This helper checks for explicit @Nullable annotation overrides.
     */
    private boolean isNullableAnnotated(java.lang.reflect.AnnotatedElement... elements) {
        for (java.lang.reflect.AnnotatedElement element : elements) {
            if (element == null) continue;
            for (java.lang.annotation.Annotation ann : element.getAnnotations()) {
                String name = ann.annotationType().getSimpleName();
                if ("Nullable".equals(name) || "Null".equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasJsonIgnore(java.lang.reflect.AnnotatedElement... elements) {
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
        if (Optional.class.isAssignableFrom(type) ||
            "java.util.Optional".equals(type.getName())) {
            if (genericType instanceof java.lang.reflect.ParameterizedType pt) {
                java.lang.reflect.Type[] args = pt.getActualTypeArguments();
                if (args.length > 0 && args[0] instanceof Class<?> innerClass) {
                    return mapJavaTypeToSchema(innerClass, innerClass);
                } else if (args.length > 0 && args[0] instanceof java.lang.reflect.ParameterizedType innerPt) {
                    Class<?> rawInner = getRawClass(innerPt);
                    if (rawInner != null) {
                        return mapJavaTypeToSchema(rawInner, innerPt);
                    }
                }
            }
            return new ObjectSchema();
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

        // Polymorphic type (sealed interface with @JsonTypeInfo) — emit $ref to parent discriminated schema
        if (isPolymorphicType(type)) {
            String refTypeName = type.getSimpleName();
            if (!generatedSchemas.containsKey(refTypeName)) {
                // Trigger polymorphic schema generation
                generateSchema(type);
            }
            return createReference(refTypeName);
        }

        // Complex object - use $ref if already generated, otherwise generate and store
        String refTypeName = type.getSimpleName();
        // Check if we need to generate or upgrade the schema
        boolean needsGeneration = !generatedSchemas.containsKey(refTypeName);
        if (!needsGeneration) {
            // Also regenerate if existing schema is an empty ObjectSchema (placeholder from $defs)
            Schema<?> existing = generatedSchemas.get(refTypeName);
            if (existing instanceof ObjectSchema &&
                (((ObjectSchema) existing).getProperties() == null ||
                 ((ObjectSchema) existing).getProperties().isEmpty()) &&
                ((ObjectSchema) existing).getAdditionalProperties() == null) {
                needsGeneration = true;
            }
        }
        if (needsGeneration) {
            // Register class so nested defs from this type can be enriched with required fields
            registerClassHierarchy(type);
            // Store placeholder first to prevent infinite recursion for circular references
            generatedSchemas.put(refTypeName, new ObjectSchema());
            // Try to generate schema by reflection for records and POJOs
            Schema<?> generated = generateSchemaByReflection(type, refTypeName);
            if (generated != null) {
                generatedSchemas.put(refTypeName, generated);
            }
        }
        return createReference(refTypeName);
    }

    private Schema<?> convertJsonSchemaToOpenApi(ObjectNode jsonSchema, String rootTypeName) {
        // Extract definitions first (they may be under $defs in draft 2020-12)
        JsonNode defs = getDefinitions(jsonSchema);
        Set<String> rootAliases = new HashSet<>();
        JsonNode rootSchema = resolveRootSchemaNode(jsonSchema, defs, rootAliases);
        rootAliases.forEach(alias -> schemaNameAliases.put(alias, rootTypeName));
        registerExistingSchemaAliases(defs);

        if (defs != null && defs.isObject()) {
            // First pass: process non-Map, non-nullable defs
            defs.fields().forEachRemaining(entry -> {
                String defName = sanitizeSchemaName(entry.getKey());
                if (defName.endsWith("-nullable") || defName.startsWith("Map_")) {
                    return;
                }
                // Skip framework-internal/Akka SDK types
                if (isInternalSchemaName(defName)) {
                    return;
                }
                // Check if this is a duplicate numbered variant (e.g. EmailConfig-2 when EmailConfig-1 is already registered)
                String existingForDef = findExistingSchemaName(defName);
                if (existingForDef != null && !existingForDef.equals(defName)) {
                    schemaNameAliases.put(defName, existingForDef);
                    return; // skip – already have an equivalent schema
                }
                // If this is a new numbered schema (e.g. EmailConfig-1) and no base exists yet,
                // register it under the base name (e.g. EmailConfig) to avoid the suffix.
                String registrationName = defName;
                String baseName = stripNumericSuffix(defName);
                if (baseName != null && !generatedSchemas.containsKey(baseName)
                        && !schemaNameAliases.containsKey(baseName)
                        && !rootAliases.contains(baseName)
                        && !schemaNameAliases.containsKey(defName)
                        && !generatedSchemas.containsKey(defName)) {
                    registrationName = baseName;
                    schemaNameAliases.put(defName, baseName);
                }
                if (!rootAliases.contains(defName)
                        && !generatedSchemas.containsKey(registrationName)) {
                    // Only process if not already aliased to something else
                    String existingAlias = schemaNameAliases.get(defName);
                    if (existingAlias != null && !existingAlias.equals(registrationName)) {
                        return;
                    }
                    Schema<?> defSchema = convertJsonNodeToSchema(entry.getValue(), registrationName);
                    if (defSchema == null) {
                        return;
                    }
                    // TODO: REMOVE IF NEEDED
                    if (defSchema.get$ref() != null) {
                        String aliasTarget = extractRefName(defSchema.get$ref(), defName);
                        if (aliasTarget != null && !aliasTarget.equals(defName)) {
                            schemaNameAliases.put(defName, aliasTarget);
                            return;
                        }
                    }
                    // Avoid storing self-referencing schemas (e.g., from "#" circular refs)
                    if (defSchema.get$ref() != null &&
                        defSchema.get$ref().equals("#/components/schemas/" + registrationName)) {
                        ObjectSchema objectSchema = new ObjectSchema();
                        objectSchema.setAdditionalProperties(new ObjectSchema());
                        generatedSchemas.put(registrationName, objectSchema);
                    } else {
                        generatedSchemas.put(registrationName, defSchema);
                    }
                }
            });

            // Second pass: process Map-type defs (value types should now be registered)
            defs.fields().forEachRemaining(entry -> {
                String defName = sanitizeSchemaName(entry.getKey());
                if (!defName.startsWith("Map_")) {
                    return;
                }
                String valueTypeName = extractMapValueType(defName);
                if (valueTypeName != null) {
                    ObjectSchema mapSchema = new ObjectSchema();
                    mapSchema.setAdditionalProperties(createReference(valueTypeName));
                    generatedSchemas.put(defName, mapSchema);
                    // If value type wasn't generated in first pass, try from additionalProperties or defs
                    if (!generatedSchemas.containsKey(valueTypeName)) {
                        JsonNode mapNode = entry.getValue();
                        if (mapNode.has("additionalProperties")) {
                            JsonNode addPropsNode = mapNode.get("additionalProperties");
                            if (addPropsNode.has("$ref")) {
                                String valRef = extractRawRefName(addPropsNode.get("$ref").asText());
                                if (valRef != null) {
                                    JsonNode valDef = findDefinition(defs, valRef);
                                    if (valDef != null) {
                                        Schema<?> valSchema = convertJsonNodeToSchema(valDef, valueTypeName);
                                        if (valSchema != null) {
                                            generatedSchemas.put(valueTypeName, valSchema);
                                        }
                                    }
                                }
                            } else {
                                Schema<?> valSchema = convertJsonNodeToSchema(addPropsNode, valueTypeName);
                                if (valSchema != null) {
                                    generatedSchemas.put(valueTypeName, valSchema);
                                }
                            }
                        } else {
                            // Look for the value type in defs by name
                            JsonNode valDef = findDefinition(defs, valueTypeName);
                            if (valDef != null) {
                                Schema<?> valSchema = convertJsonNodeToSchema(valDef, valueTypeName);
                                if (valSchema != null) {
                                    generatedSchemas.put(valueTypeName, valSchema);
                                }
                            }
                        }
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
        if (baseName == null) {
            return null;
        }
        if (generatedSchemas.containsKey(baseName)) {
            return baseName;
        }
        // Also check if any numbered variant of baseName already exists (e.g. baseName-1)
        for (String existing : generatedSchemas.keySet()) {
            String existingBase = stripNumericSuffix(existing);
            if (baseName.equals(existingBase)) {
                return existing;
            }
        }
        // Also check aliases for numbered variants
        for (Map.Entry<String, String> aliasEntry : schemaNameAliases.entrySet()) {
            String aliasBase = stripNumericSuffix(aliasEntry.getKey());
            if (baseName.equals(aliasBase)) {
                return aliasEntry.getValue();
            }
        }
        return null;
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
            String sanitizedKey = sanitizeSchemaName(entry.getKey());
            if (sanitizedRefName.equals(sanitizedKey)) {
                return entry.getValue();
            }
            // Also match by suffix (for inner classes like HealthStatus.ComponentHealth → ComponentHealth)
            if (sanitizedKey.endsWith("_" + sanitizedRefName) || sanitizedKey.endsWith("." + refName)) {
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
                // For Map-type refs, inline as object with additionalProperties
                if (refName.startsWith("Map_")) {
                    String valueTypeName = extractMapValueType(refName);
                    ObjectSchema mapSchema = new ObjectSchema();
                    if (valueTypeName != null && !valueTypeName.isEmpty()) {
                        mapSchema.setAdditionalProperties(createReference(valueTypeName));
                    } else {
                        mapSchema.setAdditionalProperties(new ObjectSchema());
                    }
                    return mapSchema;
                }
                // If refName is a subtype of a polymorphic parent, and we're not
                // currently building that parent's schema, redirect to the parent.
                String parentType = subtypeToParentMap.get(refName);
                if (parentType != null && !parentType.equals(currentTypeName)) {
                    return createReference(parentType);
                }
                // Suppress refs to internal framework types — treat as opaque object
                if (isInternalSchemaName(refName)) {
                    logger.accept("Suppressing $ref to internal framework type: " + refName);
                    return new ObjectSchema();
                }
                return createReference(refName);
            }
            // If we can't extract a proper ref name, return an object schema
            return new ObjectSchema();
        }

        Schema<?> composedSchema = tryComposedSchema(node, currentTypeName);
        if (composedSchema != null) {
            return applyCommonProperties(composedSchema, node);
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
            String format = node.has("format") ? node.get("format").asText() : null;
            Schema<?> stringSchema;
            if ("uuid".equals(format)) {
                stringSchema = new UUIDSchema();
            } else if ("date".equals(format)) {
                stringSchema = new DateSchema();
            } else if ("date-time".equals(format)) {
                stringSchema = new DateTimeSchema();
            } else if ("binary".equals(format)) {
                stringSchema = new BinarySchema();
            } else {
                stringSchema = new StringSchema();
            }
            if (node.has("format")) {
                stringSchema.setFormat(format);
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
                ((Schema) stringSchema).setEnum(enumValues);
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
        } else if ("null".equals(type)) {
            schema = new Schema<>().type("null");
        } else {
            // Default to object schema
            schema = new ObjectSchema();
        }

        return applyCommonProperties(schema, node);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Schema<?> tryComposedSchema(JsonNode node, String currentTypeName) {
        String compositionKeyword = null;
        JsonNode composedNode = null;
        if (node.has("anyOf")) {
            compositionKeyword = "anyOf";
            composedNode = node.get("anyOf");
        } else if (node.has("oneOf")) {
            compositionKeyword = "oneOf";
            composedNode = node.get("oneOf");
        } else if (node.has("allOf")) {
            compositionKeyword = "allOf";
            composedNode = node.get("allOf");
        }

        if (composedNode == null || !composedNode.isArray()) {
            return null;
        }

        List<JsonNode> nonNullEntries = new ArrayList<>();
        for (JsonNode entry : composedNode) {
            if (!isNullSchema(entry)) {
                nonNullEntries.add(entry);
            }
        }

        if (nonNullEntries.size() == 1 && composedNode.size() == 2
                && ("anyOf".equals(compositionKeyword) || "oneOf".equals(compositionKeyword))) {
            return convertJsonNodeToSchema(nonNullEntries.get(0), currentTypeName);
        }

        ComposedSchema schema = new ComposedSchema();
        List<Schema> entries = new ArrayList<>();
        for (JsonNode entry : composedNode) {
            entries.add((Schema) convertJsonNodeToSchema(entry, currentTypeName));
        }

        if ("anyOf".equals(compositionKeyword)) {
            schema.setAnyOf(entries);
        } else if ("oneOf".equals(compositionKeyword)) {
            schema.setOneOf(entries);
        } else {
            schema.setAllOf(entries);
        }
        return schema;
    }

    private boolean isNullSchema(JsonNode node) {
        if (node == null || node.isNull()) {
            return true;
        }
        JsonNode typeNode = node.get("type");
        if (typeNode == null) {
            return false;
        }
        if (typeNode.isTextual()) {
            return "null".equals(typeNode.asText());
        }
        if (typeNode.isArray() && typeNode.size() == 1) {
            return "null".equals(typeNode.get(0).asText());
        }
        return false;
    }

    private Schema<?> applyCommonProperties(Schema<?> schema, JsonNode node) {
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
        // Strip "-nullable" suffix — these are victools wrappers for Optional fields
        if (sanitizedName.endsWith("-nullable")) {
            sanitizedName = sanitizedName.substring(0, sanitizedName.length() - "-nullable".length());
        }
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

    /**
     * Returns true if this schema name refers to a framework-internal or Akka SDK
     * type that should never appear as a component in a user-facing OpenAPI spec.
     *
     * <p>Examples that should be suppressed:</p>
     * <ul>
     *   <li>{@code Source_Object_NotUsed_} — Akka Streams Source materialized value</li>
     *   <li>{@code NotUsed} — Akka's scala.runtime.BoxedUnit equivalent</li>
     *   <li>{@code Done} — Akka's akka.Done</li>
     * </ul>
     */
    private boolean isInternalSchemaName(String sanitizedName) {
        if (sanitizedName == null) return false;
        // Akka Streams Source with any type params, e.g. Source_Object_NotUsed_
        // Also match the raw class simple name "Source" to catch it before specialisation
        if (sanitizedName.equals("Source") || sanitizedName.startsWith("Source_")) return true;
        // Akka internal marker types
        if (sanitizedName.equals("NotUsed")) return true;
        if (sanitizedName.equals("Done")) return true;
        // scala.runtime / scala stdlib leaking through
        if (sanitizedName.startsWith("scala_")) return true;
        if (sanitizedName.startsWith("akka_")) return true;
        return false;
    }

    /**
     * Generates a specialized type name for parameterized types.
     * E.g., PagedResponse<Customer> becomes "PagedResponseCustomer".
     */
    private String getSpecializedTypeName(Class<?> rawClass, java.lang.reflect.ParameterizedType parameterizedType) {
        StringBuilder name = new StringBuilder(getTypeName(rawClass));
        for (Type arg : parameterizedType.getActualTypeArguments()) {
            Class<?> argClass = getRawClass(arg);
            if (argClass != null) {
                name.append(getTypeName(argClass));
            }
        }
        return name.toString();
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
     * Returns all schemas generated so far, with post-processing to remove
     * duplicate "-nullable" wrapper schemas.
     *
     * <p>These schemas should be added to the OpenAPI components/schemas section.</p>
     *
     * @return map of schema names to schemas
     */
    public Map<String, Schema<?>> getGeneratedSchemas() {
        // Ensure required fields are correctly set on all generated component schemas.
        // This catches types that were stored from victools $defs processing without being
        // individually enriched (e.g. nested records, inner static classes, propagated types).
        enrichAllGeneratedSchemas();

        // Remove "-nullable" entries, numeric-suffixed duplicates, and Map_ entries
        Map<String, Schema<?>> result = new LinkedHashMap<>(generatedSchemas);
        // Build a map of removed name → canonical replacement name for ref rewriting
        Map<String, String> replacements = new LinkedHashMap<>();
        List<String> toRemove = new ArrayList<>();

        for (String name : result.keySet()) {
            if (name.endsWith("-nullable")) {
                String baseName = name.substring(0, name.length() - "-nullable".length());
                if (result.containsKey(baseName)) {
                    toRemove.add(name);
                    replacements.put(name, baseName);
                }
            } else if (name.startsWith("Map_")) {
                toRemove.add(name);
                // Map_ entries are inlined, no canonical replacement needed
            } else if (isInternalSchemaName(name)) {
                toRemove.add(name);
                // Internal framework types are silently dropped
            } else {
                String baseName = stripNumericSuffix(name);
                if (baseName != null && result.containsKey(baseName)) {
                    toRemove.add(name);
                    // If base name is itself a subtype, redirect to parent
                    String parent = subtypeToParentMap.get(baseName);
                    replacements.put(name, parent != null ? parent : baseName);
                }
            }
        }

        toRemove.forEach(result::remove);

        // Also include all schemaNameAliases entries so that refs to numbered names that were
        // never added to generatedSchemas (skipped by dedup) still get rewritten correctly.
        schemaNameAliases.forEach((aliasName, canonicalName) -> {
            if (!result.containsKey(aliasName)) {
                // Resolve transitively: if canonicalName is itself aliased
                String resolved = canonicalName;
                while (schemaNameAliases.containsKey(resolved)) {
                    resolved = schemaNameAliases.get(resolved);
                }
                replacements.put(aliasName, resolved);
            }
        });

        // Rewrite $refs in all remaining schemas to replace removed names with canonical ones
        if (!replacements.isEmpty()) {
            for (Schema<?> schema : result.values()) {
                rewriteRefs(schema, replacements);
            }
        }

        // Final safety pass: strip any remaining $refs that point to internal framework types
        // (may have been emitted before interception was possible)
        for (Schema<?> schema : result.values()) {
            stripInternalRefs(schema);
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * Recursively rewrites $ref values in a schema using the given replacement map.
     */
    private void rewriteRefs(Schema<?> schema, Map<String, String> replacements) {
        if (schema == null) return;

        // Fix direct $ref
        if (schema.get$ref() != null) {
            String ref = schema.get$ref();
            String suffix = "#/components/schemas/";
            if (ref.startsWith(suffix)) {
                String name = ref.substring(suffix.length());
                String replacement = replacements.get(name);
                if (replacement != null) {
                    schema.set$ref(suffix + replacement);
                }
            }
        }

        // Recurse into properties
        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(prop -> rewriteRefs((Schema<?>) prop, replacements));
        }

        // Recurse into additionalProperties
        Object addProps = schema.getAdditionalProperties();
        if (addProps instanceof Schema) {
            rewriteRefs((Schema<?>) addProps, replacements);
        }

        // Recurse into items (array)
        if (schema.getItems() != null) {
            rewriteRefs(schema.getItems(), replacements);
        }

        // Recurse into oneOf / anyOf / allOf
        if (schema instanceof ComposedSchema composed) {
            if (composed.getOneOf() != null) {
                composed.getOneOf().forEach(s -> rewriteRefs(s, replacements));
            }
            if (composed.getAnyOf() != null) {
                composed.getAnyOf().forEach(s -> rewriteRefs(s, replacements));
            }
            if (composed.getAllOf() != null) {
                composed.getAllOf().forEach(s -> rewriteRefs(s, replacements));
            }
        }
    }

    /**
     * Recursively removes $refs that point to internal framework types,
     * replacing them with an inline empty object schema to avoid unresolved-ref errors.
     */
    @SuppressWarnings("unchecked")
    private void stripInternalRefs(Schema<?> schema) {
        if (schema == null) return;

        // Fix direct $ref
        if (schema.get$ref() != null) {
            String ref = schema.get$ref();
            String suffix = "#/components/schemas/";
            if (ref.startsWith(suffix)) {
                String name = ref.substring(suffix.length());
                if (isInternalSchemaName(name)) {
                    schema.set$ref(null);
                    schema.setType("object");
                }
            }
        }

        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(p -> stripInternalRefs((Schema<?>) p));
        }
        Object addProps = schema.getAdditionalProperties();
        if (addProps instanceof Schema) {
            stripInternalRefs((Schema<?>) addProps);
        }
        if (schema.getItems() != null) {
            stripInternalRefs(schema.getItems());
        }
        if (schema instanceof ComposedSchema composed) {
            if (composed.getOneOf() != null) {
                composed.getOneOf().forEach(this::stripInternalRefs);
            }
            if (composed.getAnyOf() != null) {
                composed.getAnyOf().forEach(this::stripInternalRefs);
            }
            if (composed.getAllOf() != null) {
                composed.getAllOf().forEach(this::stripInternalRefs);
            }
        }
    }

    /**
     * Clears all generated schemas.
     */
    public void clearSchemas() {
        generatedSchemas.clear();
        schemaNameAliases.clear();
        subtypeToParentMap.clear();
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
