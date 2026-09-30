/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectorResponse;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectorService;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.connector.KafkaConnectorBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Service-level tests for {@link KafkaConnectorService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaConnectorServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaConnectorService connectorService;

    KafkaConnectorServiceTest() {
    }

    @BeforeEach
    void setUp() {
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaConnector.class);
    }

    /**
     * Verify list returns empty when no KafkaConnectors exist.
     */
    @Test
    void testListConnectorsReturnsEmptyWhenNoneExist() {
        List<KafkaConnectorResponse> result = connectorService.listConnectors("production", null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify list returns empty when filtering by connect cluster and none match.
     */
    @Test
    void testListConnectorsFiltersByConnectCluster() {
        List<KafkaConnectorResponse> result =
            connectorService.listConnectors("production", "my-connect");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify get throws when connector name is null.
     */
    @Test
    void testGetConnectorThrowsWhenNameIsNull() {
        McpException ex = assertThrows(McpException.class,
            () -> connectorService.getConnector("kafka", null));

        assertTrue(ex.getMessage().contains("required"));
    }

    /**
     * Verify get throws when connector is not found.
     */
    @Test
    void testGetConnectorThrowsWhenNotFound() {
        McpException ex = assertThrows(McpException.class,
            () -> connectorService.getConnector("kafka", "nonexistent"));

        assertTrue(ex.getMessage().contains("not found"));
    }

    /**
     * Verify get returns no offsets block when {@code spec.listOffsets} is not configured.
     */
    @Test
    void testGetConnectorOmitsOffsetsWhenNotConfigured() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaConnector.class,
            List.of(connector(null)));

        assertNull(connectorService.getConnector("kafka", "my-sink").offsets());
    }

    /**
     * Verify get reads and parses the offsets ConfigMap named by {@code spec.listOffsets.toConfigMap}.
     */
    @Test
    void testGetConnectorReadsOffsetsConfigMap() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaConnector.class,
            List.of(connector("my-sink-offsets")));
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, ConfigMap.class,
            List.of(offsetsConfigMap("my-sink-offsets",
                "{\"offsets\":[{\"partition\":{\"filename\":\"/tmp/in\"},\"offset\":{\"position\":42}}]}")));

        KafkaConnectorResponse.OffsetsInfo offsets = connectorService.getConnector("kafka", "my-sink").offsets();

        assertNotNull(offsets);
        assertEquals("my-sink-offsets", offsets.configMapName());
        assertTrue(offsets.available());
        assertNull(offsets.message());
        assertEquals(1, ((List<?>) offsets.offsets().get("offsets")).size());
    }

    /**
     * Verify get reports the offsets as unavailable when the operator has not written the ConfigMap yet.
     */
    @Test
    void testGetConnectorReportsMissingOffsetsConfigMap() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaConnector.class,
            List.of(connector("my-sink-offsets")));
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, ConfigMap.class, List.of());

        KafkaConnectorResponse.OffsetsInfo offsets = connectorService.getConnector("kafka", "my-sink").offsets();

        assertNotNull(offsets);
        assertEquals("my-sink-offsets", offsets.configMapName());
        assertFalse(offsets.available());
        assertNull(offsets.offsets());
        assertTrue(offsets.message().contains("strimzi.io/connector-offsets"));
    }

    /**
     * Verify get reports unparseable offsets payloads rather than failing the whole call.
     */
    @Test
    void testGetConnectorReportsUnparseableOffsets() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaConnector.class,
            List.of(connector("my-sink-offsets")));
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, ConfigMap.class,
            List.of(offsetsConfigMap("my-sink-offsets", "not json")));

        KafkaConnectorResponse.OffsetsInfo offsets = connectorService.getConnector("kafka", "my-sink").offsets();

        assertNotNull(offsets);
        assertFalse(offsets.available());
        assertTrue(offsets.message().contains("not valid JSON"));
    }

    private static KafkaConnector connector(final String offsetsConfigMapName) {
        KafkaConnectorBuilder builder = new KafkaConnectorBuilder()
            .withNewMetadata()
                .withName("my-sink")
                .withNamespace("kafka")
            .endMetadata()
            .withNewSpec()
                .withClassName("org.apache.kafka.connect.file.FileStreamSinkConnector")
            .endSpec();

        if (offsetsConfigMapName != null) {
            builder = builder.editSpec()
                .withNewListOffsets()
                    .withNewToConfigMap(offsetsConfigMapName)
                .endListOffsets()
                .endSpec();
        }

        return builder.build();
    }

    private static ConfigMap offsetsConfigMap(final String name, final String payload) {
        return new ConfigMapBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace("kafka")
            .endMetadata()
            .withData(Map.of("offsets.json", payload))
            .build();
    }
}
