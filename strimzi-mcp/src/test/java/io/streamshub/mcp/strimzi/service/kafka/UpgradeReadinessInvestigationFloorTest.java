/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.streamshub.mcp.common.dto.PaginatedResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.kafka.UpgradePreflightFinding;
import io.streamshub.mcp.strimzi.dto.kafkatopic.KafkaTopicResponse;
import io.streamshub.mcp.strimzi.service.kafkatopic.KafkaTopicService;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that certificates and Drain Cleaner are always investigated by the upgrade readiness
 * workflow, whatever the triage answered.
 */
class UpgradeReadinessInvestigationFloorTest {

    UpgradeReadinessInvestigationFloorTest() {
    }

    private static KafkaClusterResponse cluster(final String readiness) {
        return KafkaClusterResponse.of("c", "kafka", "Kafka", "4.2.0", "4.2.0",
            null, null, null, readiness, null, null, null, null,
            null, null, null, null, 1L, null, null, null, null, List.of());
    }

    /**
     * Verify certificates and Drain Cleaner are enabled even when triage answered false for everything.
     */
    @Test
    void testCertificatesAndDrainCleanerAreAlwaysEnabled() {
        UpgradeReadinessDiagnosticService.InvestigationAreas none =
            new UpgradeReadinessDiagnosticService.InvestigationAreas(false, false, false, false, false);

        UpgradeReadinessDiagnosticService.InvestigationAreas areas = none.withFloor(cluster("Ready"));

        assertTrue(areas.certificates());
        assertTrue(areas.drainCleaner());
        assertFalse(areas.events(), "events stay off for a Ready cluster when triage skipped them");
        assertFalse(areas.performanceMetrics());
    }

    /**
     * Verify events are gathered when the cluster is not Ready.
     */
    @Test
    void testEventsEnabledWhenClusterNotReady() {
        UpgradeReadinessDiagnosticService.InvestigationAreas none =
            new UpgradeReadinessDiagnosticService.InvestigationAreas(false, false, false, false, false);

        assertTrue(none.withFloor(cluster("NotReady")).events());
    }

    /**
     * Verify a truncated topic list produces a warning instead of a silent "all topics pass".
     */
    @Test
    void testPreflightWarnsWhenTopicListIsTruncated() {
        UpgradeReadinessDiagnosticService service = new UpgradeReadinessDiagnosticService();
        service.kafkaService = mock(KafkaService.class);
        service.topicService = mock(KafkaTopicService.class);
        Kafka kafka = new KafkaBuilder()
            .withNewMetadata().withName("c").withNamespace("kafka").endMetadata()
            .withNewSpec().withNewKafka().endKafka().endSpec()
            .build();
        when(service.kafkaService.findKafkaCluster(anyString(), anyString())).thenReturn(kafka);
        when(service.topicService.listTopics(anyString(), anyString(), any(), eq(Integer.MAX_VALUE), any(), any()))
            .thenReturn(PaginatedResponse.<KafkaTopicResponse>of(List.of(), 7200, 0, 5000, false, true));

        List<UpgradePreflightFinding> findings = service.gatherPreflightFindings(
            "kafka", "c", cluster("Ready"), null, null, new ArrayList<>(), new ArrayList<>());

        assertTrue(findings.stream().anyMatch(f -> f.severity() == UpgradePreflightFinding.Severity.WARNING
            && UpgradePreflightChecks.CHECK_TOPIC_DURABILITY.equals(f.check())
            && f.message().contains("7200")), findings.toString());
    }
}
