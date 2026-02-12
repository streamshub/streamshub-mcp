/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.service.infra;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.mcp.dto.KafkaTopicsResult;
import io.strimzi.mcp.dto.TopicInfo;
import io.strimzi.mcp.util.InputUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for Kafka topic operations.
 * Handles topic discovery, configuration retrieval, and topic management.
 */
@ApplicationScoped
public class KafkaTopicService {

    KafkaTopicService() {
    }

    private static final Logger LOG = Logger.getLogger(KafkaTopicService.class);

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    StrimziDiscoveryService discoveryService;

    /**
     * Get Kafka topics for a specific cluster and namespace.
     *
     * @param namespace the namespace to search in, or null for auto-discovery
     * @param clusterName the cluster name to filter topics by, or null for all clusters
     * @return structured result containing topic information
     */
    public KafkaTopicsResult getKafkaTopics(String namespace, String clusterName) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);
        String normalizedClusterName = InputUtils.normalizeClusterName(clusterName);

        // Auto-discover namespace if not specified
        if (normalizedNamespace == null) {
            List<String> discoveredNamespaces = discoveryService.discoverStrimziNamespaces();
            if (discoveredNamespaces.size() == 1) {
                normalizedNamespace = discoveredNamespaces.get(0);
                LOG.infof("Auto-discovered Strimzi in namespace: %s", normalizedNamespace);
            } else if (!discoveredNamespaces.isEmpty()) {
                String namespaceList = String.join(", ", discoveredNamespaces);
                return KafkaTopicsResult.error("multiple-found", normalizedClusterName,
                    String.format("Found Strimzi in multiple namespaces: %s. " +
                        "Please specify: 'Show topics for %s in the %s namespace'",
                        namespaceList, normalizedClusterName != null ? normalizedClusterName : "my-cluster",
                        discoveredNamespaces.get(0)));
            } else {
                return KafkaTopicsResult.error("not-found", normalizedClusterName,
                    "No Strimzi installation found. Please ensure Kafka is deployed.");
            }
        }

        LOG.infof("KafkaTopicService: getKafkaTopics (namespace=%s, cluster=%s)",
                 normalizedNamespace, normalizedClusterName);

        try {
            List<TopicInfo> topics = new ArrayList<>();

            List<KafkaTopic> topicResources;
            if (normalizedClusterName != null) {
                topicResources = kubernetesClient.resources(KafkaTopic.class)
                    .inNamespace(normalizedNamespace)
                    .withLabel("strimzi.io/cluster", normalizedClusterName)
                    .list()
                    .getItems();
            } else {
                topicResources = kubernetesClient.resources(KafkaTopic.class)
                    .inNamespace(normalizedNamespace)
                    .list()
                    .getItems();
            }

            for (KafkaTopic topic : topicResources) {
                TopicInfo topicInfo = buildTopicInfo(topic);
                topics.add(topicInfo);
            }

            if (topics.isEmpty()) {
                return KafkaTopicsResult.empty(normalizedNamespace, normalizedClusterName);
            }

            return KafkaTopicsResult.of(normalizedNamespace, normalizedClusterName, topics);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving Kafka topics from namespace: %s", normalizedNamespace);
            return KafkaTopicsResult.error(normalizedNamespace, normalizedClusterName, e.getMessage());
        }
    }

    /**
     * Get topics for all clusters in a namespace.
     *
     * @param namespace the namespace to search in
     * @return structured result containing topic information for all clusters
     */
    public KafkaTopicsResult getAllTopicsInNamespace(String namespace) {
        return getKafkaTopics(namespace, null);
    }

    /**
     * Get topic configuration details for a specific topic.
     *
     * @param namespace the namespace to search in, or null for auto-discovery
     * @param clusterName the cluster name (unused in current implementation)
     * @param topicName the name of the topic to retrieve details for
     * @return topic details or null if not found
     */
    public TopicInfo getTopicDetails(String namespace, String clusterName, String topicName) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);

        if (normalizedNamespace == null) {
            List<String> discoveredNamespaces = discoveryService.discoverStrimziNamespaces();
            if (discoveredNamespaces.size() == 1) {
                normalizedNamespace = discoveredNamespaces.get(0);
            } else {
                return null; // Cannot determine namespace
            }
        }

        try {
            KafkaTopic topic = kubernetesClient.resources(KafkaTopic.class)
                .inNamespace(normalizedNamespace)
                .withName(topicName)
                .get();

            if (topic == null) {
                return null;
            }

            return buildTopicInfo(topic);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving topic details for %s", topicName);
            return null;
        }
    }

    // Private helper methods

    private TopicInfo buildTopicInfo(KafkaTopic topic) {
        String topicName = topic.getMetadata().getName();
        String cluster = topic.getMetadata().getLabels() != null ?
            topic.getMetadata().getLabels().get("strimzi.io/cluster") : "unknown";

        Integer partitions = null;
        Integer replicas = null;

        if (topic.getSpec() != null) {
            partitions = topic.getSpec().getPartitions();
            replicas = topic.getSpec().getReplicas();
        }

        String status = determineTopicStatus(topic);

        return new TopicInfo(topicName, cluster, partitions, replicas, status);
    }

    private String determineTopicStatus(KafkaTopic topic) {
        if (topic.getStatus() != null && topic.getStatus().getConditions() != null) {
            boolean ready = topic.getStatus().getConditions().stream()
                .anyMatch(condition -> "Ready".equals(condition.getType()) &&
                                     "True".equals(condition.getStatus()));

            if (ready) {
                return "Ready";
            }

            // Check for specific error conditions
            boolean hasError = topic.getStatus().getConditions().stream()
                .anyMatch(condition -> "Ready".equals(condition.getType()) &&
                                     "False".equals(condition.getStatus()));

            if (hasError) {
                return "Error";
            }

            return "Not Ready";
        }

        return "Unknown";
    }
}
