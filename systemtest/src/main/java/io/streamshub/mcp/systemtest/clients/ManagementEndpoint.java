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
 * served on the management port, which the Service does not expose, so each request port-forwards
 * to a ready MCP pod. This works the same for every connectivity type (NodePort, Ingress, Route).
 */
public final class ManagementEndpoint {

    /** Management interface port of the MCP server container. */
    public static final int MANAGEMENT_PORT = 9000;

    private ManagementEndpoint() {
    }

    /**
     * Send a GET request to a management endpoint of the MCP server.
     *
     * @param namespace the namespace where the MCP server runs
     * @param path      the endpoint path, for example {@code /q/metrics}
     * @return the HTTP response
     * @throws IOException          when the port-forward or request fails
     * @throws InterruptedException when interrupted while waiting for the response
     */
    public static HttpResponse<String> get(final String namespace, final String path)
            throws IOException, InterruptedException {
        KubernetesClient client = KubeResourceManager.get().kubeClient().getClient();
        Pod pod = client.pods().inNamespace(namespace)
            .withLabel(Constants.MCP_APP_LABEL_KEY, Constants.MCP_APP_LABEL)
            .list().getItems().stream()
            .filter(Readiness::isPodReady)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No ready MCP server pod in namespace " + namespace));

        try (LocalPortForward forward = client.pods().inNamespace(namespace)
                .withName(pod.getMetadata().getName()).portForward(MANAGEMENT_PORT);
             HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + forward.getLocalPort() + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
