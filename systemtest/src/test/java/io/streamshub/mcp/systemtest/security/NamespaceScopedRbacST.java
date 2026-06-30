/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.security;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.Namespace;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.annotations.ClassNamespace;
import io.skodjob.kubetest4j.annotations.InjectResourceManager;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.AbstractST;
import io.streamshub.mcp.systemtest.Constants;
import io.streamshub.mcp.systemtest.Environment;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for namespace-scoped RBAC (app developer perspective).
 *
 * <p>Deploys the MCP server with RoleBindings (not ClusterRoleBinding) so it
 * can only access resources in the primary Kafka namespace. A second Kafka
 * cluster is deployed in an inaccessible namespace to verify RBAC boundaries.
 *
 * <p>Covers three server-side error handling patterns:
 * <ol>
 *   <li>Direct tools — 403 propagated as tool error</li>
 *   <li>Fleet overview — graceful degradation with partial results</li>
 *   <li>Diagnostic tools — partial access behavior</li>
 * </ol>
 *
 */
@Epic("Strimzi MCP E2E")
@Feature("RBAC config variations across namespaces")
class NamespaceScopedRbacST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(NamespaceScopedRbacST.class);

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE_2, labels = {"app=strimzi"})
    static Namespace kafkaNamespace2;

    private static McpAssured.McpStreamableTestClient mcpClient;

    NamespaceScopedRbacST() {
    }

    @BeforeAll
    void setup() {
        if (!Environment.SKIP_STRIMZI_INSTALL) {
            String kafkaNs = kafkaNamespace.getMetadata().getName();
            String kafkaNs2 = kafkaNamespace2.getMetadata().getName();

            StrimziSetup.deploy(strimziNamespace.getMetadata().getName());

            KafkaTemplates.deployMetricsConfigMap(kafkaNs);
            KafkaTemplates.deployPodMonitors(kafkaNs);

            // Accessible cluster in primary namespace
            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafkaWithMetrics(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 1).build());

            // Inaccessible cluster in second namespace
            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs2, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME_2, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs2, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME_2, 1).build());
            
            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs2, Constants.KAFKA_CLUSTER_NAME_2, 1).build());
        }
        // Namespace-scoped RBAC: access only to kafka-ns and strimzi-ns
        McpServerSetup.builder(mcpNamespace.getMetadata().getName())
            .deployWithNamespaceScopedRbac(
                kafkaNamespace.getMetadata().getName(),
                strimziNamespace.getMetadata().getName());

        String mcpUrl = ConnectivitySetup.expose(mcpNamespace.getMetadata().getName());
        mcpClient = McpClientFactory.create(mcpUrl);
    }

    @AfterAll
    static void cleanup() {
        if (mcpClient != null) {
            mcpClient.disconnect();
        }
    }

    // ---- Pattern 1: Direct tools — RBAC boundary enforcement ----

    @Test
    @Story("list_kafka_clusters succeeds in accessible namespace")
    void testListClustersAccessibleNamespace() {
        Map<String, Object> args = Map.of("namespace", Constants.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("list_kafka_clusters", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("list_kafka_clusters (accessible ns): {}", root);
                LOGGER.debug("list_kafka_clusters (accessible ns) response:\n{}", response.content().getFirst().asText().text());
                JsonNode cluster = findByName(root, Constants.KAFKA_CLUSTER_NAME);
                assertNotNull(cluster, "Should find cluster in accessible namespace");
                assertEquals(Constants.KAFKA_NAMESPACE, cluster.path("namespace").asText(),
                    "Namespace should match");
            })
            .thenAssertResults();
    }

    @Test
    @Story("list_kafka_clusters returns error for inaccessible namespace")
    @Story("Namespace Boundary")
    void testListClustersInaccessibleNamespace() {
        Map<String, Object> args = Map.of("namespace", Constants.KAFKA_NAMESPACE_2);

        mcpClient.when()
            .toolsCall("list_kafka_clusters", args, response -> {
                assertToolError(response, "403");
                LOGGER.info("list_kafka_clusters (inaccessible ns): error as expected");
            })
            .thenAssertResults();
    }

    @Test
    @Story("list_kafka_clusters returns error without namespace (cluster-wide)")
    void testListClustersClusterWide() {
        Map<String, Object> args = Map.of();

        mcpClient.when()
            .toolsCall("list_kafka_clusters", args, response -> {
                assertToolError(response, "403");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster succeeds in accessible namespace")
    void testGetClusterAccessibleNamespace() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                JsonNode cluster = assertToolSuccess(response);

                LOGGER.info("get_kafka_cluster (accessible): {}", cluster.path("name"));
                LOGGER.debug("get_kafka_cluster (accessible) response:\n{}", response.content().getFirst().asText().text());
                assertEquals(Constants.KAFKA_CLUSTER_NAME, cluster.path("name").asText());
                assertEquals("Ready", cluster.path("readiness").asText());
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster returns error for inaccessible namespace")
    void testGetClusterInaccessibleNamespace() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME_2,
            "namespace", Constants.KAFKA_NAMESPACE_2);

        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                // TODO - check some error string
                assertToolError(response, "403");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster returns error for auto-discover without namespace")
    void testGetClusterAutoDiscoverWithoutNamespace() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);

        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                // TODO - check some error string
                assertToolError(response);
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster_pods succeeds in accessible namespace")
    void testGetClusterPodsAccessible() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_pods", args, response -> {
                JsonNode root = assertToolSuccess(response);

                // TODO - add more asserts
                LOGGER.info("get_kafka_cluster_pods (accessible): {} entries",
                    root.isArray() ? root.size() : 1);
                LOGGER.debug("get_kafka_cluster_pods (accessible) response:\n{}", response.content().getFirst().asText().text());
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_cluster_logs succeeds in accessible namespace")
    void testGetClusterLogsAccessible() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE,
            "tailLines", 20);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("get_kafka_cluster_logs (accessible): {} log lines",
                    root.path("log_lines").asInt());
                LOGGER.debug("get_kafka_cluster_logs (accessible) response:\n{}", response.content().getFirst().asText().text());
                assertEquals(Constants.KAFKA_CLUSTER_NAME, root.path("cluster_name").asText());
                // TODO assert details about logs
            })
            .thenAssertResults();
    }

    // ---- Pattern 2: Fleet overview degradation ----

    @Test
    @Story("get_kafka_fleet_overview succeeds with accessible namespace filter")
    void testFleetOverviewAccessibleNamespace() {
        Map<String, Object> args = Map.of("namespace", Constants.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("get_kafka_fleet_overview (accessible ns): total_clusters={}",
                    root.path("total_clusters").asInt());
                LOGGER.debug("get_kafka_fleet_overview (accessible ns) response:\n{}", response.content().getFirst().asText().text());
                assertEquals(1, root.path("total_clusters").asInt(),
                    "Should see exactly 1 cluster in accessible namespace");
                // TODO - assert at least cluster name and nubmer of pods
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_fleet_overview returns error for inaccessible namespace")
    void testFleetOverviewInaccessibleNamespace() {
        Map<String, Object> args = Map.of("namespace", Constants.KAFKA_NAMESPACE_2);

        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", args, response -> {
                // TODO - check some error string
                assertToolError(response);
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_fleet_overview without namespace returns error (cluster-wide)")
    void testFleetOverviewClusterWide() {
        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", Map.of(), response -> {
                // TODO - check some error string
                assertToolError(response);
            })
            .thenAssertResults();
    }

    // ---- Pattern 3: Diagnostic tools under partial access ----

    @Test
    @Story("diagnose_kafka_cluster succeeds in accessible namespace")
    void testDiagnoseClusterAccessible() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("diagnose_kafka_cluster", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("diagnose_kafka_cluster (accessible): steps={}",
                    root.path("steps_completed").size());
                LOGGER.debug("diagnose_kafka_cluster (accessible) response:\n{}", response.content().getFirst().asText().text());
                JsonNode steps = root.path("steps_completed");
                assertTrue(steps.isArray() && !steps.isEmpty(),
                    "Diagnostic should complete at least some steps");
                // TODO - add asserts for steps details
            })
            .thenAssertResults();
    }

    @Test
    @Story("diagnose_kafka_cluster returns error for inaccessible namespace")
    void testDiagnoseClusterInaccessible() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME_2,
            "namespace", Constants.KAFKA_NAMESPACE_2);

        mcpClient.when()
            .toolsCall("diagnose_kafka_cluster", args, response -> {
                // TODO - check some error string
                assertToolError(response);
            })
            .thenAssertResults();
    }

    // ---- Cluster-scoped resources ----

    @Test
    @Story("list_drain_cleaners returns 403 without cluster-scoped access")
    void testDrainCleanersRequireClusterScope() {
        Map<String, Object> args = Map.of();
        mcpClient.when()
            .toolsCall("list_drain_cleaners", args, response -> {
                assertToolError(response, "403");
            })
            .thenAssertResults();
    }

    // ---- Sensitive RBAC (certificates / secrets) ----

    @Test
    @Story("Sensitive RBAC")
    void testCertificatesSensitiveRbacLifecycle() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE);

        // Without sensitive RBAC: certificates should degrade gracefully
        mcpClient.when()
            .toolsCall("get_kafka_cluster_certificates", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("get_kafka_cluster_certificates (no sensitive RBAC): {}", root);
                LOGGER.debug("get_kafka_cluster_certificates (no sensitive RBAC) response:\n{}", response.content().getFirst().asText().text());
                assertTrue(root.has("listener_authentication"),
                    "Should include listener authentication info (from Kafka spec, no secrets needed)");
                JsonNode certs = root.path("certificates");
                assertTrue(!certs.isArray() || certs.isEmpty(),
                    "Certificates should be empty without sensitive RBAC");
                JsonNode errors = root.path("errors");
                assertTrue(errors.isArray() && !errors.isEmpty(),
                    "Should report errors about secret access");
            })
            .thenAssertResults();

        // Deploy sensitive RBAC (grants secret read access)
        McpServerSetup.deploySensitiveRbac(
            mcpNamespace.getMetadata().getName(),
            kafkaNamespace.getMetadata().getName());

        // With sensitive RBAC: certificates should be accessible
        mcpClient.when()
            .toolsCall("get_kafka_cluster_certificates", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("get_kafka_cluster_certificates (with sensitive RBAC): certs={}",
                    root.path("certificates").size());
                LOGGER.debug("get_kafka_cluster_certificates (with sensitive RBAC) response:\n{}", response.content().getFirst().asText().text());
                assertTrue(root.path("certificates").isArray(),
                    "Should have certificates array");
                assertFalse(root.toString().contains("PRIVATE KEY"),
                    "Should not expose private keys");
            })
            .thenAssertResults();
    }

    // ---- Operator tools with scoped access ----

    @Test
    @Story("list_strimzi_operators succeeds in accessible Strimzi namespace")
    void testListOperatorsAccessible() {
        Map<String, Object> args = Map.of("namespace", Constants.STRIMZI_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_strimzi_operators", args, response -> {
                JsonNode root = assertToolSuccess(response);

                // TODO - add detailed asserts
                LOGGER.info("list_strimzi_operators (accessible): {}", root);
                LOGGER.debug("list_strimzi_operators (accessible) response:\n{}", response.content().getFirst().asText().text());
            })
            .thenAssertResults();
    }

    // ---- Sensitive RBAC: metrics pods/proxy access ----

    @Test
    @Story("get_kafka_metrics returns error without pods/proxy permission")
    void testMetricsWithoutPodsProxy() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("get_kafka_metrics (no sensitive RBAC): {}", root);
                LOGGER.debug("get_kafka_metrics (no sensitive RBAC) response:\n{}", response.content().getFirst().asText().text());
                // TODO: should instead return info that MCP doesn't have access to metrics on pods?
                assertEquals(0, root.path("metric_count").asInt(),
                    "Should return 0 metrics without pods/proxy permission");
                assertFalse(root.path("provider").isMissingNode(),
                    "Should include provider field");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_metrics succeeds after deploying sensitive RBAC (pods/proxy)")
    void testMetricsWithPodsProxy() {
        McpServerSetup.deploySensitiveRbac(
            mcpNamespace.getMetadata().getName(),
            kafkaNamespace.getMetadata().getName());

        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                assertToolSuccess(response);
                // TODO - add more asserts
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_metrics (with sensitive RBAC): response length={}", text.length());
                LOGGER.debug("get_kafka_metrics (with sensitive RBAC) response:\n{}", text);
            })
            .thenAssertResults();
    }

    // ---- Sensitive RBAC: data leakage verification ----

    @Test
    @Story("Certificates response never contains private key material")
    void testCertificatesNeverLeakPrivateKeys() {
        McpServerSetup.deploySensitiveRbac(
            mcpNamespace.getMetadata().getName(),
            kafkaNamespace.getMetadata().getName());

        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_certificates", args, response -> {
                JsonNode root = assertToolSuccess(response);

                LOGGER.info("get_kafka_cluster_certificates (sensitive RBAC) full response: {}", root);
                String fullResponse = root.toString();
                assertFalse(fullResponse.contains("PRIVATE KEY"),
                    "Should not expose any private key material");
                assertFalse(fullResponse.contains("BEGIN RSA"),
                    "Should not expose RSA private keys");
                assertFalse(fullResponse.contains("BEGIN EC"),
                    "Should not expose EC private keys");
                JsonNode certs = root.path("certificates");
                if (certs.isArray() && !certs.isEmpty()) {
                    for (JsonNode cert : certs) {
                        LOGGER.info("Certificate entry fields: {}", cert);
                        assertFalse(cert.path("not_after").isMissingNode(),
                            "Each certificate should include not_after metadata");
                    }
                    LOGGER.info("Verified {} certificates contain metadata but no private keys",
                        certs.size());
                }
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_user never leaks secret credential values")
    void testUserToolNeverLeaksSecretData() {
        McpServerSetup.deploySensitiveRbac(
            mcpNamespace.getMetadata().getName(),
            kafkaNamespace.getMetadata().getName());

        Map<String, Object> args = Map.of("namespace", Constants.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("list_kafka_users", args, response -> {
                // TODO - asserts properly data about users
                assertToolSuccess(response);

                String fullResponse = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_users response length={}", fullResponse.length());
                LOGGER.debug("list_kafka_users response:\n{}", fullResponse);
                assertFalse(fullResponse.contains("sasl.jaas.config"),
                    "User listing should not contain JAAS config values");
                assertFalse(fullResponse.contains("BEGIN CERTIFICATE"),
                    "User listing should not contain raw certificate PEM data");
            })
            .thenAssertResults();
    }
}
