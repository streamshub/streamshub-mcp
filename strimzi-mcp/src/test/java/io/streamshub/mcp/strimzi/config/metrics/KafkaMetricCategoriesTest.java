/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config.metrics;

import io.streamshub.mcp.common.dto.metrics.AggregationLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Unit tests for {@link KafkaMetricCategories}.
 */
class KafkaMetricCategoriesTest {

    KafkaMetricCategoriesTest() {
    }

    @Test
    void resolveValidCategoryReturnsMetrics() {
        List<String> metrics = KafkaMetricCategories.resolve("replication");
        assertEquals(8, metrics.size());
        assertTrue(metrics.contains("kafka_server_replicamanager_underreplicatedpartitions"));
        assertTrue(metrics.contains("kafka_controller_controllerstats_uncleanleaderelections_total"));
        assertTrue(metrics.contains("kafka_controller_kafkacontroller_activecontrollercount"));

        List<String> throughputMetrics = KafkaMetricCategories.resolve("throughput");
        assertEquals(8, throughputMetrics.size());
        assertTrue(throughputMetrics.contains("kafka_server_brokertopicmetrics_failedproducerequests_total"));
        assertTrue(throughputMetrics.contains("kafka_server_brokertopicmetrics_failedfetchrequests_total"));
        assertTrue(throughputMetrics.contains("kafka_server_socket_server_metrics_connection_count"));

        List<String> resourcesMetrics = KafkaMetricCategories.resolve("resources");
        assertEquals(7, resourcesMetrics.size());
        assertTrue(resourcesMetrics.contains("process_open_fds"));

        List<String> kraftMetrics = KafkaMetricCategories.resolve("kraft");
        assertEquals(13, kraftMetrics.size());
        assertTrue(kraftMetrics.contains("kafka_server_raftmetrics_current_state"));

        List<String> partitionsMetrics = KafkaMetricCategories.resolve("partitions");
        assertEquals(3, partitionsMetrics.size());
        assertTrue(partitionsMetrics.contains("kafka_cluster_partition_underminisr"));
        assertTrue(partitionsMetrics.contains("kafka_cluster_partition_atminisr"));
        assertTrue(partitionsMetrics.contains("kafka_cluster_partition_replicascount"));
    }

    @Test
    void resolveUnknownCategoryReturnsEmpty() {
        List<String> metrics = KafkaMetricCategories.resolve("nonexistent");
        assertTrue(metrics.isEmpty());
    }

    @Test
    void resolveNullReturnsEmpty() {
        List<String> metrics = KafkaMetricCategories.resolve(null);
        assertTrue(metrics.isEmpty());
    }

    @Test
    void resolveIsCaseInsensitive() {
        List<String> lower = KafkaMetricCategories.resolve("replication");
        List<String> upper = KafkaMetricCategories.resolve("REPLICATION");
        List<String> mixed = KafkaMetricCategories.resolve("Replication");
        assertEquals(lower, upper);
        assertEquals(lower, mixed);
    }

    @Test
    void allCategoriesReturnsSixCategories() {
        Set<String> categories = KafkaMetricCategories.allCategories();
        assertEquals(6, categories.size());
        assertTrue(categories.contains("replication"));
        assertTrue(categories.contains("throughput"));
        assertTrue(categories.contains("resources"));
        assertTrue(categories.contains("performance"));
        assertTrue(categories.contains("kraft"));
        assertTrue(categories.contains("partitions"));
    }

    @Test
    void interpretationWithValidCategoryReturnsGuide() {
        String interpretation = KafkaMetricCategories.interpretation(List.of("replication"));
        assertNotNull(interpretation);
        assertTrue(interpretation.contains("underreplicatedpartitions"));
    }

    @Test
    void interpretationWithMultipleCategoriesJoinsThem() {
        String interpretation = KafkaMetricCategories.interpretation(
            List.of("replication", "throughput", "resources", "performance", "kraft", "partitions"));
        assertNotNull(interpretation);
        assertTrue(interpretation.contains("underreplicatedpartitions"));
        assertTrue(interpretation.contains("uncleanleaderelections"));
        assertTrue(interpretation.contains("activecontrollercount"));
        assertTrue(interpretation.contains("failedproducerequests"));
        assertTrue(interpretation.contains("failedfetchrequests"));
        assertTrue(interpretation.contains("connection_count"));
        assertTrue(interpretation.contains("process_open_fds"));
        assertTrue(interpretation.contains("underminisr"));
        assertTrue(interpretation.contains("atminisr"));
        assertTrue(interpretation.contains("requesthandleravgidle_percent"));
        assertTrue(interpretation.contains("current_state label"));
    }

    @Test
    void interpretationWithNullReturnsNull() {
        assertNull(KafkaMetricCategories.interpretation(null));
    }

    @Test
    void interpretationWithEmptyListReturnsNull() {
        assertNull(KafkaMetricCategories.interpretation(List.of()));
    }

    @Test
    void interpretationWithUnknownCategoryReturnsNull() {
        assertNull(KafkaMetricCategories.interpretation(List.of("nonexistent")));
    }

    @Test
    void maxGranularityPartitionsReturnsPartition() {
        assertEquals(AggregationLevel.PARTITION, KafkaMetricCategories.maxGranularity("partitions"));
        assertEquals(AggregationLevel.PARTITION, KafkaMetricCategories.maxGranularity("PARTITIONS"));
    }

    @Test
    void maxGranularityThroughputReturnsTopic() {
        assertEquals(AggregationLevel.TOPIC, KafkaMetricCategories.maxGranularity("throughput"));
        assertEquals(AggregationLevel.TOPIC, KafkaMetricCategories.maxGranularity("THROUGHPUT"));
    }

    @Test
    void maxGranularityOtherCategoriesReturnsBroker() {
        assertEquals(AggregationLevel.BROKER, KafkaMetricCategories.maxGranularity("replication"));
        assertEquals(AggregationLevel.BROKER, KafkaMetricCategories.maxGranularity("performance"));
        assertEquals(AggregationLevel.BROKER, KafkaMetricCategories.maxGranularity("resources"));
        assertEquals(AggregationLevel.BROKER, KafkaMetricCategories.maxGranularity("kraft"));
    }

    @Test
    void maxGranularityNullReturnsBroker() {
        assertEquals(AggregationLevel.BROKER, KafkaMetricCategories.maxGranularity(null));
    }

    @Test
    void maxGranularityUnknownReturnsBroker() {
        assertEquals(AggregationLevel.BROKER, KafkaMetricCategories.maxGranularity("nonexistent"));
    }
}
