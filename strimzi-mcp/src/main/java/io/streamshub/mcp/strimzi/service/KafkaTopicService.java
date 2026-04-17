/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.dto.KafkaTopicListResponse;
import io.streamshub.mcp.strimzi.dto.KafkaTopicResponse;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

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

    KafkaTopicService() {
    }

    /**
     * List Kafka topics for a specific cluster with pagination.
     *
     * @param namespace   the namespace, or null for all namespaces
     * @param clusterName the cluster name
     * @param offset      zero-based offset, or null for 0
     * @param limit       maximum topics to return, or null for the configured default
     * @return paginated topic list response
     */
    public KafkaTopicListResponse listTopics(final String namespace, final String clusterName,
                                              final Integer offset, final Integer limit) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedClusterName = InputUtils.normalizeInput(clusterName);

        if (normalizedClusterName == null) {
            throw new ToolCallException("Cluster name is required");
        }

        int effectiveOffset = offset != null ? Math.max(0, offset) : 0;
        int effectiveLimit = limit != null ? Math.max(1, limit) : defaultPageSize;

        LOG.infof("Listing Kafka topics for cluster=%s (namespace=%s, offset=%d, limit=%d)",
            normalizedClusterName, ns != null ? ns : "all", effectiveOffset, effectiveLimit);

        List<KafkaTopic> topics;
        if (ns != null) {
            topics = k8sService.queryResourcesByLabel(
                KafkaTopic.class, ns, ResourceLabels.STRIMZI_CLUSTER_LABEL, normalizedClusterName);
        } else {
            topics = k8sService.queryResourcesByLabelInAnyNamespace(
                KafkaTopic.class, ResourceLabels.STRIMZI_CLUSTER_LABEL, normalizedClusterName);
        }

        int total = topics.size();
        int fromIndex = Math.min(effectiveOffset, total);
        int toIndex = Math.min(effectiveOffset + effectiveLimit, total);
        boolean hasMore = toIndex < total;

        List<KafkaTopicResponse> page = topics.subList(fromIndex, toIndex).stream()
            .map(this::createTopicResponse)
            .toList();

        return KafkaTopicListResponse.of(page, total, effectiveOffset, effectiveLimit, hasMore);
    }

    /**
     * Get specific Kafka topic details.
     *
     * @param namespace   the namespace, or null for auto-discovery
     * @param clusterName the cluster name (used for validation)
     * @param topicName   the topic name
     * @return the topic response
     */
    public KafkaTopicResponse getTopic(final String namespace, final String clusterName, final String topicName) {
        String ns = InputUtils.normalizeInput(namespace);

        if (topicName == null) {
            throw new ToolCallException("Topic name is required");
        }

        LOG.infof("Getting topic=%s for cluster=%s in namespace=%s", topicName, clusterName, ns != null ? ns : "auto");

        KafkaTopic topic;

        if (ns != null) {
            topic = k8sService.getResource(KafkaTopic.class, ns, topicName);
        } else {
            topic = findTopicInAllNamespaces(topicName, clusterName);
        }

        if (topic == null) {
            throw new ToolCallException("Topic '" + topicName + "' not found");
        }

        if (clusterName != null) {
            String topicCluster = topic.getMetadata().getLabels() != null
                ? topic.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL)
                : null;

            if (!clusterName.equals(topicCluster)) {
                throw new ToolCallException("Topic '" + topicName + "' belongs to cluster '"
                    + topicCluster + "', not '" + clusterName + "'");
            }
        }

        return createTopicResponse(topic);
    }

    private KafkaTopic findTopicInAllNamespaces(final String topicName, final String clusterName) {
        List<KafkaTopic> allTopics = k8sService.queryResourcesInAnyNamespace(KafkaTopic.class);

        for (KafkaTopic topic : allTopics) {
            if (topicName.equals(topic.getMetadata().getName())) {
                if (clusterName != null) {
                    String topicCluster = topic.getMetadata().getLabels() != null
                        ? topic.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL)
                        : null;

                    if (!clusterName.equals(topicCluster)) {
                        continue;
                    }
                }

                LOG.debugf("Discovered topic %s in namespace %s",
                    topicName, topic.getMetadata().getNamespace());
                return topic;
            }
        }

        return null;
    }

    private KafkaTopicResponse createTopicResponse(final KafkaTopic topic) {
        String topicName = topic.getMetadata().getName();
        String cluster = topic.getMetadata().getLabels() != null
            ? topic.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL)
            : KubernetesConstants.UNKNOWN;

        Integer partitions = null;
        Integer replicas = null;

        if (topic.getSpec() != null) {
            partitions = topic.getSpec().getPartitions();
            replicas = topic.getSpec().getReplicas();
        }

        String status = topic.getStatus() != null
            ? determineResourceStatus(topic.getStatus().getConditions())
            : KubernetesConstants.ResourceStatus.UNKNOWN;

        return KafkaTopicResponse.of(topicName, cluster, partitions, replicas, status);
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
