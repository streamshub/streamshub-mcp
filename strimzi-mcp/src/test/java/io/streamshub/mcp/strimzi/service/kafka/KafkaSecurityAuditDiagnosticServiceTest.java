/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaSecurityAuditReport;
import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserAclMatrixResponse;
import io.streamshub.mcp.strimzi.service.KubernetesMockHelper;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.api.kafka.model.user.KafkaUserBuilder;
import io.strimzi.api.kafka.model.user.acl.AclResourcePatternType;
import io.strimzi.api.kafka.model.user.acl.AclRuleType;
import io.strimzi.api.kafka.model.user.acl.StrimziAclOperation;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Service-level tests for {@link KafkaSecurityAuditDiagnosticService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaSecurityAuditDiagnosticServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @InjectMock
    KafkaCertificateService certificateService;

    @Inject
    KafkaSecurityAuditDiagnosticService auditService;

    KafkaSecurityAuditDiagnosticServiceTest() {
    }

    @BeforeEach
    void setUp() {
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Kafka.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaUser.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaNodePool.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Secret.class);
    }

    @SuppressWarnings("unchecked")
    private void wireCluster(final Kafka kafka) {
        MixedOperation kafkaOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(Kafka.class)).thenReturn(kafkaOp);
        NonNamespaceOperation nsOp = Mockito.mock(NonNamespaceOperation.class);
        when(kafkaOp.inNamespace("kafka")).thenReturn(nsOp);
        Resource kafkaResource = Mockito.mock(Resource.class);
        when(nsOp.withName("my-cluster")).thenReturn(kafkaResource);
        when(kafkaResource.get()).thenReturn(kafka);
    }

    private Kafka clusterWithSimpleAuthz() {
        return new KafkaBuilder()
            .withNewMetadata()
                .withName("my-cluster")
                .withNamespace("kafka")
            .endMetadata()
            .withNewSpec()
                .withNewKafka()
                    .withNewKafkaAuthorizationSimple()
                    .endKafkaAuthorizationSimple()
                .endKafka()
            .endSpec()
            .withNewStatus()
                .addNewCondition()
                    .withType("Ready")
                    .withStatus("True")
                .endCondition()
            .endStatus()
            .build();
    }

    @Test
    void testThrowsWhenClusterNameNull() {
        assertThrows(McpException.class, () ->
            auditService.audit("kafka", null, null, null, null, null));
    }

    @Test
    void testThrowsWhenClusterNotFound() {
        assertThrows(McpException.class, () ->
            auditService.audit("kafka", "nonexistent", null, null, null, null));
    }

    @Test
    void testFullWorkflowWithoutSampling() {
        wireCluster(clusterWithSimpleAuthz());

        KafkaSecurityAuditReport report = auditService.audit(
            "kafka", "my-cluster", null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.cluster());
        assertTrue(report.stepsCompleted().contains("cluster_security"));
        assertTrue(report.stepsCompleted().contains("users"));
        assertNull(report.analysis()); // no Sampling -> raw data
        assertNotNull(report.timestamp());
    }

    @Test
    void testAuthorizationTypeNullWhenClusterHasNoAuthz() {
        Kafka kafka = new KafkaBuilder()
            .withNewMetadata().withName("my-cluster").withNamespace("kafka").endMetadata()
            .withNewSpec().withNewKafka().endKafka().endSpec()
            .build();
        wireCluster(kafka);

        KafkaSecurityAuditReport report = auditService.audit(
            "kafka", "my-cluster", null, null, null, null);

        assertNull(report.authorizationType());
        assertNotNull(report.cluster());
    }

    @Test
    void testZeroUsersSkipsAclDeepDive() {
        wireCluster(clusterWithSimpleAuthz());

        KafkaSecurityAuditReport report = auditService.audit(
            "kafka", "my-cluster", null, null, null, null);

        // No KafkaUsers mocked -> users empty -> ACL step skipped
        assertTrue(report.users().isEmpty());
        assertFalse(report.stepsCompleted().contains("acls"));
        assertNull(report.aclMatrices());
    }

    @Test
    void testAclMatricesPopulatedWithBroadGrant() {
        wireCluster(clusterWithSimpleAuthz());
        KafkaUser wildcardUser = topicAclUser("alice", "kafka", "my-cluster",
            "*", AclResourcePatternType.LITERAL, AclRuleType.ALLOW, StrimziAclOperation.READ);
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaUser.class, List.of(wildcardUser));

        KafkaSecurityAuditReport report = auditService.audit(
            "kafka", "my-cluster", null, null, null, null);

        assertFalse(report.users().isEmpty());
        assertTrue(report.stepsCompleted().contains("acls"));
        assertNotNull(report.aclMatrices());
        assertFalse(report.aclMatrices().isEmpty(), "ACL matrices should be populated");

        boolean hasWildcardBroadGrant = report.aclMatrices().stream()
            .map(KafkaUserAclMatrixResponse::broadGrants)
            .filter(grants -> grants != null)
            .flatMap(List::stream)
            .anyMatch(g -> "alice".equals(g.principal()) && "wildcard resource".equals(g.reason()));
        assertTrue(hasWildcardBroadGrant, "wildcard resource grant should be flagged in broad_grants");
    }

    @Test
    void testCertificateFailureDoesNotAbortWorkflow() {
        wireCluster(clusterWithSimpleAuthz());
        when(certificateService.getCertificates(any(), any(), any()))
            .thenThrow(new RuntimeException("secrets is forbidden"));

        KafkaSecurityAuditReport report = auditService.audit(
            "kafka", "my-cluster", null, null, null, null);

        assertNotNull(report);
        assertNotNull(report.cluster());
        assertTrue(report.stepsCompleted().contains("cluster_security"));
        assertNotNull(report.stepsFailed(), "Certificate failure should be recorded");
        assertTrue(report.stepsFailed().stream().anyMatch(s -> s.startsWith("certificates")),
            "steps_failed should contain the certificates step");
    }

    private static KafkaUser topicAclUser(final String name, final String namespace, final String cluster,
                                          final String topicName, final AclResourcePatternType patternType,
                                          final AclRuleType ruleType, final StrimziAclOperation operation) {
        return new KafkaUserBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, cluster))
            .endMetadata()
            .withNewSpec()
                .withNewKafkaUserAuthorizationSimple()
                    .addNewAcl()
                        .withType(ruleType)
                        .withNewAclRuleTopicResource()
                            .withName(topicName)
                            .withPatternType(patternType)
                        .endAclRuleTopicResource()
                        .withOperations(operation)
                    .endAcl()
                .endKafkaUserAuthorizationSimple()
            .endSpec()
            .withNewStatus()
                .withUsername(name)
            .endStatus()
            .build();
    }
}
