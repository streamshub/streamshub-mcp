/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.ReplicasInfo;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterOverviewResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.kafka.RoleReplicasInfo;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectResponse;
import io.streamshub.mcp.strimzi.dto.kafkarebalance.KafkaRebalanceResponse;
import io.streamshub.mcp.strimzi.service.draincleaner.DrainCleanerService;
import io.streamshub.mcp.strimzi.service.kafkabridge.KafkaBridgeService;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectService;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectorService;
import io.streamshub.mcp.strimzi.service.kafkamirrormaker2.KafkaMirrorMaker2Service;
import io.streamshub.mcp.strimzi.service.kafkanodepool.KafkaNodePoolService;
import io.streamshub.mcp.strimzi.service.kafkarebalance.KafkaRebalanceService;
import io.streamshub.mcp.strimzi.service.operator.StrimziOperatorService;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.topic.KafkaTopicBuilder;
import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.api.kafka.model.user.KafkaUserBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Service-level tests for {@link KafkaClusterOverviewService}, exercising its real
 * aggregation logic (summary, counts, bootstrap-based connected-resource filtering,
 * and per-section resilience) rather than mocking the service away.
 */
@QuarkusTest
class KafkaClusterOverviewServiceTest {

    private static final String CLUSTER = "my-cluster";
    private static final String NS = "kafka";

    @InjectMock
    KubernetesResourceService k8sService;

    @InjectMock
    KafkaService kafkaService;

    @InjectMock
    KafkaNodePoolService nodePoolService;

    @InjectMock
    StrimziOperatorService operatorService;

    @InjectMock
    KafkaRebalanceService rebalanceService;

    @InjectMock
    KafkaConnectService connectService;

    @InjectMock
    KafkaConnectorService connectorService;

    @InjectMock
    KafkaBridgeService bridgeService;

    @InjectMock
    KafkaMirrorMaker2Service mirrorMakerService;

    @InjectMock
    DrainCleanerService drainCleanerService;

    @Inject
    KafkaClusterOverviewService overviewService;

    KafkaClusterOverviewServiceTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient().when(kafkaService.getCluster(any(), anyString()))
            .thenReturn(buildCluster(CLUSTER, NS, "Ready", "4.2.0", 3, 3));
        lenient().when(k8sService.queryResourcesByLabel(any(Class.class), any(), anyString(), anyString()))
            .thenReturn(List.of());
        lenient().when(nodePoolService.listNodePools(any(), anyString())).thenReturn(List.of());
        lenient().when(operatorService.listOperators(any())).thenReturn(List.of());
        lenient().when(rebalanceService.listRebalances(any(), anyString())).thenReturn(List.of());
        lenient().when(connectService.listConnects(any())).thenReturn(List.of());
        lenient().when(connectorService.listConnectors(any(), anyString())).thenReturn(List.of());
        lenient().when(bridgeService.listBridges(any())).thenReturn(List.of());
        lenient().when(mirrorMakerService.listMirrorMakers(any())).thenReturn(List.of());
        lenient().when(drainCleanerService.listDrainCleaners(any())).thenReturn(List.of());
    }

    @Test
    void testOverviewBuildsClusterSummary() {
        KafkaClusterOverviewResponse response = overviewService.getOverview(NS, CLUSTER);

        assertNotNull(response);
        assertEquals(CLUSTER, response.cluster().name());
        assertEquals(NS, response.cluster().namespace());
        assertEquals("Ready", response.cluster().readiness());
        assertEquals("4.2.0", response.cluster().kafkaVersion());
        assertEquals(3, response.cluster().expectedBrokers());
        assertEquals(3, response.cluster().readyBrokers());
        assertNotNull(response.timestamp());
    }

    @Test
    void testConnectedConnectsFilteredByBootstrap() {
        KafkaConnectResponse matching = KafkaConnectResponse.of(
            "connect-a", NS, "Ready", ReplicasInfo.of(2, 2), "4.2.0",
            CLUSTER + "-kafka-bootstrap:9092", null, null, List.of(), List.of(),
            null, null, null, null);
        KafkaConnectResponse unrelated = KafkaConnectResponse.of(
            "connect-b", NS, "Ready", ReplicasInfo.of(1, 1), "4.2.0",
            "other-cluster-kafka-bootstrap:9092", null, null, List.of(), List.of(),
            null, null, null, null);
        when(connectService.listConnects(any())).thenReturn(List.of(matching, unrelated));

        KafkaClusterOverviewResponse response = overviewService.getOverview(NS, CLUSTER);

        assertEquals(1, response.kafkaConnects().size(),
            "Only the KafkaConnect pointing at this cluster's bootstrap should be included");
        assertEquals("connect-a", response.kafkaConnects().getFirst().name());
    }

    @Test
    void testTopicAndUserReadyCountsSplit() {
        KafkaTopic readyTopic = new KafkaTopicBuilder()
            .withNewMetadata().withName("t-ready").endMetadata()
            .withNewStatus().addNewCondition().withType("Ready").withStatus("True").endCondition().endStatus()
            .build();
        KafkaTopic notReadyTopic = new KafkaTopicBuilder()
            .withNewMetadata().withName("t-pending").endMetadata()
            .build();
        KafkaUser readyUser = new KafkaUserBuilder()
            .withNewMetadata().withName("u-ready").endMetadata()
            .withNewStatus().addNewCondition().withType("Ready").withStatus("True").endCondition().endStatus()
            .build();

        when(k8sService.queryResourcesByLabel(eq(KafkaTopic.class), any(), anyString(), anyString()))
            .thenReturn(List.of(readyTopic, notReadyTopic));
        when(k8sService.queryResourcesByLabel(eq(KafkaUser.class), any(), anyString(), anyString()))
            .thenReturn(List.of(readyUser));

        KafkaClusterOverviewResponse response = overviewService.getOverview(NS, CLUSTER);

        assertEquals(2, response.topics().total());
        assertEquals(1, response.topics().ready());
        assertEquals(1, response.topics().notReady());
        assertEquals(1, response.users().total());
        assertEquals(1, response.users().ready());
    }

    @Test
    void testRebalanceStatesAggregated() {
        KafkaRebalanceResponse r1 = KafkaRebalanceResponse.of(
            "rb-1", NS, CLUSTER, "ProposalReady", null, null, null, null, null, null, List.of(), null);
        KafkaRebalanceResponse r2 = KafkaRebalanceResponse.of(
            "rb-2", NS, CLUSTER, "Ready", null, null, null, null, null, null, List.of(), null);
        when(rebalanceService.listRebalances(any(), anyString())).thenReturn(List.of(r1, r2));

        KafkaClusterOverviewResponse response = overviewService.getOverview(NS, CLUSTER);

        assertEquals(2, response.rebalances().total());
        assertEquals(1, response.rebalances().states().get("ProposalReady"));
        assertEquals(1, response.rebalances().states().get("Ready"));
    }

    @Test
    void testResilienceWhenNodePoolServiceThrows() {
        when(nodePoolService.listNodePools(any(), anyString()))
            .thenThrow(new RuntimeException("boom"));

        KafkaClusterOverviewResponse response = overviewService.getOverview(NS, CLUSTER);

        assertNull(response.nodePools(), "A failing node-pool query degrades to null");
        assertNotNull(response.cluster(), "The rest of the overview is still built");
        assertEquals(CLUSTER, response.cluster().name());
    }

    @Test
    void testRequiresClusterName() {
        assertThrows(McpException.class, () -> overviewService.getOverview(NS, null));
    }

    private static KafkaClusterResponse buildCluster(final String name, final String namespace,
                                                     final String readiness, final String version,
                                                     final int expected, final int ready) {
        return KafkaClusterResponse.of(
            name, namespace, "Kafka", version, null,
            null, null, null, readiness,
            null, null, RoleReplicasInfo.of(expected, ready, null, null), null,
            null, null, null, null, 1000L, null, null, null, null, List.of());
    }
}
