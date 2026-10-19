/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.InputRequiredException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.service.kafkauser.KafkaUserService;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * MRTR regression guards for {@link KafkaSecurityAuditDiagnosticService}.
 *
 * <p>The three Phase 1/2 gather steps catch {@code Exception} so that an individual step failure is
 * recorded and the audit continues with partial data (graceful degradation). An
 * {@link InputRequiredException} is NOT a failure: it is the MRTR control signal that a stateless
 * client must supply more input, and it MUST propagate uncaught to the MCP framework (which converts
 * it to an {@code input_required} result). These tests pin that a gather step rethrows
 * {@link InputRequiredException} ahead of its {@code catch (Exception)} rather than swallowing it
 * into {@code stepsFailed} — the convention documented in AGENTS.md for every {@code catch (Exception)}
 * on the diagnostic path.</p>
 */
@QuarkusTest
class KafkaSecurityAuditDiagnosticServiceMrtrTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @InjectMock
    KafkaCertificateService certificateService;

    @InjectMock
    KafkaUserService userService;

    @Inject
    KafkaSecurityAuditDiagnosticService auditService;

    KafkaSecurityAuditDiagnosticServiceMrtrTest() {
    }

    private static InputRequiredException inputRequired() {
        return InputRequiredException.builder().setRequestState("kafka").build();
    }

    @Test
    void testGatherCertificatesPropagatesInputRequiredException() {
        when(certificateService.getCertificates(any(), any(), any())).thenThrow(inputRequired());
        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        assertThrows(InputRequiredException.class,
            () -> auditService.gatherCertificates("kafka", "my-cluster", completed, failed));
        assertTrue(failed.isEmpty(),
            "InputRequiredException is a control signal, not a failed step");
    }

    @Test
    void testGatherUsersPropagatesInputRequiredException() {
        when(userService.listUsers(any(), any())).thenThrow(inputRequired());
        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        assertThrows(InputRequiredException.class,
            () -> auditService.gatherUsers("kafka", "my-cluster", completed, failed));
        assertTrue(failed.isEmpty(),
            "InputRequiredException is a control signal, not a failed step");
    }

    @Test
    void testGatherAclMatricesPropagatesInputRequiredException() {
        when(userService.getAclMatrix(any(), any(), any())).thenThrow(inputRequired());
        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        assertThrows(InputRequiredException.class,
            () -> auditService.gatherAclMatrices("kafka", "my-cluster", completed, failed));
        assertTrue(failed.isEmpty(),
            "InputRequiredException is a control signal, not a failed step");
    }
}
