/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaCertificateResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaConnectivityDiagnosticReport;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Step reporting of {@link KafkaConnectivityDiagnosticService}: a step with partial errors is
 * degraded, not completed.
 */
@QuarkusTest
class KafkaConnectivityDiagnosticStepsTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @InjectMock
    KafkaCertificateService certificateService;

    @Inject
    KafkaConnectivityDiagnosticService connectivityService;

    KafkaConnectivityDiagnosticStepsTest() {
    }

    @Test
    void testCertificatesWithErrorsAreDegraded() {
        when(certificateService.getCertificates(any(), any(), any())).thenReturn(
            KafkaCertificateResponse.of("my-cluster", "kafka", null, null, List.of(), List.of(),
                List.of("Unable to read cluster certificate secret 'my-cluster-cluster-ca-cert': forbidden",
                    "Unable to read clients certificate secret 'my-cluster-clients-ca-cert': forbidden")));
        List<String> completed = new ArrayList<>();
        List<String> degraded = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        KafkaCertificateResponse result = connectivityService.gatherCertificates(
            "kafka", "my-cluster", null, completed, degraded, failed);

        assertNotNull(result, "The certificates that could be read are still reported");
        assertTrue(completed.isEmpty(), "A step with partial errors is not completed");
        assertEquals(List.of("certificates: 2 certificate Secret(s) could not be read, see certificates.errors"),
            degraded);
        assertTrue(failed.isEmpty());
    }

    @Test
    void testCertificatesWithoutErrorsAreCompleted() {
        when(certificateService.getCertificates(any(), any(), any())).thenReturn(
            KafkaCertificateResponse.of("my-cluster", "kafka", null, null, List.of(), List.of(), List.of()));
        List<String> completed = new ArrayList<>();
        List<String> degraded = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        connectivityService.gatherCertificates("kafka", "my-cluster", null, completed, degraded, failed);

        assertEquals(List.of("certificates"), completed);
        assertTrue(degraded.isEmpty());
    }

    @Test
    void testReportCountsDegradedSteps() {
        KafkaConnectivityDiagnosticReport report = KafkaConnectivityDiagnosticReport.of(
            null, null, null, null, null, null, null, null,
            List.of("cluster_status", "bootstrap_servers"),
            List.of("certificates: 1 certificate Secret(s) could not be read, see certificates.errors"),
            List.of());

        assertEquals("Connectivity diagnostic completed for cluster 'unknown': "
            + "2 steps succeeded, 1 step degraded", report.message());
        assertEquals(1, report.stepsDegraded().size());
        assertNull(report.stepsFailed(), "An empty steps_failed is left out");
    }
}
