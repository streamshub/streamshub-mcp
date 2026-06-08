/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.ReplicasInfo;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaFleetOverviewResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaFleetOverviewResponse.ClusterWarning;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgeResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectResponse;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response;
import io.streamshub.mcp.strimzi.dto.kafkarebalance.KafkaRebalanceResponse;
import io.streamshub.mcp.strimzi.service.kafka.KafkaFleetOverviewService;
import io.streamshub.mcp.strimzi.service.kafka.KafkaService;
import io.streamshub.mcp.strimzi.service.kafkabridge.KafkaBridgeService;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectService;
import io.streamshub.mcp.strimzi.service.kafkamirrormaker2.KafkaMirrorMaker2Service;
import io.streamshub.mcp.strimzi.service.kafkarebalance.KafkaRebalanceService;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.user.KafkaUser;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
/**
 * Service-level tests for {@link KafkaFleetOverviewService}.
 */
@QuarkusTest
class KafkaFleetOverviewServiceTest {

    @InjectMock
    KafkaService kafkaService;

    @InjectMock
    KubernetesResourceService k8sService;

    @InjectMock
    KafkaRebalanceService rebalanceService;

    @InjectMock
    KafkaConnectService connectService;

    @InjectMock
    KafkaBridgeService bridgeService;

    @InjectMock
    KafkaMirrorMaker2Service mirrorMakerService;

    @Inject
    KafkaFleetOverviewService fleetOverviewService;

    KafkaFleetOverviewServiceTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient().when(k8sService.queryResourcesByLabel(
            any(Class.class), anyString(), anyString(), anyString())).thenReturn(List.of());
        lenient().when(rebalanceService.listRebalances(anyString(), anyString()))
            .thenReturn(List.of());
        lenient().when(connectService.listConnects(any())).thenReturn(List.of());
        lenient().when(bridgeService.listBridges(any())).thenReturn(List.of());
        lenient().when(mirrorMakerService.listMirrorMakers(any())).thenReturn(List.of());
    }

    @Test
    void testEmptyFleetReturnsZeros() {
        when(kafkaService.listClusters(null)).thenReturn(List.of());

        KafkaFleetOverviewResponse response = fleetOverviewService.getFleetOverview(null);

        assertNotNull(response);
        assertEquals(0, response.totalClusters());
        assertEquals(0, response.totalBrokers());
        assertEquals(0, response.statusDistribution().ready());
        assertEquals(0, response.statusDistribution().notReady());
        assertEquals(0, response.statusDistribution().error());
        assertEquals(0, response.statusDistribution().unknown());
        assertTrue(response.clusters().isEmpty());
        assertTrue(response.warnings().isEmpty());
        assertNotNull(response.timestamp());
        assertNull(response.namespaceFilter());
    }

    @Test
    void testMixedStatusDistribution() {
        List<KafkaClusterResponse> clusters = List.of(
            buildCluster("prod", "kafka-prod", "Ready", "4.2.0", 3, 3),
            buildCluster("staging", "kafka-staging", "NotReady", "4.2.0", 3, 3),
            buildCluster("dev", "kafka-dev", "Error", "4.1.0", 3, 3),
            buildCluster("test", "kafka-test", "Unknown", "4.2.0", 1, 0)
        );
        when(kafkaService.listClusters(null)).thenReturn(clusters);

        KafkaFleetOverviewResponse response = fleetOverviewService.getFleetOverview(null);

        assertEquals(4, response.totalClusters());
        assertEquals(10, response.totalBrokers());
        assertEquals(1, response.statusDistribution().ready());
        assertEquals(1, response.statusDistribution().notReady());
        assertEquals(1, response.statusDistribution().error());
        assertEquals(1, response.statusDistribution().unknown());
    }

    @Test
    void testWarningsGeneratedForNotReadyAndError() {
        List<KafkaClusterResponse> clusters = List.of(
            buildCluster("prod", "kafka", "Ready", "4.2.0", 3, 3),
            buildCluster("staging", "kafka", "NotReady", "4.2.0", 3, 3),
            buildCluster("dev", "kafka", "Error", "4.1.0", 3, 3)
        );
        when(kafkaService.listClusters(null)).thenReturn(clusters);

        KafkaFleetOverviewResponse response = fleetOverviewService.getFleetOverview(null);

        assertEquals(2, response.warnings().size());
        assertTrue(response.warnings().stream()
            .anyMatch(w -> "staging".equals(w.clusterName()) && "NotReady".equals(w.warningType())));
        assertTrue(response.warnings().stream()
            .anyMatch(w -> "dev".equals(w.clusterName()) && "Error".equals(w.warningType())));
    }

    @Test
    void testReplicaMismatchWarning() {
        List<KafkaClusterResponse> clusters = List.of(
            buildCluster("prod", "kafka", "Ready", "4.2.0", 3, 2)
        );
        when(kafkaService.listClusters(null)).thenReturn(clusters);

        KafkaFleetOverviewResponse response = fleetOverviewService.getFleetOverview(null);

        assertEquals(1, response.warnings().size());
        ClusterWarning warning = response.warnings().get(0);
        assertEquals("prod", warning.clusterName());
        assertEquals("ReplicaMismatch", warning.warningType());
        assertTrue(warning.message().contains("2/3"));
    }

    @Test
    void testWarningsCappedAtTwenty() {
        List<KafkaClusterResponse> clusters = new ArrayList<>();
        IntStream.range(0, 25).forEach(i ->
            clusters.add(buildCluster("cluster-" + i, "ns", "Error", "4.2.0", 3, 1)));
        when(kafkaService.listClusters(null)).thenReturn(clusters);

        KafkaFleetOverviewResponse response = fleetOverviewService.getFleetOverview(null);

        assertEquals(20, response.warnings().size());
    }

    @Test
    void testNamespaceFilterPassedThrough() {
        when(kafkaService.listClusters("kafka-prod")).thenReturn(List.of(
            buildCluster("prod", "kafka-prod", "Ready", "4.2.0", 3, 3)
        ));

        KafkaFleetOverviewResponse response = fleetOverviewService.getFleetOverview("kafka-prod");

        assertEquals(1, response.totalClusters());
        assertEquals("kafka-prod", response.namespaceFilter());
    }

    @Test
    void testNullReplicasHandled() {
        KafkaClusterResponse cluster = KafkaClusterResponse.of(
            "test", "ns", "Kafka", "4.2.0", "Ready",
            null, null, null, null, null,
            null, null, null, null, null, null);
        when(kafkaService.listClusters(null)).thenReturn(List.of(cluster));

        KafkaFleetOverviewResponse response = fleetOverviewService.getFleetOverview(null);

        assertEquals(1, response.totalClusters());
        assertEquals(0, response.totalBrokers());
        assertEquals(0, response.clusters().get(0).brokers());
        assertEquals(0, response.clusters().get(0).readyBrokers());
    }

    @Test
    void testClusterSummaryFields() {
        List<KafkaClusterResponse> clusters = List.of(
            buildCluster("prod", "kafka-prod", "Ready", "4.2.0", 3, 3)
        );
        when(kafkaService.listClusters(null)).thenReturn(clusters);

        KafkaFleetOverviewResponse response = fleetOverviewService.getFleetOverview(null);

        assertEquals(1, response.clusters().size());
        var summary = response.clusters().get(0);
        assertEquals("prod", summary.name());
        assertEquals("kafka-prod", summary.namespace());
        assertEquals("Ready", summary.readiness());
        assertEquals("4.2.0", summary.kafkaVersion());
        assertEquals(3, summary.brokers());
        assertEquals(3, summary.readyBrokers());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testTopicAndUserCounts() {
        when(kafkaService.listClusters(null)).thenReturn(List.of(
            buildCluster("prod", "kafka", "Ready", "4.2.0", 3, 3)
        ));
        KafkaTopic topic1 = new KafkaTopic();
        KafkaTopic topic2 = new KafkaTopic();
        when(k8sService.queryResourcesByLabel(
            eq(KafkaTopic.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("prod")))
            .thenReturn(List.of(topic1, topic2));

        KafkaUser user1 = new KafkaUser();
        when(k8sService.queryResourcesByLabel(
            eq(KafkaUser.class), eq("kafka"),
            eq(ResourceLabels.STRIMZI_CLUSTER_LABEL), eq("prod")))
            .thenReturn(List.of(user1));

        KafkaFleetOverviewResponse response = fleetOverviewService.getFleetOverview(null);

        var summary = response.clusters().get(0);
        assertEquals(2, summary.topicCount());
        assertEquals(1, summary.userCount());
    }

    @Test
    void testActiveRebalanceCounts() {
        when(kafkaService.listClusters(null)).thenReturn(List.of(
            buildCluster("prod", "kafka", "Ready", "4.2.0", 3, 3)
        ));
        when(rebalanceService.listRebalances("kafka", "prod")).thenReturn(List.of(
            buildRebalance("rebalance-1", "Rebalancing"),
            buildRebalance("rebalance-2", "Ready"),
            buildRebalance("rebalance-3", "ProposalReady")
        ));

        KafkaFleetOverviewResponse response = fleetOverviewService.getFleetOverview(null);

        assertEquals(2, response.clusters().get(0).activeRebalances());
    }

    @Test
    void testConnectedConnectCount() {
        when(kafkaService.listClusters(null)).thenReturn(List.of(
            buildCluster("prod", "kafka", "Ready", "4.2.0", 3, 3)
        ));
        when(connectService.listConnects(null)).thenReturn(List.of(
            buildConnect("my-connect", "prod-kafka-bootstrap:9092"),
            buildConnect("other-connect", "other-cluster-kafka-bootstrap:9092")
        ));

        KafkaFleetOverviewResponse response = fleetOverviewService.getFleetOverview(null);

        assertEquals(1, response.clusters().get(0).connectCount());
    }

    @Test
    void testConnectedBridgeCount() {
        when(kafkaService.listClusters(null)).thenReturn(List.of(
            buildCluster("prod", "kafka", "Ready", "4.2.0", 3, 3)
        ));
        when(bridgeService.listBridges(null)).thenReturn(List.of(
            buildBridge("my-bridge", "prod-kafka-bootstrap:9092")
        ));

        KafkaFleetOverviewResponse response = fleetOverviewService.getFleetOverview(null);

        assertEquals(1, response.clusters().get(0).bridgeCount());
    }

    @Test
    void testConnectedMirrorMakerCount() {
        when(kafkaService.listClusters(null)).thenReturn(List.of(
            buildCluster("prod", "kafka", "Ready", "4.2.0", 3, 3)
        ));
        when(mirrorMakerService.listMirrorMakers(null)).thenReturn(List.of(
            buildMirrorMaker("my-mm2", "prod-kafka-bootstrap:9092",
                "prod", List.of("source-cluster"))
        ));

        KafkaFleetOverviewResponse response = fleetOverviewService.getFleetOverview(null);

        assertEquals(1, response.clusters().get(0).mirrorMakerCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFailedResourceQueryReturnsNull() {
        when(kafkaService.listClusters(null)).thenReturn(List.of(
            buildCluster("prod", "kafka", "Ready", "4.2.0", 3, 3)
        ));
        when(k8sService.queryResourcesByLabel(
            eq(KafkaTopic.class), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("API unavailable"));

        KafkaFleetOverviewResponse response = fleetOverviewService.getFleetOverview(null);

        assertNull(response.clusters().get(0).topicCount());
    }

    // ---- Test helpers ----

    private static KafkaClusterResponse buildCluster(String name, String namespace,
                                                      String readiness, String version,
                                                      int expected, int ready) {
        return KafkaClusterResponse.of(
            name, namespace, "Kafka", version, readiness,
            null, null, ReplicasInfo.of(expected, ready),
            null, null, null, null, null, null, 1000L, null);
    }

    private static KafkaRebalanceResponse buildRebalance(String name, String state) {
        return KafkaRebalanceResponse.of(
            name, "kafka", "prod", state, null, null, null, null, null, null, null);
    }

    private static KafkaConnectResponse buildConnect(String name, String bootstrapServers) {
        return KafkaConnectResponse.of(
            name, "kafka", "Ready", null, null, bootstrapServers,
            null, null, null, null, null, null);
    }

    private static KafkaBridgeResponse buildBridge(String name, String bootstrapServers) {
        return KafkaBridgeResponse.of(
            name, "kafka", "Ready", null, bootstrapServers,
            null, null, null, null, null,
            null, null, null, null, null, null, null, null);
    }

    private static KafkaMirrorMaker2Response buildMirrorMaker(String name,
                                                               String bootstrapServers,
                                                               String targetCluster,
                                                               List<String> sourceAliases) {
        return KafkaMirrorMaker2Response.of(
            name, "kafka", "Ready", null,
            targetCluster, sourceAliases, null, null, null,
            null, bootstrapServers, null, null, null);
    }
}
