package io.strimzi.mcp.api;

import io.strimzi.mcp.config.LlmConfigurationDetector;
import io.strimzi.mcp.dto.ChatRequest;
import io.strimzi.mcp.dto.ChatResponse;
import io.strimzi.mcp.service.ChatService;
import io.strimzi.mcp.service.LlmNotAvailableException;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;

/**
 * REST API endpoints for chat functionality.
 */
@Path("/api/chat")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChatResource {

    private static final Logger LOG = Logger.getLogger(ChatResource.class);

    @Inject
    ChatService chatService;

    @Inject
    LlmConfigurationDetector llmDetector;

    /**
     * Send a chat message to the LLM.
     *
     * @param request The chat request
     * @return Chat response from the LLM
     */
    @POST
    public Response chat(ChatRequest request) {
        if (!llmDetector.isLlmAvailable()) {
            return Response.status(503)
                .entity(Map.of(
                    "error", "LLM service unavailable",
                    "message", llmDetector.getLlmUnavailableReason(),
                    "suggestion", "Set ENABLE_LLM=true and configure a provider",
                    "available_endpoints", Map.of("mcp", "/mcp"),
                    "timestamp", Instant.now()
                ))
                .build();
        }

        try {
            LOG.infof("Received chat request: %s", request.message());

            ChatResponse response = chatService.chat(request);
            return Response.ok(response).build();

        } catch (LlmNotAvailableException e) {
            return Response.status(503)
                .entity(Map.of(
                    "error", "LLM configuration error",
                    "message", e.getMessage(),
                    "timestamp", Instant.now()
                ))
                .build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid chat request: %s", e.getMessage());
            return Response.status(400)
                .entity(Map.of(
                    "error", "Invalid request",
                    "message", e.getMessage(),
                    "timestamp", Instant.now()
                ))
                .build();

        } catch (Exception e) {
            LOG.errorf(e, "Unexpected error processing chat request: %s", e.getMessage());
            return Response.status(500)
                .entity(Map.of(
                    "error", "Internal server error",
                    "message", "Failed to process chat request",
                    "timestamp", Instant.now()
                ))
                .build();
        }
    }

    /**
     * Get chat service health and configuration info.
     *
     * @return Service status
     */
    @GET
    @Path("/health")
    public Response health() {
        if (!llmDetector.isLlmAvailable()) {
            return Response.status(503)
                .entity(Map.of(
                    "status", "unavailable",
                    "message", llmDetector.getLlmUnavailableReason(),
                    "suggestion", "Set ENABLE_LLM=true and configure a provider",
                    "timestamp", Instant.now()
                ))
                .build();
        }

        try {
            String provider = chatService.getProvider();
            boolean healthy = chatService.isHealthy();

            return Response.ok(Map.of(
                "status", healthy ? "healthy" : "unhealthy",
                "provider", provider,
                "timestamp", Instant.now(),
                "message", healthy ?
                    "Chat service is ready with " + provider + " provider" :
                    "Chat service has issues with " + provider + " provider"
            )).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error checking chat service health: %s", e.getMessage());
            return Response.status(500)
                .entity(Map.of(
                    "status", "error",
                    "message", "Failed to check service health",
                    "timestamp", Instant.now()
                ))
                .build();
        }
    }

    /**
     * Get available providers and configuration info.
     *
     * @return Provider information
     */
    @GET
    @Path("/info")
    public Response info() {
        if (!llmDetector.isLlmAvailable()) {
            return Response.status(503)
                .entity(Map.of(
                    "error", "LLM service unavailable",
                    "message", llmDetector.getLlmUnavailableReason(),
                    "suggestion", "Set ENABLE_LLM=true and configure a provider",
                    "available_endpoints", Map.of("mcp", "/mcp"),
                    "mcp_endpoint", "/mcp",
                    "tools", new String[]{
                        "strimzi_kafka_clusters",
                        "strimzi_cluster_pods",
                        "strimzi_kafka_topics",
                        "strimzi_operator_status",
                        "strimzi_operator_logs"
                    },
                    "timestamp", Instant.now()
                ))
                .build();
        }

        return Response.ok(Map.of(
            "current_provider", chatService.getProvider(),
            "available_providers", new String[]{"openai", "ollama"},
            "endpoints", Map.of(
                "chat", "POST /api/chat",
                "health", "GET /api/chat/health",
                "info", "GET /api/chat/info"
            ),
            "mcp_endpoint", "/mcp",
            "tools", new String[]{
                "readLogsFromOperator",
                "getKafkaClusterPods",
                "getOperatorStatus",
                "getKafkaClusters",
                "getKafkaTopics"
            },
            "timestamp", Instant.now()
        )).build();
    }
}