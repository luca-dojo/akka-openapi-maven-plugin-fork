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
    /** Tracks all classes that map to a given simple name, for collision detection. */
    private final Map<String, Set<Class<?>>> classesPerSimpleName;
    /** Canonical schema name resolved for each class (handles collisions via qualification). */
    private final Map<Class<?>, String> resolvedSchemaNames;

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
        this.classesPerSimpleName = new ConcurrentHashMap<>();
        this.resolvedSchemaNames = new ConcurrentHashMap<>();
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
        if (clazz.isPrimitive() || clazz.isArray()
                || clazz.getPackageName().startsWith("java.")
                || clazz.getPackageName().startsWith("javax.")
                || clazz.getPackageName().startsWith("scala.")
                || clazz.getPackageName().startsWith("akka.")) {
            return;
        }
        visited.add(clazz);
        classRegistry.putIfAbsent(clazz.getSimpleName(), clazz);
        // Track all classes per simple name for collision detection
        classesPerSimpleName.computeIfAbsent(clazz.getSimpleName(), k -> ConcurrentHashMap.newKeySet()).add(clazz);

        // For enums, register but don't recurse into fields/nested classes
        if (clazz.isEnum()) {
            return;
        }

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
        preGeneratePolymorphicFieldsRecursive(rawClass, new HashSet<>());
    }

    private void preGeneratePolymorphicFieldsRecursive(Class<?> rawClass, Set<Class<?>> visited) {
        if (!visited.add(rawClass)) return;
        // Check record components
        java.lang.reflect.RecordComponent[] components = rawClass.getRecordComponents();
        if (components != null) {
            for (java.lang.reflect.RecordComponent component : components) {
                preGenerateFieldType(component.getType(), component.getGenericType(), visited);
            }
        }
        // Check declared fields (for non-record classes)
        for (java.lang.reflect.Field field : rawClass.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            preGenerateFieldType(field.getType(), field.getGenericType(), visited);
        }
    }

    private void preGenerateFieldType(Class<?> fieldType, java.lang.reflect.Type genericType) {
        preGenerateFieldType(fieldType, genericType, new HashSet<>());
    }

    private void preGenerateFieldType(Class<?> fieldType, java.lang.reflect.Type genericType, Set<Class<?>> visited) {
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
        // Recurse into collection/array element types to find nested polymorphic types
        Class<?> elementClass = null;
        if (genericType instanceof java.lang.reflect.ParameterizedType pt
                && (List.class.isAssignableFrom(fieldType) || Set.class.isAssignableFrom(fieldType)
                    || Collection.class.isAssignableFrom(fieldType))
                && pt.getActualTypeArguments().length == 1) {
            elementClass = getRawClass(pt.getActualTypeArguments()[0]);
        } else if (fieldType.isArray()) {
            elementClass = fieldType.getComponentType();
        }
        if (elementClass != null && !elementClass.isPrimitive()
                && trySimpleTypeSchema(elementClass) == null) {
            preGeneratePolymorphicFieldsRecursive(elementClass, visited);
        }
        // Recurse into nested complex types (records/POJOs) to find polymorphic fields
        if (!fieldType.isPrimitive() && !fieldType.isArray()
                && !Collection.class.isAssignableFrom(fieldType)
                && !Map.class.isAssignableFrom(fieldType)
                && trySimpleTypeSchema(fieldType) == null
                && !isPolymorphicType(fieldType)
                && !fieldType.isEnum()) {
            preGeneratePolymorphicFieldsRecursive(fieldType, visited);
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

        // Enforce discriminator property as required at the union root level
        composedSchema.setRequired(Collections.singletonList(discriminatorProperty));
        StringSchema discriminatorPropSchema = new StringSchema();
        composedSchema.addProperty(discriminatorProperty, discriminatorPropSchema);

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
                        // Mark Optional properties as nullable (OAS 3.1 style)
                        if (Optional.class.isAssignableFrom(propType)) {
                            Schema<?> nullableSchema = makeNullableSchema(propSchema);
                            if (nullableSchema != propSchema) {
                                objectSchema.getProperties().put(propName, nullableSchema);
                            }
                        }
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
                        // Mark Optional return types as nullable (OAS 3.1 style)
                        if (Optional.class.isAssignableFrom(returnType)) {
                            Schema<?> nullableSchema = makeNullableSchema(propSchema);
                            if (nullableSchema != propSchema) {
                                objectSchema.getProperties().put(propName, nullableSchema);
                            }
                        }
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
     * Also marks Optional properties as nullable.
     * This supplements what victools detects via @NotNull/@NotBlank.
     */
    @SuppressWarnings("unchecked")
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
                if (!schema.getProperties().containsKey(propName)) continue;

                if (Optional.class.isAssignableFrom(component.getType())) {
                    // Mark Optional properties as nullable (OAS 3.1 style)
                    Schema<?> propSchema = (Schema<?>) schema.getProperties().get(propName);
                    if (propSchema != null) {
                        Schema<?> nullableSchema = makeNullableSchema(propSchema);
                        if (nullableSchema != propSchema) {
                            schema.getProperties().put(propName, nullableSchema);
                        }
                    }
                } else if (!existingRequired.contains(propName)
                        && !isNullableAnnotated(component, component.getAccessor())) {
                    toAdd.add(propName);
                }
            }
        } else {
            // Check declared fields for non-record classes
            for (java.lang.reflect.Field field : rawClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                String propName = getJsonPropertyName(field.getName(), field);
                if (!schema.getProperties().containsKey(propName)) continue;

                if (Optional.class.isAssignableFrom(field.getType())) {
                    // Mark Optional properties as nullable (OAS 3.1 style)
                    Schema<?> propSchema = (Schema<?>) schema.getProperties().get(propName);
                    if (propSchema != null) {
                        Schema<?> nullableSchema = makeNullableSchema(propSchema);
                        if (nullableSchema != propSchema) {
                            schema.getProperties().put(propName, nullableSchema);
                        }
                    }
                } else if (!existingRequired.contains(propName)
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

    /**
     * Makes a schema nullable in an OpenAPI 3.1-compatible way.
     * For $ref schemas, wraps in oneOf with a null type.
     * For typed schemas, adds "null" to the types set.
     * Idempotent: returns the schema unchanged if already nullable.
     *
     * @param schema the schema to make nullable
     * @return the nullable schema (may be a new ComposedSchema if input was a $ref)
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Schema<?> makeNullableSchema(Schema<?> schema) {
        if (schema == null) return null;
        // Already nullable: check if types include "null" or oneOf already has a null entry
        if (schema.getTypes() != null && schema.getTypes().contains("null")) {
            return schema;
        }
        if (schema.getOneOf() != null && schema.getOneOf().stream().anyMatch(this::isNullTypeSchema)) {
            return schema;
        }
        if (schema.get$ref() != null) {
            // For $ref schemas in OAS 3.1, wrap in oneOf with null type
            ComposedSchema composed = new ComposedSchema();
            Schema<?> refPart = new Schema<>();
            refPart.set$ref(schema.get$ref());
            Schema<?> nullPart = new Schema<>();
            nullPart.addType("null");
            composed.setOneOf(List.of(refPart, nullPart));
            return composed;
        } else {
            // For typed schemas, add "null" to types
            schema.addType("null");
            return schema;
        }
    }

    private boolean isNullTypeSchema(Schema<?> schema) {
        return schema != null
            && schema.getTypes() != null
            && schema.getTypes().contains("null")
            && schema.getTypes().size() == 1;
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
            // Register the enum class for collision detection
            registerClassHierarchy(type);
            String enumSchemaName = resolveSchemaName(type);
            StringSchema enumSchema = new StringSchema();
            for (Object constant : type.getEnumConstants()) {
                enumSchema.addEnumItem(((Enum<?>) constant).name());
            }
            // Store in generatedSchemas so it becomes a referenceable component
            if (!generatedSchemas.containsKey(enumSchemaName)) {
                generatedSchemas.put(enumSchemaName, enumSchema);
            }
            return createReference(enumSchemaName);
        }

        // Polymorphic type (sealed interface with @JsonTypeInfo) — emit $ref to parent discriminated schema
        if (isPolymorphicType(type)) {
            String refTypeName = resolveSchemaName(type);
            if (!generatedSchemas.containsKey(refTypeName)) {
                // Trigger polymorphic schema generation
                generateSchema(type);
            }
            return createReference(refTypeName);
        }

        // Complex object - use $ref if already generated, otherwise generate and store
        String refTypeName = resolveSchemaName(type);
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

                // Check for name collision: if multiple classes share this simple name,
                // resolve to a qualified name for the class belonging to the current root type
                String resolvedName = resolveDefNameForRoot(defName, rootTypeName, defs);
                if (resolvedName != null && !resolvedName.equals(defName)) {
                    // This def has a collision — store under the qualified name.
                    // Do NOT set a global alias for defName, since it maps to different
                    // qualified names depending on context (handled by extractRefName).
                    if (!generatedSchemas.containsKey(resolvedName)) {
                        Schema<?> defSchema = convertJsonNodeToSchema(entry.getValue(), resolvedName);
                        if (defSchema != null && defSchema.get$ref() == null) {
                            generatedSchemas.put(resolvedName, defSchema);
                        }
                    }
                    return;
                }

                // Check if this is a duplicate numbered variant (e.g. EmailConfig-2 when EmailConfig-1 is already registered)
                String existingForDef = findExistingSchemaName(defName);
                if (existingForDef != null && !existingForDef.equals(defName)) {
                    // Before aliasing, check if the existing schema is from a different class (collision)
                    if (isSchemaNameCollision(defName, entry.getValue())) {
                        String qualifiedName = qualifyDefNameForRoot(defName, rootTypeName);
                        if (qualifiedName != null) {
                            // Don't set global alias — context-aware extractRefName handles it
                            if (!generatedSchemas.containsKey(qualifiedName)) {
                                Schema<?> defSchema = convertJsonNodeToSchema(entry.getValue(), qualifiedName);
                                if (defSchema != null) {
                                    generatedSchemas.put(qualifiedName, defSchema);
                                }
                            }
                            return;
                        }
                    }
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

    /**
     * Resolves a def name to a qualified name when a collision exists, based on the root type
     * currently being processed. Returns null if no collision exists.
     *
     * <p>When a collision is detected, this method also ensures ALL colliding classes
     * have their schemas registered under their qualified names, so that $refs generated
     * by extractRefName can be resolved regardless of processing order.</p>
     *
     * @param defName the simple def name from victools (e.g., "Status" or "Status-2")
     * @param rootTypeName the root type being processed (e.g., "EmailNotification")
     * @param defs the $defs node from the current schema (used to identify which enclosing
     *             classes are co-located in this schema)
     * @return qualified name if collision detected, null otherwise
     */
    private String resolveDefNameForRoot(String defName, String rootTypeName, JsonNode defs) {
        // First, try the defName directly
        Set<Class<?>> classesWithName = classesPerSimpleName.get(defName);

        // If no collision for defName directly, check the base name (strip numeric suffix)
        // This handles victools-generated names like "Status-1", "Status-2" where the
        // base name "Status" has a collision.
        String baseName = null;
        boolean isNumberedVariant = false;
        if (classesWithName == null || classesWithName.size() <= 1) {
            baseName = stripNumericSuffix(defName);
            if (baseName != null) {
                classesWithName = classesPerSimpleName.get(baseName);
                isNumberedVariant = (classesWithName != null && classesWithName.size() > 1);
            }
        }

        if (classesWithName == null || classesWithName.size() <= 1) {
            return null; // No collision
        }

        // For numbered variants, prioritize enum-value matching to identify the exact class
        // since the enclosing-class checks may be ambiguous when multiple collision candidates
        // have their enclosing classes as co-located $defs.
        if (isNumberedVariant && defs != null) {
            JsonNode defNode = defs.get(defName);
            if (defNode == null) {
                // Try with sanitized name
                defNode = defs.get(sanitizeSchemaName(defName));
            }
            if (defNode != null && defNode.has("enum")) {
                Set<String> defEnumValues = new HashSet<>();
                defNode.get("enum").forEach(n -> defEnumValues.add(n.asText()));
                for (Class<?> clazz : classesWithName) {
                    if (clazz.isEnum()) {
                        Set<String> classEnumValues = new HashSet<>();
                        for (Object constant : clazz.getEnumConstants()) {
                            classEnumValues.add(((Enum<?>) constant).name());
                        }
                        if (defEnumValues.equals(classEnumValues)) {
                            ensureAllCollisionSchemasRegistered(classesWithName);
                            return resolveSchemaName(clazz);
                        }
                    }
                }
            }
            // Also try property matching for non-enum numbered variants
            if (defNode != null) {
                Class<?> matched = matchDefNodeToClass(defNode, classesWithName);
                if (matched != null) {
                    ensureAllCollisionSchemasRegistered(classesWithName);
                    return resolveSchemaName(matched);
                }
            }
        }

        // First check: enclosing class matches root type directly
        for (Class<?> clazz : classesWithName) {
            Class<?> enclosing = clazz.getEnclosingClass();
            if (enclosing != null && enclosing.getSimpleName().equals(rootTypeName)) {
                ensureAllCollisionSchemasRegistered(classesWithName);
                return resolveSchemaName(clazz);
            }
        }

        // Second check: find the class whose enclosing class is also a $def in this schema.
        // Only use this when there's exactly ONE candidate with a co-located enclosing def.
        if (defs != null) {
            List<Class<?>> candidates = new ArrayList<>();
            for (Class<?> clazz : classesWithName) {
                Class<?> enclosing = clazz.getEnclosingClass();
                if (enclosing != null && defs.has(enclosing.getSimpleName())) {
                    candidates.add(clazz);
                }
            }
            if (candidates.size() == 1) {
                ensureAllCollisionSchemasRegistered(classesWithName);
                return resolveSchemaName(candidates.get(0));
            }
        }

        // Third check: match by enum values in the def node against class constants.
        if (defs != null) {
            JsonNode defNode = defs.get(defName);
            if (defNode != null && defNode.has("enum")) {
                Set<String> defEnumValues = new HashSet<>();
                defNode.get("enum").forEach(n -> defEnumValues.add(n.asText()));
                for (Class<?> clazz : classesWithName) {
                    if (clazz.isEnum()) {
                        Set<String> classEnumValues = new HashSet<>();
                        for (Object constant : clazz.getEnumConstants()) {
                            classEnumValues.add(((Enum<?>) constant).name());
                        }
                        if (defEnumValues.equals(classEnumValues)) {
                            ensureAllCollisionSchemasRegistered(classesWithName);
                            return resolveSchemaName(clazz);
                        }
                    }
                }
            }
        }

        // Fourth check: match by property names in the def node against class fields/record components.
        // This handles non-enum types (e.g., inner records) where enum-value matching doesn't apply.
        if (defs != null) {
            JsonNode defNode = defs.get(defName);
            if (defNode == null) {
                defNode = defs.get(sanitizeSchemaName(defName));
            }
            Class<?> matched = matchDefNodeToClass(defNode, classesWithName);
            if (matched != null) {
                ensureAllCollisionSchemasRegistered(classesWithName);
                return resolveSchemaName(matched);
            }
        }

        // Fallback: qualify with root type name
        ensureAllCollisionSchemasRegistered(classesWithName);
        return qualifyDefNameForRoot(baseName != null ? baseName : defName, rootTypeName);
    }

    /**
     * Ensures all classes involved in a name collision have their schemas registered
     * under their qualified names. This guarantees that any $ref computed by extractRefName
     * can be resolved, regardless of the order in which types are processed.
     * Also removes any ambiguous simple-name schema that was stored before the collision
     * was detected.
     */
    private void ensureAllCollisionSchemasRegistered(Set<Class<?>> classesWithName) {
        String simpleName = null;
        // Capture the existing schema under the simple name BEFORE resolveSchemaName removes it
        Schema<?> existingSimpleNameSchema = null;
        Class<?> simpleNameOwner = null;

        for (Class<?> clazz : classesWithName) {
            if (simpleName == null) {
                simpleName = clazz.getSimpleName();
                existingSimpleNameSchema = generatedSchemas.get(simpleName);
                simpleNameOwner = classRegistry.get(simpleName);
            }
            break;
        }

        for (Class<?> clazz : classesWithName) {
            if (simpleName == null) simpleName = clazz.getSimpleName();
            String qualifiedName = resolveSchemaName(clazz);
            if (clazz.isEnum()) {
                // Always create from the actual class constants to ensure correctness,
                // regardless of what the retroactive rename may have stored previously.
                StringSchema enumSchema = new StringSchema();
                for (Object constant : clazz.getEnumConstants()) {
                    enumSchema.addEnumItem(((Enum<?>) constant).name());
                }
                generatedSchemas.put(qualifiedName, enumSchema);
            }
        }
        // Remove the ambiguous simple-name schema if it exists (it was stored before
        // the collision was detected and shouldn't remain in the final output)
        if (simpleName != null && generatedSchemas.containsKey(simpleName)) {
            generatedSchemas.remove(simpleName);
        }
        // For non-enum types: if there was a schema stored under the simple name,
        // move it to the correct qualified name (the class that originally stored it,
        // identified via classRegistry which uses putIfAbsent on simple names).
        if (existingSimpleNameSchema != null && simpleNameOwner != null && !simpleNameOwner.isEnum()) {
            String ownerQualified = resolvedSchemaNames.get(simpleNameOwner);
            if (ownerQualified != null && !generatedSchemas.containsKey(ownerQualified)) {
                generatedSchemas.put(ownerQualified, existingSimpleNameSchema);
            }
        }
    }

    /**
     * Computes a qualified def name using the root type as a prefix.
     * Used as a fallback when the def class cannot be found via enclosing class lookup.
     *
     * @param defName the simple def name
     * @param rootTypeName the root type name to use as prefix
     * @return the qualified name (e.g., "EmailNotificationStatus")
     */
    private String qualifyDefNameForRoot(String defName, String rootTypeName) {
        return rootTypeName + defName;
    }

    /**
     * Checks if registering a schema under defName would be a collision with a different
     * class's schema (i.e., the existing schema has different content, not just a numbered duplicate).
     *
     * @param defName the def name being registered
     * @param defNode the JSON node for the new def
     * @return true if the existing schema under defName represents a different type
     */
    @SuppressWarnings("unchecked")
    private boolean isSchemaNameCollision(String defName, JsonNode defNode) {
        Set<Class<?>> classesWithName = classesPerSimpleName.get(defName);
        if (classesWithName != null && classesWithName.size() > 1) {
            return true;
        }

        // Also check by base name (strip numeric suffix, e.g., "Status-2" → "Status")
        String baseName = stripNumericSuffix(defName);
        if (baseName != null) {
            Set<Class<?>> classesWithBaseName = classesPerSimpleName.get(baseName);
            if (classesWithBaseName != null && classesWithBaseName.size() > 1) {
                return true;
            }
        }

        // Also check by content: if existing schema has different enum values
        String lookupName = baseName != null ? baseName : defName;
        Schema<?> existing = generatedSchemas.get(lookupName);
        if (existing == null) {
            // Check if it's an alias
            String aliased = schemaNameAliases.get(lookupName);
            if (aliased != null) {
                existing = generatedSchemas.get(aliased);
            }
        }
        if (existing != null && existing.getEnum() != null && defNode.has("enum")) {
            @SuppressWarnings("unchecked")
            List<Object> existingEnumValues = (List<Object>) existing.getEnum();
            List<String> newEnumValues = new ArrayList<>();
            defNode.get("enum").forEach(n -> newEnumValues.add(n.asText()));
            if (!existingEnumValues.equals(newEnumValues)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches a JSON Schema def node to the most likely class from a set of collision candidates
     * by comparing the def's property names against each class's record components or declared fields.
     *
     * @param defNode the JSON node for the def (must have a "properties" object for matching)
     * @param candidates the set of candidate classes sharing the same simple name
     * @return the best-matching class, or null if no match can be determined
     */
    private Class<?> matchDefNodeToClass(JsonNode defNode, Set<Class<?>> candidates) {
        if (defNode == null || !defNode.has("properties")) return null;

        Set<String> defProperties = new HashSet<>();
        defNode.get("properties").fieldNames().forEachRemaining(defProperties::add);
        if (defProperties.isEmpty()) return null;

        Class<?> bestMatch = null;
        int bestScore = 0;

        for (Class<?> clazz : candidates) {
            Set<String> classFields = new HashSet<>();
            if (clazz.isRecord()) {
                for (var component : clazz.getRecordComponents()) {
                    classFields.add(component.getName());
                }
            } else {
                for (var field : clazz.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        classFields.add(field.getName());
                    }
                }
            }

            // Count matching fields
            int score = 0;
            for (String prop : defProperties) {
                if (classFields.contains(prop)) score++;
            }

            if (score > bestScore) {
                bestScore = score;
                bestMatch = clazz;
            }
        }

        // Only return a match if at least one property matched and the best match is unambiguous
        return bestScore > 0 ? bestMatch : null;
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
        boolean nullableFromTypeArray = false;

        // Handle arrays with multiple types (JSON Schema 2020-12 style)
        if (node.has("type") && node.get("type").isArray()) {
            ArrayNode typeArray = (ArrayNode) node.get("type");
            type = null;
            // Find the non-null type and detect nullable
            for (JsonNode typeNode : typeArray) {
                String t = typeNode.asText();
                if ("null".equals(t)) {
                    nullableFromTypeArray = true;
                } else if (type == null) {
                    type = t;
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

        if (nullableFromTypeArray) {
            schema.addType("null");
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
            Schema<?> collapsed = convertJsonNodeToSchema(nonNullEntries.get(0), currentTypeName);
            if (collapsed != null) {
                if (collapsed.get$ref() != null) {
                    // For $ref schemas, keep as oneOf with null type (OAS 3.1 nullable $ref)
                    ComposedSchema nullableRef = new ComposedSchema();
                    Schema<?> nullPart = new Schema<>();
                    nullPart.addType("null");
                    nullableRef.setOneOf(List.of(collapsed, nullPart));
                    return nullableRef;
                }
                // For typed schemas, add "null" to types
                collapsed.addType("null");
            }
            return collapsed;
        }

        ComposedSchema schema = new ComposedSchema();
        List<Schema> entries = new ArrayList<>();
        for (JsonNode entry : composedNode) {
            entries.add((Schema) convertJsonNodeToSchema(entry, currentTypeName));
        }

        // Deduplicate: if all entries resolve to the same $ref, collapse to a single ref
        if (entries.size() > 1 && entries.stream().allMatch(e -> e.get$ref() != null)) {
            String firstRef = entries.get(0).get$ref();
            if (entries.stream().allMatch(e -> firstRef.equals(e.get$ref()))) {
                return entries.get(0);
            }
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
            schema.addType("null");
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

        if (refName == null) {
            return null;
        }

        String sanitizedRefName = sanitizeSchemaName(refName);
        // Check if this ref name has a collision and resolve using context
        Set<Class<?>> classesWithName = classesPerSimpleName.get(sanitizedRefName);

        // Also check the base name for numbered variants (e.g., "Status-1" → "Status")
        if (classesWithName == null || classesWithName.size() <= 1) {
            String baseName = stripNumericSuffix(sanitizedRefName);
            if (baseName != null) {
                Set<Class<?>> baseClasses = classesPerSimpleName.get(baseName);
                if (baseClasses != null && baseClasses.size() > 1) {
                    classesWithName = baseClasses;
                }
            }
        }

        if (classesWithName != null && classesWithName.size() > 1) {
            // Collision: find the class that belongs to currentTypeName's context
            for (Class<?> clazz : classesWithName) {
                Class<?> enclosing = clazz.getEnclosingClass();
                if (enclosing != null && enclosing.getSimpleName().equals(currentTypeName)) {
                    return resolveSchemaName(clazz);
                }
            }
            // Second pass: find a class whose enclosing class is known and reachable
            // from the current context (e.g., ComponentHealth uses HealthStatus.Status)
            for (Class<?> clazz : classesWithName) {
                Class<?> enclosing = clazz.getEnclosingClass();
                if (enclosing != null) {
                    // Check if currentTypeName is a nested class OF the enclosing class
                    Class<?> currentClass = classRegistry.get(currentTypeName);
                    if (currentClass != null && currentClass.getEnclosingClass() != null
                            && currentClass.getEnclosingClass().getSimpleName().equals(enclosing.getSimpleName())) {
                        return resolveSchemaName(clazz);
                    }
                }
            }
            // If no enclosing class match, check if there's an alias registered
            String alias = schemaNameAliases.get(sanitizedRefName);
            if (alias != null) {
                return alias;
            }
            // Last resort: use the first class's resolved name (deterministic via iteration order)
            for (Class<?> clazz : classesWithName) {
                return resolveSchemaName(clazz);
            }
        }

        return canonicalSchemaName(refName);
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
        return resolveSchemaName(type);
    }

    /**
     * Resolves the canonical schema name for a class, handling collisions
     * by fully qualifying names when multiple different classes share the same simple name.
     *
     * <p>Strategy for qualified names:</p>
     * <ul>
     *   <li>Inner/nested classes: prefix with enclosing class name (e.g., EmailNotification.Status → EmailNotificationStatus)</li>
     *   <li>Top-level classes in different packages: prefix with last package segment</li>
     * </ul>
     *
     * @param type the class to resolve a schema name for
     * @return the canonical schema name (simple name if no collision, qualified otherwise)
     */
    private String resolveSchemaName(Class<?> type) {
        // Return cached resolution if available
        String cached = resolvedSchemaNames.get(type);
        if (cached != null) {
            return cached;
        }

        String simpleName = type.getSimpleName();
        Set<Class<?>> classesWithSameName = classesPerSimpleName.get(simpleName);

        // No collision: use simple name
        if (classesWithSameName == null || classesWithSameName.size() <= 1) {
            resolvedSchemaNames.put(type, simpleName);
            return simpleName;
        }

        // Collision detected: compute qualified name
        String qualifiedName = computeQualifiedName(type);
        resolvedSchemaNames.put(type, qualifiedName);
        // Also register the qualified name in classRegistry so enrichAllGeneratedSchemas
        // and rewriteCollidingRefs can look up the class for qualified schema names.
        classRegistry.putIfAbsent(qualifiedName, type);

        // Retroactively re-qualify the other class(es) that share this simple name
        for (Class<?> otherClass : classesWithSameName) {
            if (otherClass == type) continue;
            String otherResolved = resolvedSchemaNames.get(otherClass);
            if (otherResolved == null || otherResolved.equals(simpleName)) {
                String otherQualified = computeQualifiedName(otherClass);
                resolvedSchemaNames.put(otherClass, otherQualified);
                classRegistry.putIfAbsent(otherQualified, otherClass);
            }
        }

        // Remove the ambiguous simple name from generatedSchemas — the correct qualified
        // schemas will be created by ensureAllCollisionSchemasRegistered.
        // DON'T set a global alias since the simple name maps to different qualified names
        // depending on context; the collision-aware rewrite pass in getGeneratedSchemas handles it.
        if (generatedSchemas.containsKey(simpleName)) {
            Schema<?> removedSchema = generatedSchemas.remove(simpleName);
            logger.accept("Removed ambiguous schema '" + simpleName + "' (collision detected)");
            // For non-enum types: move the schema to its owner's qualified name so it isn't lost.
            // classRegistry tells us which class originally stored this schema (first-registered wins).
            if (removedSchema != null) {
                Class<?> ownerClass = classRegistry.get(simpleName);
                if (ownerClass != null && !ownerClass.isEnum()) {
                    String ownerQualified = resolvedSchemaNames.get(ownerClass);
                    if (ownerQualified != null && !generatedSchemas.containsKey(ownerQualified)) {
                        generatedSchemas.put(ownerQualified, removedSchema);
                    }
                }
            }
        }

        return qualifiedName;
    }

    /**
     * Computes a qualified schema name for a class that has a name collision.
     * Uses the enclosing class name as prefix for inner classes, or the last
     * package segment for top-level classes.
     */
    private String computeQualifiedName(Class<?> type) {
        Class<?> enclosing = type.getEnclosingClass();
        if (enclosing != null) {
            // Inner/nested class: EnclosingSimpleName + SimpleName
            return enclosing.getSimpleName() + type.getSimpleName();
        }

        // Top-level class: use last meaningful package segment as prefix
        String packageName = type.getPackageName();
        if (packageName != null && !packageName.isEmpty()) {
            String[] segments = packageName.split("\\.");
            // Find last segment that isn't a generic term
            for (int i = segments.length - 1; i >= 0; i--) {
                String segment = segments[i];
                if (!segment.equals("model") && !segment.equals("domain")
                        && !segment.equals("dto") && !segment.equals("api")) {
                    String prefix = Character.toUpperCase(segment.charAt(0)) + segment.substring(1);
                    return prefix + type.getSimpleName();
                }
            }
        }

        // Fallback: use full canonical name with dots replaced
        return type.getCanonicalName().replace('.', '_');
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

        // Collision-aware ref rewrite: fix pre-collision $refs that point to ambiguous
        // simple names (e.g., "Status") by resolving them using the owning class context.
        rewriteCollidingRefs(result);

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

        // Recurse into oneOf / anyOf / allOf (these fields are on base Schema class)
        if (schema.getOneOf() != null) {
            schema.getOneOf().forEach(s -> rewriteRefs(s, replacements));
        }
        if (schema.getAnyOf() != null) {
            schema.getAnyOf().forEach(s -> rewriteRefs(s, replacements));
        }
        if (schema.getAllOf() != null) {
            schema.getAllOf().forEach(s -> rewriteRefs(s, replacements));
        }
    }

    /**
     * Rewrites $refs that point to colliding simple names by resolving them using the
     * owning class context. For example, if "SmsNotification" schema has a property with
     * $ref pointing to "Status" (set before collision was detected), this resolves it to
     * "SmsNotificationStatus" by finding that SmsNotification has an inner Status class.
     */
    private void rewriteCollidingRefs(Map<String, Schema<?>> schemas) {
        String prefix = "#/components/schemas/";
        for (Map.Entry<String, Schema<?>> entry : schemas.entrySet()) {
            String schemaName = entry.getKey();
            Class<?> ownerClass = classRegistry.get(schemaName);
            if (ownerClass == null) continue;
            rewriteCollidingRefsInSchema(entry.getValue(), ownerClass, prefix);
        }
    }

    private void rewriteCollidingRefsInSchema(Schema<?> schema, Class<?> ownerClass, String prefix) {
        if (schema == null) return;

        if (schema.get$ref() != null) {
            String ref = schema.get$ref();
            if (ref.startsWith(prefix)) {
                String name = ref.substring(prefix.length());
                Set<Class<?>> collision = classesPerSimpleName.get(name);
                if (collision != null && collision.size() > 1) {
                    // Find the class that's an inner class of ownerClass
                    for (Class<?> clazz : collision) {
                        if (clazz.getEnclosingClass() == ownerClass) {
                            schema.set$ref(prefix + resolveSchemaName(clazz));
                            break;
                        }
                    }
                    // If not found, check if ownerClass is itself nested inside the
                    // collision candidate's enclosing class (e.g., ComponentHealth is nested
                    // inside HealthStatus, which is also the enclosing class of HealthStatus.Status)
                    if (schema.get$ref().equals(ref)) {
                        Class<?> enclosingOfOwner = ownerClass.getEnclosingClass();
                        if (enclosingOfOwner != null) {
                            for (Class<?> clazz : collision) {
                                if (clazz.getEnclosingClass() == enclosingOfOwner) {
                                    schema.set$ref(prefix + resolveSchemaName(clazz));
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (schema.getProperties() != null) {
            for (Object prop : schema.getProperties().values()) {
                rewriteCollidingRefsInSchema((Schema<?>) prop, ownerClass, prefix);
            }
        }
        Object addProps = schema.getAdditionalProperties();
        if (addProps instanceof Schema) {
            rewriteCollidingRefsInSchema((Schema<?>) addProps, ownerClass, prefix);
        }
        if (schema.getItems() != null) {
            rewriteCollidingRefsInSchema(schema.getItems(), ownerClass, prefix);
        }
        if (schema.getOneOf() != null) {
            schema.getOneOf().forEach(s -> rewriteCollidingRefsInSchema(s, ownerClass, prefix));
        }
        if (schema.getAnyOf() != null) {
            schema.getAnyOf().forEach(s -> rewriteCollidingRefsInSchema(s, ownerClass, prefix));
        }
        if (schema.getAllOf() != null) {
            schema.getAllOf().forEach(s -> rewriteCollidingRefsInSchema(s, ownerClass, prefix));
        }
    }

    /**
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
        if (schema.getOneOf() != null) {
            schema.getOneOf().forEach(this::stripInternalRefs);
        }
        if (schema.getAnyOf() != null) {
            schema.getAnyOf().forEach(this::stripInternalRefs);
        }
        if (schema.getAllOf() != null) {
            schema.getAllOf().forEach(this::stripInternalRefs);
        }
    }

    /**
     * Clears all generated schemas.
     */
    public void clearSchemas() {
        generatedSchemas.clear();
        schemaNameAliases.clear();
        subtypeToParentMap.clear();
        classesPerSimpleName.clear();
        resolvedSchemaNames.clear();
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
