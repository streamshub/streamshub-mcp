/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.service.infra;

import io.streamshub.mcp.config.Constants;
import io.streamshub.mcp.dto.KafkaTopicResponse;
import io.streamshub.mcp.dto.ToolError;
import io.streamshub.mcp.service.common.KubernetesResourceService;
import io.streamshub.mcp.util.InputUtils;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Service for Kafka topic operations.
 * Handles topic discovery, configuration retrieval, and topic management.
 */
@ApplicationScoped
public class KafkaTopicService {

    private static final Logger LOG = Logger.getLogger(KafkaTopicService.class);

    @Inject
    KubernetesResourceService resourceService;

    KafkaTopicService() {
    }

    /**
     * Get Kafka topics for a specific cluster and namespace.
     *
     * @param namespace   the namespace to search in, or null for all namespaces
     * @param clusterName the cluster name to filter topics by, or null for all clusters
     * @return list of topic information or ToolError for errors
     */
    public Object getKafkaTopics(String namespace, String clusterName) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);
        String normalizedClusterName = InputUtils.normalizeClusterName(clusterName);

        LOG.infof("KafkaTopicService: getKafkaTopics (namespace=%s, cluster=%s)", normalizedNamespace, normalizedClusterName);

        try {
            List<KafkaTopic> topicResources;
            if (normalizedClusterName != null) {
                if (normalizedNamespace != null) {
                    topicResources = resourceService.queryResourcesByLabel(
                        KafkaTopic.class, normalizedNamespace,
                        ResourceLabels.STRIMZI_CLUSTER_LABEL, normalizedClusterName);
                } else {
                    topicResources = resourceService.queryResourcesByLabelInAnyNamespace(
                        KafkaTopic.class, ResourceLabels.STRIMZI_CLUSTER_LABEL, normalizedClusterName);
                }
            } else {
                topicResources = resourceService.queryResources(KafkaTopic.class, normalizedNamespace);
            }

            return topicResources.stream()
                .map(this::createKafkaTopicResponse)
                .toList();

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving Kafka topics from namespace: %s", normalizedNamespace);
            return ToolError.of("Failed to retrieve Kafka topics from namespace " + normalizedNamespace, e);
        }
    }


    /**
     * Get topic configuration details for a specific topic.
     *
     * @param namespace   the namespace to search in, or null for auto-discovery across all namespaces
     * @param clusterName the cluster name to validate topic belongs to (optional)
     * @param topicName   the name of the topic to retrieve details for
     * @return topic details or null if not found
     */
    public KafkaTopicResponse getTopicDetails(String namespace, String clusterName, String topicName) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);

        if (normalizedNamespace == null) {
            return findTopicInAllNamespaces(topicName, clusterName);
        }

        try {
            KafkaTopic topic = resourceService.getResource(KafkaTopic.class, normalizedNamespace, topicName);

            if (topic == null) {
                return null;
            }

            if (clusterName != null) {
                String topicCluster = topic.getMetadata().getLabels() != null ?
                    topic.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL) : null;

                if (!clusterName.equals(topicCluster)) {
                    LOG.debugf("Topic %s found but belongs to cluster %s, not %s", topicName, topicCluster, clusterName);
                    return null;
                }
            }

            return createKafkaTopicResponse(topic);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving topic details for %s in namespace %s", topicName, normalizedNamespace);
            return null;
        }
    }

    /**
     * Find a topic by name across all namespaces when namespace is not specified.
     */
    private KafkaTopicResponse findTopicInAllNamespaces(String topicName, String clusterName) {
        try {
            List<KafkaTopic> allTopics = resourceService.queryResources(KafkaTopic.class, null);

            for (KafkaTopic topic : allTopics) {
                if (topicName.equals(topic.getMetadata().getName())) {
                    if (clusterName != null) {
                        String topicCluster = topic.getMetadata().getLabels() != null ?
                            topic.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL) : null;

                        if (!clusterName.equals(topicCluster)) {
                            continue; // Topic found but wrong cluster, keep searching
                        }
                    }

                    LOG.infof("Found topic %s in namespace %s", topicName, topic.getMetadata().getNamespace());
                    return createKafkaTopicResponse(topic);
                }
            }

            LOG.debugf("Topic %s not found in any namespace", topicName);
            return null;

        } catch (Exception e) {
            LOG.errorf(e, "Error searching for topic %s across all namespaces", topicName);
            return null;
        }
    }

    private KafkaTopicResponse createKafkaTopicResponse(KafkaTopic topic) {
        String topicName = topic.getMetadata().getName();
        String cluster = topic.getMetadata().getLabels() != null ?
            topic.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL) : Constants.Kubernetes.StatusValues.UNKNOWN_CLUSTER;

        Integer partitions = null;
        Integer replicas = null;

        if (topic.getSpec() != null) {
            partitions = topic.getSpec().getPartitions();
            replicas = topic.getSpec().getReplicas();
        }

        String status = determineKafkaTopicStatus(topic);

        return new KafkaTopicResponse(topicName, cluster, partitions, replicas, status);
    }

    private String determineKafkaTopicStatus(KafkaTopic topic) {
        if (topic.getStatus() != null && topic.getStatus().getConditions() != null) {
            boolean ready = topic.getStatus().getConditions().stream()
                .anyMatch(condition -> Constants.Kubernetes.ConditionTypes.READY.equals(condition.getType()) &&
                    Constants.Kubernetes.ConditionStatuses.TRUE.equals(condition.getStatus()));

            if (ready) {
                return Constants.Strimzi.StatusValues.READY;
            }

            boolean hasError = topic.getStatus().getConditions().stream()
                .anyMatch(condition -> Constants.Kubernetes.ConditionTypes.READY.equals(condition.getType()) &&
                    Constants.Kubernetes.ConditionStatuses.FALSE.equals(condition.getStatus()));

            if (hasError) {
                return Constants.Strimzi.StatusValues.ERROR;
            }

            return Constants.Strimzi.StatusValues.NOT_READY;
        }

        return Constants.Strimzi.StatusValues.UNKNOWN;
    }
}
