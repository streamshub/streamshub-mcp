/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkamirrormaker2;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkiverse.mcp.server.InputRequiredException;
import io.quarkiverse.mcp.server.Sampling;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2DiagnosticReport;
import io.streamshub.mcp.strimzi.service.KubernetesMockHelper;
import io.streamshub.mcp.strimzi.testutil.MrtrTestHelper;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2Builder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
/**
 * Stateless MRTR tests for {@link KafkaMirrorMaker2DiagnosticService}.
 *
 * <p>Verifies the service correctly throws {@link InputRequiredException} when a stateless
 * client has no prior response, and returns analysis text when the response is present.</p>
 */
@QuarkusTest
class KafkaMirrorMaker2DiagnosticServiceStatelessTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaMirrorMaker2DiagnosticService diagnosticService;

    KafkaMirrorMaker2DiagnosticServiceStatelessTest() {
    }

    @BeforeEach
    void setUp() {
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);
    }

    @Test
    void testStatelessThrowsInputRequiredWhenNoAnalysisResponse() {
        setupMirrorMaker2("my-mm2", "kafka");

        Sampling sampling = MrtrTestHelper.mockStatelessSampling(false, null);

        assertThrows(InputRequiredException.class,
            () -> diagnosticService.diagnose("kafka", "my-mm2", "replication lag",
                null, sampling, null, null, null));
    }

    @Test
    void testStatelessReturnsAnalysisWhenResponsePresent() {
        setupMirrorMaker2("my-mm2", "kafka");

        String analysisText = "Root cause: source cluster connectivity issue\nSeverity: CRITICAL";
        Sampling sampling = MrtrTestHelper.mockStatelessSampling(true, analysisText);

        KafkaMirrorMaker2DiagnosticReport report = diagnosticService.diagnose(
            "kafka", "my-mm2", "replication lag",
            null, sampling, null, null, null);

        assertNotNull(report);
        assertNotNull(report.mirrorMaker());
        assertTrue(report.stepsCompleted().contains("mm2_status"));
        assertNotNull(report.analysis());
        assertTrue(report.analysis().contains("Root cause"));
        assertTrue(report.analysis().contains("CRITICAL"));
    }

    @Test
    void testNullSamplingFallbackReturnsReportWithoutAnalysis() {
        setupMirrorMaker2("my-mm2", "kafka");

        KafkaMirrorMaker2DiagnosticReport report = diagnosticService.diagnose(
            "kafka", "my-mm2", null,
            null, null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.mirrorMaker());
        assertTrue(report.stepsCompleted().contains("mm2_status"));
        assertNotNull(report.timestamp());
    }

    // ---- Test helpers ----

    @SuppressWarnings("unchecked")
    private void setupMirrorMaker2(final String name, final String namespace) {
        KafkaMirrorMaker2 mm2 = new KafkaMirrorMaker2Builder()
            .withMetadata(new ObjectMetaBuilder().withName(name).withNamespace(namespace).build())
            .withNewSpec()
                .withVersion("4.2.0")
                .withReplicas(1)
            .endSpec()
            .withNewStatus()
                .addNewCondition().withType("Ready").withStatus("True").endCondition()
            .endStatus()
            .build();

        MixedOperation mm2Op = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(KafkaMirrorMaker2.class)).thenReturn(mm2Op);

        NonNamespaceOperation nsMm2Op = Mockito.mock(NonNamespaceOperation.class);
        when(mm2Op.inNamespace(namespace)).thenReturn(nsMm2Op);

        Resource mm2Resource = Mockito.mock(Resource.class);
        when(nsMm2Op.withName(name)).thenReturn(mm2Resource);
        when(mm2Resource.get()).thenReturn(mm2);
    }
}
