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
import io.quarkus.scheduler.Scheduled;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.KafkaNodePoolResponse;
import io.streamshub.mcp.strimzi.dto.KafkaTopicResponse;
import io.streamshub.mcp.strimzi.dto.KafkaUserResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorResponse;
import io.streamshub.mcp.strimzi.service.KafkaNodePoolService;
import io.streamshub.mcp.strimzi.service.KafkaService;
import io.streamshub.mcp.strimzi.service.KafkaTopicService;
import io.streamshub.mcp.strimzi.service.KafkaUserService;
import io.streamshub.mcp.strimzi.service.StrimziOperatorService;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.user.KafkaUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Manages Kubernetes watches on Strimzi resources and sends MCP resource
 * update notifications to subscribed clients when resource status changes.
 *
 * <p>Watches Kafka CRs, KafkaNodePool CRs, KafkaTopic CRs, KafkaUser CRs, and Strimzi operator Deployments.
 * On each change, dynamically registers or updates the corresponding MCP resource
 * and notifies clients via {@link ResourceManager.ResourceInfo#sendUpdateAndForget()}.</p>
 */
@ApplicationScoped
public class ResourceSubscriptionManager implements Closeable {

    private static final Logger LOG = Logger.getLogger(ResourceSubscriptionManager.class);
    private static final String STRIMZI_URI_PREFIX = "strimzi://";
    private static final int URI_PART_NAMESPACE = 2;
    private static final int URI_PART_KIND = 3;
    private static final int URI_PART_NAME = 4;
    private static final int URI_MIN_PARTS = 5;

    static final long RECONNECT_INITIAL_DELAY_MS = 1000;
    static final long RECONNECT_MAX_DELAY_MS = 60_000;
    static final int RECONNECT_MAX_ATTEMPTS = 10;

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
    KafkaUserService userService;

    @Inject
    StrimziOperatorService operatorService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "mcp.resource-watches.enabled", defaultValue = "true")
    boolean watchesEnabled;

    private final List<Watch> activeWatches = new CopyOnWriteArrayList<>();
    private final Map<String, String> lastKnownState = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown;

    List<Watch> getActiveWatches() {
        return activeWatches;
    }

    Map<String, String> getLastKnownState() {
        return lastKnownState;
    }

    boolean isShuttingDown() {
        return shuttingDown;
    }

    void setShuttingDown(final boolean shuttingDown) {
        this.shuttingDown = shuttingDown;
    }

    void setWatchesEnabled(final boolean watchesEnabled) {
        this.watchesEnabled = watchesEnabled;
    }

    private final ScheduledExecutorService reconnectExecutor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watch-reconnect");
            t.setDaemon(true);
            return t;
        });

    ResourceSubscriptionManager() {
    }

    /**
     * Start Kubernetes watches on application startup.
     *
     * @param event the startup event
     */
    void onStart(@Observes final StartupEvent event) {
        if (!watchesEnabled) {
            LOG.info("Resource watches disabled (mcp.resource-watches.enabled=false)");
            return;
        }
        LOG.info("Starting Strimzi resource watches for MCP subscriptions");
        startKafkaWatch();
        startKafkaNodePoolWatch();
        startKafkaTopicWatch();
        startKafkaUserWatch();
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
        shuttingDown = true;
        reconnectExecutor.shutdownNow();
        for (Watch watch : activeWatches) {
            try {
                watch.close();
            } catch (Exception e) {
                LOG.warnf("Error closing watch: %s", e.getMessage());
            }
        }
        activeWatches.clear();
        lastKnownState.clear();
    }

    // ---- Watch starters ----

    private boolean startKafkaWatch() {
        try {
            final Watch[] holder = new Watch[1];
            holder[0] = kubernetesClient.resources(Kafka.class)
                .inAnyNamespace()
                .watch(new Watcher<>() {
                    @Override
                    public void eventReceived(final Action action, final Kafka kafka) {
                        handleKafkaEvent(action, kafka);
                    }

                    @Override
                    public void onClose(final WatcherException cause) {
                        if (cause != null) {
                            LOG.warnf("Kafka watch closed unexpectedly: %s", cause.getMessage());
                            activeWatches.remove(holder[0]);
                            scheduleReconnect("Kafka",
                                () -> startKafkaWatch(), 1);
                        }
                    }
                });
            activeWatches.add(holder[0]);
            LOG.info("Started watch on Kafka resources");
            return true;
        } catch (Exception e) {
            LOG.warnf("Could not start Kafka watch: %s", e.getMessage());
            return false;
        }
    }

    private boolean startKafkaNodePoolWatch() {
        try {
            final Watch[] holder = new Watch[1];
            holder[0] = kubernetesClient.resources(KafkaNodePool.class)
                .inAnyNamespace()
                .watch(new Watcher<>() {
                    @Override
                    public void eventReceived(final Action action, final KafkaNodePool nodePool) {
                        handleNodePoolEvent(action, nodePool);
                    }

                    @Override
                    public void onClose(final WatcherException cause) {
                        if (cause != null) {
                            LOG.warnf("KafkaNodePool watch closed unexpectedly: %s", cause.getMessage());
                            activeWatches.remove(holder[0]);
                            scheduleReconnect("KafkaNodePool",
                                () -> startKafkaNodePoolWatch(), 1);
                        }
                    }
                });
            activeWatches.add(holder[0]);
            LOG.info("Started watch on KafkaNodePool resources");
            return true;
        } catch (Exception e) {
            LOG.warnf("Could not start KafkaNodePool watch: %s", e.getMessage());
            return false;
        }
    }

    private boolean startKafkaTopicWatch() {
        try {
            final Watch[] holder = new Watch[1];
            holder[0] = kubernetesClient.resources(KafkaTopic.class)
                .inAnyNamespace()
                .watch(new Watcher<>() {
                    @Override
                    public void eventReceived(final Action action, final KafkaTopic topic) {
                        handleTopicEvent(action, topic);
                    }

                    @Override
                    public void onClose(final WatcherException cause) {
                        if (cause != null) {
                            LOG.warnf("KafkaTopic watch closed unexpectedly: %s", cause.getMessage());
                            activeWatches.remove(holder[0]);
                            scheduleReconnect("KafkaTopic",
                                () -> startKafkaTopicWatch(), 1);
                        }
                    }
                });
            activeWatches.add(holder[0]);
            LOG.info("Started watch on KafkaTopic resources");
            return true;
        } catch (Exception e) {
            LOG.warnf("Could not start KafkaTopic watch: %s", e.getMessage());
            return false;
        }
    }

    private boolean startKafkaUserWatch() {
        try {
            final Watch[] holder = new Watch[1];
            holder[0] = kubernetesClient.resources(KafkaUser.class)
                .inAnyNamespace()
                .watch(new Watcher<>() {
                    @Override
                    public void eventReceived(final Action action, final KafkaUser user) {
                        handleUserEvent(action, user);
                    }

                    @Override
                    public void onClose(final WatcherException cause) {
                        if (cause != null) {
                            LOG.warnf("KafkaUser watch closed unexpectedly: %s", cause.getMessage());
                            activeWatches.remove(holder[0]);
                            scheduleReconnect("KafkaUser",
                                () -> startKafkaUserWatch(), 1);
                        }
                    }
                });
            activeWatches.add(holder[0]);
            LOG.info("Started watch on KafkaUser resources");
            return true;
        } catch (Exception e) {
            LOG.warnf("Could not start KafkaUser watch: %s", e.getMessage());
            return false;
        }
    }

    private boolean startOperatorWatch() {
        try {
            final Watch[] holder = new Watch[1];
            holder[0] = kubernetesClient.apps().deployments()
                .inAnyNamespace()
                .withLabel(KubernetesConstants.Labels.APP, StrimziConstants.Operator.APP_LABEL_VALUE)
                .watch(new Watcher<>() {
                    @Override
                    public void eventReceived(final Action action, final Deployment deployment) {
                        handleOperatorEvent(action, deployment);
                    }

                    @Override
                    public void onClose(final WatcherException cause) {
                        if (cause != null) {
                            LOG.warnf("Operator watch closed unexpectedly: %s", cause.getMessage());
                            activeWatches.remove(holder[0]);
                            scheduleReconnect("Operator",
                                () -> startOperatorWatch(), 1);
                        }
                    }
                });
            activeWatches.add(holder[0]);
            LOG.info("Started watch on Strimzi operator Deployments");
            return true;
        } catch (Exception e) {
            LOG.warnf("Could not start operator watch: %s", e.getMessage());
            return false;
        }
    }

    // ---- Reconnection ----

    void scheduleReconnect(final String watchName, final Supplier<Boolean> starter, final int attempt) {
        if (shuttingDown) {
            return;
        }
        if (attempt > RECONNECT_MAX_ATTEMPTS) {
            LOG.errorf("%s watch reconnection failed after %d attempts",
                watchName, RECONNECT_MAX_ATTEMPTS);
            return;
        }
        long delay = Math.min(
            RECONNECT_INITIAL_DELAY_MS << (attempt - 1),
            RECONNECT_MAX_DELAY_MS);
        LOG.infof("Scheduling %s watch reconnect (attempt %d/%d, delay %dms)",
            watchName, attempt, RECONNECT_MAX_ATTEMPTS, delay);
        reconnectExecutor.schedule(() -> {
            if (shuttingDown) {
                return;
            }
            if (starter.get()) {
                LOG.infof("%s watch reconnected successfully", watchName);
            } else {
                scheduleReconnect(watchName, starter, attempt + 1);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    // ---- Reconciliation ----

    @Scheduled(every = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reconcileState() {
        if (!watchesEnabled || shuttingDown) {
            return;
        }
        LOG.debug("Running periodic lastKnownState reconciliation");
        int removed = 0;
        for (String uri : lastKnownState.keySet()) {
            if (shuttingDown) {
                return;
            }
            try {
                if (!resourceExistsInCluster(uri)) {
                    lastKnownState.remove(uri);
                    resourceManager.removeResource(uri);
                    removed++;
                    LOG.infof("Reconciliation: removed orphaned entry %s", uri);
                }
            } catch (Exception e) {
                LOG.debugf("Reconciliation: could not verify %s: %s", uri, e.getMessage());
            }
        }
        if (removed > 0) {
            LOG.infof("Reconciliation complete: removed %d orphaned entries", removed);
        }
    }

    boolean resourceExistsInCluster(final String uri) {
        if (!uri.startsWith(STRIMZI_URI_PREFIX)) {
            return true;
        }
        String path = uri.substring(STRIMZI_URI_PREFIX.length());
        String[] parts = path.split("/");
        if (parts.length < URI_MIN_PARTS) {
            return true;
        }
        String namespace = parts[URI_PART_NAMESPACE];
        String kind = parts[URI_PART_KIND];
        String name = parts[URI_PART_NAME];

        return switch (kind) {
            case "kafkas" ->
                kubernetesClient.resources(Kafka.class)
                    .inNamespace(namespace).withName(name).get() != null;
            case "kafkanodepools" ->
                kubernetesClient.resources(KafkaNodePool.class)
                    .inNamespace(namespace).withName(name).get() != null;
            case "kafkatopics" ->
                kubernetesClient.resources(KafkaTopic.class)
                    .inNamespace(namespace).withName(name).get() != null;
            case "kafkausers" ->
                kubernetesClient.resources(KafkaUser.class)
                    .inNamespace(namespace).withName(name).get() != null;
            case "clusteroperator" ->
                kubernetesClient.apps().deployments()
                    .inNamespace(namespace).withName(name).get() != null;
            default -> true;
        };
    }

    // ---- Event handlers ----

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

    private void handleUserEvent(final Watcher.Action action, final KafkaUser user) {
        String name = user.getMetadata().getName();
        String namespace = user.getMetadata().getNamespace();
        String userUri = StrimziConstants.ResourceUris.userStatus(namespace, name);

        LOG.debugf("KafkaUser %s event: %s/%s", action, namespace, name);

        if (action == Watcher.Action.DELETED) {
            resourceManager.removeResource(userUri);
            lastKnownState.remove(userUri);
            return;
        }

        notifyUserStatus(userUri, namespace, name);
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

    // ---- Notification helpers ----

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

    private void notifyUserStatus(final String uri, final String namespace, final String name) {
        try {
            KafkaUserResponse user = userService.getUser(namespace, name);
            String json = objectMapper.writeValueAsString(user);
            registerAndNotify(uri, "KafkaUser " + namespace + "/" + name + " status", json);
        } catch (JsonProcessingException e) {
            LOG.warnf("Failed to serialize KafkaUser status for %s/%s: %s", namespace, name, e.getMessage());
        } catch (Exception e) {
            LOG.debugf("Could not update KafkaUser status resource %s: %s", uri, e.getMessage());
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
