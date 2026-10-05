/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.strimzi.dto.operator.StrimziOperatorConfigResponse;
import io.streamshub.mcp.strimzi.dto.operator.StrimziOperatorLogsResponse;
import io.streamshub.mcp.strimzi.dto.operator.StrimziOperatorResponse;
import io.streamshub.mcp.strimzi.service.operator.StrimziOperatorService;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
/**
 * Service-level tests for {@link StrimziOperatorService} with mocked Kubernetes.
 */
@QuarkusTest
class StrimziOperatorServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    StrimziOperatorService operatorService;

    StrimziOperatorServiceTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MixedOperation<Pod, PodList, PodResource> podOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.pods()).thenReturn(podOp);

        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deploymentOp =
            Mockito.mock(MixedOperation.class);
        AppsAPIGroupDSL appsApi = Mockito.mock(AppsAPIGroupDSL.class);
        Mockito.lenient().when(kubernetesClient.apps()).thenReturn(appsApi);
        Mockito.lenient().when(appsApi.deployments()).thenReturn(deploymentOp);

        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Deployment.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);
    }

    @Test
    void testGetOperatorLogsReturnsNotFoundWhenNoPodsExist() {
        setupEmptyPodResponses("kafka-system");

        StrimziOperatorLogsResponse result = operatorService.getOperatorLogs(
            "kafka-system", null, LogCollectionParams.of(null, null, 200, null));

        assertNotNull(result);
        assertEquals("kafka-system", result.namespace());
        assertNotNull(result.message());
        assertTrue(result.message().contains("No Strimzi operator pods found"));
    }

    @Test
    void testGetOperatorLogsNormalizesNamespaceInput() {
        setupEmptyPodResponses("kafka");

        StrimziOperatorLogsResponse result1 = operatorService.getOperatorLogs(
            "  KAFKA  ", null, LogCollectionParams.of(null, null, 200, null));
        StrimziOperatorLogsResponse result2 = operatorService.getOperatorLogs(
            "kafka", null, LogCollectionParams.of(null, null, 200, null));

        assertEquals("kafka", result1.namespace());
        assertEquals("kafka", result2.namespace());
    }

    @Test
    void testListOperatorsReturnsEmptyWhenNoneExist() {
        List<StrimziOperatorResponse> result = operatorService.listOperators("kafka-system");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetEntityOperatorLogsReturnsNotFoundWhenNoPodsExist() {
        setupEmptyPodResponses("kafka");

        StrimziOperatorLogsResponse result = operatorService.getEntityOperatorLogs(
            "kafka", "my-cluster", LogCollectionParams.of(null, null, 200, null));

        assertNotNull(result);
        assertEquals("kafka", result.namespace());
        assertNotNull(result.message());
        assertTrue(result.message().contains("No Strimzi operator pods found"));
    }

    @Test
    void testGetOperatorConfigReturnsAllEnvVars() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, Deployment.class,
            List.of(operatorDeployment()));

        StrimziOperatorConfigResponse result = operatorService.getOperatorConfig(
            "kafka-system", "strimzi-cluster-operator");

        // All env vars are returned, including credential-named ones
        assertTrue(result.config().containsKey("STRIMZI_FEATURE_GATES"), "Should contain STRIMZI_FEATURE_GATES");
        assertTrue(result.config().containsKey("STRIMZI_NAMESPACE"), "Should contain STRIMZI_NAMESPACE");
        assertTrue(result.config().containsKey("STRIMZI_KAFKA_IMAGES"), "Should contain STRIMZI_KAFKA_IMAGES");
        assertTrue(result.config().containsKey("STRIMZI_REGISTRY_PASSWORD"), "Should contain STRIMZI_REGISTRY_PASSWORD");
        assertTrue(result.config().containsKey("STRIMZI_WEBHOOK_TOKEN"), "Should contain STRIMZI_WEBHOOK_TOKEN");
        assertTrue(result.config().containsKey("STRIMZI_TLS_KEY"), "Should contain STRIMZI_TLS_KEY");
        assertTrue(result.config().containsKey("STRIMZI_CLIENT_SECRET"), "Should contain STRIMZI_CLIENT_SECRET");
        // Literal values are returned as-is
        assertEquals("hunter2", result.config().get("STRIMZI_REGISTRY_PASSWORD"));
    }

    @Test
    void testGetOperatorConfigEncodesValueFromAsReferenceString() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, Deployment.class,
            List.of(operatorDeployment()));

        StrimziOperatorConfigResponse result = operatorService.getOperatorConfig(
            "kafka-system", "strimzi-cluster-operator");

        // STRIMZI_OPERATION_TIMEOUT_MS is sourced from a Secret via valueFrom — should appear as a reference descriptor
        assertEquals("secretKeyRef:operator-config/timeout", result.config().get("STRIMZI_OPERATION_TIMEOUT_MS"),
            "valueFrom entry should be encoded as a reference string, not the resolved secret value");
        // The structured field on the response also picks up the reference string
        assertEquals("secretKeyRef:operator-config/timeout", result.operationTimeoutMs());
    }

    @Test
    void testGetOperatorConfigParsesNamespacesAndKafkaVersions() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, Deployment.class,
            List.of(operatorDeployment()));

        StrimziOperatorConfigResponse result = operatorService.getOperatorConfig(
            "kafka-system", "strimzi-cluster-operator");

        assertEquals(List.of("kafka", "kafka-dev"), result.watchedNamespaces());
        assertFalse(result.watchesAllNamespaces());
        assertEquals(List.of("3.9.0", "4.0.0"), result.supportedKafkaVersions());
        assertEquals("+UseKRaft", result.featureGates());
        assertEquals("true", result.leaderElectionEnabled());
    }

    @Test
    void testGetOperatorConfigDetectsWatchAllNamespaces() {
        Deployment deployment = operatorDeployment();
        deployment.getSpec().getTemplate().getSpec().getContainers().getFirst()
            .setEnv(List.of(new EnvVarBuilder().withName("STRIMZI_NAMESPACE").withValue("*").build()));
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, Deployment.class, List.of(deployment));

        StrimziOperatorConfigResponse result = operatorService.getOperatorConfig(
            "kafka-system", "strimzi-cluster-operator");

        assertTrue(result.watchesAllNamespaces());
        assertTrue(result.watchedNamespaces().isEmpty());
        assertTrue(result.supportedKafkaVersions().isEmpty());
    }

    @Test
    void testGetOperatorConfigHandlesNullContainers() {
        Deployment deployment = operatorDeployment();
        deployment.getSpec().getTemplate().getSpec().setContainers(null);
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, Deployment.class, List.of(deployment));

        StrimziOperatorConfigResponse result = operatorService.getOperatorConfig(
            "kafka-system", "strimzi-cluster-operator");

        assertNotNull(result);
        assertTrue(result.config().isEmpty());
    }

    @Test
    void testGetOperatorConfigThrowsWhenOperatorNotFound() {
        assertThrows(McpException.class,
            () -> operatorService.getOperatorConfig("kafka-system", "missing-operator"));
    }

    private static Deployment operatorDeployment() {
        return new DeploymentBuilder()
            .withNewMetadata()
                .withName("strimzi-cluster-operator")
                .withNamespace("kafka-system")
            .endMetadata()
            .withNewSpec()
                .withReplicas(1)
                .withNewTemplate()
                    .withNewSpec()
                        .addNewContainer()
                            .withName("strimzi-cluster-operator")
                            .withImage("quay.io/strimzi/operator:0.48.0")
                            .withEnv(
                                new EnvVarBuilder().withName("STRIMZI_FEATURE_GATES")
                                    .withValue("+UseKRaft").build(),
                                new EnvVarBuilder().withName("STRIMZI_NAMESPACE")
                                    .withValue("kafka,kafka-dev").build(),
                                new EnvVarBuilder().withName("STRIMZI_KAFKA_IMAGES")
                                    .withValue("3.9.0=quay.io/strimzi/kafka:0.48.0-kafka-3.9.0\n"
                                        + "4.0.0=quay.io/strimzi/kafka:0.48.0-kafka-4.0.0").build(),
                                new EnvVarBuilder().withName("STRIMZI_FULL_RECONCILIATION_INTERVAL_MS")
                                    .withValue("120000").build(),
                                new EnvVarBuilder().withName("STRIMZI_LEADER_ELECTION_ENABLED")
                                    .withValue("true").build(),
                                // Sourced from a Secret via valueFrom - encoded as reference string
                                new EnvVarBuilder().withName("STRIMZI_OPERATION_TIMEOUT_MS")
                                    .withNewValueFrom()
                                        .withNewSecretKeyRef("timeout", "operator-config", false)
                                    .endValueFrom().build(),
                                // Credential-shaped vars with literal values - returned as-is
                                new EnvVarBuilder().withName("STRIMZI_REGISTRY_PASSWORD")
                                    .withValue("hunter2").build(),
                                new EnvVarBuilder().withName("STRIMZI_WEBHOOK_TOKEN")
                                    .withValue("hunter2").build(),
                                new EnvVarBuilder().withName("STRIMZI_TLS_KEY")
                                    .withValue("hunter2").build(),
                                new EnvVarBuilder().withName("STRIMZI_CLIENT_SECRET")
                                    .withValue("hunter2").build())
                        .endContainer()
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build();
    }

    @SuppressWarnings("unchecked")
    private void setupEmptyPodResponses(final String namespace) {
        PodList emptyPodList = new PodList();
        emptyPodList.setItems(List.of());

        MixedOperation<Pod, PodList, PodResource> podOp = kubernetesClient.pods();
        NonNamespaceOperation<Pod, PodList, PodResource> namespacedPodOp =
            Mockito.mock(NonNamespaceOperation.class);
        FilterWatchListDeletable<Pod, PodList, PodResource> labeledPodOp =
            Mockito.mock(FilterWatchListDeletable.class);

        when(podOp.inNamespace(namespace)).thenReturn(namespacedPodOp);
        when(namespacedPodOp.withLabel(anyString(), anyString())).thenReturn(labeledPodOp);
        when(labeledPodOp.list()).thenReturn(emptyPodList);

        DeploymentList emptyDeploymentList = new DeploymentList();
        emptyDeploymentList.setItems(List.of());

        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deploymentOp =
            kubernetesClient.apps().deployments();
        NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> namespacedDeploymentOp =
            Mockito.mock(NonNamespaceOperation.class);
        FilterWatchListDeletable<Deployment, DeploymentList, RollableScalableResource<Deployment>> labeledDeploymentOp =
            Mockito.mock(FilterWatchListDeletable.class);

        when(deploymentOp.inNamespace(namespace)).thenReturn(namespacedDeploymentOp);
        when(namespacedDeploymentOp.withLabel(anyString(), anyString())).thenReturn(labeledDeploymentOp);
        when(labeledDeploymentOp.list()).thenReturn(emptyDeploymentList);
    }
}
