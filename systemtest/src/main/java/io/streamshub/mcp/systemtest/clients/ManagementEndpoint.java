/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.clients;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.readiness.Readiness;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.Constants;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Reads the MCP server's management endpoints ({@code /q/health}, {@code /q/metrics}). They are
 * served on the management port, which the Service does not expose, so requests are sent through
 * a port-forward to a ready MCP pod. This works the same for every connectivity type (NodePort,
 * Ingress, Route). The forward is cached for the JVM and re-established automatically when it goes
 * stale (for example after a pod restart); {@link #close()} tears it down.
 */
public final class ManagementEndpoint {

    /** Management interface port of the MCP server container. */
    public static final int MANAGEMENT_PORT = 9000;

    private static final Object FORWARD_LOCK = new Object();

    private static volatile LocalPortForward forward;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ManagementEndpoint::close));
    }

    private ManagementEndpoint() {
    }

    /**
     * Send a GET request to a management endpoint of the MCP server. A stale forward (the pod
     * restarted or the tunnel died) is re-established and the request retried once.
     *
     * @param namespace the namespace where the MCP server runs
     * @param path      the endpoint path, for example {@code /q/metrics}
     * @return the HTTP response
     * @throws IOException          when the port-forward or request fails
     * @throws InterruptedException when interrupted while waiting for the response
     */
    public static HttpResponse<String> get(final String namespace, final String path)
            throws IOException, InterruptedException {
        try {
            return send(portForward(namespace), path);
        } catch (IOException e) {
            // The cached forward went stale (pod restart, dead tunnel): re-establish and retry once.
            closeForward();
            return send(portForward(namespace), path);
        }
    }

    /**
     * Tear down the cached port-forward, if any. Safe to call repeatedly.
     */
    public static void close() {
        closeForward();
    }

    private static HttpResponse<String> send(final LocalPortForward forward, final String path)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + forward.getLocalPort() + path))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static LocalPortForward portForward(final String namespace) throws IOException {
        LocalPortForward current = forward;
        if (current != null && current.isAlive() && !current.errorOccurred()) {
            return current;
        }
        synchronized (FORWARD_LOCK) {
            if (forward == null || !forward.isAlive() || forward.errorOccurred()) {
                closeQuietly(forward);
                forward = open(namespace);
            }
            return forward;
        }
    }

    private static LocalPortForward open(final String namespace) throws IOException {
        KubernetesClient client = KubeResourceManager.get().kubeClient().getClient();
        Pod pod = client.pods().inNamespace(namespace)
            .withLabel(Constants.MCP_APP_LABEL_KEY, Constants.MCP_APP_LABEL)
            .list().getItems().stream()
            .filter(Readiness::isPodReady)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No ready MCP server pod in namespace " + namespace));

        return client.pods().inNamespace(namespace)
            .withName(pod.getMetadata().getName()).portForward(MANAGEMENT_PORT);
    }

    private static void closeForward() {
        synchronized (FORWARD_LOCK) {
            closeQuietly(forward);
            forward = null;
        }
    }

    private static void closeQuietly(final LocalPortForward forward) {
        if (forward != null) {
            try {
                forward.close();
            } catch (IOException e) {
                // A stale forward that fails to close is simply dropped.
            }
        }
    }
}
