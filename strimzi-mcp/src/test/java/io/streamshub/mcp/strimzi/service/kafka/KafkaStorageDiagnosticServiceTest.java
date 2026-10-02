/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.config.metrics.KafkaMetricCategories;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterPodsResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaPvcInfo;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaPvcResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaStorageDiagnosticReport;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;
import io.streamshub.mcp.strimzi.service.kafkanodepool.KafkaNodePoolService;
import io.streamshub.mcp.strimzi.service.metrics.KafkaMetricsService;
import io.streamshub.mcp.strimzi.service.operator.StrimziEventsService;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KafkaStorageDiagnosticService}.
 */
@QuarkusTest
class KafkaStorageDiagnosticServiceTest {

    private static final String NAMESPACE = "kafka";
    private static final String CLUSTER_NAME = "my-cluster";

    @InjectMock
    KafkaService kafkaService;

    @InjectMock
    KafkaNodePoolService nodePoolService;

    @InjectMock
    KafkaMetricsService metricsService;

    @InjectMock
    StrimziEventsService eventsService;

    @Inject
    KafkaStorageDiagnosticService diagnosticService;

    KafkaStorageDiagnosticServiceTest() {
    }

    @BeforeEach
    void setUp() {
        when(kafkaService.getCluster(NAMESPACE, CLUSTER_NAME)).thenReturn(
            new KafkaClusterResponse(CLUSTER_NAME, NAMESPACE, "Kafka", "4.2.0",
                "4.2.0", null, null, null, "Ready", List.of(), List.of(), null, null,
                false, true, false, null, 60L, "strimzi", null, null, null, List.of())
        );
        when(kafkaService.getClusterPvcs(NAMESPACE, CLUSTER_NAME)).thenReturn(
            KafkaPvcResponse.of(CLUSTER_NAME, NAMESPACE, List.of(
                KafkaPvcInfo.of("data-0", NAMESPACE, "Bound", "standard", "100Gi", "100Gi", "pvc-1", true)
            ))
        );
        when(kafkaService.getClusterPods(NAMESPACE, CLUSTER_NAME)).thenReturn(
            KafkaClusterPodsResponse.of(CLUSTER_NAME, NAMESPACE, PodSummaryResponse.of(NAMESPACE, List.of()))
        );
        when(nodePoolService.listNodePools(NAMESPACE, CLUSTER_NAME)).thenReturn(List.of());
        when(metricsService.getKafkaMetrics(eq(NAMESPACE), eq(CLUSTER_NAME), eq(KafkaMetricCategories.STORAGE), any(), any(), any(), any(), any(), any(), any())).thenReturn(
            KafkaMetricsResponse.empty(CLUSTER_NAME, NAMESPACE, "No storage metrics found")
        );
        when(eventsService.getEvents(eq(NAMESPACE), eq(CLUSTER_NAME), eq(StrimziConstants.KindValues.KAFKA), any())).thenReturn(
            StrimziEventsResponse.empty(CLUSTER_NAME, NAMESPACE)
        );
    }

    @Test
    void testThrowsWhenClusterNameNull() {
        assertThrows(McpException.class, () ->
            diagnosticService.diagnose(NAMESPACE, null, null, null, null, null, null, null));
    }

    @Test
    void testThrowsWhenClusterNotFound() {
        when(kafkaService.getCluster(NAMESPACE, "nonexistent")).thenThrow(
            new McpException("Kafka cluster 'nonexistent' not found", -32002)
        );
        assertThrows(McpException.class, () ->
            diagnosticService.diagnose(NAMESPACE, "nonexistent", null, null, null, null, null, null));
    }

    @Test
    void testFullWorkflowWithoutSampling() {
        KafkaStorageDiagnosticReport report = diagnosticService.diagnose(
            NAMESPACE, CLUSTER_NAME, null, null, null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.cluster());
        assertEquals(CLUSTER_NAME, report.cluster().name());
        assertNotNull(report.pvcs());
        assertEquals(1, report.pvcs().pvcs().size());
        assertNotNull(report.pods());
        assertNotNull(report.nodePools());
        assertNotNull(report.storageMetrics());
        assertNotNull(report.events());
        assertNull(report.analysis());
        assertTrue(report.stepsCompleted().contains("cluster_status"));
        assertTrue(report.stepsCompleted().contains("pvcs"));
        assertTrue(report.stepsCompleted().contains("node_pools"));
        assertTrue(report.stepsCompleted().contains("pods"));
        assertTrue(report.stepsCompleted().contains("storage_metrics"));
        assertTrue(report.stepsCompleted().contains("events"));
    }
}
