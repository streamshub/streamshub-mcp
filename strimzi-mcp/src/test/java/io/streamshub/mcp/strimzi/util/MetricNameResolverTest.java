/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.util;

import io.quarkiverse.mcp.server.McpException;
import io.streamshub.mcp.common.util.metrics.MetricAggregation;
import io.streamshub.mcp.strimzi.config.metrics.KafkaMetricCategories;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Unit tests for {@link MetricNameResolver}.
 */
class MetricNameResolverTest {

    MetricNameResolverTest() {
        // default constructor for checkstyle
    }

    // ---------------------------------------------------------------
    // Existing 5-arg overload (delegates to 7-arg, JMX_EXPORTER path)
    // ---------------------------------------------------------------

    @Test
    void defaultCategoryUsedWhenNeitherCategoryNorNamesProvided() {
        List<String> result = MetricNameResolver.resolve(
            null, null, "default",
            cat -> cat.equals("default") ? List.of("metric_a") : List.of(),
            Set.of("default"));
        assertEquals(List.of("metric_a"), result);
    }

    @Test
    void explicitCategoryOverridesDefault() {
        List<String> result = MetricNameResolver.resolve(
            "other", null, "default",
            cat -> cat.equals("other") ? List.of("metric_b") : List.of(),
            Set.of("default", "other"));
        assertEquals(List.of("metric_b"), result);
    }

    @Test
    void unknownCategoryThrowsMcpException() {
        assertThrows(McpException.class, () ->
            MetricNameResolver.resolve(
                "bogus", null, "default",
                cat -> List.of(),
                Set.of("default")));
    }

    @Test
    void explicitMetricNamesAddedWithoutDuplicates() {
        List<String> result = MetricNameResolver.resolve(
            "cat", "metric_a,metric_extra", "default",
            cat -> List.of("metric_a"),
            Set.of("cat", "default"));
        assertEquals(List.of("metric_a", "metric_extra"), result);
    }

    @Test
    void invalidMetricNameSkipped() {
        List<String> result = MetricNameResolver.resolve(
            null, "valid_metric_name,##invalid##", "default",
            cat -> List.of(),
            Set.of("default"));
        assertEquals(1, result.size());
        assertEquals("valid_metric_name", result.get(0));
    }

    // ---------------------------------------------------------------
    // Backend-resolution via 7-arg overload
    // ---------------------------------------------------------------

    @Test
    void jmxBackendPassesNamesUnchanged() {
        Map<String, String> aliases = Map.of("jmx_name_total", "smr_name_total");
        List<String> result = MetricNameResolver.resolve(
            "cat", null, "default",
            cat -> List.of("jmx_name_total"),
            Set.of("cat"),
            MetricsBackend.JMX_EXPORTER, aliases);
        assertEquals(List.of("jmx_name_total"), result, "JMX_EXPORTER must not translate names");
    }

    @Test
    void smrBackendTranslatesAliasedName() {
        Map<String, String> aliases = Map.of("jmx_name_total", "smr_name_total");
        List<String> result = MetricNameResolver.resolve(
            "cat", null, "default",
            cat -> List.of("jmx_name_total"),
            Set.of("cat"),
            MetricsBackend.STRIMZI_REPORTER, aliases);
        assertEquals(List.of("smr_name_total"), result, "SMR backend must translate aliased name");
    }

    @Test
    void smrBackendExcludesUnmappedName() {
        Map<String, String> aliases = Map.of("jmx_only_metric", MetricNameResolver.UNMAPPED);
        List<String> result = MetricNameResolver.resolve(
            "cat", null, "default",
            cat -> List.of("jmx_only_metric"),
            Set.of("cat"),
            MetricsBackend.STRIMZI_REPORTER, aliases);
        assertTrue(result.isEmpty(), "UNMAPPED metric must be excluded on SMR backend");
    }

    @Test
    void smrBackendPassesThroughNamesNotInAliasMap() {
        Map<String, String> aliases = Map.of("other_jmx_name", "other_smr_name");
        List<String> result = MetricNameResolver.resolve(
            "cat", null, "default",
            cat -> List.of("shared_metric_name"),
            Set.of("cat"),
            MetricsBackend.STRIMZI_REPORTER, aliases);
        assertEquals(List.of("shared_metric_name"), result,
            "Names absent from alias map must pass through unchanged");
    }

    @Test
    void smrBackendNoDuplicatesAfterTranslation() {
        // Two JMX names that both alias to the same SMR name (hypothetical edge case)
        Map<String, String> aliases = Map.of(
            "jmx_a", "smr_common",
            "jmx_b", "smr_common");
        List<String> result = MetricNameResolver.resolve(
            "cat", null, "default",
            cat -> List.of("jmx_a", "jmx_b"),
            Set.of("cat"),
            MetricsBackend.STRIMZI_REPORTER, aliases);
        assertEquals(1, result.size(), "Duplicate SMR names must be de-duplicated");
        assertEquals("smr_common", result.get(0));
    }

    @Test
    void emptyAliasMapSkipsTranslationEvenOnSmrBackend() {
        List<String> result = MetricNameResolver.resolve(
            "cat", null, "default",
            cat -> List.of("some_metric_name"),
            Set.of("cat"),
            MetricsBackend.STRIMZI_REPORTER, Map.of());
        assertEquals(List.of("some_metric_name"), result,
            "Empty alias map must be a no-op");
    }

    // ---------------------------------------------------------------
    // Kafka alias map totality — every entry must have the correct shape
    // ---------------------------------------------------------------

    @Test
    void kafkaAliasMapValuesAreValidMetricNamesOrUnmapped() {
        Map<String, String> aliasMap = KafkaMetricCategories.aliasMap();
        for (Map.Entry<String, String> entry : aliasMap.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            assertTrue(key != null && !key.isBlank(), "Alias map key must not be blank");
            assertTrue(value != null && !value.isBlank(), "Alias map value must not be blank");
            if (!MetricNameResolver.UNMAPPED.equals(value)) {
                // A valid Prometheus metric name: [a-zA-Z_][a-zA-Z0-9_]*
                assertTrue(value.matches("[a-zA-Z_][a-zA-Z0-9_]*"),
                    "Alias map value '" + value + "' for key '" + key + "' is not a valid metric name");
            }
        }
    }

    @Test
    void kafkaAliasMapHasNoSelfMappings() {
        Map<String, String> aliasMap = KafkaMetricCategories.aliasMap();
        for (Map.Entry<String, String> entry : aliasMap.entrySet()) {
            assertFalse(entry.getKey().equals(entry.getValue()),
                "Alias map must not contain self-mappings; found: " + entry.getKey());
        }
    }

    /**
     * Catalog names deliberately left out of the alias map because they are spelled
     * identically under the Strimzi Metrics Reporter, so the resolver passes them through.
     * <p>
     * Every name below was confirmed present by scraping a live Kafka 4.3.1 SMR broker and
     * controller. The same scrape disproved one former member —
     * {@code kafka_server_replicafetchermanager_maxlag}, which SMR does not expose at all —
     * and it is now UNMAPPED in the alias map.
     * <p>
     * The invariant this set enforces: a catalog name lives in the alias map or here, never
     * in neither. Adding a metric to {@link KafkaMetricCategories} forces an SMR decision.
     */
    private static final Set<String> IDENTICAL_ON_SMR = Set.of(
        // REPLICATION — gauges, no PerSec suffix to reconcile
        "kafka_server_replicamanager_underreplicatedpartitions",
        "kafka_server_replicamanager_leadercount",
        "kafka_server_replicamanager_partitioncount",
        "kafka_server_replicamanager_offlinereplicacount",
        "kafka_controller_kafkacontroller_offlinepartitionscount",
        "kafka_controller_kafkacontroller_activecontrollercount",
        // THROUGHPUT — Kafka client-style metric, already snake_case in both backends
        "kafka_server_socket_server_metrics_connection_count",
        // PERFORMANCE — request metric histograms
        "kafka_network_requestmetrics_totaltimems",
        "kafka_network_requestmetrics_requestqueuetimems",
        "kafka_network_requestmetrics_responsequeuetimems",
        // PARTITIONS — per-partition gauges
        "kafka_cluster_partition_underminisr",
        "kafka_cluster_partition_atminisr",
        "kafka_cluster_partition_replicascount",
        // RESOURCES — JVM/process collectors, not Kafka MBeans
        "jvm_memory_used_bytes",
        "jvm_memory_max_bytes",
        "jvm_gc_collection_seconds_count",
        "jvm_gc_collection_seconds_sum",
        "jvm_threads_current",
        "process_cpu_seconds_total",
        "process_open_fds"
    );

    @Test
    void everyKafkaCatalogNameIsClassifiedForSmr() {
        Set<String> catalogNames = KafkaMetricCategories.allCategories().stream()
            .flatMap(c -> KafkaMetricCategories.resolve(c).stream())
            .collect(Collectors.toSet());
        Set<String> aliasKeys = KafkaMetricCategories.aliasMap().keySet();

        Set<String> unclassified = new TreeSet<>(catalogNames);
        unclassified.removeAll(aliasKeys);
        unclassified.removeAll(IDENTICAL_ON_SMR);
        assertTrue(unclassified.isEmpty(),
            "Catalog names with no SMR decision — add an entry to KafkaMetricCategories.aliasMap() "
            + "(or to IDENTICAL_ON_SMR if the name is unchanged under SMR): " + unclassified);

        Set<String> stale = new TreeSet<>(aliasKeys);
        stale.addAll(IDENTICAL_ON_SMR);
        stale.removeAll(catalogNames);
        assertTrue(stale.isEmpty(),
            "SMR classifications for names no longer in any category — remove them: " + stale);
    }

    @Test
    void aliasMapAndIdenticalSetAreDisjoint() {
        Set<String> overlap = new TreeSet<>(KafkaMetricCategories.aliasMap().keySet());
        overlap.retainAll(IDENTICAL_ON_SMR);
        assertTrue(overlap.isEmpty(),
            "A name cannot be both aliased and identical under SMR: " + overlap);
    }

    /**
     * {@code MetricAggregation} is keyed in JMX catalog spelling, but it is consulted with the
     * name in the response — which on an SMR cluster is the alias. A non-default aggregation
     * would then silently degrade to the mean on SMR only, the hardest kind of bug to notice.
     * The rate rename is absorbed by {@code forMetric} itself; the alias cannot be, because the
     * alias map lives in this module and the table lives in {@code common}. So enforce the
     * pairing here.
     */
    @Test
    void everyAliasedMetricWithANonDefaultAggregationCoversItsSmrSpelling() {
        Set<String> missing = new TreeSet<>();
        KafkaMetricCategories.aliasMap().forEach((jmxName, smrName) -> {
            if (MetricNameResolver.UNMAPPED.equals(smrName)) {
                return;
            }
            MetricAggregation jmxFn = MetricAggregation.forMetric(jmxName);
            if (jmxFn != MetricAggregation.AVG && MetricAggregation.forMetric(smrName) != jmxFn) {
                missing.add(smrName + " (should be " + jmxFn + ", like " + jmxName + ")");
            }
        });
        assertTrue(missing.isEmpty(),
            "SMR aliases missing from MetricAggregation — add them or the metric averages on "
            + "SMR clusters while summing on JMX ones: " + missing);
    }

    // ---------------------------------------------------------------
    // Semantic trap — requesthandleravgidle is UNMAPPED on SMR (F7)
    // ---------------------------------------------------------------

    @Test
    void requestHandlerAvgIdleIsUnmappedOnSmr() {
        Map<String, String> aliasMap = KafkaMetricCategories.aliasMap();
        assertEquals(MetricNameResolver.UNMAPPED,
            aliasMap.get("kafka_server_kafkarequesthandlerpool_requesthandleravgidle_percent"),
            "requesthandleravgidle must be UNMAPPED on SMR due to unit difference (gauge vs ns counter)");
    }

    @Test
    void smrPerformanceCategoryExcludesRequestHandlerAvgIdle() {
        List<String> resolved = MetricNameResolver.resolve(
            KafkaMetricCategories.PERFORMANCE, null, KafkaMetricCategories.REPLICATION,
            KafkaMetricCategories::resolve, KafkaMetricCategories.allCategories(),
            MetricsBackend.STRIMZI_REPORTER, KafkaMetricCategories.aliasMap());
        assertFalse(resolved.contains("kafka_server_kafkarequesthandlerpool_requesthandleravgidle_percent"),
            "requesthandleravgidle must be absent from PERFORMANCE on SMR backend");
        // Network processor idle must be present (valid alias)
        assertTrue(resolved.contains("kafka_network_socketserver_networkprocessoravgidlepercent"),
            "networkprocessoravgidle SMR alias must be present");
    }

    /**
     * SMR ships no ReplicaFetcherManager MBeans, so the JMX name resolves to nothing and the
     * REPLICATION category silently came back one metric short against a live SMR cluster.
     */
    @Test
    void smrReplicationCategoryExcludesReplicaFetcherMaxLag() {
        List<String> resolved = MetricNameResolver.resolve(
            KafkaMetricCategories.REPLICATION, null, KafkaMetricCategories.REPLICATION,
            KafkaMetricCategories::resolve, KafkaMetricCategories.allCategories(),
            MetricsBackend.STRIMZI_REPORTER, KafkaMetricCategories.aliasMap());
        assertFalse(resolved.contains("kafka_server_replicafetchermanager_maxlag"),
            "maxlag must be absent from REPLICATION on SMR — the MBean does not exist there");
        assertTrue(resolved.contains("kafka_controller_controllerstats_uncleanleaderelectionspersec_total"),
            "the rest of the category must still resolve");
    }

    @Test
    void jmxReplicationCategoryKeepsReplicaFetcherMaxLag() {
        List<String> resolved = MetricNameResolver.resolve(
            KafkaMetricCategories.REPLICATION, null, KafkaMetricCategories.REPLICATION,
            KafkaMetricCategories::resolve, KafkaMetricCategories.allCategories(),
            MetricsBackend.JMX_EXPORTER, KafkaMetricCategories.aliasMap());
        assertTrue(resolved.contains("kafka_server_replicafetchermanager_maxlag"),
            "maxlag is exposed by the JMX exporter and must not be dropped there");
    }

    @Test
    void smrKraftCategoryResolvesAllAliases() {
        List<String> resolved = MetricNameResolver.resolve(
            KafkaMetricCategories.KRAFT, null, KafkaMetricCategories.REPLICATION,
            KafkaMetricCategories::resolve, KafkaMetricCategories.allCategories(),
            MetricsBackend.STRIMZI_REPORTER, KafkaMetricCategories.aliasMap());
        assertEquals(13, resolved.size());
        assertTrue(resolved.contains("kafka_server_raft_metrics_current_state_info"));
        assertTrue(resolved.contains("kafka_server_raft_metrics_current_leader"));
        assertTrue(resolved.contains("kafka_server_raft_metrics_current_epoch"));
        assertTrue(resolved.contains("kafka_server_raft_metrics_current_vote"));
        assertTrue(resolved.contains("kafka_server_raft_metrics_high_watermark"));
        assertTrue(resolved.contains("kafka_server_raft_metrics_log_end_offset"));
        assertTrue(resolved.contains("kafka_server_raft_metrics_commit_latency_avg"));
        assertTrue(resolved.contains("kafka_server_raft_metrics_append_records_rate"));
        assertTrue(resolved.contains("kafka_server_raft_metrics_fetch_records_rate"));
        // The *_rate siblings, not the cumulative series: the JMX counterparts are counters
        // that the Prometheus provider rate-converts, so SMR must yield a per-second value too.
        assertTrue(resolved.contains("kafka_server_raft_channel_metrics_incoming_byte_rate"));
        assertTrue(resolved.contains("kafka_server_raft_channel_metrics_outgoing_byte_rate"));
        assertTrue(resolved.contains("kafka_server_raft_channel_metrics_request_rate"));
        assertTrue(resolved.contains("kafka_server_raft_channel_metrics_response_rate"));
    }

    // ---------------------------------------------------------------
    // alignInterpretation — the guide must name the metrics that came back
    // ---------------------------------------------------------------

    @Test
    void alignInterpretationLeavesNullAndBlankAlone() {
        assertEquals(null, MetricNameResolver.alignInterpretation(
            null, MetricsBackend.STRIMZI_REPORTER, KafkaMetricCategories.aliasMap(), Set.of()));
        assertEquals("", MetricNameResolver.alignInterpretation(
            "", MetricsBackend.STRIMZI_REPORTER, KafkaMetricCategories.aliasMap(), Set.of()));
    }

    @Test
    void alignInterpretationRewritesJmxNameToSmrSpelling() {
        String aligned = MetricNameResolver.alignInterpretation(
            "kafka_network_socketserver_networkprocessoravgidle_percent: idle ratio",
            MetricsBackend.STRIMZI_REPORTER, KafkaMetricCategories.aliasMap(), Set.of());
        assertEquals("kafka_network_socketserver_networkprocessoravgidlepercent: idle ratio", aligned);
    }

    @Test
    void alignInterpretationLeavesJmxBackendSpellingAlone() {
        String text = "kafka_network_socketserver_networkprocessoravgidle_percent: idle ratio";
        assertEquals(text, MetricNameResolver.alignInterpretation(
            text, MetricsBackend.JMX_EXPORTER, KafkaMetricCategories.aliasMap(), Set.of()));
    }

    /**
     * An UNMAPPED metric has no SMR name to point at, so the sentence is annotated rather than
     * left naming a series the caller will never see in the response.
     */
    @Test
    void alignInterpretationAnnotatesUnmappedName() {
        String aligned = MetricNameResolver.alignInterpretation(
            "kafka_server_replicafetchermanager_maxlag: follower lag",
            MetricsBackend.STRIMZI_REPORTER, KafkaMetricCategories.aliasMap(), Set.of());
        assertTrue(aligned.startsWith("kafka_server_replicafetchermanager_maxlag "
            + "(not exposed by the Strimzi Metrics Reporter):"), aligned);
    }

    /**
     * The Prometheus provider rate-converts counters and renames them, so the guide's {@code _total}
     * spelling is wrong on <em>both</em> backends unless it follows the returned sample names.
     */
    @Test
    void alignInterpretationAppliesRateRenameOnJmxBackend() {
        String aligned = MetricNameResolver.alignInterpretation(
            "kafka_controller_controllerstats_uncleanleaderelections_total: data loss",
            MetricsBackend.JMX_EXPORTER, KafkaMetricCategories.aliasMap(),
            Set.of("kafka_controller_controllerstats_uncleanleaderelections_rate_per_second"));
        assertEquals("kafka_controller_controllerstats_uncleanleaderelections_rate_per_second: data loss",
            aligned);
    }

    /**
     * The full chain a name travels on SMR: catalog spelling → alias → provider rate rename.
     */
    @Test
    void alignInterpretationChainsAliasThenRateRename() {
        String aligned = MetricNameResolver.alignInterpretation(
            "kafka_controller_controllerstats_uncleanleaderelections_total: data loss",
            MetricsBackend.STRIMZI_REPORTER, KafkaMetricCategories.aliasMap(),
            Set.of("kafka_controller_controllerstats_uncleanleaderelectionspersec_rate_per_second"));
        assertEquals(
            "kafka_controller_controllerstats_uncleanleaderelectionspersec_rate_per_second: data loss",
            aligned);
    }

    /**
     * A counter named in the guide but absent from the response keeps its catalog spelling —
     * the rename is derived from real samples, never guessed from the suffix.
     */
    @Test
    void alignInterpretationKeepsTotalWhenNoRateSampleReturned() {
        String text = "kafka_controller_controllerstats_uncleanleaderelections_total: data loss";
        assertEquals(text, MetricNameResolver.alignInterpretation(
            text, MetricsBackend.JMX_EXPORTER, KafkaMetricCategories.aliasMap(), Set.of()));
    }

    @Test
    void alignInterpretationDoesNotMatchInsideLongerName() {
        Map<String, String> aliases = Map.of("kafka_metric", "smr_metric");
        String aligned = MetricNameResolver.alignInterpretation(
            "kafka_metric_extended and kafka_metric",
            MetricsBackend.STRIMZI_REPORTER, aliases, Set.of());
        assertEquals("kafka_metric_extended and smr_metric", aligned,
            "a name that is a prefix of another must not rewrite the longer one");
    }
}
