/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkatopic;

import io.quarkiverse.mcp.server.Sampling;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.kafkatopic.KafkaTopicResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tests that the topic diagnostic does not skip investigation only because the topic and the
 * cluster are Ready.
 */
class KafkaTopicTriageFloorTest {

    private static final KafkaTopicDiagnosticService.InvestigationAreas NONE =
        new KafkaTopicDiagnosticService.InvestigationAreas(false, false, false);

    KafkaTopicTriageFloorTest() {
    }

    private static KafkaTopicResponse topic(final String status, final KafkaTopicResponse.ReplicasChangeInfo change) {
        return KafkaTopicResponse.of("t", "kafka", "c", 3, 3, status, null, "t", change, null, null, null, null);
    }

    private static KafkaClusterResponse cluster(final String readiness) {
        return KafkaClusterResponse.of("c", "kafka", "Kafka", "4.0.0", "4.0.0",
            null, null, null, readiness, null, null, null, null,
            null, null, null, null, 1L, null, null, null, null, List.of());
    }

    /**
     * Verify a Ready topic on a Ready cluster with no symptom may skip everything.
     */
    @Test
    void testReadyWithoutSymptomMaySkip() {
        KafkaTopicDiagnosticService.InvestigationAreas areas =
            NONE.withFloor(topic("Ready", null), cluster("Ready"), null);

        assertFalse(areas.operatorLogs());
        assertFalse(areas.events());
        assertFalse(areas.exporterMetrics());
    }

    /**
     * Verify a reported symptom forces all areas even when both resources are Ready.
     */
    @Test
    void testSymptomForcesInvestigation() {
        KafkaTopicDiagnosticService.InvestigationAreas areas =
            NONE.withFloor(topic("Ready", null), cluster("Ready"), "consumers are lagging");

        assertTrue(areas.operatorLogs());
        assertTrue(areas.events());
        assertTrue(areas.exporterMetrics());
    }

    /**
     * Verify a replication change in flight forces investigation.
     */
    @Test
    void testReplicasChangeForcesInvestigation() {
        KafkaTopicResponse.ReplicasChangeInfo change =
            new KafkaTopicResponse.ReplicasChangeInfo(2, "pending", null, null);

        KafkaTopicDiagnosticService.InvestigationAreas areas =
            NONE.withFloor(topic("Ready", change), cluster("Ready"), null);

        assertTrue(areas.operatorLogs());
        assertTrue(areas.exporterMetrics());
    }

    /**
     * Verify a topic that is not Ready, or an unknown cluster, forces logs and events.
     */
    @Test
    void testNotReadyOrUnknownClusterForcesLogsAndEvents() {
        KafkaTopicDiagnosticService.InvestigationAreas notReady =
            NONE.withFloor(topic("NotReady", null), cluster("Ready"), null);
        assertTrue(notReady.operatorLogs());
        assertTrue(notReady.events());
        assertFalse(notReady.exporterMetrics());

        KafkaTopicDiagnosticService.InvestigationAreas noCluster =
            NONE.withFloor(topic("Ready", null), null, null);
        assertTrue(noCluster.operatorLogs());
        assertTrue(noCluster.events());
    }

    /**
     * Verify a reported symptom skips the triage round-trip, since the floor already enables every area.
     */
    @Test
    void testSymptomSkipsTriageSampling() {
        Sampling sampling = mock(Sampling.class);

        KafkaTopicDiagnosticService.InvestigationAreas areas = new KafkaTopicDiagnosticService()
            .investigateAreas(sampling, topic("Ready", null), null, cluster("Ready"), "consumers are lagging", null);

        assertTrue(areas.operatorLogs());
        assertTrue(areas.events());
        assertTrue(areas.exporterMetrics());
        verifyNoInteractions(sampling);
    }
}
