/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkatopic;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.service.metrics.KafkaExporterMetricsService;
import io.streamshub.mcp.strimzi.service.operator.StrimziEventsService;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

/**
 * The topic diagnostic gathers events and exporter metrics for the topic, not the whole cluster.
 */
@QuarkusTest
class KafkaTopicDiagnosticScopingTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @InjectMock
    KafkaExporterMetricsService exporterMetricsService;

    @InjectMock
    StrimziEventsService eventsService;

    @Inject
    KafkaTopicDiagnosticService diagnosticService;

    KafkaTopicDiagnosticScopingTest() {
    }

    @Test
    void testExporterMetricsAreFilteredToTheTopic() {
        List<String> completed = new ArrayList<>();
        diagnosticService.gatherExporterMetrics("kafka", "my-cluster", "orders.v1", completed, new ArrayList<>());

        verify(exporterMetricsService).getKafkaExporterMetrics("kafka", "my-cluster", "partitions",
            null, null, null, null, null, null, Map.of("topic", "orders.v1"));
        assertEquals(List.of("exporter_metrics"), completed);
    }

    @Test
    void testConsumerLagCoversTheTopicOverFifteenMinutesPerGroup() {
        List<String> completed = new ArrayList<>();
        diagnosticService.gatherConsumerLag("kafka", "my-cluster", "orders.v1", completed, new ArrayList<>());

        verify(exporterMetricsService).getKafkaExporterMetrics("kafka", "my-cluster", null,
            "kafka_consumergroup_lag", 15, null, null, null, "TOPIC", Map.of("topic", "orders.v1"));
        assertEquals(List.of("consumer_lag"), completed);
    }

    @Test
    void testEventsCoverTheKafkaTopicResource() {
        diagnosticService.gatherEvents("kafka", "orders", "my-cluster", new ArrayList<>(), new ArrayList<>());

        verify(eventsService).getKafkaTopicEvents(eq("kafka"), eq("orders"), eq("my-cluster"), isNull());
    }
}
