/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.metrics.MetricsQueryService;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.config.metrics.KafkaMetricCategories;
import io.streamshub.mcp.strimzi.dto.metrics.KafkaMetricsResponse;
import io.streamshub.mcp.strimzi.service.metrics.KafkaMetricsService;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Kafka;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that {@link KafkaMetricsDiagnosticService#gatherAllMetrics} queries each category on its own,
 * so a truncated or failing category does not affect the others.
 */
class KafkaMetricsDiagnosticServiceGatherTest {

    private static final List<String> CATEGORIES = List.of(
        KafkaMetricCategories.REPLICATION, KafkaMetricCategories.PERFORMANCE,
        KafkaMetricCategories.RESOURCES, KafkaMetricCategories.THROUGHPUT);

    private final KafkaMetricsService metricsService = Mockito.mock(KafkaMetricsService.class);
    private final List<String> completed = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();

    KafkaMetricsDiagnosticServiceGatherTest() {
    }

    private void givenCategory(final String category, final KafkaMetricsResponse response) {
        when(metricsService.getKafkaMetrics(eq("kafka"), eq("my-cluster"), eq(category), isNull(),
            any(), any(), any(), any(), isNull(), isNull())).thenReturn(response);
    }

    private Map<String, KafkaMetricsResponse> gather() {
        KafkaMetricsDiagnosticService service = new KafkaMetricsDiagnosticService();
        service.kafkaMetricsService = metricsService;
        return service.gatherAllMetrics("kafka", "my-cluster", KafkaMetricsDiagnosticService.InvestigationAreas.all(),
            null, null, null, null, completed, failed);
    }

    private static KafkaMetricsResponse response(final String category, final Integer truncatedAtLimit) {
        return KafkaMetricsResponse.ofTimeSeries("my-cluster", "kafka", "pod-scraping", List.of(category), List.of(),
            "guide", truncatedAtLimit);
    }

    /**
     * Verify each category is queried with its own category name and its response is used as is.
     */
    @Test
    void testQueriesEachCategorySeparately() {
        CATEGORIES.forEach(c -> givenCategory(c, response(c, null)));

        Map<String, KafkaMetricsResponse> results = gather();

        assertEquals(CATEGORIES, List.copyOf(results.keySet()));
        CATEGORIES.forEach(c -> {
            verify(metricsService).getKafkaMetrics(eq("kafka"), eq("my-cluster"), eq(c), isNull(),
                any(), any(), any(), any(), isNull(), isNull());
            assertEquals(List.of(c), results.get(c).categories());
            assertNull(results.get(c).truncated(), c);
        });
        assertEquals(4, completed.size());
        assertTrue(failed.isEmpty());
    }

    /**
     * Verify a truncated category is flagged without flagging the others.
     */
    @Test
    void testTruncationStaysWithItsCategory() {
        CATEGORIES.forEach(c -> givenCategory(c, response(c, null)));
        KafkaMetricsResponse truncated = response(KafkaMetricCategories.PERFORMANCE, 10000);
        givenCategory(KafkaMetricCategories.PERFORMANCE, truncated);

        Map<String, KafkaMetricsResponse> results = gather();

        assertSame(truncated, results.get(KafkaMetricCategories.PERFORMANCE));
        assertEquals(Boolean.TRUE, truncated.truncated());
        assertNull(results.get(KafkaMetricCategories.REPLICATION).truncated());
        assertNull(results.get(KafkaMetricCategories.THROUGHPUT).truncated());
    }

    /**
     * Verify a failing category is reported as failed while the others still return data.
     */
    @Test
    void testFailingCategoryDoesNotDropTheOthers() {
        CATEGORIES.forEach(c -> givenCategory(c, response(c, null)));
        when(metricsService.getKafkaMetrics(eq("kafka"), eq("my-cluster"), eq(KafkaMetricCategories.RESOURCES),
            isNull(), any(), any(), any(), any(), isNull(), isNull()))
            .thenThrow(new IllegalStateException("boom"));

        Map<String, KafkaMetricsResponse> results = gather();

        assertEquals(3, results.size());
        assertEquals(1, failed.size());
        assertTrue(failed.getFirst().contains("boom"), failed.getFirst());
    }

    /**
     * Verify throughput samples under the names the backend returns (Prometheus rate names, SMR
     * {@code persec} names) reach the throughput result. Splitting one combined result by JMX name
     * used to drop every one of them.
     */
    @Test
    void testRenamedThroughputMetricsAreKept() throws Exception {
        java.lang.reflect.Constructor<KafkaMetricsService> ctor = KafkaMetricsService.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        KafkaMetricsService realService = ctor.newInstance();
        KubernetesResourceService k8s = Mockito.mock(KubernetesResourceService.class);
        MetricsQueryService query = Mockito.mock(MetricsQueryService.class);
        KafkaService kafkaService = Mockito.mock(KafkaService.class);
        setField(realService, "k8sService", k8s);
        setField(realService, "metricsQueryService", query);
        setField(realService, "kafkaService", kafkaService);

        Kafka kafka = new Kafka();
        kafka.setMetadata(new ObjectMeta());
        kafka.getMetadata().setName("my-cluster");
        kafka.getMetadata().setNamespace("kafka");
        when(kafkaService.findKafkaCluster("kafka", "my-cluster")).thenReturn(kafka);
        Pod pod = new Pod();
        pod.setMetadata(new ObjectMeta());
        pod.getMetadata().setName("my-cluster-kafka-0");
        pod.getMetadata().setLabels(Map.of(ResourceLabels.STRIMZI_COMPONENT_TYPE_LABEL,
            StrimziConstants.ComponentTypes.KAFKA));
        when(k8s.queryResourcesByLabel(Pod.class, "kafka", ResourceLabels.STRIMZI_CLUSTER_LABEL, "my-cluster"))
            .thenReturn(List.of(pod));
        when(query.queryMetrics(anyList(), anyMap(),
            eq(KafkaMetricCategories.resolve(KafkaMetricCategories.THROUGHPUT)), any(), any(), any(), any()))
            .thenReturn(List.of(
                MetricSample.of("kafka_server_brokertopicmetrics_bytesin_rate_per_second", Map.of(), 10.0),
                MetricSample.of("kafka_server_brokertopicmetrics_bytesinpersec_total", Map.of(), 20.0)));

        KafkaMetricsDiagnosticService service = new KafkaMetricsDiagnosticService();
        service.kafkaMetricsService = realService;
        Map<String, KafkaMetricsResponse> results = service.gatherAllMetrics("kafka", "my-cluster",
            KafkaMetricsDiagnosticService.InvestigationAreas.all(), null, null, null, null, completed, failed);

        assertEquals(2, results.get(KafkaMetricCategories.THROUGHPUT).sampleCount());
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
