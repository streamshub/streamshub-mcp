/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.clients;

import io.qameta.allure.Step;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.wait.Wait;
import org.awaitility.Awaitility;
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
    /** Status the MCP endpoint returns for a GET without a session (method not allowed). */
    private static final int MCP_GET_WITHOUT_SESSION_STATUS = 405;

    private McpClientFactory() {
    }

    /**
     * Create and connect an MCP streamable HTTP test client to the given URL.
     * Waits for the MCP endpoint to respond before connecting.
     *
     * @param mcpUrl the MCP server base URL (e.g. {@code http://localhost:30080})
     * @return a connected MCP streamable HTTP test client
     */
    @Step("Create MCP streamable HTTP client connected to {mcpUrl}")
    public static McpAssured.McpStreamableTestClient create(final String mcpUrl) {
        LOGGER.info("Waiting for MCP server to be reachable at {}", mcpUrl);
        waitForMcpReady(mcpUrl);

        // Set all timeouts to 60s instead of default 10s.
        // This should make tests more stable when actions will take longer time to finish.
        Awaitility.setDefaultTimeout(Duration.ofSeconds(60));

        LOGGER.info("Connecting MCP streamable HTTP client to {}", mcpUrl);
        McpAssured.baseUri = URI.create(mcpUrl);
        return McpAssured.newConnectedStreamableClient();
    }

    /**
     * Poll the MCP endpoint until the MCP server answers through the exposed URL. A bare GET without
     * {@code Mcp-Session-Id} gets 405 from the server; any other status (such as an Ingress default
     * backend's 404) means traffic does not reach it yet. Pod readiness is already enforced by the
     * deployment wait; health is served on the management port, which the exposed Service does not carry.
     *
     * @param mcpUrl the MCP server base URL
     */
    private static void waitForMcpReady(final String mcpUrl) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(mcpUrl + "/mcp"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

        Wait.until("MCP server ready at " + mcpUrl, READY_POLL_MS, READY_TIMEOUT_MS, () -> {
            try {
                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == MCP_GET_WITHOUT_SESSION_STATUS) {
                    LOGGER.info("MCP server is reachable");
                    return true;
                }
                LOGGER.debug("MCP endpoint returned {}", response.statusCode());
                return false;
            } catch (Exception e) {
                LOGGER.debug("MCP endpoint not reachable yet: {}", e.getMessage());
                return false;
            }
        });
    }
}
