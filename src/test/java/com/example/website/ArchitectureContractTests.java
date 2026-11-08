package com.example.website;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.persistence.EntityManagerFactory;
import javax.persistence.metamodel.EntityType;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Package moves must preserve HTTP, JSON and persistent entity contracts. */
@SpringBootTest
class ArchitectureContractTests {
    @Autowired @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManagerFactory entities;

    @Test
    void publicContractsRemainCompatibleAcrossInternalRefactoring() throws Exception {
        Map<String, Object> actual = new TreeMap<>();
        Map<String, Object> routes = new TreeMap<>();
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            if (!handler.getBeanType().getName().startsWith("com.example.website.")) return;
            List<Object> parameters = new ArrayList<>();
            for (java.lang.reflect.Parameter parameter : handler.getMethod().getParameters()) {
                Map<String, Object> p = new TreeMap<>();
                p.put("type", normalize(parameter.getParameterizedType().getTypeName()));
                p.put("annotations", annotations(parameter.getAnnotations()));
                parameters.add(p);
            }
            Map<String, Object> route = new TreeMap<>();
            route.put("returns", normalize(handler.getMethod().getGenericReturnType().getTypeName()));
            route.put("parameters", parameters);
            routes.put(mapping.toString(), route);
        });
        actual.put("routes", routes);

        Map<String, Object> models = new TreeMap<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        CachingMetadataReaderFactory metadata = new CachingMetadataReaderFactory(resolver);
        for (Resource resource : resolver.getResources("classpath*:com/example/website/**/*.class")) {
            String name = metadata.getMetadataReader(resource).getClassMetadata().getClassName();
            if (!name.contains(".dto.") || name.contains("$")) continue;
            Class<?> type = Class.forName(name);
            if (type.isEnum()) {
                models.put(type.getSimpleName(), mapper.valueToTree(type.getEnumConstants()));
                continue;
            }
            Map<String, Object> shape = new TreeMap<>();
            for (boolean serialization : Arrays.asList(true, false)) {
                BeanDescription description = serialization
                        ? mapper.getSerializationConfig().introspect(mapper.constructType(type))
                        : mapper.getDeserializationConfig().introspect(mapper.constructType(type));
                Map<String, Object> properties = new TreeMap<>();
                for (BeanPropertyDefinition property : description.findProperties()) {
                    properties.put(property.getName(), Arrays.asList(
                            normalize(property.getPrimaryType().toCanonical()),
                            property.isRequired(), property.couldSerialize(), property.couldDeserialize()));
                }
                shape.put(serialization ? "response" : "request", properties);
            }
            assertNull(models.put(type.getSimpleName(), shape), "DTO names must remain unambiguous");
        }
        actual.put("jsonModels", models);

        Map<String, Object> tables = new TreeMap<>();
        for (EntityType<?> entity : entities.getMetamodel().getEntities()) {
            Map<String, Object> table = new TreeMap<>();
            table.put("annotations", annotations(entity.getJavaType().getAnnotations()));
            Map<String, Object> fields = new TreeMap<>();
            for (Field field : entity.getJavaType().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                fields.put(field.getName(), Arrays.asList(normalize(field.getGenericType().getTypeName()),
                        annotations(field.getAnnotations())));
            }
            table.put("fields", fields);
            tables.put(entity.getName(), table);
        }
        actual.put("entities", tables);
        Path snapshot = Paths.get("src/test/resources/contracts/public-api-and-entities.json");
        if (Boolean.getBoolean("writeApiContract")) {
            Files.createDirectories(snapshot.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(snapshot.toFile(), actual);
        }
        assertTrue(Files.isRegularFile(snapshot), "Versioned API baseline is required");
        assertEquals(mapper.readTree(snapshot.toFile()), mapper.valueToTree(actual));
        assertTrue(routes.size() > 100);
        assertTrue(models.size() > 100);
        assertEquals(34, tables.size());
    }

    private static List<String> annotations(Annotation[] values) {
        List<String> result = new ArrayList<>();
        for (Annotation value : values) {
            Map<String, String> attributes = new TreeMap<>();
            for (java.lang.reflect.Method member : value.annotationType().getDeclaredMethods()) {
                try {
                    attributes.put(member.getName(), annotationValue(member.invoke(value)));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            result.add(value.annotationType().getName() + attributes);
        }
        Collections.sort(result);
        return result;
    }

    private static String annotationValue(Object value) {
        if (value instanceof Class) return normalize(((Class<?>) value).getName());
        if (value instanceof Annotation) return annotations(new Annotation[] {(Annotation) value}).get(0);
        if (value.getClass().isArray()) {
            List<String> elements = new ArrayList<>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++)
                elements.add(annotationValue(java.lang.reflect.Array.get(value, i)));
            return elements.toString();
        }
        return normalize(value.toString());
    }

    private static String normalize(String value) {
        return value.replaceAll("com\\.example\\.website\\.(?:[a-z][a-zA-Z0-9_]*\\.)+", "");
    }
}
