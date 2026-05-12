/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.dto.KafkaClusterOverviewResponse;
import io.streamshub.mcp.strimzi.dto.KafkaClusterOverviewResponse.BridgeSummary;
import io.streamshub.mcp.strimzi.dto.KafkaClusterOverviewResponse.ClusterSummary;
import io.streamshub.mcp.strimzi.dto.KafkaClusterOverviewResponse.ConnectSummary;
import io.streamshub.mcp.strimzi.dto.KafkaClusterOverviewResponse.DrainCleanerSummary;
import io.streamshub.mcp.strimzi.dto.KafkaClusterOverviewResponse.MirrorMakerSummary;
import io.streamshub.mcp.strimzi.dto.KafkaClusterOverviewResponse.NodePoolSummary;
import io.streamshub.mcp.strimzi.dto.KafkaClusterOverviewResponse.RebalanceSummary;
import io.streamshub.mcp.strimzi.dto.KafkaClusterOverviewResponse.ResourceCount;
import io.streamshub.mcp.strimzi.dto.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.KafkaNodePoolResponse;
import io.streamshub.mcp.strimzi.dto.KafkaRebalanceResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorResponse;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgeResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectResponse;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response;
import io.streamshub.mcp.strimzi.service.kafkabridge.KafkaBridgeService;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectService;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectorService;
import io.streamshub.mcp.strimzi.service.kafkamirrormaker2.KafkaMirrorMaker2Service;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Status;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.user.KafkaUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service that builds a full overview of a Kafka cluster and all related resources.
 *
 * <p>Aggregates data from existing domain services to produce a dependency graph showing
 * the operator, node pools, topics, users, rebalances, and connected
 * KafkaConnect/Bridge resources. Each sub-query is independently resilient —
 * a failure in one area does not prevent the rest from being gathered.</p>
 *
 * <p>Reuses existing services (KafkaConnectService, KafkaBridgeService, etc.)
 * to avoid duplicating readiness detection and status extraction logic.</p>
 */
@ApplicationScoped
public class KafkaClusterOverviewService {

    private static final Logger LOG = Logger.getLogger(KafkaClusterOverviewService.class);

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    KafkaService kafkaService;

    @Inject
    KafkaNodePoolService nodePoolService;

    @Inject
    StrimziOperatorService operatorService;

    @Inject
    KafkaRebalanceService rebalanceService;

    @Inject
    KafkaConnectService connectService;

    @Inject
    KafkaConnectorService connectorService;

    @Inject
    KafkaBridgeService bridgeService;

    @Inject
    KafkaMirrorMaker2Service mirrorMakerService;

    @Inject
    DrainCleanerService drainCleanerService;

    KafkaClusterOverviewService() {
    }

    /**
     * Build a full overview of a Kafka cluster and all related resources.
     *
     * @param namespace   the namespace, or null for auto-discovery
     * @param clusterName the Kafka cluster name
     * @return the cluster overview response
     */
    public KafkaClusterOverviewResponse getOverview(final String namespace, final String clusterName) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(clusterName);

        if (name == null) {
            throw new ToolCallException("Cluster name is required");
        }
        InputUtils.validateK8sName(name, "cluster name");
        InputUtils.validateK8sName(ns, "namespace");

        LOG.infof("Building overview for cluster=%s (namespace=%s)", name, ns != null ? ns : "auto");

        KafkaClusterResponse cluster = kafkaService.getCluster(ns, name);
        String resolvedNs = cluster.namespace();

        Set<String> bootstrapAddresses = extractBootstrapAddresses(cluster, name);

        return new KafkaClusterOverviewResponse(
            buildClusterSummary(cluster),
            gatherOperator(resolvedNs),
            gatherNodePools(resolvedNs, name),
            countTopics(resolvedNs, name),
            countUsers(resolvedNs, name),
            gatherRebalances(resolvedNs, name),
            findConnectedConnects(resolvedNs, bootstrapAddresses),
            findConnectedMirrorMakers(resolvedNs, bootstrapAddresses),
            findConnectedBridges(resolvedNs, bootstrapAddresses),
            gatherDrainCleaner(),
            Instant.now());
    }

    private ClusterSummary buildClusterSummary(final KafkaClusterResponse cluster) {
        Integer expected = cluster.replicas() != null ? cluster.replicas().expected() : null;
        Integer ready = cluster.replicas() != null ? cluster.replicas().ready() : null;
        return new ClusterSummary(cluster.name(), cluster.namespace(),
            cluster.kafkaVersion(), cluster.readiness(), expected, ready);
    }

    private KafkaClusterOverviewResponse.OperatorSummary gatherOperator(final String namespace) {
        try {
            List<StrimziOperatorResponse> operators = operatorService.listOperators(namespace);
            if (operators.isEmpty()) {
                return null;
            }
            StrimziOperatorResponse op = operators.getFirst();
            return new KafkaClusterOverviewResponse.OperatorSummary(
                op.name(), op.namespace(), op.version(), op.status());
        } catch (Exception e) {
            LOG.warnf("Could not gather operator info: %s", e.getMessage());
            return null;
        }
    }

    private List<NodePoolSummary> gatherNodePools(final String namespace, final String clusterName) {
        try {
            List<KafkaNodePoolResponse> pools = nodePoolService.listNodePools(namespace, clusterName);
            if (pools == null || pools.isEmpty()) {
                return List.of();
            }
            return pools.stream()
                .map(p -> new NodePoolSummary(p.name(), p.roles(), p.replicas(), null, null))
                .toList();
        } catch (Exception e) {
            LOG.warnf("Could not gather node pools: %s", e.getMessage());
            return null;
        }
    }

    private ResourceCount countTopics(final String namespace, final String clusterName) {
        try {
            List<KafkaTopic> topics = k8sService.queryResourcesByLabel(
                KafkaTopic.class, namespace, ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName);
            int ready = (int) topics.stream().filter(t -> isReady(t.getStatus())).count();
            return ResourceCount.of(topics.size(), ready, topics.size() - ready);
        } catch (Exception e) {
            LOG.warnf("Could not count topics: %s", e.getMessage());
            return null;
        }
    }

    private ResourceCount countUsers(final String namespace, final String clusterName) {
        try {
            List<KafkaUser> users = k8sService.queryResourcesByLabel(
                KafkaUser.class, namespace, ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName);
            int ready = (int) users.stream().filter(u -> isReady(u.getStatus())).count();
            return ResourceCount.of(users.size(), ready, users.size() - ready);
        } catch (Exception e) {
            LOG.warnf("Could not count users: %s", e.getMessage());
            return null;
        }
    }

    private RebalanceSummary gatherRebalances(final String namespace, final String clusterName) {
        try {
            List<KafkaRebalanceResponse> rebalances = rebalanceService.listRebalances(
                namespace, clusterName);
            if (rebalances.isEmpty()) {
                return new RebalanceSummary(0, 0, null);
            }
            Map<String, Integer> states = new LinkedHashMap<>();
            int active = 0;
            for (KafkaRebalanceResponse r : rebalances) {
                states.merge(r.state(), 1, Integer::sum);
                if (KafkaRebalanceService.isActiveRebalanceState(r.state())) {
                    active++;
                }
            }
            return new RebalanceSummary(rebalances.size(), active, states);
        } catch (Exception e) {
            LOG.warnf("Could not gather rebalances: %s", e.getMessage());
            return null;
        }
    }

    private List<ConnectSummary> findConnectedConnects(final String namespace,
                                                       final Set<String> bootstrapAddresses) {
        try {
            List<KafkaConnectResponse> allConnects = connectService.listConnects(namespace);
            return allConnects.stream()
                .filter(c -> matchesBootstrap(c.bootstrapServers(), bootstrapAddresses))
                .map(c -> {
                    int connectorCount = countConnectors(namespace, c.name());
                    Integer replicas = c.replicas() != null ? c.replicas().expected() : null;
                    return new ConnectSummary(c.name(), c.namespace(),
                        c.readiness(), replicas, connectorCount);
                })
                .toList();
        } catch (Exception e) {
            LOG.warnf("Could not find connected KafkaConnect clusters: %s", e.getMessage());
            return null;
        }
    }

    private List<MirrorMakerSummary> findConnectedMirrorMakers(final String namespace,
                                                               final Set<String> bootstrapAddresses) {
        try {
            List<KafkaMirrorMaker2Response> allMm2 = mirrorMakerService.listMirrorMakers(namespace);
            return allMm2.stream()
                .filter(m -> mm2ConnectsToCluster(m, bootstrapAddresses))
                .map(m -> {
                    Integer replicas = m.replicas() != null ? m.replicas().expected() : null;
                    int mirrorCount = m.sourceClusterAliases() != null
                        ? m.sourceClusterAliases().size() : 0;
                    return new MirrorMakerSummary(m.name(), m.namespace(),
                        m.readiness(), replicas, mirrorCount);
                })
                .toList();
        } catch (Exception e) {
            LOG.warnf("Could not find connected KafkaMirrorMaker2 instances: %s", e.getMessage());
            return null;
        }
    }

    private List<BridgeSummary> findConnectedBridges(final String namespace,
                                                     final Set<String> bootstrapAddresses) {
        try {
            List<KafkaBridgeResponse> allBridges = bridgeService.listBridges(namespace);
            return allBridges.stream()
                .filter(b -> matchesBootstrap(b.bootstrapServers(), bootstrapAddresses))
                .map(b -> {
                    Integer replicas = b.replicas() != null ? b.replicas().expected() : null;
                    return new BridgeSummary(b.name(), b.namespace(), b.readiness(), replicas);
                })
                .toList();
        } catch (Exception e) {
            LOG.warnf("Could not find connected KafkaBridge instances: %s", e.getMessage());
            return null;
        }
    }

    private DrainCleanerSummary gatherDrainCleaner() {
        try {
            var readiness = drainCleanerService.checkReadiness(null);
            if (!readiness.deployed()) {
                return null;
            }
            return new DrainCleanerSummary("strimzi-drain-cleaner", readiness.overallReady());
        } catch (Exception e) {
            LOG.warnf("Could not check Drain Cleaner: %s", e.getMessage());
            return null;
        }
    }

    // ---- Helpers ----

    private Set<String> extractBootstrapAddresses(final KafkaClusterResponse cluster,
                                                   final String clusterName) {
        Set<String> addresses = new HashSet<>();
        addresses.add(clusterName + "-kafka-bootstrap");

        if (cluster.listeners() != null) {
            for (var listener : cluster.listeners()) {
                if (listener.bootstrapAddress() != null) {
                    addresses.add(listener.bootstrapAddress());
                    String host = listener.bootstrapAddress().split(":")[0];
                    addresses.add(host);
                }
            }
        }
        return addresses;
    }

    private boolean mm2ConnectsToCluster(final KafkaMirrorMaker2Response mm2,
                                         final Set<String> clusterAddresses) {
        if (matchesBootstrap(mm2.bootstrapServers(), clusterAddresses)) {
            return true;
        }
        if (mm2.targetCluster() != null) {
            for (String addr : clusterAddresses) {
                if (addr.contains(mm2.targetCluster())) {
                    return true;
                }
            }
        }
        if (mm2.sourceClusterAliases() != null) {
            for (String alias : mm2.sourceClusterAliases()) {
                for (String addr : clusterAddresses) {
                    if (addr.contains(alias)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean matchesBootstrap(final String specBootstrapServers,
                                     final Set<String> clusterAddresses) {
        if (specBootstrapServers == null || specBootstrapServers.isBlank()) {
            return false;
        }
        for (String address : clusterAddresses) {
            if (specBootstrapServers.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private int countConnectors(final String namespace, final String connectClusterName) {
        try {
            return connectorService.listConnectors(namespace, connectClusterName).size();
        } catch (Exception e) {
            LOG.debugf("Could not count connectors for %s: %s", connectClusterName, e.getMessage());
            return 0;
        }
    }

    private boolean isReady(final Status status) {
        if (status == null || status.getConditions() == null) {
            return false;
        }
        return status.getConditions().stream()
            .anyMatch(c -> KubernetesConstants.Conditions.TYPE_READY.equals(c.getType())
                && KubernetesConstants.Conditions.STATUS_TRUE.equals(c.getStatus()));
    }
}
