/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.clients;

import io.qameta.allure.Step;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.wait.Wait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Factory for creating MCP streamable HTTP test clients.
 */
public final class McpClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpClientFactory.class);

    private static final long READY_TIMEOUT_MS = 60_000L;
    private static final long READY_POLL_MS = 2_000L;

    private McpClientFactory() {
    }

    /**
     * Create and connect an MCP streamable HTTP test client to the given URL.
     * Waits for the MCP server health endpoint to respond before connecting.
     *
     * @param mcpUrl the MCP server base URL (e.g. {@code http://localhost:30080})
     * @return a connected MCP streamable HTTP test client
     */
    @Step("Create MCP streamable HTTP client connected to {mcpUrl}")
    public static McpAssured.McpStreamableTestClient create(final String mcpUrl) {
        LOGGER.info("Waiting for MCP server to be reachable at {}", mcpUrl);
        waitForMcpReady(mcpUrl);

        LOGGER.info("Connecting MCP streamable HTTP client to {}", mcpUrl);
        McpAssured.baseUri = URI.create(mcpUrl);
        return McpAssured.newConnectedStreamableClient();
    }

    /**
     * Poll the MCP server health endpoint until it responds with HTTP 200.
     *
     * @param mcpUrl the MCP server base URL
     */
    private static void waitForMcpReady(final String mcpUrl) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(mcpUrl + "/q/health/ready"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        Wait.until("MCP server ready at " + mcpUrl, READY_POLL_MS, READY_TIMEOUT_MS, () -> {
            try {
                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    LOGGER.info("MCP server is ready");
                    return true;
                }
                LOGGER.debug("MCP health check returned {}", response.statusCode());
                return false;
            } catch (Exception e) {
                LOGGER.debug("MCP health check failed: {}", e.getMessage());
                return false;
            }
        });
    }
}
