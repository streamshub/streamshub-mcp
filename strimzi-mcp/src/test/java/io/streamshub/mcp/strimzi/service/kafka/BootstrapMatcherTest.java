/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.kafka.ListenerInfo;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BootstrapMatcher}.
 */
class BootstrapMatcherTest {

    private static final Set<String> ADDRESSES = Set.of("my-cluster-kafka-bootstrap",
        "my-cluster-kafka-bootstrap.kafka.svc:9092");

    BootstrapMatcherTest() {
    }

    private static KafkaClusterResponse clusterWith(final List<ListenerInfo> listeners) {
        return new KafkaClusterResponse("my-cluster", "kafka", "Kafka", null, null, null, null, null, null,
            null, listeners, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static KafkaMirrorMaker2Response mm2(final String target, final List<String> sources,
                                                  final String bootstrap) {
        return new KafkaMirrorMaker2Response("mm2", "kafka", "Ready", null, target, sources,
            null, null, null, null, bootstrap, null, null, null, null);
    }

    @Test
    void testExtractIncludesConventionalNameAndEveryListenerAddressAndHost() {
        KafkaClusterResponse cluster = clusterWith(List.of(
            ListenerInfo.of("plain", "internal", "my-cluster-kafka-bootstrap.kafka.svc:9092"),
            ListenerInfo.of("ext", "route", "bootstrap.example.com:443")));

        Set<String> addresses = BootstrapMatcher.extractBootstrapAddresses(cluster, "my-cluster");

        assertEquals(Set.of("my-cluster-kafka-bootstrap",
            "my-cluster-kafka-bootstrap.kafka.svc:9092", "my-cluster-kafka-bootstrap.kafka.svc",
            "bootstrap.example.com:443", "bootstrap.example.com"), addresses);
    }

    @Test
    void testExtractToleratesNullListenersAndNullBootstrapAddress() {
        assertEquals(Set.of("c-kafka-bootstrap"),
            BootstrapMatcher.extractBootstrapAddresses(clusterWith(null), "c"));
        assertEquals(Set.of("c-kafka-bootstrap"), BootstrapMatcher.extractBootstrapAddresses(
            clusterWith(List.of(ListenerInfo.of("plain", "internal", null))), "c"));
    }

    @Test
    void testMatchesBootstrapOnSubstring() {
        assertTrue(BootstrapMatcher.matchesBootstrap(
            "my-cluster-kafka-bootstrap.kafka.svc:9092,other:9092", ADDRESSES));
        assertFalse(BootstrapMatcher.matchesBootstrap("unrelated:9092", ADDRESSES));
    }

    @Test
    void testMatchesBootstrapRejectsNullAndBlank() {
        assertFalse(BootstrapMatcher.matchesBootstrap(null, ADDRESSES));
        assertFalse(BootstrapMatcher.matchesBootstrap("  ", ADDRESSES));
    }

    @Test
    void testMm2MatchesByBootstrapServers() {
        assertTrue(BootstrapMatcher.mm2ConnectsToCluster(
            mm2(null, null, "my-cluster-kafka-bootstrap:9092"), ADDRESSES));
    }

    @Test
    void testMm2MatchesByTargetClusterAlias() {
        assertTrue(BootstrapMatcher.mm2ConnectsToCluster(mm2("my-cluster", null, null), ADDRESSES));
    }

    @Test
    void testMm2MatchesBySourceClusterAlias() {
        assertTrue(BootstrapMatcher.mm2ConnectsToCluster(
            mm2("other", List.of("elsewhere", "my-cluster"), null), ADDRESSES));
    }

    @Test
    void testMm2DoesNotMatchUnrelatedCluster() {
        assertFalse(BootstrapMatcher.mm2ConnectsToCluster(
            mm2("other", List.of("elsewhere"), "other-kafka-bootstrap:9092"), ADDRESSES));
        assertFalse(BootstrapMatcher.mm2ConnectsToCluster(mm2(null, null, null), ADDRESSES));
    }
}
