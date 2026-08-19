/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.observability;

import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.McpTrafficListener;
import io.quarkiverse.mcp.server.RawMessage;
import io.quarkiverse.mcp.server.RequestId;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * MCP protocol traffic listener for observability.
 *
 * <p>Logs all inbound and outbound MCP messages at DEBUG level, capturing:
 * <ul>
 *   <li>Direction (received/sent)</li>
 *   <li>Connection ID</li>
 *   <li>JSON-RPC method name (requests/notifications)</li>
 *   <li>Request ID (requests/responses)</li>
 *   <li>Full message content (formatted JSON)</li>
 * </ul>
 *
 * <p>Unlike the built-in {@code TrafficLogger} (which respects
 * {@code quarkus.mcp.server.traffic-logging.enabled}), this listener is
 * always invoked. Actual logging is gated by the JBoss Logger DEBUG level,
 * allowing it to be enabled/disabled via logging configuration without
 * server restarts.</p>
 *
 * <p>Registered automatically as a CDI bean. No explicit configuration needed.</p>
 */
@ApplicationScoped
public class McpTrafficLogger implements McpTrafficListener {

    private static final Logger LOG = Logger.getLogger(McpTrafficLogger.class);

    McpTrafficLogger() {
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always returns {@code true}. Actual logging is controlled by
     * the JBoss Logger DEBUG level.</p>
     */
    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs received messages at DEBUG level with method name, request ID,
     * connection ID, and formatted message content.</p>
     */
    @Override
    public void onMessageReceived(final RawMessage message, final McpConnection connection) {
        if (!LOG.isDebugEnabled()) {
            return;
        }

        String method = message.method();
        RequestId id = message.id();
        String messageStr = formatMessage(message);

        LOG.debugf("MCP RECEIVED [conn=%s, method=%s, id=%s]:%n%s",
            connection.id(), method, id, messageStr);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Logs sent messages at DEBUG level with method name, request ID,
     * connection ID, and formatted message content.</p>
     */
    @Override
    public void onMessageSent(final RawMessage message, final McpConnection connection) {
        if (!LOG.isDebugEnabled()) {
            return;
        }

        String method = message.method();
        RequestId id = message.id();
        String messageStr = formatMessage(message);

        LOG.debugf("MCP SENT [conn=%s, method=%s, id=%s]:%n%s",
            connection.id(), method, id, messageStr);
    }

    private String formatMessage(final RawMessage message) {
        String pretty = message.asPrettyString();
        return (pretty != null && !pretty.isBlank()) ? pretty : "n/a";
    }
}
