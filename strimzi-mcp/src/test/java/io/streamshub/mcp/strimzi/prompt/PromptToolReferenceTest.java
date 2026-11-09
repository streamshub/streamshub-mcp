/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.prompt;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the contract between prompt text and the tools it tells the client to call: every
 * {@code `tool_name(arg='x')`} example in a prompt must name a real tool and only real
 * {@code @ToolArg} parameters. A wrong argument name makes an LLM client send a call the
 * server rejects, so renaming a tool argument must fail this test until the prompts follow.
 */
class PromptToolReferenceTest {

    PromptToolReferenceTest() {
    }

    private static final String TOOL_PACKAGE_PATH = "io/streamshub/mcp/strimzi/tool";
    private static final Path PROMPT_SOURCE_DIR =
        Paths.get("src/main/java/io/streamshub/mcp/strimzi/prompt");

    /** Matches an inline tool call in prompt text, e.g. {@code `get_kafka_metrics(clusterName='%s', category='x')`}. */
    private static final Pattern TOOL_CALL = Pattern.compile("`([a-z]+(?:_[a-z0-9]+)+)\\(([^)]*)\\)");
    private static final Pattern ARG_NAME = Pattern.compile("(\\w+)\\s*=");

    @Test
    void promptToolCallsUseRealToolsAndArguments() throws Exception {
        Map<String, Set<String>> toolArgs = discoverToolArguments();
        assertFalse(toolArgs.isEmpty(), "No @Tool methods discovered");
        assertTrue(Files.isDirectory(PROMPT_SOURCE_DIR),
            "Prompt sources not found at " + PROMPT_SOURCE_DIR.toAbsolutePath());

        List<String> violations = new ArrayList<>();
        int checkedCalls = 0;

        for (Path file : promptSources()) {
            String text = Files.readString(file);
            Matcher call = TOOL_CALL.matcher(text);
            while (call.find()) {
                String toolName = call.group(1);
                Set<String> allowed = toolArgs.get(toolName);
                String where = file.getFileName() + ": " + toolName + "(...)";
                if (allowed == null) {
                    violations.add(where + " is not a registered tool");
                    continue;
                }
                checkedCalls++;
                // "%s" is a format placeholder (optional namespace/cluster fragment), not part of a name
                Matcher arg = ARG_NAME.matcher(call.group(2).replace("%s", " "));
                while (arg.find()) {
                    if (!allowed.contains(arg.group(1))) {
                        violations.add(where + " uses unknown argument '" + arg.group(1)
                            + "' (valid: " + allowed + ")");
                    }
                }
            }
        }

        assertTrue(checkedCalls > 0, "No tool calls found in prompt sources; the matcher is broken");
        if (!violations.isEmpty()) {
            fail("Prompt text references tools or arguments that do not exist:\n  - "
                + String.join("\n  - ", violations));
        }
    }

    private static List<Path> promptSources() throws IOException {
        try (Stream<Path> files = Files.list(PROMPT_SOURCE_DIR)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /**
     * Maps each registered tool name to its user-supplied argument names (the {@code @ToolArg}
     * parameters; framework-injected parameters such as {@code Progress} are excluded).
     */
    private static Map<String, Set<String>> discoverToolArguments() throws Exception {
        Class<?> anchor = Class.forName("io.streamshub.mcp.strimzi.tool.kafka.KafkaTools");
        URL location = anchor.getProtectionDomain().getCodeSource().getLocation();
        Path toolRoot = Paths.get(location.toURI()).resolve(TOOL_PACKAGE_PATH);
        Path classesRoot = Paths.get(location.toURI());

        Map<String, Set<String>> result = new HashMap<>();
        try (Stream<Path> walk = Files.walk(toolRoot)) {
            List<Path> classFiles = walk
                .filter(p -> p.toString().endsWith(".class") && !p.getFileName().toString().contains("$"))
                .toList();
            for (Path classFile : classFiles) {
                String relative = classesRoot.relativize(classFile).toString();
                String className = relative.substring(0, relative.length() - ".class".length())
                    .replace('/', '.');
                Class<?> clazz = Class.forName(className);
                for (Method method : clazz.getDeclaredMethods()) {
                    Tool tool = method.getAnnotation(Tool.class);
                    if (tool == null) {
                        continue;
                    }
                    Set<String> args = new HashSet<>();
                    for (Parameter parameter : method.getParameters()) {
                        if (parameter.isAnnotationPresent(ToolArg.class)) {
                            args.add(parameter.getName());
                        }
                    }
                    result.put(tool.name(), args);
                }
            }
        }
        return result;
    }
}
