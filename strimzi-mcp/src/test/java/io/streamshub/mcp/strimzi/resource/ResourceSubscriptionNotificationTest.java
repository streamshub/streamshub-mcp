/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.resource;

import io.quarkiverse.mcp.server.ResourceManager;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Verifies that {@link ResourceManager.ResourceInfo#sendUpdateAndForget()} — the API used by
 * {@link ResourceSubscriptionManager} — delivers {@code notifications/resources/updated} to subscribed
 * MCP clients over both HTTP transports after the quarkus-mcp-server 2.0 upgrade (issue #228):
 *
 * <ul>
 *   <li>Streamable HTTP, stateless, via {@code subscriptions/listen} (protocol {@code 2026-07-28}).</li>
 *   <li>Legacy SSE, stateful, via {@code resources/subscribe} (protocol {@code 2024-11-05}).</li>
 * </ul>
 *
 * <p>The McpAssured test client exposes no subscription/notification API, so these tests drive the
 * raw JSON-RPC wire protocol with a minimal SSE reader. The update is triggered deterministically by
 * registering a resource through the injected {@link ResourceManager} and calling
 * {@code sendUpdateAndForget()} — no live Kubernetes cluster is required.</p>
 */
@QuarkusTest
class ResourceSubscriptionNotificationTest {

    private static final String STATELESS_PROTOCOL = "2026-07-28";
    private static final String STATEFUL_PROTOCOL = "2024-11-05";
    private static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    private static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    private static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    private static final String UPDATED_METHOD = "notifications/resources/updated";
    private static final String ACKNOWLEDGED_METHOD = "notifications/subscriptions/acknowledged";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int OK = 200;

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @Inject
    ResourceManager resourceManager;

    private HttpClient httpClient;

    ResourceSubscriptionNotificationTest() {
    }

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    // ---- Streamable HTTP (stateless, subscriptions/listen) ----

    @Test
    void testStatelessSubscriptionsListenReceivesResourceUpdate() throws Exception {
        String uri = "strimzi://kafka.strimzi.io/namespaces/issue-228/kafkas/streamable/status";
        registerResource(uri);

        JsonObject request = new JsonObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "subscriptions/listen")
            .put("params", new JsonObject()
                .put("notifications", new JsonObject()
                    .put("resourceSubscriptions", new JsonArray().add(uri)))
                .put("_meta", statelessMeta()));

        HttpRequest listen = HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Mcp-Protocol-Version", STATELESS_PROTOCOL)
            .header("Mcp-Method", "subscriptions/listen")
            .POST(HttpRequest.BodyPublishers.ofString(request.encode()))
            .build();

        HttpResponse<InputStream> response = httpClient.send(listen, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(OK, response.statusCode(), "subscriptions/listen should open a stream");

        try (SseReader reader = new SseReader(response.body())) {
            SseEvent ack = reader.await(event -> isMethod(event, ACKNOWLEDGED_METHOD), TIMEOUT);
            assertNotNull(ack, "server should acknowledge the subscription");

            triggerUpdate(uri);

            SseEvent updated = reader.await(event -> isResourceUpdate(event, uri), TIMEOUT);
            assertNotNull(updated, "stateless subscriber should receive " + UPDATED_METHOD + " for " + uri);
        }
    }

    // ---- Legacy SSE (stateful, resources/subscribe) ----

    @Test
    void testStatefulSseSubscribeReceivesResourceUpdate() throws Exception {
        String uri = "strimzi://kafka.strimzi.io/namespaces/issue-228/kafkas/sse/status";
        registerResource(uri);

        HttpRequest openSse = HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp/sse"))
            .header("Accept", "text/event-stream")
            .GET()
            .build();

        HttpResponse<InputStream> sse = httpClient.send(openSse, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(OK, sse.statusCode(), "SSE channel should open");

        try (SseReader reader = new SseReader(sse.body())) {
            SseEvent endpoint = reader.await(event -> "endpoint".equals(event.event()), TIMEOUT);
            assertNotNull(endpoint, "SSE handshake should send the message endpoint");
            String messageUrl = baseUrl() + endpoint.data();

            postMessage(messageUrl, new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "initialize")
                .put("params", new JsonObject()
                    .put("protocolVersion", STATEFUL_PROTOCOL)
                    .put("capabilities", new JsonObject())
                    .put("clientInfo", clientInfo())));
            assertNotNull(reader.await(event -> isResult(event, 1), TIMEOUT), "initialize should return a result");

            postMessage(messageUrl, new JsonObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized"));

            postMessage(messageUrl, new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", "resources/subscribe")
                .put("params", new JsonObject().put("uri", uri)));
            assertNotNull(reader.await(event -> isResult(event, 2), TIMEOUT), "resources/subscribe should return a result");

            triggerUpdate(uri);

            SseEvent updated = reader.await(event -> isResourceUpdate(event, uri), TIMEOUT);
            assertNotNull(updated, "stateful subscriber should receive " + UPDATED_METHOD + " for " + uri);
        }
    }

    // ---- Negative: an update for an unrelated resource must not reach the subscriber ----

    @Test
    void testStatelessSubscriberIgnoresUnrelatedResourceUpdate() throws Exception {
        String subscribedUri = "strimzi://kafka.strimzi.io/namespaces/issue-228/kafkas/wanted/status";
        String otherUri = "strimzi://kafka.strimzi.io/namespaces/issue-228/kafkas/unwanted/status";
        registerResource(subscribedUri);
        registerResource(otherUri);

        JsonObject request = new JsonObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "subscriptions/listen")
            .put("params", new JsonObject()
                .put("notifications", new JsonObject()
                    .put("resourceSubscriptions", new JsonArray().add(subscribedUri)))
                .put("_meta", statelessMeta()));

        HttpRequest listen = HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("Mcp-Protocol-Version", STATELESS_PROTOCOL)
            .header("Mcp-Method", "subscriptions/listen")
            .POST(HttpRequest.BodyPublishers.ofString(request.encode()))
            .build();

        HttpResponse<InputStream> response = httpClient.send(listen, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(OK, response.statusCode());

        try (SseReader reader = new SseReader(response.body())) {
            assertNotNull(reader.await(event -> isMethod(event, ACKNOWLEDGED_METHOD), TIMEOUT));

            triggerUpdate(otherUri);
            SseEvent leaked = reader.await(event -> isResourceUpdate(event, otherUri), Duration.ofSeconds(3));
            assertTrue(leaked == null, "subscriber must not receive updates for an unsubscribed resource");

            triggerUpdate(subscribedUri);
            assertNotNull(reader.await(event -> isResourceUpdate(event, subscribedUri), TIMEOUT),
                "subscriber should still receive updates for its own resource");
        }
    }

    // ---- Helpers ----

    private String baseUrl() {
        return "http://localhost:" + testPort;
    }

    private void registerResource(final String uri) {
        resourceManager.removeResource(uri);
        resourceManager.newResource(uri)
            .setDescription("issue-228 subscription verification resource")
            .setUri(uri)
            .setMimeType("application/json")
            .setHandler(args -> new ResourceResponse(TextResourceContents.create(uri, "{}")))
            .register();
    }

    private void triggerUpdate(final String uri) {
        ResourceManager.ResourceInfo info = resourceManager.getResource(uri);
        assertNotNull(info, "resource should be registered before triggering an update: " + uri);
        info.sendUpdateAndForget();
    }

    private void postMessage(final String url, final JsonObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.encode()))
            .build();
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject statelessMeta() {
        return new JsonObject()
            .put(META_PROTOCOL_VERSION, STATELESS_PROTOCOL)
            .put(META_CLIENT_INFO, clientInfo())
            .put(META_CLIENT_CAPABILITIES, new JsonObject());
    }

    private static JsonObject clientInfo() {
        return new JsonObject().put("name", "issue-228-test").put("version", "1.0");
    }

    private static JsonObject parse(final SseEvent event) {
        String data = event.data();
        if (data == null || data.isEmpty() || data.charAt(0) != '{') {
            return null;
        }
        try {
            return new JsonObject(data);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isMethod(final SseEvent event, final String method) {
        JsonObject json = parse(event);
        return json != null && method.equals(json.getString("method"));
    }

    private static boolean isResourceUpdate(final SseEvent event, final String uri) {
        JsonObject json = parse(event);
        if (json == null || !UPDATED_METHOD.equals(json.getString("method"))) {
            return false;
        }
        JsonObject params = json.getJsonObject("params");
        return params != null && uri.equals(params.getString("uri"));
    }

    private static boolean isResult(final SseEvent event, final int id) {
        JsonObject json = parse(event);
        return json != null && json.containsKey("result") && Integer.valueOf(id).equals(json.getInteger("id"));
    }

    /**
     * A single Server-Sent Event: its {@code event:} type (may be {@code null}) and concatenated {@code data:} payload.
     */
    private record SseEvent(String event, String data) {
    }

    /**
     * Reads Server-Sent Events off an {@link InputStream} on a daemon thread and lets callers await a matching event.
     */
    private static final class SseReader implements AutoCloseable {

        private final BlockingQueue<SseEvent> events = new LinkedBlockingQueue<>();
        private final InputStream stream;
        private final Thread worker;
        private volatile boolean closed;

        SseReader(final InputStream stream) {
            this.stream = stream;
            this.worker = new Thread(this::readLoop, "issue-228-sse-reader");
            this.worker.setDaemon(true);
            this.worker.start();
        }

        private void readLoop() {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String eventType = null;
            StringBuilder data = new StringBuilder();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (eventType != null || data.length() > 0) {
                            events.add(new SseEvent(eventType, data.toString()));
                        }
                        eventType = null;
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        eventType = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(line.substring("data:".length()).trim());
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }

        SseEvent await(final Predicate<SseEvent> predicate, final Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return null;
                }
                SseEvent event = events.poll(remaining, TimeUnit.NANOSECONDS);
                if (event == null) {
                    return null;
                }
                if (predicate.test(event)) {
                    return event;
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            try {
                stream.close();
            } catch (IOException e) {
                // ignore — we are tearing the reader down
            }
        }
    }
}
