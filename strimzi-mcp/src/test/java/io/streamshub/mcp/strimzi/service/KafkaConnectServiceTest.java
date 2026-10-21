/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectLogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectPodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectResponse;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectService;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connect.KafkaConnectBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
/**
 * Service-level tests for {@link KafkaConnectService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaConnectServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaConnectService connectService;

    KafkaConnectServiceTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MixedOperation<Pod, PodList, PodResource> podOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.pods()).thenReturn(podOp);

        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaConnect.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);
    }

    /**
     * Verify list returns empty when no KafkaConnect clusters exist.
     */
    @Test
    void testListConnectsReturnsEmptyWhenNoneExist() {
        List<KafkaConnectResponse> result = connectService.listConnects("production");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify get throws when connect name is null.
     */
    @Test
    void testGetConnectThrowsWhenNameIsNull() {
        McpException ex = assertThrows(McpException.class,
            () -> connectService.getConnect("kafka", null));

        assertTrue(ex.getMessage().contains("required"));
    }

    /**
     * Verify pods returns empty for a missing KafkaConnect cluster.
     */
    @Test
    void testGetConnectPodsReturnsEmptyForMissingCluster() {
        KafkaConnectPodsResponse result = connectService.getConnectPods("kafka", "my-connect");

        assertNotNull(result);
        assertEquals("kafka", result.namespace());
        assertEquals("my-connect", result.connectName());
        assertNotNull(result.podSummary());
        assertEquals(0, result.podSummary().totalPods());
    }

    /**
     * Verify logs returns empty when no pods are found.
     */
    @Test
    void testGetConnectLogsReturnsEmptyWhenNoPods() {
        io.streamshub.mcp.common.dto.LogCollectionParams params =
            io.streamshub.mcp.common.dto.LogCollectionParams.builder(200).build();
        KafkaConnectLogsResponse result = connectService.getConnectLogs("kafka", "my-connect", params);

        assertNotNull(result);
        assertEquals("kafka", result.namespace());
        assertEquals("my-connect", result.connectName());
        assertTrue(result.message().contains("No KafkaConnect pods found"));
    }

    /**
     * Verify getConnect returns build == null for a cluster without spec.build.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testGetConnectReturnsBuildNullWhenNoBuildSpec() {
        KafkaConnect connect = new KafkaConnectBuilder()
            .withNewMetadata()
                .withName("my-connect")
                .withNamespace("kafka")
            .endMetadata()
            .withNewSpec()
            .endSpec()
            .withNewStatus()
                .addNewCondition()
                    .withType("Ready")
                    .withStatus("True")
                .endCondition()
            .endStatus()
            .build();

        MixedOperation connectOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(KafkaConnect.class)).thenReturn(connectOp);
        NonNamespaceOperation nsConnectOp = Mockito.mock(NonNamespaceOperation.class);
        when(connectOp.inNamespace("kafka")).thenReturn(nsConnectOp);
        Resource connectResource = Mockito.mock(Resource.class);
        when(nsConnectOp.withName("my-connect")).thenReturn(connectResource);
        when(connectResource.get()).thenReturn(connect);

        KafkaConnectResponse result = connectService.getConnect("kafka", "my-connect");

        assertNotNull(result);
        assertEquals("my-connect", result.name());
        assertNull(result.build());
    }

    /**
     * Verify getConnect returns build with output image for a cluster with spec.build.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testGetConnectReturnsBuildWhenBuildSpecPresent() {
        KafkaConnect connect = new KafkaConnectBuilder()
            .withNewMetadata()
                .withName("my-connect")
                .withNamespace("kafka")
            .endMetadata()
            .withNewSpec()
                .withNewBuild()
                    .withNewDockerOutput()
                        .withImage("my.registry/connect:latest")
                    .endDockerOutput()
                .endBuild()
            .endSpec()
            .withNewStatus()
                .addNewCondition()
                    .withType("Ready")
                    .withStatus("True")
                .endCondition()
            .endStatus()
            .build();

        MixedOperation connectOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(KafkaConnect.class)).thenReturn(connectOp);
        NonNamespaceOperation nsConnectOp = Mockito.mock(NonNamespaceOperation.class);
        when(connectOp.inNamespace("kafka")).thenReturn(nsConnectOp);
        Resource connectResource = Mockito.mock(Resource.class);
        when(nsConnectOp.withName("my-connect")).thenReturn(connectResource);
        when(connectResource.get()).thenReturn(connect);

        KafkaConnectResponse result = connectService.getConnect("kafka", "my-connect");

        assertNotNull(result);
        assertEquals("my-connect", result.name());
        assertNotNull(result.build());
        assertEquals("my.registry/connect:latest", result.build().outputImage());
        assertEquals("docker", result.build().outputType());
    }
}
