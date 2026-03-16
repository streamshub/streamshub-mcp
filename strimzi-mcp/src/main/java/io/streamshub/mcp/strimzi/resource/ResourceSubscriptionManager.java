/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.quarkiverse.mcp.server.ResourceManager;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.KafkaNodePoolResponse;
import io.streamshub.mcp.strimzi.dto.KafkaTopicResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorResponse;
import io.streamshub.mcp.strimzi.service.KafkaNodePoolService;
import io.streamshub.mcp.strimzi.service.KafkaService;
import io.streamshub.mcp.strimzi.service.KafkaTopicService;
import io.streamshub.mcp.strimzi.service.StrimziOperatorService;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages Kubernetes watches on Strimzi resources and sends MCP resource
 * update notifications to subscribed clients when resource status changes.
 *
 * <p>Watches Kafka CRs, KafkaNodePool CRs, KafkaTopic CRs, and Strimzi operator Deployments.
 * On each change, dynamically registers or updates the corresponding MCP resource
 * and notifies clients via {@link ResourceManager.ResourceInfo#sendUpdateAndForget()}.</p>
 */
@ApplicationScoped
public class ResourceSubscriptionManager implements Closeable {

    private static final Logger LOG = Logger.getLogger(ResourceSubscriptionManager.class);

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    ResourceManager resourceManager;

    @Inject
    KafkaService kafkaService;

    @Inject
    KafkaNodePoolService nodePoolService;

    @Inject
    KafkaTopicService topicService;

    @Inject
    StrimziOperatorService operatorService;

    @Inject
    ObjectMapper objectMapper;

    private final List<Watch> activeWatches = new CopyOnWriteArrayList<>();
    private final Map<String, String> lastKnownState = new ConcurrentHashMap<>();

    ResourceSubscriptionManager() {
    }

    /**
     * Start Kubernetes watches on application startup.
     *
     * @param event the startup event
     */
    void onStart(@Observes final StartupEvent event) {
        LOG.info("Starting Strimzi resource watches for MCP subscriptions");
        startKafkaWatch();
        startKafkaNodePoolWatch();
        startKafkaTopicWatch();
        startOperatorWatch();
    }

    /**
     * Stop all Kubernetes watches on application shutdown.
     *
     * @param event the shutdown event
     */
    void onStop(@Observes final ShutdownEvent event) {
        close();
    }

    @Override
    public void close() {
        LOG.info("Closing Strimzi resource watches");
        for (Watch watch : activeWatches) {
            watch.close();
        }
        activeWatches.clear();
        lastKnownState.clear();
    }

    private void startKafkaWatch() {
        try {
            Watch watch = kubernetesClient.resources(Kafka.class)
                .inAnyNamespace()
                .watch(new Watcher<Kafka>() {
                    @Override
                    public void eventReceived(final Action action, final Kafka kafka) {
                        handleKafkaEvent(action, kafka);
                    }

                    @Override
                    public void onClose(final WatcherException cause) {
                        if (cause != null) {
                            LOG.warnf("Kafka watch closed unexpectedly: %s", cause.getMessage());
                        }
                    }
                });
            activeWatches.add(watch);
            LOG.info("Started watch on Kafka resources");
        } catch (Exception e) {
            LOG.warnf("Could not start Kafka watch: %s", e.getMessage());
        }
    }

    private void startKafkaNodePoolWatch() {
        try {
            Watch watch = kubernetesClient.resources(KafkaNodePool.class)
                .inAnyNamespace()
                .watch(new Watcher<KafkaNodePool>() {
                    @Override
                    public void eventReceived(final Action action, final KafkaNodePool nodePool) {
                        handleNodePoolEvent(action, nodePool);
                    }

                    @Override
                    public void onClose(final WatcherException cause) {
                        if (cause != null) {
                            LOG.warnf("KafkaNodePool watch closed unexpectedly: %s", cause.getMessage());
                        }
                    }
                });
            activeWatches.add(watch);
            LOG.info("Started watch on KafkaNodePool resources");
        } catch (Exception e) {
            LOG.warnf("Could not start KafkaNodePool watch: %s", e.getMessage());
        }
    }

    private void startKafkaTopicWatch() {
        try {
            Watch watch = kubernetesClient.resources(KafkaTopic.class)
                .inAnyNamespace()
                .watch(new Watcher<KafkaTopic>() {
                    @Override
                    public void eventReceived(final Action action, final KafkaTopic topic) {
                        handleTopicEvent(action, topic);
                    }

                    @Override
                    public void onClose(final WatcherException cause) {
                        if (cause != null) {
                            LOG.warnf("KafkaTopic watch closed unexpectedly: %s", cause.getMessage());
                        }
                    }
                });
            activeWatches.add(watch);
            LOG.info("Started watch on KafkaTopic resources");
        } catch (Exception e) {
            LOG.warnf("Could not start KafkaTopic watch: %s", e.getMessage());
        }
    }

    private void startOperatorWatch() {
        try {
            Watch watch = kubernetesClient.apps().deployments()
                .inAnyNamespace()
                .withLabel(KubernetesConstants.Labels.APP, StrimziConstants.Operator.APP_LABEL_VALUE)
                .watch(new Watcher<Deployment>() {
                    @Override
                    public void eventReceived(final Action action, final Deployment deployment) {
                        handleOperatorEvent(action, deployment);
                    }

                    @Override
                    public void onClose(final WatcherException cause) {
                        if (cause != null) {
                            LOG.warnf("Operator watch closed unexpectedly: %s", cause.getMessage());
                        }
                    }
                });
            activeWatches.add(watch);
            LOG.info("Started watch on Strimzi operator Deployments");
        } catch (Exception e) {
            LOG.warnf("Could not start operator watch: %s", e.getMessage());
        }
    }

    private void handleKafkaEvent(final Watcher.Action action, final Kafka kafka) {
        String name = kafka.getMetadata().getName();
        String namespace = kafka.getMetadata().getNamespace();
        String statusUri = StrimziConstants.ResourceUris.kafkaStatus(namespace, name);
        String topologyUri = StrimziConstants.ResourceUris.kafkaTopology(namespace, name);

        LOG.debugf("Kafka %s event: %s/%s", action, namespace, name);

        if (action == Watcher.Action.DELETED) {
            resourceManager.removeResource(statusUri);
            resourceManager.removeResource(topologyUri);
            lastKnownState.remove(statusUri);
            lastKnownState.remove(topologyUri);
            return;
        }

        notifyClusterStatus(statusUri, namespace, name);
        notifyClusterTopology(topologyUri, namespace, name);
    }

    private void handleNodePoolEvent(final Watcher.Action action, final KafkaNodePool nodePool) {
        String name = nodePool.getMetadata().getName();
        String namespace = nodePool.getMetadata().getNamespace();
        String clusterName = nodePool.getMetadata().getLabels() != null
            ? nodePool.getMetadata().getLabels().get(ResourceLabels.STRIMZI_CLUSTER_LABEL)
            : null;

        LOG.debugf("KafkaNodePool %s event: %s/%s (cluster=%s)",
            action, namespace, name, clusterName);

        String nodePoolUri = StrimziConstants.ResourceUris.nodePoolStatus(namespace, name);

        if (action == Watcher.Action.DELETED) {
            resourceManager.removeResource(nodePoolUri);
            lastKnownState.remove(nodePoolUri);
        } else {
            notifyNodePoolStatus(nodePoolUri, namespace, name);
        }

        if (clusterName != null) {
            String topologyUri = StrimziConstants.ResourceUris.kafkaTopology(namespace, clusterName);
            notifyClusterTopology(topologyUri, namespace, clusterName);
        }
    }

    private void handleTopicEvent(final Watcher.Action action, final KafkaTopic topic) {
        String name = topic.getMetadata().getName();
        String namespace = topic.getMetadata().getNamespace();
        String topicUri = StrimziConstants.ResourceUris.topicStatus(namespace, name);

        LOG.debugf("KafkaTopic %s event: %s/%s", action, namespace, name);

        if (action == Watcher.Action.DELETED) {
            resourceManager.removeResource(topicUri);
            lastKnownState.remove(topicUri);
            return;
        }

        notifyTopicStatus(topicUri, namespace, name);
    }

    private void handleOperatorEvent(final Watcher.Action action, final Deployment deployment) {
        String name = deployment.getMetadata().getName();
        String namespace = deployment.getMetadata().getNamespace();
        String operatorUri = StrimziConstants.ResourceUris.operatorStatus(namespace, name);

        LOG.debugf("Operator %s event: %s/%s", action, namespace, name);

        if (action == Watcher.Action.DELETED) {
            resourceManager.removeResource(operatorUri);
            lastKnownState.remove(operatorUri);
            return;
        }

        notifyOperatorStatus(operatorUri, namespace, name);
    }

    private void notifyClusterStatus(final String uri, final String namespace, final String name) {
        try {
            KafkaClusterResponse cluster = kafkaService.getCluster(namespace, name);
            String json = objectMapper.writeValueAsString(cluster);
            registerAndNotify(uri, "Kafka cluster " + namespace + "/" + name + " status", json);
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to serialize cluster status for %s/%s: %s", namespace, name, e.getMessage());
        } catch (Exception e) {
            LOG.debugf("Could not update cluster status resource %s: %s", uri, e.getMessage());
        }
    }

    private void notifyClusterTopology(final String uri, final String namespace, final String clusterName) {
        try {
            List<KafkaNodePoolResponse> nodePools = nodePoolService.listNodePools(namespace, clusterName);
            String json = objectMapper.writeValueAsString(nodePools);
            registerAndNotify(uri, "Kafka cluster " + namespace + "/" + clusterName + " topology", json);
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to serialize topology for %s/%s: %s", namespace, clusterName, e.getMessage());
        } catch (Exception e) {
            LOG.debugf("Could not update topology resource %s: %s", uri, e.getMessage());
        }
    }

    private void notifyNodePoolStatus(final String uri, final String namespace, final String name) {
        try {
            KafkaNodePoolResponse nodePool = nodePoolService.getNodePool(namespace, null, name);
            String json = objectMapper.writeValueAsString(nodePool);
            registerAndNotify(uri, "KafkaNodePool " + namespace + "/" + name + " status", json);
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to serialize node pool status for %s/%s: %s", namespace, name, e.getMessage());
        } catch (Exception e) {
            LOG.debugf("Could not update node pool status resource %s: %s", uri, e.getMessage());
        }
    }

    private void notifyTopicStatus(final String uri, final String namespace, final String name) {
        try {
            KafkaTopicResponse topic = topicService.getTopic(namespace, null, name);
            String json = objectMapper.writeValueAsString(topic);
            registerAndNotify(uri, "KafkaTopic " + namespace + "/" + name + " status", json);
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to serialize topic status for %s/%s: %s", namespace, name, e.getMessage());
        } catch (Exception e) {
            LOG.debugf("Could not update topic status resource %s: %s", uri, e.getMessage());
        }
    }

    private void notifyOperatorStatus(final String uri, final String namespace, final String name) {
        try {
            StrimziOperatorResponse operator = operatorService.getOperator(namespace, name);
            String json = objectMapper.writeValueAsString(operator);
            registerAndNotify(uri, "Strimzi operator " + namespace + "/" + name + " status", json);
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to serialize operator status for %s: %s", namespace, e.getMessage());
        } catch (Exception e) {
            LOG.debugf("Could not update operator status resource %s: %s", uri, e.getMessage());
        }
    }

    private void registerAndNotify(
        final String uri,
        final String description,
        final String json
    ) {
        String previous = lastKnownState.put(uri, json);

        if (json.equals(previous)) {
            LOG.debugf(
                "No change detected for resource: %s", uri);
            return;
        }

        ResourceManager.ResourceInfo existing =
            resourceManager.getResource(uri);

        if (existing != null) {
            existing.sendUpdateAndForget();
            LOG.debugf("Resource changed, sent update: %s", uri);
        } else {
            resourceManager.newResource(uri)
                .setDescription(description)
                .setUri(uri)
                .setMimeType("application/json")
                .setHandler(args ->
                    new ResourceResponse(
                        TextResourceContents.create(uri,
                            lastKnownState
                                .getOrDefault(uri, ""))))
                .register();
            LOG.debugf(
                "Registered new dynamic resource: %s", uri);
        }
    }
}
