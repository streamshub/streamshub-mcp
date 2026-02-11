/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Service to detect if LLM configuration is properly set up.
 * Used to determine if application should run in MCP-only mode.
 */
@ApplicationScoped
public class LlmConfigurationDetector {

    private static final Logger LOG = Logger.getLogger(LlmConfigurationDetector.class);

    @ConfigProperty(name = "app.llm.enable", defaultValue = "false")
    boolean llmEnabled;

    @ConfigProperty(name = "app.llm.provider", defaultValue = "none")
    String llmProvider;

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key", defaultValue = "not-set")
    String openaiApiKey;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url", defaultValue = "http://localhost:11434")
    String ollamaBaseUrl;

    private Boolean llmAvailable = null;

    LlmConfigurationDetector() {
    }

    /**
     * Check if LLM is properly configured and available.
     *
     * @return true if LLM is available and configured
     */
    public boolean isLlmAvailable() {
        if (llmAvailable != null) {
            return llmAvailable;
        }

        // Primary gate - must be explicitly enabled
        if (!llmEnabled) {
            llmAvailable = false;
            return false;
        }

        // Secondary check - validate provider config
        llmAvailable = checkLlmConfiguration();
        return llmAvailable;
    }

    /**
     * Get the configured LLM provider.
     *
     * @return the LLM provider name
     */
    public String getLlmProvider() {
        return llmProvider;
    }

    /**
     * Get the reason why LLM is not available (for logging).
     *
     * @return a human-readable reason why LLM is unavailable
     */
    public String getLlmUnavailableReason() {
        if (!llmEnabled) {
            return "LLM functionality disabled (set ENABLE_LLM=true to enable)";
        }

        return switch (llmProvider.toLowerCase(Locale.ENGLISH)) {
            case "none" -> "LLM provider disabled (set LLM_PROVIDER=openai or LLM_PROVIDER=ollama to enable)";
            case "openai" -> openaiApiKey == null || "not-set".equals(openaiApiKey) ?
                "OpenAI API key not configured (set OPENAI_API_KEY environment variable)" :
                "OpenAI configuration appears invalid";
            case "ollama" -> "Ollama server not reachable at " + ollamaBaseUrl +
                " (set OLLAMA_BASE_URL environment variable or start Ollama)";
            default -> "Unknown LLM provider: " + llmProvider;
        };
    }

    private boolean checkLlmConfiguration() {
        try {
            return switch (llmProvider.toLowerCase(Locale.ENGLISH)) {
                case "none" -> {
                    LOG.debug("LLM provider is disabled (none)");
                    yield false;
                }
                case "openai" -> checkOpenAiConfiguration();
                case "ollama" -> checkOllamaConfiguration();
                default -> {
                    LOG.warnf("Unknown LLM provider: %s", llmProvider);
                    yield false;
                }
            };
        } catch (Exception e) {
            LOG.debugf("Error checking LLM configuration: %s", e.getMessage());
            return false;
        }
    }

    private boolean checkOpenAiConfiguration() {
        if (openaiApiKey == null || "not-set".equals(openaiApiKey) || openaiApiKey.trim().isEmpty()) {
            LOG.debug("OpenAI API key not configured");
            return false;
        }

        // Basic validation - OpenAI keys usually start with 'sk-'
        if (!openaiApiKey.startsWith("sk-")) {
            LOG.debug("OpenAI API key format appears invalid");
            return false;
        }

        LOG.debug("OpenAI API key is configured");
        return true;
    }

    private boolean checkOllamaConfiguration() {
        try {
            // Quick health check to Ollama server
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaBaseUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                LOG.debug("Ollama server is reachable");
                return true;
            } else {
                LOG.debugf("Ollama server returned status %d", response.statusCode());
                return false;
            }

        } catch (Exception e) {
            LOG.debugf("Cannot reach Ollama server at %s: %s", ollamaBaseUrl, e.getMessage());
            return false;
        }
    }
}
