/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.quarkiverse.mcp.server.CompleteContext;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.user.KafkaUser;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link CompletionService} with mocked Kubernetes.
 */
@QuarkusTest
class CompletionServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    CompletionService completionService;

    CompletionServiceTest() {
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Kafka.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaNodePool.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaTopic.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaUser.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaConnect.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaBridge.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaConnector.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);

        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deploymentOp =
            Mockito.mock(MixedOperation.class);
        AppsAPIGroupDSL appsApi = Mockito.mock(AppsAPIGroupDSL.class);
        Mockito.lenient().when(kubernetesClient.apps()).thenReturn(appsApi);
        Mockito.lenient().when(appsApi.deployments()).thenReturn(deploymentOp);

        MixedOperation<Pod, PodList, PodResource> podOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.pods()).thenReturn(podOp);
    }

    /**
     * Verify argument completions return empty when no resources exist.
     *
     * @param argName the argument name to complete
     */
    @ParameterizedTest
    @ValueSource(strings = {"cluster_name", "topic_name", "user_name", "connector_name"})
    void testCompleteByArgumentNameReturnsEmpty(final String argName) {
        CompleteContext context = mockContext(Map.of(argName, ""));

        List<String> result = completionService.completeByArgumentName("", context);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify unknown argument returns empty list.
     */
    @Test
    void testCompleteUnknownArgumentReturnsEmpty() {
        CompleteContext context = mockContext(Map.of("unknown_arg", ""));

        List<String> result = completionService.completeByArgumentName("", context);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify Kafka cluster resource template completion returns empty when no clusters exist.
     */
    @Test
    void testCompleteKafkaClusterArgsNameReturnsEmpty() {
        CompleteContext context = mockContext(Map.of("name", ""));

        List<String> result = completionService.completeKafkaClusterArgs("", context);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    private CompleteContext mockContext(final Map<String, String> args) {
        CompleteContext context = Mockito.mock(CompleteContext.class);
        Mockito.when(context.arguments()).thenReturn(args);
        return context;
    }
}
