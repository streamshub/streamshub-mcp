package io.strimzi.mcp.service.chat;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.strimzi.mcp.config.StrimziAssistantPrompts;
import io.strimzi.mcp.tool.StrimziOperatorTools;
import io.strimzi.mcp.tool.KafkaClusterTools;

/**
 * LangChain4J AI Service for Strimzi Kafka assistant.
 *
 * This will be configured with either OpenAI or Ollama provider
 * based on the application configuration.
 */
@RegisterAiService(tools = {StrimziOperatorTools.class, KafkaClusterTools.class})
public interface StrimziChatAssistant {

    @SystemMessage(StrimziAssistantPrompts.STRIMZI_ASSISTANT_SYSTEM_MESSAGE)
    @UserMessage("{message}")
    String chat(String message);
}