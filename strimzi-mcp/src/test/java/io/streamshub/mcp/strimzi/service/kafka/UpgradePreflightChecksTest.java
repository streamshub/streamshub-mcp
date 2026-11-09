/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.kafka.UpgradePreflightFinding;
import io.streamshub.mcp.strimzi.dto.kafkatopic.KafkaTopicResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link UpgradePreflightChecks}.
 */
class UpgradePreflightChecksTest {

    UpgradePreflightChecksTest() {
    }

    private static KafkaTopicResponse topic(final String name, final Integer replicas, final Map<String, Object> config) {
        return KafkaTopicResponse.of(name, "kafka", "c", 3, replicas, "Ready", null, name,
            null, null, config, null, null);
    }

    private static KafkaClusterResponse cluster(final String running, final String metadata) {
        return KafkaClusterResponse.of("c", "kafka", "Kafka", running, running,
            metadata, null, null, "Ready", null, null, null, null,
            null, null, null, null, 1L, null, null, null, null, List.of());
    }

    private static List<UpgradePreflightFinding> blockers(final List<UpgradePreflightFinding> findings) {
        return findings.stream().filter(f -> f.severity() == UpgradePreflightFinding.Severity.BLOCKER).toList();
    }

    /**
     * Verify a topic whose min.insync.replicas equals its replication factor is a blocker.
     */
    @Test
    void testTopicMinIsrEqualToReplicasBlocks() {
        List<UpgradePreflightFinding> findings = UpgradePreflightChecks.topicFindings(null,
            List.of(topic("orders", 3, Map.of("min.insync.replicas", "3")), topic("ok", 3, Map.of("min.insync.replicas", 2))));

        assertEquals(1, blockers(findings).size());
        assertTrue(findings.getFirst().message().contains("orders"));
        assertFalse(findings.getFirst().message().contains("ok (replicas"));
    }

    /**
     * Verify the broker default min.insync.replicas applies to topics without an override.
     */
    @Test
    void testBrokerDefaultMinIsrApplies() {
        List<UpgradePreflightFinding> findings = UpgradePreflightChecks.topicFindings(
            Map.of("min.insync.replicas", 3), List.of(topic("t1", 3, null), topic("t2", 3, Map.of("min.insync.replicas", 2))));

        assertEquals(1, blockers(findings).size());
        assertTrue(findings.getFirst().message().contains("t1"));
        assertFalse(findings.getFirst().message().contains("t2"));
    }

    /**
     * Verify a topic without spec.replicas uses the broker default.replication.factor.
     */
    @Test
    void testTopicWithoutReplicasUsesBrokerDefaultReplicationFactor() {
        List<UpgradePreflightFinding> findings = UpgradePreflightChecks.topicFindings(
            Map.of("default.replication.factor", 3, "min.insync.replicas", 3),
            List.of(topic("unset", null, null), topic("explicit", 3, Map.of("min.insync.replicas", 2))));

        assertEquals(1, blockers(findings).size());
        assertTrue(findings.getFirst().message().contains("unset (replicas=3, min.insync.replicas=3)"));
        assertFalse(findings.getFirst().message().contains("explicit"));
    }

    /**
     * Verify a topic without spec.replicas falls back to Kafka's default of 1 replica when the broker sets none.
     */
    @Test
    void testTopicWithoutReplicasAndWithoutBrokerDefaultIsSingleReplica() {
        List<UpgradePreflightFinding> findings = UpgradePreflightChecks.topicFindings(
            Map.of("min.insync.replicas", 3), List.of(topic("unset", null, null)));

        assertEquals(1, findings.size());
        assertEquals(UpgradePreflightFinding.Severity.WARNING, findings.getFirst().severity());
        assertTrue(findings.getFirst().message().contains("single replica"));
        assertTrue(findings.getFirst().message().contains("unset"));
    }

    /**
     * Verify an oversized version number is treated as unparseable instead of throwing.
     */
    @Test
    void testOversizedVersionNumberIsRejectedWithoutThrowing() {
        assertNull(UpgradePreflightChecks.parseKafkaTarget("Kafka 40000000000.0"));
        assertNull(UpgradePreflightChecks.parseKafkaTarget("40000000000.0.0"));
        assertNull(UpgradePreflightChecks.parseKafkaTarget("Kafka 4.2.99999999999"));
        assertTrue(UpgradePreflightChecks.versionFindings(
            cluster("4.2.0", "99999999999.0"), "Kafka 40000000000.0", List.of("4.2.0")).isEmpty());
    }

    /**
     * Verify healthy topics produce no findings and the Kafka default of 1 never blocks.
     */
    @Test
    void testHealthyTopicsHaveNoFindings() {
        assertTrue(UpgradePreflightChecks.topicFindings(null, List.of(topic("a", 3, null), topic("b", 2, Map.of()))).isEmpty());
    }

    /**
     * Verify single-replica topics and unclean election are warnings, not blockers.
     */
    @Test
    void testSingleReplicaAndUncleanElectionWarn() {
        List<UpgradePreflightFinding> findings = UpgradePreflightChecks.topicFindings(
            Map.of("unclean.leader.election.enable", true),
            List.of(topic("solo", 1, null), topic("risky", 3, Map.of("unclean.leader.election.enable", "true"))));

        assertTrue(blockers(findings).isEmpty());
        assertEquals(3, findings.size());
        assertTrue(findings.stream().anyMatch(f -> f.message().contains("solo")));
        assertTrue(findings.stream().anyMatch(f -> f.message().contains("risky")));
        assertTrue(findings.stream().anyMatch(f -> f.message().contains("Broker config")));
    }

    /**
     * Verify long topic lists are truncated in the message.
     */
    @Test
    void testLongListIsTruncated() {
        List<KafkaTopicResponse> topics = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            topics.add(topic("t" + i, 3, Map.of("min.insync.replicas", 3)));
        }
        String message = UpgradePreflightChecks.topicFindings(null, topics).getFirst().message();
        assertTrue(message.startsWith("25 topic(s)"));
        assertTrue(message.contains("and 15 more"));
    }

    /**
     * Verify Kafka target parsing distinguishes Kafka from Strimzi versions.
     */
    @Test
    void testParseKafkaTarget() {
        assertEquals("4.2.0", UpgradePreflightChecks.parseKafkaTarget("Kafka 4.2.0"));
        assertEquals("4.2.0", UpgradePreflightChecks.parseKafkaTarget("4.2.0"));
        assertEquals("4.3", UpgradePreflightChecks.parseKafkaTarget("kafka 4.3"));
        assertNull(UpgradePreflightChecks.parseKafkaTarget("Strimzi 1.3.0"));
        assertNull(UpgradePreflightChecks.parseKafkaTarget("Strimzi 0.45.0"));
        assertNull(UpgradePreflightChecks.parseKafkaTarget("0.45.0"));
        assertNull(UpgradePreflightChecks.parseKafkaTarget("1.4.0"));
        assertEquals("1.4.0", UpgradePreflightChecks.parseKafkaTarget("Kafka 1.4.0"));
        assertEquals("3.9.0", UpgradePreflightChecks.parseKafkaTarget("3.9.0"));
        assertNull(UpgradePreflightChecks.parseKafkaTarget("latest"));
        assertNull(UpgradePreflightChecks.parseKafkaTarget(null));
    }

    /**
     * Verify a target unsupported by the operator blocks.
     */
    @Test
    void testUnsupportedTargetBlocks() {
        List<UpgradePreflightFinding> findings = UpgradePreflightChecks.versionFindings(
            cluster("4.2.0", "4.2-IV1"), "Kafka 4.4.0", List.of("4.2.0", "4.3.0"));

        assertEquals(1, blockers(findings).size());
        assertTrue(findings.getFirst().message().contains("not supported"));
    }

    /**
     * Verify a supported target (including a minor-only target) produces no findings.
     */
    @Test
    void testSupportedTargetPasses() {
        assertTrue(UpgradePreflightChecks.versionFindings(
            cluster("4.2.0", "4.2-IV1"), "Kafka 4.3.0", List.of("4.2.0", "4.3.0")).isEmpty());
        assertTrue(UpgradePreflightChecks.versionFindings(
            cluster("4.2.0", "4.2-IV1"), "Kafka 4.3", List.of("4.2.0", "4.3.0")).isEmpty());
    }

    /**
     * Verify downgrade and same-version targets warn.
     */
    @Test
    void testDowngradeAndSameVersionWarn() {
        List<UpgradePreflightFinding> down = UpgradePreflightChecks.versionFindings(
            cluster("4.3.0", "4.3-IV0"), "Kafka 4.2.0", List.of("4.2.0", "4.3.0"));
        assertEquals(1, down.size());
        assertTrue(down.getFirst().message().contains("downgrade"));

        List<UpgradePreflightFinding> same = UpgradePreflightChecks.versionFindings(
            cluster("4.3.0", "4.3-IV0"), "Kafka 4.3.0", List.of("4.3.0"));
        assertEquals(1, same.size());
        assertTrue(same.getFirst().message().contains("nothing to upgrade"));
    }

    /**
     * Verify a metadata version behind the running version is reported as an unfinalized upgrade.
     */
    @Test
    void testUnfinalizedMetadataVersionWarns() {
        List<UpgradePreflightFinding> findings = UpgradePreflightChecks.versionFindings(
            cluster("4.3.0", "4.2-IV1"), null, null);

        assertEquals(1, findings.size());
        assertTrue(findings.getFirst().message().contains("not been finalized"));
    }

    /**
     * Verify a Strimzi target or unknown operator versions are not checked against Kafka versions.
     */
    @Test
    void testStrimziTargetAndMissingSupportListAreSkipped() {
        assertTrue(UpgradePreflightChecks.versionFindings(
            cluster("4.2.0", "4.2-IV1"), "Strimzi 1.3.0", List.of("4.2.0")).isEmpty());
        assertTrue(UpgradePreflightChecks.versionFindings(
            cluster("4.2.0", "4.2-IV1"), "1.4.0", List.of("4.2.0")).isEmpty());
        assertTrue(UpgradePreflightChecks.versionFindings(
            cluster("4.2.0", "4.2-IV1"), "Kafka 9.9.9", null).isEmpty());
    }

    /**
     * Verify a capped topic list is reported as a topic-durability warning with the real counts.
     */
    @Test
    void testTruncationFindingWarnsAboutUncheckedTopics() {
        UpgradePreflightFinding finding = UpgradePreflightChecks.truncationFinding(5000, 7200);

        assertEquals(UpgradePreflightFinding.Severity.WARNING, finding.severity());
        assertEquals(UpgradePreflightChecks.CHECK_TOPIC_DURABILITY, finding.check());
        assertTrue(finding.message().contains("first 5000 of 7200"), finding.message());
        assertTrue(finding.message().contains("2200"), finding.message());
    }

    @Test
    void testSeveritySerializesAsName() throws Exception {
        String json = new ObjectMapper().writeValueAsString(UpgradePreflightFinding.blocker("c", "m"));

        assertTrue(json.contains("\"severity\":\"BLOCKER\""), json);
    }
}
