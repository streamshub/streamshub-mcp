/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkatopic;

import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.PaginatedResponse;
import io.streamshub.mcp.common.dto.ReconciliationInfo;
import io.streamshub.mcp.common.guardrail.SensitiveKeys;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.GlobMatcher;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.common.util.PaginationUtils;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.kafkatopic.KafkaTopicResponse;
import io.streamshub.mcp.strimzi.dto.kafkatopic.TopicStatusFilter;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.topic.ReplicasChangeStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
/**
 * Service for Kafka topic operations.
 */
@ApplicationScoped
public class KafkaTopicService {

    private static final Logger LOG = Logger.getLogger(KafkaTopicService.class);

    @Inject
    KubernetesResourceService k8sService;

    @ConfigProperty(name = "mcp.topics.default-page-size", defaultValue = "100")
    int defaultPageSize;

    @ConfigProperty(name = "mcp.topics.max-list-size", defaultValue = "5000")
    int maxTopicListSize;

    KafkaTopicService() {
    }

    /**
     * List Kafka topics for a specific cluster with pagination.
     *
     * @param namespace    the namespace, or null for all namespaces
     * @param clusterName  the cluster name
     * @param offset       zero-based offset, or null for 0
     * @param limit        maximum topics to return, or null for the configured default
     * @param nameFilter   optional prefix/glob name filter ({@code *}/{@code ?}); null or blank for no filter
     * @param statusFilter optional status filter; null for no filter
     * @return paginated topic list response (filters applied before pagination)
     */
    public PaginatedResponse<KafkaTopicResponse> listTopics(final String namespace, final String clusterName,
                                                             final Integer offset, final Integer limit,
                                                             final String nameFilter,
                                                             final TopicStatusFilter statusFilter) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedClusterName = InputUtils.normalizeInput(clusterName);

        if (normalizedClusterName == null) {
            throw McpErrors.invalidParams("Cluster name is required");
        }

        LOG.infof("Listing Kafka topics for cluster=%s (namespace=%s)",
            normalizedClusterName, ns != null ? ns : "all");

        List<KafkaTopic> topics;
        if (ns != null) {
            topics = k8sService.queryResourcesByLabel(
                KafkaTopic.class, ns, ResourceLabels.STRIMZI_CLUSTER_LABEL, normalizedClusterName);
        } else {
            topics = k8sService.queryResourcesByLabelInAnyNamespace(
                KafkaTopic.class, ResourceLabels.STRIMZI_CLUSTER_LABEL, normalizedClusterName);
        }

        List<KafkaTopicResponse> allResponses = topics.stream()
            .map(this::createTopicResponse)
            .toList();

        // nameFilter is intentionally NOT passed through InputUtils.normalizeInput(): that helper
        // lowercases its input, but the name filter is case-sensitive and matched in-memory against
        // topic names (never sent to the Kubernetes API). Blank input is treated as "no filter" here.
        List<KafkaTopicResponse> filtered = allResponses.stream()
            .filter(t -> nameFilter == null || nameFilter.isBlank()
                || GlobMatcher.matches(nameFilter.trim(), t.name()))
            .filter(t -> statusFilter == null || statusFilter.matches(t))
            .toList();

        return PaginationUtils.paginate(filtered, offset, limit, defaultPageSize, maxTopicListSize);
    }

    /**
     * Get specific Kafka topic details.
     *
     * <p>The topic is resolved by KafkaTopic resource name first and, when that does not match
     * (or the name is not a valid Kubernetes name, as for {@code my_topic} or
     * {@code __consumer_offsets}), by the Kafka topic name in {@code spec.topicName} or
     * {@code status.topicName}.</p>
     *
     * @param namespace   the namespace, or null for auto-discovery
     * @param clusterName the cluster name (used for validation and to narrow the search)
     * @param topicName   the Kafka topic name or KafkaTopic resource name (case-sensitive)
     * @return the topic response
     */
    public KafkaTopicResponse getTopic(final String namespace, final String clusterName, final String topicName) {
        String ns = InputUtils.normalizeInput(namespace);
        String clusterNameNormalized = InputUtils.normalizeInput(clusterName);
        String topicNameNormalized = InputUtils.normalizeName(topicName);

        if (topicNameNormalized == null) {
            throw McpErrors.invalidParams("Topic name is required");
        }
        InputUtils.validateKafkaTopicName(topicNameNormalized, "topic name");
        InputUtils.validateK8sName(ns, "namespace");

        LOG.infof("Getting topic=%s for cluster=%s in namespace=%s", topicNameNormalized, clusterNameNormalized, ns != null ? ns : "auto");

        KafkaTopic topic = findTopic(ns, clusterNameNormalized, topicNameNormalized);

        if (topic == null) {
            throw McpErrors.notFound("Topic", topicNameNormalized, ns);
        }

        if (clusterNameNormalized != null) {
            String topicCluster = topic.getMetadata().getLabels() != null
                ? topic.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL)
                : null;

            if (!clusterNameNormalized.equals(topicCluster)) {
                throw McpErrors.invalidParams("Topic '" + topicNameNormalized + "' belongs to cluster '"
                    + topicCluster + "', not '" + clusterNameNormalized + "'");
            }
        }

        return createTopicResponse(topic);
    }

    private KafkaTopic findTopic(final String ns, final String cluster, final String topicName) {
        List<KafkaTopic> candidates;
        if (ns != null) {
            if (InputUtils.isValidK8sName(topicName)) {
                KafkaTopic direct = k8sService.getResource(KafkaTopic.class, ns, topicName);
                if (direct != null) {
                    return direct;
                }
            }
            if (cluster != null) {
                candidates = k8sService.queryResourcesByLabel(
                    KafkaTopic.class, ns, ResourceLabels.STRIMZI_CLUSTER_LABEL, cluster);
            } else {
                candidates = k8sService.queryResources(KafkaTopic.class, ns);
            }
        } else if (cluster != null) {
            candidates = k8sService.queryResourcesByLabelInAnyNamespace(
                KafkaTopic.class, ResourceLabels.STRIMZI_CLUSTER_LABEL, cluster);
        } else {
            candidates = k8sService.queryResourcesInAnyNamespace(KafkaTopic.class);
        }

        List<KafkaTopic> matching = candidates.stream()
            .filter(t -> exposesTopicName(t, topicName))
            .toList();
        return selectUnique(matching, topicName);
    }

    /**
     * Whether a KafkaTopic resource is named {@code topicName} or manages the Kafka topic of that name.
     */
    private static boolean exposesTopicName(final KafkaTopic topic, final String topicName) {
        return topicName.equals(topic.getMetadata().getName())
            || topic.getSpec() != null && topicName.equals(topic.getSpec().getTopicName())
            || topic.getStatus() != null && topicName.equals(topic.getStatus().getTopicName());
    }

    private static KafkaTopic selectUnique(final List<KafkaTopic> matching, final String topicName) {
        if (matching.isEmpty()) {
            return null;
        }
        if (matching.size() == 1) {
            return matching.getFirst();
        }

        List<String> namespaces = matching.stream()
            .map(t -> t.getMetadata().getNamespace())
            .distinct()
            .toList();
        if (namespaces.size() > 1) {
            throw McpErrors.ambiguous("KafkaTopic", topicName, namespaces);
        }

        List<KafkaTopic> byResourceName = matching.stream()
            .filter(t -> topicName.equals(t.getMetadata().getName()))
            .toList();
        if (byResourceName.size() == 1) {
            return byResourceName.getFirst();
        }

        List<String> clusters = matching.stream()
            .map(t -> t.getMetadata().getLabels() != null
                ? t.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL) : null)
            .distinct()
            .map(c -> c != null ? c : KubernetesConstants.UNKNOWN)
            .toList();
        throw McpErrors.invalidParams("Multiple KafkaTopic resources in namespace '" + namespaces.getFirst()
            + "' manage the topic '" + topicName + "' (clusters: " + String.join(", ", clusters)
            + "). Please specify the cluster name.");
    }

    private KafkaTopicResponse createTopicResponse(final KafkaTopic topic) {
        String name = topic.getMetadata().getName();
        String namespace = topic.getMetadata().getNamespace();
        String cluster = topic.getMetadata().getLabels() != null
            ? topic.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL)
            : KubernetesConstants.UNKNOWN;

        Integer partitions = null;
        Integer replicas = null;
        Map<String, Object> config = null;

        if (topic.getSpec() != null) {
            partitions = topic.getSpec().getPartitions();
            replicas = topic.getSpec().getReplicas();
            Map<String, Object> specConfig = SensitiveKeys.redactValues(topic.getSpec().getConfig());
            config = (specConfig != null && !specConfig.isEmpty()) ? specConfig : null;
        }

        String status = KubernetesConstants.ResourceStatus.UNKNOWN;
        String topicId = null;
        String topicName = null;
        KafkaTopicResponse.ReplicasChangeInfo replicasChange = null;
        List<io.streamshub.mcp.common.dto.ConditionInfo> conditions = null;

        if (topic.getStatus() != null) {
            status = determineResourceStatus(topic.getStatus().getConditions());
            topicId = topic.getStatus().getTopicId();
            topicName = topic.getStatus().getTopicName();
            if (topic.getStatus().getConditions() != null) {
                conditions = topic.getStatus().getConditions().stream()
                    .map(c -> io.streamshub.mcp.common.dto.ConditionInfo.of(
                        c.getType(), c.getStatus(), c.getReason(), c.getMessage(), c.getLastTransitionTime()))
                    .toList();
            }
            if (topic.getStatus().getReplicasChange() != null) {
                ReplicasChangeStatus rc = topic.getStatus().getReplicasChange();
                String state = rc.getState() != null ? rc.getState().toString() : null;
                replicasChange = new KafkaTopicResponse.ReplicasChangeInfo(
                    rc.getTargetReplicas(), state, rc.getSessionId(), rc.getMessage());
            }
        }

        ReconciliationInfo reconciliation = ReconciliationInfo.ofStatus(
            topic.getMetadata().getGeneration(),
            topic.getStatus() != null ? topic.getStatus().getObservedGeneration() : 0L);

        Map<String, String> annotations = topic.getMetadata().getAnnotations();
        Boolean managed = annotations == null
            ? Boolean.TRUE
            : !"false".equalsIgnoreCase(annotations.get(StrimziConstants.Annotations.MANAGED));

        return KafkaTopicResponse.of(name, namespace, cluster, partitions, replicas,
            status, topicId, topicName, replicasChange, conditions, config, reconciliation, managed);
    }

    private String determineResourceStatus(final List<Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return KubernetesConstants.ResourceStatus.UNKNOWN;
        }
        boolean ready = conditions.stream().anyMatch(c ->
            KubernetesConstants.Conditions.TYPE_READY.equals(c.getType())
                && KubernetesConstants.Conditions.STATUS_TRUE.equals(c.getStatus()));
        if (ready) {
            return KubernetesConstants.ResourceStatus.READY;
        }

        boolean hasError = conditions.stream().anyMatch(c ->
            KubernetesConstants.Conditions.TYPE_READY.equals(c.getType())
                && KubernetesConstants.Conditions.STATUS_FALSE.equals(c.getStatus()));
        return hasError ? KubernetesConstants.ResourceStatus.ERROR : KubernetesConstants.ResourceStatus.NOT_READY;
    }
}
