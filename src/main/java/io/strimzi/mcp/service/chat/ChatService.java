/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.service.chat;

import io.strimzi.mcp.config.LlmConfigurationDetector;
import io.strimzi.mcp.dto.ChatRequest;
import io.strimzi.mcp.dto.ChatResponse;
import io.strimzi.mcp.exception.LlmNotAvailableException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
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
    Instance<StrimziChatAssistant> assistantInstance;

    @Inject
    LlmConfigurationDetector llmDetector;

    @ConfigProperty(name = "app.llm.provider", defaultValue = "ollama")
    String llmProvider;

    ChatService() {
    }

    /**
     * Process a chat message using the configured LLM provider.
     *
     * @param request the chat request containing the user message
     * @return the chat response from the LLM
     */
    public ChatResponse chat(ChatRequest request) {
        // Check if LLM is available
        if (!llmDetector.isLlmAvailable()) {
            throw new LlmNotAvailableException(llmDetector.getLlmUnavailableReason());
        }

        if (assistantInstance.isUnsatisfied()) {
            throw new LlmNotAvailableException("LLM assistant not available - check provider configuration");
        }

        try {
            LOG.infof("Processing chat message with provider: %s", llmProvider);
            LOG.debugf("Message: %s", request.message());

            // Use the AI service (provider is configured automatically by Quarkus)
            StrimziChatAssistant assistant = assistantInstance.get();
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
     *
     * @return the LLM provider name
     */
    public String getProvider() {
        return llmProvider;
    }

    /**
     * Check if the LLM provider is healthy.
     *
     * @return true if the LLM provider is healthy and responding
     */
    public boolean isHealthy() {
        if (!llmDetector.isLlmAvailable()) {
            return false;
        }

        if (assistantInstance.isUnsatisfied()) {
            return false;
        }

        try {
            // Simple health check - try a basic chat
            StrimziChatAssistant assistant = assistantInstance.get();
            assistant.chat("Hello");
            return true;
        } catch (Exception e) {
            LOG.warnf("LLM provider %s health check failed: %s", llmProvider, e.getMessage());
            return false;
        }
    }
}
