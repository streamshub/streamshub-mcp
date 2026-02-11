/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Service that handles application startup logic and determines running mode.
 */
@ApplicationScoped
public class ApplicationStartupService {

    private static final Logger LOG = Logger.getLogger(ApplicationStartupService.class);

    @Inject
    LlmConfigurationDetector llmDetector;

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    String httpPort;

    private boolean mcpOnlyMode = false;

    ApplicationStartupService() {
    }

    void onStart(@Observes StartupEvent ev) {
        detectRunningMode();
        displayStartupBanner();
    }

    /**
     * Check whether the application is running in MCP-only mode.
     *
     * @return true if running in MCP-only mode without LLM
     */
    public boolean isMcpOnlyMode() {
        return mcpOnlyMode;
    }

    private void detectRunningMode() {
        boolean llmAvailable = llmDetector.isLlmAvailable();

        if (!llmAvailable) {
            mcpOnlyMode = true;
            LOG.infof("Running in MCP-only mode: %s", llmDetector.getLlmUnavailableReason());
        } else {
            LOG.infof("Running in full mode with LLM provider: %s", llmDetector.getLlmProvider());
        }
    }

    private void displayStartupBanner() {
        System.out.println();

        if (mcpOnlyMode) {
            System.out.println("Starting Strimzi MCP Server (LLM disabled)");
            System.out.println();
            System.out.println("MCP endpoint available at:");
            System.out.println("  • MCP Server:  http://localhost:" + httpPort + "/mcp");
            System.out.println();
            System.out.println("To enable Chat API, configure LLM provider:");
            System.out.println("  • OpenAI: set OPENAI_API_KEY environment variable");
            System.out.println("  • Ollama: start Ollama server or set OLLAMA_BASE_URL");
        } else {
            System.out.println("Starting Strimzi MCP & Chat Server");
            System.out.println();
            System.out.println("Endpoints available at:");
            System.out.println("  • Chat API:    http://localhost:" + httpPort + "/api/chat");
            System.out.println("  • MCP Server:  http://localhost:" + httpPort + "/mcp");
            System.out.println("  • Health:      http://localhost:" + httpPort + "/api/chat/health");
        }

        System.out.println();
        System.out.println("For CLI access, use: jbang hack/strimzi-chat-cli.java");
        System.out.println("Use Ctrl+C to stop the server");
        System.out.println("─".repeat(60));
    }
}
