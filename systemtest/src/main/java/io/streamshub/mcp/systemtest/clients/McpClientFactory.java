/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.clients;

import io.qameta.allure.Step;
import io.quarkiverse.mcp.server.test.McpAssured;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * Factory for creating MCP streamable HTTP test clients.
 */
public final class McpClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpClientFactory.class);

    private McpClientFactory() {
    }

    /**
     * Create and connect an MCP streamable HTTP test client to the given URL.
     *
     * @param mcpUrl the MCP server base URL (e.g. {@code http://10.89.0.4:30080})
     * @return a connected MCP streamable HTTP test client
     */
    @Step("Create MCP streamable HTTP client connected to {mcpUrl}")
    public static McpAssured.McpStreamableTestClient create(final String mcpUrl) {
        LOGGER.info("Connecting MCP streamable HTTP client to {}", mcpUrl);
        McpAssured.baseUri = URI.create(mcpUrl);
        return McpAssured.newConnectedStreamableClient();
    }
}
