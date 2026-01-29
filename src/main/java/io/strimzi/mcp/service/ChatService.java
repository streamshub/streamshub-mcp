package io.strimzi.mcp.service;

import io.strimzi.mcp.dto.ChatRequest;
import io.strimzi.mcp.dto.ChatResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Main chat service that handles LLM interactions.
 */
@ApplicationScoped
public class ChatService {

    private static final Logger LOG = Logger.getLogger(ChatService.class);

    @Inject
    StrimziChatAssistant assistant;

    @ConfigProperty(name = "app.llm.provider", defaultValue = "ollama")
    String llmProvider;

    /**
     * Process a chat message using the configured LLM provider.
     */
    public ChatResponse chat(ChatRequest request) {
        try {
            LOG.infof("Processing chat message with provider: %s", llmProvider);
            LOG.debugf("Message: %s", request.message());

            // Use the AI service (provider is configured automatically by Quarkus)
            String response = assistant.chat(request.message());

            LOG.infof("Generated response with %s (%d chars)", llmProvider, response.length());

            return ChatResponse.of(response, llmProvider);

        } catch (Exception e) {
            LOG.errorf(e, "Error processing chat message with provider %s: %s", llmProvider, e.getMessage());

            // Return helpful error message based on provider
            String errorMsg = switch (llmProvider) {
                case "openai" -> "OpenAI API error: " + e.getMessage() +
                    ". Please check your OPENAI_API_KEY and internet connection.";
                case "ollama" -> "Ollama error: " + e.getMessage() +
                    ". Please ensure Ollama is running and the model is available.";
                default -> "LLM error: " + e.getMessage();
            };

            return ChatResponse.of(errorMsg, llmProvider + " (error)");
        }
    }

    /**
     * Get the current LLM provider.
     */
    public String getProvider() {
        return llmProvider;
    }

    /**
     * Check if the LLM provider is healthy.
     */
    public boolean isHealthy() {
        try {
            // Simple health check - try a basic chat
            assistant.chat("Hello");
            return true;
        } catch (Exception e) {
            LOG.warnf("LLM provider %s health check failed: %s", llmProvider, e.getMessage());
            return false;
        }
    }
}