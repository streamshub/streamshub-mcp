/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectorResponse;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectorService;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> connectorService.getConnector("kafka", null));

        assertTrue(ex.getMessage().contains("required"));
    }

    /**
     * Verify get throws when connector is not found.
     */
    @Test
    void testGetConnectorThrowsWhenNotFound() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> connectorService.getConnector("kafka", "nonexistent"));

        assertTrue(ex.getMessage().contains("not found"));
    }
}
