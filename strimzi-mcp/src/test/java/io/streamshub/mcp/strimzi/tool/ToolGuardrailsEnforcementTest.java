/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolGuardrails;
import io.streamshub.mcp.common.guardrail.ArgumentSanitizationGuardrail;
import io.streamshub.mcp.common.guardrail.GeneralRateLimitGuardrail;
import io.streamshub.mcp.common.guardrail.LogRateLimitGuardrail;
import io.streamshub.mcp.common.guardrail.LogRedactionGuardrail;
import io.streamshub.mcp.common.guardrail.MetricsRateLimitGuardrail;
import io.streamshub.mcp.common.guardrail.ResponseSizeLimitGuardrail;
import io.streamshub.mcp.common.observability.MeasuredTool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Build-time enforcement test: ensures all {@code @Tool} methods declare proper guardrails
 * and response shapes. This prevents new tools from silently shipping unguarded.
 */
class ToolGuardrailsEnforcementTest {

    ToolGuardrailsEnforcementTest() {
    }

    private static final String TOOL_PACKAGE = "io.streamshub.mcp.strimzi.tool";
    private static final int EXPECTED_TOOL_METHOD_COUNT = 56;

    // Known tool sub-packages - add new sub-packages here when creating new tool categories
    private static final List<String> TOOL_SUBPACKAGES = List.of(
        "diagnostic", "draincleaner", "kafka", "kafkabridge", "kafkaconnect",
        "kafkamirrormaker2", "kafkanodepool", "kafkarebalance", "kafkatopic",
        "kafkauser", "metrics", "operator"
    );

    private static final Set<Class<?>> REQUIRED_OUTPUT_GUARDRAILS = Set.of(
        LogRedactionGuardrail.class,
        ResponseSizeLimitGuardrail.class
    );

    private static final Set<Class<?>> REQUIRED_INPUT_GUARDRAILS = Set.of(
        ArgumentSanitizationGuardrail.class
    );

    private static final Set<Class<?>> RATE_LIMIT_GUARDRAILS = Set.of(
        GeneralRateLimitGuardrail.class,
        LogRateLimitGuardrail.class,
        MetricsRateLimitGuardrail.class
    );

    private static final Set<String> FORBIDDEN_RETURN_TYPE_PREFIXES = Set.of(
        "java.util.List",
        "java.util.Collection",
        "java.util.Set",
        "io.quarkiverse.mcp.server.Content",
        "io.quarkiverse.mcp.server.TextContent",
        "io.quarkiverse.mcp.server.ImageContent",
        "io.quarkiverse.mcp.server.ToolResponse",
        "io.smallrye.mutiny.Uni"
    );

    @Test
    void allToolMethodsHaveRequiredGuardrailsAndResponseShape() throws Exception {
        List<Class<?>> toolClasses = discoverToolClasses();
        assertTrue(toolClasses.size() > 0,
            "Package scan must discover at least one tool class");

        List<Method> toolMethods = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        for (Class<?> toolClass : toolClasses) {
            // Check 0: class-level @MeasuredTool for tool-call metrics
            if (!toolClass.isAnnotationPresent(MeasuredTool.class)) {
                violations.add(toolClass.getSimpleName() + " lacks class-level @MeasuredTool");
            }

            for (Method method : toolClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    toolMethods.add(method);
                    String methodName = toolClass.getSimpleName() + "#" + method.getName();

                    // Check 1: @ToolGuardrails presence
                    ToolGuardrails guardrails = method.getAnnotation(ToolGuardrails.class);
                    if (guardrails == null) {
                        violations.add(methodName + " lacks @ToolGuardrails");
                        continue;
                    }

                    // Check 2: output guardrails
                    Set<Class<?>> outputGuardrails = new HashSet<>(Arrays.asList(guardrails.output()));
                    for (Class<?> required : REQUIRED_OUTPUT_GUARDRAILS) {
                        if (!outputGuardrails.contains(required)) {
                            violations.add(methodName + " output missing " + required.getSimpleName());
                        }
                    }

                    // Check 3: input guardrails
                    Set<Class<?>> inputGuardrails = new HashSet<>(Arrays.asList(guardrails.input()));
                    for (Class<?> required : REQUIRED_INPUT_GUARDRAILS) {
                        if (!inputGuardrails.contains(required)) {
                            violations.add(methodName + " input missing " + required.getSimpleName());
                        }
                    }

                    // Check 4: exactly one rate limit guardrail
                    long rateLimitCount = inputGuardrails.stream()
                        .filter(RATE_LIMIT_GUARDRAILS::contains)
                        .count();
                    if (rateLimitCount != 1) {
                        violations.add(methodName + " has " + rateLimitCount +
                            " rate limit guardrails (expected exactly 1)");
                    }

                    // Check 4a: input guardrail ORDER
                    checkInputGuardrailOrder(methodName, guardrails.input(), violations);

                    // Check 4b: output guardrail ORDER
                    checkOutputGuardrailOrder(methodName, guardrails.output(), violations);

                    // Check 5: @Tool(structuredContent = true)
                    Tool toolAnnotation = method.getAnnotation(Tool.class);
                    if (!toolAnnotation.structuredContent()) {
                        violations.add(methodName + " has structuredContent=false (expected true)");
                    }

                    // Check 6: return type is not forbidden
                    String returnType = method.getReturnType().getName();
                    for (String forbidden : FORBIDDEN_RETURN_TYPE_PREFIXES) {
                        if (returnType.startsWith(forbidden)) {
                            violations.add(methodName + " returns " + returnType +
                                " (expected POJO/record, not List/Collection/Content/ToolResponse/Uni)");
                        }
                    }
                }
            }
        }

        // Check 7: total count
        assertEquals(EXPECTED_TOOL_METHOD_COUNT, toolMethods.size(),
            "Expected exactly " + EXPECTED_TOOL_METHOD_COUNT + " @Tool methods across all tool classes");

        // Report violations
        if (!violations.isEmpty()) {
            fail("Tool guardrail enforcement failures:\n  - " + String.join("\n  - ", violations));
        }
    }

    private void checkInputGuardrailOrder(final String methodName, final Class<?>[] inputArray,
                                          final List<String> violations) {
        if (inputArray.length < 2) {
            return;
        }
        if (!RATE_LIMIT_GUARDRAILS.contains(inputArray[0])) {
            violations.add(methodName + " input[0] is " + inputArray[0].getSimpleName()
                + " (expected rate-limit guardrail first)");
        }
        if (!inputArray[1].equals(ArgumentSanitizationGuardrail.class)) {
            violations.add(methodName + " input[1] is " + inputArray[1].getSimpleName()
                + " (expected ArgumentSanitizationGuardrail second)");
        }
    }

    private void checkOutputGuardrailOrder(final String methodName, final Class<?>[] outputArray,
                                           final List<String> violations) {
        if (outputArray.length < 2) {
            return;
        }
        if (!outputArray[0].equals(LogRedactionGuardrail.class)) {
            violations.add(methodName + " output[0] is " + outputArray[0].getSimpleName()
                + " (expected LogRedactionGuardrail first)");
        }
        if (!outputArray[1].equals(ResponseSizeLimitGuardrail.class)) {
            violations.add(methodName + " output[1] is " + outputArray[1].getSimpleName()
                + " (expected ResponseSizeLimitGuardrail second)");
        }
    }

    /**
     * Discovers all classes in the tool package that have at least one {@code @Tool} method.
     * Scans each known sub-package for .class files rather than relying on a hardcoded class list.
     *
     * <p>Uses the Maven target/classes directory structure directly to avoid classloader
     * ambiguity between test and production classes.</p>
     *
     * @return list of classes with {@code @Tool} methods
     */
    private List<Class<?>> discoverToolClasses() throws Exception {
        List<Class<?>> classes = new ArrayList<>();

        // Find target/classes by resolving from a known production class's code source
        Class<?> knownToolClass = Class.forName("io.streamshub.mcp.strimzi.tool.kafka.KafkaTools");
        URL classLocation = knownToolClass.getProtectionDomain().getCodeSource().getLocation();
        Path targetClasses = Paths.get(classLocation.toURI());

        // Navigate to the tool package directory
        Path toolPackageRoot = targetClasses.resolve(TOOL_PACKAGE.replace('.', '/'));

        for (String subpackage : TOOL_SUBPACKAGES) {
            Path subpackageDir = toolPackageRoot.resolve(subpackage);

            if (!Files.exists(subpackageDir) || !Files.isDirectory(subpackageDir)) {
                fail("Sub-package directory does not exist: " + subpackageDir);
            }

            String fullPackage = TOOL_PACKAGE + "." + subpackage;
            try (Stream<Path> walk = Files.list(subpackageDir)) {
                walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .forEach(path -> processClassFile(fullPackage, path, classes));
            }
        }

        return classes;
    }

    private void processClassFile(final String packageName, final Path classFile, final List<Class<?>> classes) {
        try {
            String fileName = classFile.getFileName().toString();
            String className = packageName + "." + fileName.replaceAll("\\.class$", "");

            // Skip inner classes
            if (fileName.contains("$")) {
                return;
            }

            Class<?> clazz = Class.forName(className);
            // Only include classes that have at least one @Tool method
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    classes.add(clazz);
                    break;
                }
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Skip classes that can't be loaded
        }
    }
}
