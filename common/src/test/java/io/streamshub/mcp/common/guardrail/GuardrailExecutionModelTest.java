/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import io.quarkiverse.mcp.server.ExecutionModel;
import io.quarkiverse.mcp.server.SupportedExecutionModels;
import io.quarkiverse.mcp.server.ToolInputGuardrail;
import io.quarkiverse.mcp.server.ToolOutputGuardrail;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Enforces that every concrete guardrail bean declares
 * {@code @SupportedExecutionModels({WORKER_THREAD, VIRTUAL_THREAD})}, matching the
 * project invariant that no tool runs on the event loop. Prevents new guardrails
 * from silently defaulting to a different (permissive) execution-model contract.
 */
class GuardrailExecutionModelTest {

    GuardrailExecutionModelTest() {
    }

    private static final Set<ExecutionModel> EXPECTED =
        Set.of(ExecutionModel.WORKER_THREAD, ExecutionModel.VIRTUAL_THREAD);

    @Test
    void allConcreteGuardrailsDeclareExpectedExecutionModels() throws Exception {
        List<Class<?>> guardrails = discoverGuardrailClasses();
        assertTrue(guardrails.size() >= 6,
            "Package scan must discover all guardrail beans, found: " + guardrails.size());

        List<String> violations = new ArrayList<>();
        for (Class<?> clazz : guardrails) {
            SupportedExecutionModels ann = clazz.getDeclaredAnnotation(SupportedExecutionModels.class);
            if (ann == null) {
                violations.add(clazz.getSimpleName() + " is missing @SupportedExecutionModels");
                continue;
            }
            Set<ExecutionModel> declared = Set.copyOf(Arrays.asList(ann.value()));
            if (!declared.equals(EXPECTED)) {
                violations.add(clazz.getSimpleName() + " declares " + declared + " (expected " + EXPECTED + ")");
            }
        }

        if (!violations.isEmpty()) {
            fail("Guardrail execution-model violations:\n  - " + String.join("\n  - ", violations));
        }
    }

    private List<Class<?>> discoverGuardrailClasses() throws Exception {
        Class<?> known = Class.forName("io.streamshub.mcp.common.guardrail.LogRedactionGuardrail");
        URL location = known.getProtectionDomain().getCodeSource().getLocation();
        Path targetClasses = Paths.get(location.toURI());
        Path pkgDir = targetClasses.resolve("io/streamshub/mcp/common/guardrail");

        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> walk = Files.list(pkgDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".class"))
                .filter(p -> !p.getFileName().toString().contains("$"))
                .forEach(p -> collectIfGuardrail(p, classes));
        }
        return classes;
    }

    private void collectIfGuardrail(final Path classFile, final List<Class<?>> classes) {
        String fileName = classFile.getFileName().toString();
        String className = "io.streamshub.mcp.common.guardrail." + fileName.replaceAll("\\.class$", "");
        try {
            Class<?> clazz = Class.forName(className);
            boolean isGuardrail = ToolInputGuardrail.class.isAssignableFrom(clazz)
                || ToolOutputGuardrail.class.isAssignableFrom(clazz);
            boolean concrete = !clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers());
            if (isGuardrail && concrete) {
                classes.add(clazz);
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // skip unloadable classes
        }
    }
}
