/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaBootstrapResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaPvcInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the serialized DTO conventions from AGENTS.md over every serialized record in the
 * {@code io.streamshub.mcp.strimzi.dto} and {@code io.streamshub.mcp.common.dto} packages (and their
 * sub-packages, including nested records). A record counts as serialized when it is annotated with
 * {@link JsonInclude} (the repo's marker for serialized DTOs) or when any of its components carries
 * {@link JsonProperty} (some responses use {@code @JsonProperty} without {@code @JsonInclude}):
 * <ul>
 *     <li>no public bean-style {@code getX()}/{@code isX()} method other than a record component accessor,
 *     because Jackson serializes it as an extra camelCase property (methods marked {@link JsonIgnore}, the
 *     escape hatch AGENTS.md allows, are not serialized and are skipped);</li>
 *     <li>every record component carries {@link JsonProperty} with a snake_case name.</li>
 * </ul>
 * Internal carriers without Jackson annotations (for example {@code MetricsQueryParams}) are out of scope.
 * Classes are found through the code source of a known DTO in each package, so the scan works on a
 * classes directory (reactor build) and on an installed jar.
 */
class SerializedRecordConventionsTest {

    private static final String STRIMZI_DTO_PACKAGE = "io.streamshub.mcp.strimzi.dto";
    private static final String COMMON_DTO_PACKAGE = "io.streamshub.mcp.common.dto";
    private static final Pattern SNAKE_CASE = Pattern.compile("^[a-z0-9_]+$");
    private static final Pattern BEAN_ACCESSOR = Pattern.compile("^(get|is)[A-Z].*");

    /**
     * Offenders that already existed when this guard was added, skipped by name until they are fixed.
     * Each entry is an offender key exactly as the failure message prints it. Keep this empty: fix new
     * records instead of adding them here.
     */
    private static final Set<String> KNOWN_OFFENDERS = Set.of();

    SerializedRecordConventionsTest() {
    }

    @Test
    void testSerializedRecordsUseSnakeCaseJsonPropertiesAndNoBeanGetters() throws Exception {
        List<Class<?>> records = new ArrayList<>();
        records.addAll(serializedRecords(KafkaClusterResponse.class, STRIMZI_DTO_PACKAGE));
        records.addAll(serializedRecords(ConditionInfo.class, COMMON_DTO_PACKAGE));

        // The scan must reach both packages, sub-packages and nested records, and records that use
        // @JsonProperty without @JsonInclude, or the guard checks nothing.
        List<Class<?>> expected = List.of(KafkaClusterResponse.class, KafkaClusterResponse.ClusterSecurityInfo.class,
            KafkaPvcInfo.class, ConditionInfo.class, PodSummaryResponse.class, KafkaBootstrapResponse.class,
            KafkaBootstrapResponse.BootstrapServerInfo.class, KafkaClusterPodsResponse.class);
        List<Class<?>> missing = expected.stream().filter(type -> !records.contains(type)).toList();
        assertTrue(missing.isEmpty(), "Scan missed known serialized records: " + missing);

        Set<String> offenders = new TreeSet<>();
        for (Class<?> type : records) {
            offenders.addAll(beanGetterOffenders(type));
            offenders.addAll(jsonPropertyOffenders(type));
        }
        offenders.removeAll(KNOWN_OFFENDERS);

        assertTrue(offenders.isEmpty(), "Serialized records break the DTO conventions in AGENTS.md:\n  "
            + String.join("\n  ", offenders));
    }

    /**
     * Lists public, non-static, no-argument {@code getX()}/{@code isX()} methods that are not record component
     * accessors, including default methods inherited from interfaces.
     *
     * @param type the record to check
     * @return one offender key per method
     */
    private static List<String> beanGetterOffenders(final Class<?> type) {
        Set<String> accessors = Arrays.stream(type.getRecordComponents())
            .map(RecordComponent::getName)
            .collect(Collectors.toSet());
        return Arrays.stream(type.getMethods())
            .filter(m -> m.getDeclaringClass() != Object.class)
            .filter(m -> !Modifier.isStatic(m.getModifiers()) && !m.isSynthetic() && !m.isBridge())
            .filter(m -> m.getParameterCount() == 0)
            .filter(m -> BEAN_ACCESSOR.matcher(m.getName()).matches())
            .filter(m -> !accessors.contains(m.getName()))
            .filter(m -> !isJsonIgnored(m))
            .map(m -> type.getName() + "#" + m.getName() + "() is a bean-style getter, serialized as a camelCase "
                + "property")
            .toList();
    }

    /**
     * Lists record components without a snake_case {@link JsonProperty}, read through {@link #jsonProperty}.
     *
     * @param type the record to check
     * @return one offender key per component
     */
    private static List<String> jsonPropertyOffenders(final Class<?> type) throws NoSuchFieldException {
        List<String> offenders = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            JsonProperty property = jsonProperty(type, component);
            String key = type.getName() + "#" + component.getName();
            if (property == null) {
                offenders.add(key + " has no @JsonProperty");
            } else if (!SNAKE_CASE.matcher(property.value()).matches()) {
                offenders.add(key + " has @JsonProperty(\"" + property.value() + "\"), not snake_case");
            }
        }
        return offenders;
    }

    /**
     * Reads a component's {@link JsonProperty}. The annotation does not target record components, so the
     * compiler propagates it to the accessor and the field; the accessor wins, the field is the fallback.
     *
     * @param type      the record declaring the component
     * @param component the record component
     * @return the annotation, or null when the component has none
     */
    private static JsonProperty jsonProperty(final Class<?> type, final RecordComponent component)
            throws NoSuchFieldException {
        JsonProperty property = component.getAccessor().getAnnotation(JsonProperty.class);
        return property != null
            ? property
            : type.getDeclaredField(component.getName()).getAnnotation(JsonProperty.class);
    }

    /**
     * Tells whether a record is serialized: annotated with {@link JsonInclude}, or with {@link JsonProperty}
     * on any component.
     *
     * @param type the record
     * @return true when the record is a serialized DTO
     */
    private static boolean isSerialized(final Class<?> type) throws NoSuchFieldException {
        if (type.isAnnotationPresent(JsonInclude.class)) {
            return true;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            if (jsonProperty(type, component) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJsonIgnored(final Method method) {
        JsonIgnore ignore = method.getAnnotation(JsonIgnore.class);
        return ignore != null && ignore.value();
    }

    /**
     * Loads every serialized record (see {@link #isSerialized}) under a package, searching the classes
     * directory or jar that holds {@code anchor}.
     *
     * @param anchor      a class from the same code source as the package
     * @param packageName the root package to scan, including sub-packages
     * @return the serialized records found
     */
    private static List<Class<?>> serializedRecords(final Class<?> anchor, final String packageName)
            throws IOException, URISyntaxException, ClassNotFoundException, NoSuchFieldException {
        List<Class<?>> records = new ArrayList<>();
        for (String className : classNamesUnder(anchor, packageName)) {
            Class<?> type = Class.forName(className, false, anchor.getClassLoader());
            if (type.isRecord() && isSerialized(type)) {
                records.add(type);
            }
        }
        return records;
    }

    private static List<String> classNamesUnder(final Class<?> anchor, final String packageName)
            throws IOException, URISyntaxException {
        Path codeSource = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        String packagePath = packageName.replace('.', '/') + "/";
        List<String> entries = new ArrayList<>();
        if (Files.isDirectory(codeSource)) {
            try (Stream<Path> files = Files.walk(codeSource.resolve(packagePath))) {
                files.map(file -> codeSource.relativize(file).toString().replace('\\', '/'))
                    .forEach(entries::add);
            }
        } else {
            try (JarFile jar = new JarFile(codeSource.toFile())) {
                jar.stream()
                    .map(JarEntry::getName)
                    .filter(name -> name.startsWith(packagePath))
                    .forEach(entries::add);
            }
        }
        return entries.stream()
            .filter(name -> name.endsWith(".class") && !name.endsWith("package-info.class"))
            .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
            .toList();
    }
}
