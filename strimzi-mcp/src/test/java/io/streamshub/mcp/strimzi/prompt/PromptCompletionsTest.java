/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.prompt;

import io.quarkiverse.mcp.server.CompleteContext;
import io.quarkiverse.mcp.server.CompletePrompt;
import io.quarkiverse.mcp.server.Prompt;
import io.streamshub.mcp.strimzi.service.CompletionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PromptCompletions}: every {@code @Prompt} has a completion handler and
 * every handler delegates to {@link CompletionService}.
 */
class PromptCompletionsTest {

    PromptCompletionsTest() {
    }

    @Test
    void testEveryPromptHasACompletionHandler() throws Exception {
        Set<String> promptNames = new HashSet<>();
        for (Class<?> promptClass : promptClasses()) {
            for (Method method : promptClass.getDeclaredMethods()) {
                Prompt prompt = method.getAnnotation(Prompt.class);
                if (prompt != null) {
                    promptNames.add(prompt.name());
                }
            }
        }
        Set<String> completed = new HashSet<>();
        for (Method method : PromptCompletions.class.getDeclaredMethods()) {
            CompletePrompt completePrompt = method.getAnnotation(CompletePrompt.class);
            if (completePrompt != null) {
                assertTrue(completed.add(completePrompt.value()),
                    "Duplicate completion handler for prompt " + completePrompt.value());
            }
        }

        assertFalse(promptNames.isEmpty(), "No @Prompt methods discovered");
        Set<String> missing = new HashSet<>(promptNames);
        missing.removeAll(completed);
        Set<String> orphaned = new HashSet<>(completed);
        orphaned.removeAll(promptNames);
        assertTrue(missing.isEmpty(), "Prompts without a completion handler: " + missing);
        assertTrue(orphaned.isEmpty(), "Completion handlers for unknown prompts: " + orphaned);
    }

    @Test
    void testEveryHandlerDelegatesToCompletionService() throws Exception {
        CompletionService service = Mockito.mock(CompletionService.class);
        CompleteContext context = Mockito.mock(CompleteContext.class);
        when(service.completeByArgumentName("pre", context)).thenReturn(List.of("prefix-a"));
        PromptCompletions completions = new PromptCompletions();
        completions.completionService = service;

        int handlers = 0;
        for (Method method : PromptCompletions.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(CompletePrompt.class)) {
                handlers++;
                assertEquals(List.of("prefix-a"), method.invoke(completions, "pre", context),
                    method.getName() + " must return the CompletionService result");
            }
        }
        assertTrue(handlers > 0);
        Mockito.verify(service, Mockito.times(handlers)).completeByArgumentName("pre", context);
    }

    private static List<Class<?>> promptClasses() throws IOException, ClassNotFoundException, URISyntaxException {
        List<Class<?>> classes = new ArrayList<>();
        Path dir = Paths.get(PromptCompletions.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .resolve(PromptCompletions.class.getPackageName().replace('.', '/'));
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith("Prompt.class")).toList()) {
                String simple = file.getFileName().toString().replace(".class", "");
                classes.add(Class.forName(PromptCompletions.class.getPackageName() + "." + simple));
            }
        }
        return classes;
    }
}
