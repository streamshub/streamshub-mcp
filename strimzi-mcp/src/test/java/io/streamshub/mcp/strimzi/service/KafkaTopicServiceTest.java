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
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.PaginatedResponse;
import io.streamshub.mcp.common.dto.error.McpErrorCategory;
import io.streamshub.mcp.common.dto.error.McpErrorData;
import io.streamshub.mcp.common.util.NamespaceElicitationHelper;
import io.streamshub.mcp.strimzi.dto.kafkatopic.KafkaTopicResponse;
import io.streamshub.mcp.strimzi.dto.kafkatopic.TopicStatusFilter;
import io.streamshub.mcp.strimzi.service.kafkatopic.KafkaTopicService;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.topic.KafkaTopicBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
/**
 * Service-level tests for {@link KafkaTopicService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaTopicServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaTopicService topicService;

    KafkaTopicServiceTest() {
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

        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaTopic.class);
    }

    @Test
    void testListTopicsReturnsEmptyWhenNoneExist() {
        PaginatedResponse<?> result = topicService.listTopics("kafka", "my-cluster", null, null, null, null);

        assertNotNull(result);
        assertTrue(result.items().isEmpty());
        assertEquals(0, result.total());
        assertFalse(result.hasMore());
    }

    @Test
    void testListTopicsDefaultPagination() {
        PaginatedResponse<?> result = topicService.listTopics("kafka", "my-cluster", null, null, null, null);

        assertEquals(0, result.offset());
        assertEquals(100, result.limit());
    }

    @Test
    void testListTopicsCustomPagination() {
        PaginatedResponse<?> result = topicService.listTopics("kafka", "my-cluster", 5, 10, null, null);

        assertEquals(5, result.offset());
        assertEquals(10, result.limit());
    }

    @Test
    void testListTopicsNegativeOffsetNormalized() {
        PaginatedResponse<?> result = topicService.listTopics("kafka", "my-cluster", -1, null, null, null);

        assertEquals(0, result.offset());
    }

    @Test
    void testGetTopicIncludesTopicIdWhenPresent() {
        setupKafkaTopic(new KafkaTopicBuilder()
            .withNewMetadata().withName("my-topic").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).endSpec()
            .withNewStatus()
                .addNewCondition().withType("Ready").withStatus("True").endCondition()
                .withTopicId("abc123")
                .withTopicName("my-topic")
            .endStatus()
            .build());

        KafkaTopicResponse response = topicService.getTopic("kafka", "my-cluster", "my-topic");

        assertEquals("abc123", response.topicId());
        assertEquals("my-topic", response.topicName());
    }

    @Test
    void testGetTopicRedactsSecretConfigValues() {
        setupKafkaTopic(new KafkaTopicBuilder()
            .withNewMetadata().withName("my-topic").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3)
                .withConfig(Map.of("retention.ms", 60000, "custom.api.key", "abc123"))
            .endSpec()
            .build());

        Map<String, Object> config = topicService.getTopic("kafka", "my-cluster", "my-topic").configuration();

        assertEquals(60000, config.get("retention.ms"));
        assertEquals("[REDACTED]", config.get("custom.api.key"));
    }

    /**
     * Pins the source-level redaction with a key that only the strict configuration-map rules treat
     * as secret: {@code basic.auth.user.info} is redacted because of the {@code auth} config word,
     * but the lenient JSON-key rules used by the response guardrail leave it visible. The test calls
     * the service directly (no guardrail), so a redacted value proves the source redaction in
     * {@code createTopicResponse}, which also covers the resource template and its notifications.
     */
    @Test
    void testGetTopicRedactsConfigStrictOnlyKeyAtSource() {
        setupKafkaTopic(new KafkaTopicBuilder()
            .withNewMetadata().withName("my-topic").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3)
                .withConfig(Map.of("basic.auth.user.info", "user:secret"))
            .endSpec()
            .build());

        Map<String, Object> config = topicService.getTopic("kafka", "my-cluster", "my-topic").configuration();

        assertEquals("[REDACTED]", config.get("basic.auth.user.info"));
    }

    @Test
    void testGetTopicTopicIdAbsentWhenStatusMissing() {
        setupKafkaTopic(new KafkaTopicBuilder()
            .withNewMetadata().withName("my-topic").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).endSpec()
            .build());

        KafkaTopicResponse response = topicService.getTopic("kafka", "my-cluster", "my-topic");

        assertNull(response.topicId());
        assertNull(response.topicName());
        assertNull(response.conditions());
        assertNull(response.replicasChange());
    }

    @Test
    void testGetTopicMapsStatusConditions() {
        setupKafkaTopic(new KafkaTopicBuilder()
            .withNewMetadata().withName("my-topic").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).endSpec()
            .withNewStatus()
                .addNewCondition()
                    .withType("Ready").withStatus("True")
                    .withReason("Reconciled").withMessage("Topic is ready")
                    .withLastTransitionTime("2026-01-01T00:00:00Z")
                .endCondition()
            .endStatus()
            .build());

        KafkaTopicResponse response = topicService.getTopic("kafka", "my-cluster", "my-topic");

        assertEquals(1, response.conditions().size());
        assertEquals("Ready", response.conditions().get(0).type());
        assertEquals("True", response.conditions().get(0).status());
        assertEquals("Reconciled", response.conditions().get(0).reason());
        assertEquals("Topic is ready", response.conditions().get(0).message());
        assertEquals("2026-01-01T00:00:00Z", response.conditions().get(0).lastTransitionTime());
    }

    @Test
    void testGetTopicReplicasChangeNullInSteadyState() {
        setupKafkaTopic(new KafkaTopicBuilder()
            .withNewMetadata().withName("my-topic").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).endSpec()
            .withNewStatus()
                .addNewCondition().withType("Ready").withStatus("True").endCondition()
            .endStatus()
            .build());

        KafkaTopicResponse response = topicService.getTopic("kafka", "my-cluster", "my-topic");

        assertNull(response.replicasChange());
    }

    @Test
    void testGetTopicReconciliationUsesObservedGeneration() {
        setupKafkaTopic(new KafkaTopicBuilder()
            .withNewMetadata().withName("my-topic").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
                .withGeneration(3L)
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).endSpec()
            .withNewStatus()
                .addNewCondition().withType("Ready").withStatus("True").endCondition()
                .withObservedGeneration(2L)
            .endStatus()
            .build());

        KafkaTopicResponse response = topicService.getTopic("kafka", "my-cluster", "my-topic");

        assertNotNull(response.reconciliation());
        assertEquals(3L, response.reconciliation().generation());
        assertEquals(2L, response.reconciliation().observedGeneration());
        assertFalse(response.reconciliation().upToDate());
    }

    @Test
    void testGetTopicManagedTrueWhenAnnotationAbsent() {
        setupKafkaTopic(new KafkaTopicBuilder()
            .withNewMetadata().withName("my-topic").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).endSpec()
            .build());

        KafkaTopicResponse response = topicService.getTopic("kafka", "my-cluster", "my-topic");

        assertEquals(Boolean.TRUE, response.managed());
    }

    @Test
    void testGetTopicManagedFalseWhenAnnotationFalse() {
        setupKafkaTopic(new KafkaTopicBuilder()
            .withNewMetadata().withName("my-topic").withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
                .withAnnotations(Map.of("strimzi.io/managed", "false"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).endSpec()
            .build());

        KafkaTopicResponse response = topicService.getTopic("kafka", "my-cluster", "my-topic");

        assertEquals(Boolean.FALSE, response.managed());
    }

    @Test
    void testListTopicsNameFilterPrefix() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topic("mcp-topic-alpha", true, null),
            topic("mcp-topic-beta", true, null),
            topic("other-topic", true, null)));

        PaginatedResponse<?> result = topicService.listTopics(
            "kafka", "my-cluster", null, null, "mcp-topic-a", null);

        assertEquals(1, result.total());
        assertEquals(1, result.items().size());
    }

    @Test
    void testListTopicsNameFilterGlob() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topic("mcp-topic-alpha", true, null),
            topic("mcp-topic-beta", true, null),
            topic("other-topic", true, null)));

        PaginatedResponse<?> result = topicService.listTopics(
            "kafka", "my-cluster", null, null, "mcp-*", null);

        assertEquals(2, result.total());
    }

    @Test
    void testListTopicsStatusFilterNotReadyExcludesError() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topic("ready-topic", true, null),
            topic("error-topic", false, null),
            notReadyTopic("stalled-topic")));

        PaginatedResponse<?> result = topicService.listTopics(
            "kafka", "my-cluster", null, null, null, TopicStatusFilter.NOT_READY);

        assertEquals(1, result.total());
        assertEquals("stalled-topic", ((KafkaTopicResponse) result.items().get(0)).name());
    }

    @Test
    void testListTopicsStatusFilterUnmanaged() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topic("managed-ready", true, null),
            topic("unmanaged-ready", true, "false")));

        PaginatedResponse<?> result = topicService.listTopics(
            "kafka", "my-cluster", null, null, null, TopicStatusFilter.UNMANAGED);

        assertEquals(1, result.total());
        assertEquals("unmanaged-ready", ((KafkaTopicResponse) result.items().get(0)).name());
    }

    @Test
    void testListTopicsNoFilterReturnsAll() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topic("a", true, null), topic("b", true, null)));

        PaginatedResponse<?> result = topicService.listTopics(
            "kafka", "my-cluster", null, null, "   ", null);

        assertEquals(2, result.total());
    }

    @Test
    void testGetTopicResolvesKafkaTopicNameWithUnderscoresViaSpecTopicName() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topicWithKafkaName("my-topic-resource", "kafka", "my_topic", null)));

        KafkaTopicResponse response = topicService.getTopic("kafka", "my-cluster", "my_topic");

        assertEquals("my-topic-resource", response.name());
    }

    @Test
    void testGetTopicResolvesInternalTopicViaStatusTopicName() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topicWithKafkaName("consumer-offsets", "kafka", null, "__consumer_offsets")));

        KafkaTopicResponse response = topicService.getTopic(null, "my-cluster", "__consumer_offsets");

        assertEquals("consumer-offsets", response.name());
        assertEquals("__consumer_offsets", response.topicName());
    }

    @Test
    void testGetTopicKeepsCaseOfKafkaTopicName() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topicWithKafkaName("orders-v1", "kafka", "Orders_V1", null)));

        assertEquals("orders-v1", topicService.getTopic("kafka", "my-cluster", "Orders_V1").name());
        assertThrows(McpException.class, () -> topicService.getTopic("kafka", "my-cluster", "orders_v1"));
    }

    @Test
    void testGetTopicRejectsIllegalKafkaTopicName() {
        McpException ex = assertThrows(McpException.class,
            () -> topicService.getTopic("kafka", "my-cluster", "bad/topic"));

        assertTrue(ex.getMessage().contains("Invalid topic name"));
    }

    @Test
    void testGetTopicAmbiguousAcrossNamespacesReportsCandidates() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topicWithKafkaName("my-topic", "team-a", null, null),
            topicWithKafkaName("my-topic", "team-b", null, null)));

        McpException ex = assertThrows(McpException.class,
            () -> topicService.getTopic(null, "my-cluster", "my-topic"));

        assertTrue(NamespaceElicitationHelper.isMultipleNamespacesError(ex));
        McpErrorData data = (McpErrorData) ex.getData();
        assertEquals(List.of("team-a", "team-b"), data.candidates());
    }

    @Test
    void testGetTopicInOneNamespaceIsNotAmbiguous() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topicWithKafkaName("my-topic", "team-a", null, null),
            topicWithKafkaName("other", "team-b", null, null)));

        assertEquals("team-a", topicService.getTopic(null, "my-cluster", "my-topic").namespace());
    }

    @Test
    void testGetTopicNormalizesClusterName() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topicWithKafkaName("my-topic", "kafka", null, null)));

        assertEquals("my-topic", topicService.getTopic("kafka", "  My-Cluster ", "my-topic").name());
        assertEquals("my-topic", topicService.getTopic("kafka", "null", "my-topic").name());
    }

    @Test
    void testGetTopicFromOtherClusterIsNotFound() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topicWithKafkaName("my-topic", "kafka", null, null)));

        McpException ex = assertThrows(McpException.class,
            () -> topicService.getTopic("kafka", "other-cluster", "my-topic"));

        assertEquals(McpErrorCategory.RESOURCE_NOT_FOUND, ((McpErrorData) ex.getData()).category());
        assertTrue(ex.getMessage().contains("Only cluster 'other-cluster' was searched; the topic exists in "
            + "cluster(s) 'my-cluster' (namespace kafka); retry with that clusterName and namespace"), ex.getMessage());
    }

    @Test
    void testGetTopicInOtherClustersAcrossNamespacesListsThemSorted() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topicInCluster("orders", "team-b", "cluster-b", null),
            topicInCluster("orders", "team-a", "cluster-a", null)));

        McpException ex = assertThrows(McpException.class,
            () -> topicService.getTopic(null, "cluster-c", "orders"));

        assertEquals(McpErrorCategory.RESOURCE_NOT_FOUND, ((McpErrorData) ex.getData()).category());
        assertTrue(ex.getMessage().contains(
            "cluster(s) 'cluster-a' (namespace team-a), 'cluster-b' (namespace team-b)"), ex.getMessage());
        assertEquals("Retry with the clusterName and namespace of an owning cluster.",
            ((McpErrorData) ex.getData()).remediation(), ex.getMessage());
    }

    @Test
    void testGetTopicMissingEverywhereHasNoClusterHint() {
        McpException ex = assertThrows(McpException.class,
            () -> topicService.getTopic("kafka", "my-cluster", "missing"));

        assertFalse(ex.getMessage().contains("cluster(s)"), ex.getMessage());
    }

    @Test
    void testGetTopicHintListsAtMostFiveOwners() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topicInCluster("orders", "team-a", "cluster-a", null),
            topicInCluster("orders", "team-b", "cluster-b", null),
            topicInCluster("orders", "team-c", "cluster-c", null),
            topicInCluster("orders", "team-d", "cluster-d", null),
            topicInCluster("orders", "team-e", "cluster-e", null),
            topicInCluster("orders", "team-f", "cluster-f", null)));

        McpException ex = assertThrows(McpException.class,
            () -> topicService.getTopic(null, "cluster-zz", "orders"));

        assertEquals(McpErrorCategory.RESOURCE_NOT_FOUND, ((McpErrorData) ex.getData()).category());
        assertTrue(ex.getMessage().contains("cluster(s) 'cluster-a' (namespace team-a),"
            + " 'cluster-b' (namespace team-b), 'cluster-c' (namespace team-c),"
            + " 'cluster-d' (namespace team-d), 'cluster-e' (namespace team-e); and 1 more"), ex.getMessage());
        assertFalse(ex.getMessage().contains("cluster-f"), ex.getMessage());
    }

    @Test
    void testGetTopicHintSkippedOnLargeTopicEstate() {
        // One more than KafkaTopicService.MAX_HINT_CANDIDATES (2000): the hint scan is skipped.
        List<KafkaTopic> estate = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            estate.add(topicInCluster("orders", "team-" + i, "cluster-" + i, null));
        }
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, estate);

        McpException ex = assertThrows(McpException.class,
            () -> topicService.getTopic(null, "cluster-zz", "orders"));

        assertEquals(McpErrorCategory.RESOURCE_NOT_FOUND, ((McpErrorData) ex.getData()).category());
        assertFalse(ex.getMessage().contains("cluster(s)"), ex.getMessage());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void testGetTopicHintLookupFailureStillReportsNotFound() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topicWithKafkaName("my-topic", "kafka", null, null)));
        // The cluster-scoped lookup uses a label query; only the unfiltered hint lookup fails.
        NonNamespaceOperation topicsInNamespace = kubernetesClient.resources(KafkaTopic.class).inNamespace("kafka");
        when(topicsInNamespace.list()).thenThrow(new KubernetesClientException("forbidden"));

        McpException ex = assertThrows(McpException.class,
            () -> topicService.getTopic("kafka", "other-cluster", "missing"));

        assertEquals(McpErrorCategory.RESOURCE_NOT_FOUND, ((McpErrorData) ex.getData()).category());
        assertFalse(ex.getMessage().contains("cluster(s)"), ex.getMessage());
    }

    @Test
    void testGetTopicDirectHitInOtherClusterFallsThroughToRequestedCluster() {
        // "orders" belongs to cluster a; "orders-b" in cluster b manages the Kafka topic "orders".
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topicInCluster("orders", "kafka", "cluster-a", null),
            topicInCluster("orders-b", "kafka", "cluster-b", "orders")));

        assertEquals("orders", topicService.getTopic("kafka", "cluster-a", "orders").name());
        assertEquals("orders-b", topicService.getTopic("kafka", "cluster-b", "orders").name());
    }

    @Test
    void testGetTopicPrefersResourceNameWhenSeveralClustersManageTheTopicInOneNamespace() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topicInCluster("orders", "kafka", "cluster-a", null),
            topicInCluster("orders-b", "kafka", "cluster-b", "orders")));

        assertEquals("orders", topicService.getTopic("kafka", null, "orders").name());
    }

    @Test
    void testGetTopicSeveralClustersNoResourceNameMatchAsksForCluster() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topicInCluster("orders-a", "kafka", "cluster-a", "orders"),
            topicInCluster("orders-b", "kafka", "cluster-b", "orders")));

        McpException ex = assertThrows(McpException.class,
            () -> topicService.getTopic("kafka", null, "orders"));

        assertTrue(ex.getMessage().contains("Multiple KafkaTopic resources"));
        assertTrue(ex.getMessage().contains("cluster-a"));
        assertTrue(ex.getMessage().contains("cluster-b"));
        assertTrue(ex.getMessage().contains("specify the cluster name"));
    }

    @Test
    void testGetTopicConflictWithinOneClusterNamesResourcesInsteadOfAskingForCluster() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topicInCluster("orders-1", "kafka", "cluster-a", "orders"),
            topicInCluster("orders-2", "kafka", "cluster-a", "orders")));

        McpException ex = assertThrows(McpException.class,
            () -> topicService.getTopic("kafka", "cluster-a", "orders"));

        assertTrue(ex.getMessage().contains("orders-1, orders-2"));
        assertFalse(ex.getMessage().contains("specify the cluster name"));
    }

    @Test
    void testGetTopicResolvesSpecTopicNameWithExplicitNamespaceAndCluster() {
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaTopic.class, List.of(
            topicInCluster("orders-b", "team-b", "cluster-b", "orders")));

        KafkaTopicResponse response = topicService.getTopic("team-b", "cluster-b", "orders");

        assertEquals("orders-b", response.name());
        assertEquals("team-b", response.namespace());
    }

    private KafkaTopic topicWithKafkaName(final String resourceName, final String namespace,
                                          final String specTopicName, final String statusTopicName) {
        return topicInCluster(resourceName, namespace, "my-cluster", specTopicName, statusTopicName);
    }

    private KafkaTopic topicInCluster(final String resourceName, final String namespace,
                                      final String cluster, final String specTopicName) {
        return topicInCluster(resourceName, namespace, cluster, specTopicName, null);
    }

    private KafkaTopic topicInCluster(final String resourceName, final String namespace, final String cluster,
                                      final String specTopicName, final String statusTopicName) {
        KafkaTopicBuilder builder = new KafkaTopicBuilder()
            .withNewMetadata().withName(resourceName).withNamespace(namespace)
                .withLabels(Map.of("strimzi.io/cluster", cluster))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).withTopicName(specTopicName).endSpec();
        if (statusTopicName != null) {
            builder = builder.withNewStatus().withTopicName(statusTopicName).endStatus();
        }
        return builder.build();
    }

    private KafkaTopic topic(final String name, final boolean ready, final String managedAnnotation) {
        KafkaTopic t = new KafkaTopicBuilder()
            .withNewMetadata().withName(name).withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).endSpec()
            .withNewStatus()
                .addNewCondition().withType("Ready").withStatus(ready ? "True" : "False").endCondition()
            .endStatus()
            .build();
        if (managedAnnotation != null) {
            t.getMetadata().setAnnotations(Map.of("strimzi.io/managed", managedAnnotation));
        }
        return t;
    }

    private KafkaTopic notReadyTopic(final String name) {
        return new KafkaTopicBuilder()
            .withNewMetadata().withName(name).withNamespace("kafka")
                .withLabels(Map.of("strimzi.io/cluster", "my-cluster"))
            .endMetadata()
            .withNewSpec().withPartitions(3).withReplicas(3).endSpec()
            .withNewStatus()
                .addNewCondition().withType("Warning").withStatus("True").endCondition()
            .endStatus()
            .build();
    }

    @SuppressWarnings("unchecked")
    private void setupKafkaTopic(final KafkaTopic topic) {
        String namespace = topic.getMetadata().getNamespace();
        String name = topic.getMetadata().getName();

        MixedOperation topicOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(KafkaTopic.class)).thenReturn(topicOp);

        NonNamespaceOperation nsTopicOp = Mockito.mock(NonNamespaceOperation.class);
        when(topicOp.inNamespace(namespace)).thenReturn(nsTopicOp);

        Resource topicResource = Mockito.mock(Resource.class);
        when(nsTopicOp.withName(name)).thenReturn(topicResource);
        when(topicResource.get()).thenReturn(topic);
    }
}
