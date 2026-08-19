/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.observability;

import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.RawMessage;
import io.quarkiverse.mcp.server.RequestId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link McpTrafficLogger}.
 */
class McpTrafficLoggerTest {

    private McpTrafficLogger logger;
    private RawMessage message;
    private McpConnection connection;

    McpTrafficLoggerTest() {
    }

    @BeforeEach
    void setUp() {
        logger = new McpTrafficLogger();
        message = Mockito.mock(RawMessage.class);
        connection = Mockito.mock(McpConnection.class);
    }

    @Test
    void testIsEnabledReturnsTrue() {
        assertTrue(logger.isEnabled(), "McpTrafficLogger should always be enabled");
    }

    @Test
    void testOnMessageReceivedWithRequestMessage() {
        RequestId requestId = Mockito.mock(RequestId.class);
        when(requestId.toString()).thenReturn("123");
        when(message.method()).thenReturn("tools/call");
        when(message.id()).thenReturn(requestId);
        when(message.asPrettyString()).thenReturn("{\n  \"method\": \"tools/call\"\n}");
        when(connection.id()).thenReturn("conn-abc");

        logger.onMessageReceived(message, connection);

        // Verify the logger accessed message properties
        verify(message).method();
        verify(message).id();
        verify(message).asPrettyString();
        verify(connection).id();
    }

    @Test
    void testOnMessageReceivedWithNotification() {
        when(message.method()).thenReturn("notifications/message");
        when(message.id()).thenReturn(null); // Notifications have no ID
        when(message.asPrettyString()).thenReturn("{\n  \"method\": \"notifications/message\"\n}");
        when(connection.id()).thenReturn("conn-xyz");

        logger.onMessageReceived(message, connection);

        // Verify the logger accessed message properties
        verify(message).method();
        verify(message).id();
        verify(message).asPrettyString();
        verify(connection).id();
    }

    @Test
    void testOnMessageReceivedWithResponse() {
        RequestId requestId = Mockito.mock(RequestId.class);
        when(requestId.toString()).thenReturn("456");
        when(message.method()).thenReturn(null); // Responses have no method
        when(message.id()).thenReturn(requestId);
        when(message.asPrettyString()).thenReturn("{\n  \"result\": {...}\n}");
        when(connection.id()).thenReturn("conn-def");

        logger.onMessageReceived(message, connection);

        // Verify the logger accessed message properties
        verify(message).method();
        verify(message).id();
        verify(message).asPrettyString();
        verify(connection).id();
    }

    @Test
    void testOnMessageSentWithRequestMessage() {
        RequestId requestId = Mockito.mock(RequestId.class);
        when(requestId.toString()).thenReturn("789");
        when(message.method()).thenReturn("tools/list");
        when(message.id()).thenReturn(requestId);
        when(message.asPrettyString()).thenReturn("{\n  \"method\": \"tools/list\"\n}");
        when(connection.id()).thenReturn("conn-ghi");

        logger.onMessageSent(message, connection);

        // Verify the logger accessed message properties
        verify(message).method();
        verify(message).id();
        verify(message).asPrettyString();
        verify(connection).id();
    }

    @Test
    void testOnMessageSentWithNullPrettyString() {
        when(message.method()).thenReturn("tools/call");
        when(message.id()).thenReturn(null);
        when(message.asPrettyString()).thenReturn(null);
        when(connection.id()).thenReturn("conn-jkl");

        logger.onMessageSent(message, connection);

        // Verify null is handled - formatMessage still called asPrettyString()
        verify(message).method();
        verify(message).id();
        verify(message).asPrettyString();
        verify(connection).id();
    }

    @Test
    void testOnMessageSentWithEmptyPrettyString() {
        when(message.method()).thenReturn("tools/call");
        when(message.id()).thenReturn(null);
        when(message.asPrettyString()).thenReturn("");
        when(connection.id()).thenReturn("conn-mno");

        logger.onMessageSent(message, connection);

        // Verify empty string is handled gracefully
        verify(message).method();
        verify(message).id();
        verify(message).asPrettyString();
        verify(connection).id();
    }

    @Test
    void testOnMessageReceivedWithBlankPrettyString() {
        when(message.method()).thenReturn("initialize");
        when(message.id()).thenReturn(null);
        when(message.asPrettyString()).thenReturn("   ");
        when(connection.id()).thenReturn("conn-pqr");

        logger.onMessageReceived(message, connection);

        // Verify blank string is handled gracefully (falls back to "n/a")
        verify(message).method();
        verify(message).id();
        verify(message).asPrettyString();
        verify(connection).id();
    }
}
