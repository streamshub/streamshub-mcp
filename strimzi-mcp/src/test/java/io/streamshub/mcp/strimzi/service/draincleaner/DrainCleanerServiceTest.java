/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.draincleaner;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfiguration;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfigurationBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.service.KubernetesQueryException;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.strimzi.dto.draincleaner.DrainCleanerLogsResponse;
import io.streamshub.mcp.strimzi.dto.draincleaner.DrainCleanerReadinessResponse;
import io.streamshub.mcp.strimzi.dto.draincleaner.DrainCleanerResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests {@link DrainCleanerService}: listing, logs, and the readiness decision logic (replicas, webhook,
 * operating mode and TLS certificate evaluation), plus namespace ambiguity handling.
 */
@QuarkusTest
class DrainCleanerServiceTest {

    private static final String NS = "drain-cleaner";
    private static final String NAME = "strimzi-drain-cleaner";

    // Generated with keytool; "valid" expires in year 2126, "expired" expired in January 2020.
    // To regenerate: keytool -genkeypair -alias x -keyalg EC -dname CN=valid -validity 36500 -keystore k.p12
    // -storepass changeit, then keytool -exportcert -rfc and base64-encode the PEM (single line).
    private static final String VALID_CERT_B64 =
        "LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tDQpNSUlCT0RDQjRLQURBZ0VDQWdrQWx6cjZWdmlxeW1Fd0NnWUlLb1pJemowRUF3TXdFREVP"
            + "TUF3R0ExVUVBeE1GDQpkbUZzYVdRd0lCY05Nall4TVRBek1UUXhPRFUzV2hnUE1qRXlOakV3TVRBeE5ERTROVGRhTUJBeERqQU1CZ05WDQpC"
            + "QU1UQlhaaGJHbGtNRmt3RXdZSEtvWkl6ajBDQVFZSUtvWkl6ajBEQVFjRFFnQUVuY2VNZDlNMGo2a2h0QjhrDQprQTNkNnRwWXI0K24wM3h5"
            + "YTgwcDFMbGdwRHZPWmczVGkrTVgrNkt4cklabCtrT0pQT2IvVlFkSWgrUnRQd3QyDQphMzVJZjZNaE1COHdIUVlEVlIwT0JCWUVGRHptVEUv"
            + "OTJJQW54WGY1L0pjMmJlMEpaRDhDTUFvR0NDcUdTTTQ5DQpCQU1EQTBjQU1FUUNJQS9qV1hteVMrRmg5WDU4MnplSGVYR2RMbE4wTUV6K1Rw"
            + "ckt2SmhCUTIyeEFpQXRzd0FXDQpYMVlmREc3ZzlaUGgydFExWnFlQjJlWU0vcHNtck9zWTh5elBCdz09DQotLS0tLUVORCBDRVJUSUZJQ0FU"
            + "RS0tLS0tDQo=";
    private static final String EXPIRED_CERT_B64 =
        "LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tDQpNSUlCT2pDQjRxQURBZ0VDQWdrQTE1QzAzdHlyVjFjd0NnWUlLb1pJemowRUF3TXdFakVR"
            + "TUE0R0ExVUVBeE1IDQpaWGh3YVhKbFpEQWVGdzB4T1RFeU16RXlNekF3TURCYUZ3MHlNREF4TXpBeU16QXdNREJhTUJJeEVEQU9CZ05WDQpC"
            + "QU1UQjJWNGNHbHlaV1F3V1RBVEJnY3Foa2pPUFFJQkJnZ3Foa2pPUFFNQkJ3TkNBQVJEWlVuZ3doSXRTamwvDQpUUE82TDg0Y0JIMndhcEFx"
            + "TmdiV3k1L3BVdVNDQWlDOFhINmFKQ21hbS9MZkg5Y0tKOEQ4MHJPeW9LTHVWS042DQo3dWdSWnhuam95RXdIekFkQmdOVkhRNEVGZ1FVTU9I"
            + "NUFTWDZFc1FjSzJ6dEZIWG9FSDVPZDZJd0NnWUlLb1pJDQp6ajBFQXdNRFJ3QXdSQUlnTEVQTjBuNzFpZVdlL2xyQ1lNcEVoT0VvdXJCa05j"
            + "a0V2N0RwNHdsVlRrc0NJQ3RSDQpIOGJHRXFJRVhTK2ZFaDhyOFhUTHgvZ0pWa21BRXJpV3ZJbFI5R0xLDQotLS0tLUVORCBDRVJUSUZJQ0FU"
            + "RS0tLS0tDQo=";

    @InjectMock
    KubernetesResourceService k8sService;

    @Inject
    DrainCleanerService service;

    DrainCleanerServiceTest() {
    }

    @BeforeEach
    void setUp() {
        lenient().when(k8sService.queryResourcesByLabel(eq(Deployment.class), anyString(), anyString(), anyString()))
            .thenReturn(List.of());
        lenient().when(k8sService.queryResourcesByLabelInAnyNamespace(eq(Deployment.class), anyString(), anyString()))
            .thenReturn(List.of());
    }

    private static Deployment deployment(final String namespace, final Integer replicas,
                                         final Integer ready, final String denyEviction) {
        ContainerBuilder container = new ContainerBuilder()
            .withName("drain-cleaner")
            .withImage("quay.io/strimzi/drain-cleaner:1.4.0");
        if (denyEviction != null) {
            container.addNewEnv().withName("STRIMZI_DENY_EVICTION").withValue(denyEviction).endEnv();
        }
        return deploymentWith(namespace, replicas, ready, container.build());
    }

    private static Deployment deploymentWithConfigMapEnv(final String... varNames) {
        ContainerBuilder container = new ContainerBuilder()
            .withName("drain-cleaner")
            .withImage("quay.io/strimzi/drain-cleaner:1.4.0");
        for (String varName : varNames) {
            container.addNewEnv().withName(varName)
                .withNewValueFrom().withNewConfigMapKeyRef("key-" + varName, "drain-config", false).endValueFrom()
                .endEnv();
        }
        return deploymentWith(NS, 1, 1, container.build());
    }

    private static Deployment deploymentWith(final String namespace, final Integer replicas,
                                             final Integer ready, final Container container) {
        return new DeploymentBuilder()
            .withNewMetadata().withName(NAME).withNamespace(namespace).endMetadata()
            .withNewSpec().withReplicas(replicas)
                .withNewTemplate().withNewSpec().addToContainers(container).endSpec().endTemplate()
            .endSpec()
            .withNewStatus().withReadyReplicas(ready).endStatus()
            .build();
    }

    private static Deployment readyDeployment() {
        return deployment(NS, 1, 1, null);
    }

    private static ValidatingWebhookConfiguration webhook() {
        return new ValidatingWebhookConfigurationBuilder()
            .withNewMetadata().withName(NAME).endMetadata()
            .addNewWebhook().withName("drain").withFailurePolicy("Ignore").endWebhook()
            .build();
    }

    private static Secret tlsSecret(final Map<String, String> data) {
        return new SecretBuilder().withNewMetadata().withName(NAME).withNamespace(NS).endMetadata()
            .withData(data).build();
    }

    private void givenDeployment(final Deployment deployment) {
        when(k8sService.queryResourcesByLabel(eq(Deployment.class), eq(NS), anyString(), anyString()))
            .thenReturn(List.of(deployment));
    }

    private void givenWebhookAndSecret(final ValidatingWebhookConfiguration webhook, final Secret secret) {
        when(k8sService.getClusterScopedResource(eq(ValidatingWebhookConfiguration.class), anyString()))
            .thenReturn(webhook);
        when(k8sService.getResource(eq(Secret.class), eq(NS), anyString())).thenReturn(secret);
    }

    private DrainCleanerReadinessResponse.ReadinessCheck check(final DrainCleanerReadinessResponse response,
                                                                final String name) {
        return response.checks().stream()
            .filter(c -> name.equals(c.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing check: " + name));
    }

    @Test
    void testFullyReadyDrainCleaner() {
        givenDeployment(readyDeployment());
        givenWebhookAndSecret(webhook(), tlsSecret(Map.of("tls.crt", VALID_CERT_B64)));

        DrainCleanerReadinessResponse result = service.checkReadiness(NS);

        assertTrue(result.deployed());
        assertTrue(result.allReplicasReady());
        assertTrue(result.webhookConfigured());
        assertEquals("Ignore", result.failurePolicy());
        assertEquals("standard", result.mode());
        assertTrue(result.certificateValid());
        assertNotNull(result.certificateDaysUntilExpiry());
        assertTrue(result.certificateDaysUntilExpiry() > 30);
        assertTrue(result.overallReady());
    }

    @Test
    void testLegacyModeIsNotOverallReady() {
        givenDeployment(deployment(NS, 1, 1, "false"));
        givenWebhookAndSecret(webhook(), tlsSecret(Map.of("tls.crt", VALID_CERT_B64)));

        DrainCleanerReadinessResponse result = service.checkReadiness(NS);

        assertEquals("legacy", result.mode());
        assertNotNull(result.modeRecommendation());
        assertFalse(check(result, "Operating mode").passed());
        assertFalse(result.overallReady());
    }

    @Test
    void testDenyEvictionFromValueFromIsUnknownAndNotOverallReady() {
        givenDeployment(deploymentWithConfigMapEnv("STRIMZI_DENY_EVICTION"));
        givenWebhookAndSecret(webhook(), tlsSecret(Map.of("tls.crt", VALID_CERT_B64)));

        DrainCleanerReadinessResponse result = service.checkReadiness(NS);

        assertEquals("unknown", result.mode());
        assertNotNull(result.modeRecommendation());
        assertFalse(check(result, "Operating mode").passed());
        assertTrue(check(result, "Operating mode").detail().contains("configMapKeyRef:drain-config/"));
        assertFalse(result.overallReady());
    }

    @Test
    void testWatchedNamespacesFromValueFromIsUnknownAndNotOverallReady() {
        givenDeployment(deploymentWithConfigMapEnv("STRIMZI_DRAIN_NAMESPACES"));
        givenWebhookAndSecret(webhook(), tlsSecret(Map.of("tls.crt", VALID_CERT_B64)));

        DrainCleanerReadinessResponse result = service.checkReadiness(NS);

        assertEquals("standard", result.mode());
        assertTrue(result.coveredNamespaces().startsWith("unknown"), result.coveredNamespaces());
        assertFalse(check(result, "Namespace coverage").passed());
        assertFalse(result.overallReady());
    }

    @Test
    void testListDrainCleanersWithValueFromEnvVarsDoesNotFail() {
        when(k8sService.queryResourcesByLabel(eq(Deployment.class), eq(NS), anyString(), anyString()))
            .thenReturn(List.of(deploymentWithConfigMapEnv(
                "STRIMZI_DENY_EVICTION", "STRIMZI_DRAIN_KAFKA", "STRIMZI_DRAIN_NAMESPACES")));

        List<DrainCleanerResponse> result = service.listDrainCleaners(NS);

        assertEquals(1, result.size());
        DrainCleanerResponse response = result.getFirst();
        assertEquals("unknown", response.mode());
        assertNull(response.denyEviction());
        assertNull(response.drainKafka());
        assertEquals("configMapKeyRef:drain-config/key-STRIMZI_DRAIN_NAMESPACES", response.watchedNamespaces());
    }

    @Test
    void testGetDrainCleanerWithValueFromEnvVarsDoesNotFail() {
        when(k8sService.getResource(Deployment.class, NS, NAME))
            .thenReturn(deploymentWithConfigMapEnv("STRIMZI_DENY_EVICTION"));
        when(k8sService.getClusterScopedResource(eq(ValidatingWebhookConfiguration.class), anyString()))
            .thenReturn(webhook());

        DrainCleanerResponse response = service.getDrainCleaner(NS, NAME);

        assertEquals("unknown", response.mode());
        assertNull(response.denyEviction());
        assertEquals(Boolean.TRUE, response.webhookConfigured());
    }

    @Test
    void testMissingWebhookIsNotOverallReady() {
        givenDeployment(readyDeployment());
        givenWebhookAndSecret(null, tlsSecret(Map.of("tls.crt", VALID_CERT_B64)));

        DrainCleanerReadinessResponse result = service.checkReadiness(NS);

        assertFalse(result.webhookConfigured());
        assertNull(result.failurePolicy());
        assertFalse(result.overallReady());
    }

    @Test
    void testUnreadyReplicasAreNotOverallReady() {
        givenDeployment(deployment(NS, 2, 1, null));
        givenWebhookAndSecret(webhook(), tlsSecret(Map.of("tls.crt", VALID_CERT_B64)));

        DrainCleanerReadinessResponse result = service.checkReadiness(NS);

        assertFalse(result.allReplicasReady());
        assertEquals("1/2 replicas ready", check(result, "Replicas ready").detail());
        assertFalse(result.overallReady());
    }

    @Test
    void testExpiredCertificateIsReported() {
        givenDeployment(readyDeployment());
        givenWebhookAndSecret(webhook(), tlsSecret(Map.of("tls.crt", EXPIRED_CERT_B64)));

        DrainCleanerReadinessResponse result = service.checkReadiness(NS);

        assertFalse(result.certificateValid());
        assertTrue(check(result, "TLS certificate").detail().contains("expired"));
        assertFalse(result.overallReady());
    }

    @Test
    void testMissingSecretIsReported() {
        givenDeployment(readyDeployment());
        givenWebhookAndSecret(webhook(), null);

        DrainCleanerReadinessResponse result = service.checkReadiness(NS);

        assertFalse(result.certificateValid());
        assertTrue(check(result, "TLS certificate").detail().contains("not found"));
        assertFalse(result.overallReady());
    }

    @Test
    void testSecretWithoutTlsCrtIsReported() {
        givenDeployment(readyDeployment());
        givenWebhookAndSecret(webhook(), tlsSecret(Map.of("other", "x")));

        DrainCleanerReadinessResponse result = service.checkReadiness(NS);

        assertFalse(result.certificateValid());
        assertTrue(check(result, "TLS certificate").detail().contains("tls.crt"));
    }

    @Test
    void testUnparsableCertificateIsReported() {
        givenDeployment(readyDeployment());
        givenWebhookAndSecret(webhook(), tlsSecret(Map.of("tls.crt", "!!not-base64!!")));

        DrainCleanerReadinessResponse result = service.checkReadiness(NS);

        assertFalse(result.certificateValid());
        assertTrue(check(result, "TLS certificate").detail().contains("No certificates"));
    }

    @Test
    void testUnreadableSecretMentionsRbac() {
        givenDeployment(readyDeployment());
        when(k8sService.getClusterScopedResource(eq(ValidatingWebhookConfiguration.class), anyString()))
            .thenReturn(webhook());
        when(k8sService.getResource(eq(Secret.class), eq(NS), anyString()))
            .thenThrow(new KubernetesQueryException("forbidden", new RuntimeException("403")));

        DrainCleanerReadinessResponse result = service.checkReadiness(NS);

        assertFalse(result.certificateValid());
        assertTrue(check(result, "TLS certificate").detail().contains("RBAC"));
        assertFalse(result.overallReady());
    }

    @Test
    void testDeploymentQueryFailureBecomesToolError() {
        when(k8sService.queryResourcesByLabel(eq(Deployment.class), eq(NS), anyString(), anyString()))
            .thenThrow(new KubernetesQueryException("forbidden", new RuntimeException("403")));

        ToolCallException e = assertThrows(ToolCallException.class, () -> service.checkReadiness(NS));

        assertTrue(e.getMessage().contains("Unable to check Drain Cleaner readiness"));
    }

    @Test
    void testGetDrainCleanerAcrossNamespacesIsAmbiguous() {
        when(k8sService.queryResourcesByLabelInAnyNamespace(eq(Deployment.class), anyString(), anyString()))
            .thenReturn(List.of(deployment("ns-a", 1, 1, null), deployment("ns-b", 1, 1, null)));

        assertThrows(McpException.class, () -> service.getDrainCleaner(null, NAME));
    }

    @Test
    void testGetDrainCleanerNotFoundThrows() {
        assertThrows(McpException.class, () -> service.getDrainCleaner(null, NAME));
    }

    @Test
    void testGetDrainCleanerWithoutNameThrows() {
        assertThrows(McpException.class, () -> service.getDrainCleaner(NS, null));
    }

    @Test
    void testLogsReportNotFoundWhenNamespaceCannotBeDiscovered() {
        DrainCleanerLogsResponse result = service.getDrainCleanerLogs(
            null, null, LogCollectionParams.of(null, null, 200, null));

        assertNotNull(result.message());
        assertTrue(result.message().contains("No Strimzi Drain Cleaner pods found"));
    }

    @Test
    void testListDrainCleanersReturnsEmptyWhenNoneExist() {
        assertTrue(service.listDrainCleaners(NS).isEmpty());
    }

    @Test
    void testListDrainCleanersReturnsEmptyForAllNamespaces() {
        assertTrue(service.listDrainCleaners(null).isEmpty());
    }

    @Test
    void testGetDrainCleanerLogsReturnsNotFoundWhenNoPodsExist() {
        DrainCleanerLogsResponse result = service.getDrainCleanerLogs(
            NS, null, LogCollectionParams.of(null, null, 200, null));

        assertEquals(NS, result.namespace());
        assertNotNull(result.message());
        assertTrue(result.message().contains("No Strimzi Drain Cleaner pods found"));
    }

    @Test
    void testCheckReadinessWhenNotDeployed() {
        DrainCleanerReadinessResponse result = service.checkReadiness(NS);

        assertFalse(result.deployed());
        assertFalse(result.overallReady());
        assertFalse(result.checks().isEmpty());
        assertFalse(result.checks().getFirst().passed());
    }
}
