/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.setup.mcp;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.qameta.allure.Step;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.Constants;
import io.streamshub.mcp.systemtest.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * Sets up connectivity to the MCP server from the test JVM.
 * All resources created via {@link KubeResourceManager#get()} for proper lifecycle management.
 *
 * <p>Controlled by environment variables:
 * <ul>
 *   <li>{@code MCP_URL} — direct URL override, skips all setup</li>
 *   <li>{@code MCP_CONNECTIVITY} — strategy: {@code NODEPORT}, {@code INGRESS}, or {@code ROUTE}</li>
 *   <li>{@code MCP_INGRESS_PORT} — localhost port for ingress (default: 9090)</li>
 * </ul>
 *
 * <p>If neither is set, auto-detects: OpenShift → ROUTE, otherwise → NODEPORT.
 */
public final class ConnectivitySetup {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectivitySetup.class);

    private ConnectivitySetup() {
    }

    /**
     * Connectivity strategy for reaching the MCP server from the test JVM.
     */
    public enum ConnectivityType {
        /** Patch Service to NodePort and connect via node IP. */
        NODEPORT,
        /** Create an Ingress resource and connect via localhost (e.g. Contour on Podman Desktop kind). */
        INGRESS,
        /** Create an OpenShift Route. */
        ROUTE
    }

    /**
     * Expose the MCP server for test access and return the MCP endpoint URL.
     * If {@code MCP_URL} env var is set, uses that directly (for manual port-forward).
     * Otherwise selects strategy from {@code MCP_CONNECTIVITY} env var or auto-detects.
     *
     * @param namespace the namespace where MCP server is deployed
     * @return the MCP endpoint URL
     */
    @Step("Expose MCP server for test access")
    public static String expose(final String namespace) {
        String overrideUrl = Environment.MCP_URL;
        if (overrideUrl != null && !overrideUrl.isBlank()) {
            LOGGER.info("Using MCP_URL override: {}", overrideUrl);
            return overrideUrl;
        }

        ConnectivityType type = resolveConnectivityType();
        LOGGER.info("Using connectivity strategy: {}", type);

        return switch (type) {
            case NODEPORT -> exposeViaNodePort(namespace);
            case INGRESS -> exposeViaIngress(namespace);
            case ROUTE -> exposeViaRoute(namespace);
        };
    }

    /**
     * Resolve which connectivity strategy to use.
     *
     * @return the connectivity type
     */
    private static ConnectivityType resolveConnectivityType() {
        String env = Environment.MCP_CONNECTIVITY;
        if (env != null && !env.isBlank()) {
            return ConnectivityType.valueOf(env.toUpperCase(Locale.ROOT));
        }
        return isOpenShift() ? ConnectivityType.ROUTE : ConnectivityType.NODEPORT;
    }

    // --- NODEPORT ---
    @Step("Patch MCP Service to NodePort")
    private static String exposeViaNodePort(final String namespace) {
        KubernetesClient client = KubeResourceManager.get().kubeClient().getClient();

        LOGGER.info("Patching MCP Service to NodePort with port {}", Constants.MCP_NODE_PORT);

        Service current = client.services()
            .inNamespace(namespace).withName(Constants.MCP_NAME).get();
        Service nodePortService = new ServiceBuilder(current)
            .editSpec()
                .withType("NodePort")
                .editFirstPort()
                    .withNodePort(Constants.MCP_NODE_PORT)
                .endPort()
            .endSpec()
            .build();
        KubeResourceManager.get().createOrUpdateResourceWithoutWait(nodePortService);

        String nodeIp = getNodeIp(client);
        String url = "http://" + nodeIp + ":" + Constants.MCP_NODE_PORT;
        LOGGER.info("MCP server exposed via NodePort at {}", url);
        return url;
    }

    /**
     * Resolve the IP address of a cluster node for NodePort access.
     * Prefers InternalIP, falls back to ExternalIP, then Hostname.
     *
     * @param client the Kubernetes client
     * @return the node IP address
     */
    private static String getNodeIp(final KubernetesClient client) {
        List<Node> nodes = client.nodes().list().getItems();
        if (nodes.isEmpty()) {
            throw new IllegalStateException("No nodes found in the cluster");
        }

        List<NodeAddress> addresses = nodes.get(0).getStatus().getAddresses();
        for (String type : List.of("InternalIP", "ExternalIP", "Hostname")) {
            for (NodeAddress addr : addresses) {
                if (type.equals(addr.getType())) {
                    return addr.getAddress();
                }
            }
        }
        throw new IllegalStateException("Could not resolve any node address from: " + addresses);
    }

    // --- INGRESS (Contour) ---
    @Step("Create Ingress for MCP server")
    private static String exposeViaIngress(final String namespace) {
        String host = Environment.MCP_INGRESS_HOST;

        LOGGER.info("Creating Ingress for MCP server in namespace {} with host '{}'", namespace, host);

        Ingress ingress = new IngressBuilder()
            .withNewMetadata()
                .withName(Constants.MCP_NAME)
                .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
                .addNewRule()
                    .withHost(host.isEmpty() ? null : host)
                    .withNewHttp()
                        .addNewPath()
                            .withPath("/")
                            .withPathType("Prefix")
                            .withNewBackend()
                                .withNewService()
                                    .withName(Constants.MCP_NAME)
                                    .withNewPort()
                                        .withNumber(Constants.MCP_PORT)
                                    .endPort()
                                .endService()
                            .endBackend()
                        .endPath()
                    .endHttp()
                .endRule()
            .endSpec()
            .build();
        KubeResourceManager.get().createOrUpdateResourceWithoutWait(ingress);

        int port = Environment.MCP_INGRESS_PORT;
        String url = "http://localhost:" + port;
        LOGGER.info("MCP server exposed via Ingress at {}", url);
        return url;
    }

    // --- ROUTE (OpenShift) ---
    @Step("Create OpenShift Route for MCP server")
    private static String exposeViaRoute(final String namespace) {
        LOGGER.info("Creating OpenShift Route for MCP server in namespace {}", namespace);

        Route route = new RouteBuilder()
            .withNewMetadata()
                .withName(Constants.MCP_NAME)
                .withNamespace(namespace)
                .addToAnnotations("haproxy.router.openshift.io/timeout", "3600s")
            .endMetadata()
            .withNewSpec()
                .withNewTo()
                    .withKind("Service")
                    .withName(Constants.MCP_NAME)
                .endTo()
                .withNewPort()
                    .withTargetPort(new IntOrString("http"))
                .endPort()
            .endSpec()
            .build();
        KubeResourceManager.get().createOrUpdateResourceWithoutWait(route);

        // Read back to get the assigned host
        Route created = KubeResourceManager.get().kubeClient().getClient()
            .resource(route).inNamespace(namespace).get();
        String host = created.getSpec().getHost();
        String url = "http://" + host;
        LOGGER.info("MCP server exposed via Route at {}", url);
        return url;
    }

    /**
     * Detect whether the cluster is OpenShift by checking for the route.openshift.io API group.
     *
     * @return true if the cluster is OpenShift
     */
    public static boolean isOpenShift() {
        try {
            return KubeResourceManager.get().kubeClient().getClient()
                .supports("route.openshift.io/v1", "Route");
        } catch (Exception e) {
            LOGGER.debug("Failed to detect OpenShift, assuming vanilla Kubernetes", e);
            return false;
        }
    }
}
