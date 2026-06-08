/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaFleetOverviewResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaFleetOverviewResponse.ClusterSummary;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaFleetOverviewResponse.ClusterWarning;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaFleetOverviewResponse.StatusDistribution;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgeResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectResponse;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response;
import io.streamshub.mcp.strimzi.dto.kafkarebalance.KafkaRebalanceResponse;
import io.streamshub.mcp.strimzi.service.kafkabridge.KafkaBridgeService;
import io.streamshub.mcp.strimzi.service.kafkaconnect.KafkaConnectService;
import io.streamshub.mcp.strimzi.service.kafkamirrormaker2.KafkaMirrorMaker2Service;
import io.streamshub.mcp.strimzi.service.kafkarebalance.KafkaRebalanceService;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.user.KafkaUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
/**
 * Service that aggregates health information across all Kafka clusters
 * into a single fleet-level overview with cross-resource relationship counts.
 */
@ApplicationScoped
public class KafkaFleetOverviewService {

    private static final Logger LOG = Logger.getLogger(KafkaFleetOverviewService.class);

    private static final int MAX_WARNINGS = 20;

    @Inject
    KafkaService kafkaService;

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    KafkaRebalanceService rebalanceService;

    @Inject
    KafkaConnectService connectService;

    @Inject
    KafkaBridgeService bridgeService;

    @Inject
    KafkaMirrorMaker2Service mirrorMakerService;

    KafkaFleetOverviewService() {
    }

    /**
     * Get an aggregated health overview across all Kafka clusters.
     *
     * @param namespace optional namespace filter (null for all namespaces)
     * @return the fleet overview response
     */
    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    public KafkaFleetOverviewResponse getFleetOverview(final String namespace) {
        String ns = InputUtils.normalizeInput(namespace);
        LOG.infof("Getting fleet overview (namespace=%s)", ns != null ? ns : "all");

        List<KafkaClusterResponse> clusters = kafkaService.listClusters(ns);

        List<KafkaConnectResponse> allConnects = bulkListConnects(ns);
        List<KafkaBridgeResponse> allBridges = bulkListBridges(ns);
        List<KafkaMirrorMaker2Response> allMirrorMakers = bulkListMirrorMakers(ns);

        int totalBrokers = 0;
        int ready = 0;
        int notReady = 0;
        int error = 0;
        int unknown = 0;

        List<ClusterSummary> summaries = new ArrayList<>(clusters.size());
        List<ClusterWarning> warnings = new ArrayList<>();

        for (KafkaClusterResponse cluster : clusters) {
            int brokers = cluster.replicas() != null && cluster.replicas().expected() != null
                ? cluster.replicas().expected() : 0;
            int readyBrokers = cluster.replicas() != null && cluster.replicas().ready() != null
                ? cluster.replicas().ready() : 0;
            totalBrokers += brokers;

            Set<String> bootstrapAddresses = BootstrapMatcher.extractBootstrapAddresses(
                cluster, cluster.name());

            summaries.add(new ClusterSummary(
                cluster.name(), cluster.namespace(), cluster.readiness(),
                cluster.kafkaVersion(), brokers, readyBrokers, cluster.ageMinutes(),
                countTopics(cluster.namespace(), cluster.name()),
                countUsers(cluster.namespace(), cluster.name()),
                countActiveRebalances(cluster.namespace(), cluster.name()),
                countMatching(allConnects, bootstrapAddresses),
                countMatchingBridges(allBridges, bootstrapAddresses),
                countMatchingMirrorMakers(allMirrorMakers, bootstrapAddresses)));

            switch (cluster.readiness()) {
                case KubernetesConstants.ResourceStatus.READY -> ready++;
                case KubernetesConstants.ResourceStatus.NOT_READY -> {
                    notReady++;
                    addWarning(warnings, cluster.name(), cluster.namespace(),
                        KubernetesConstants.ResourceStatus.NOT_READY,
                        "Cluster is not ready: condition Ready=False");
                }
                case KubernetesConstants.ResourceStatus.ERROR -> {
                    error++;
                    addWarning(warnings, cluster.name(), cluster.namespace(),
                        KubernetesConstants.ResourceStatus.ERROR,
                        "Cluster is in error state");
                }
                default -> unknown++;
            }

            if (brokers > 0 && readyBrokers < brokers) {
                addWarning(warnings, cluster.name(), cluster.namespace(),
                    "ReplicaMismatch",
                    String.format("Broker replica mismatch: %d/%d ready", readyBrokers, brokers));
            }
        }

        return new KafkaFleetOverviewResponse(
            clusters.size(),
            totalBrokers,
            new StatusDistribution(ready, notReady, error, unknown),
            summaries,
            warnings,
            Instant.now(),
            ns);
    }

    // ---- Label-based counts (per cluster) ----

    private Integer countTopics(final String namespace, final String clusterName) {
        try {
            return k8sService.queryResourcesByLabel(
                KafkaTopic.class, namespace,
                ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName).size();
        } catch (Exception e) {
            LOG.warnf("Could not count topics for %s: %s", clusterName, e.getMessage());
            return null;
        }
    }

    private Integer countUsers(final String namespace, final String clusterName) {
        try {
            return k8sService.queryResourcesByLabel(
                KafkaUser.class, namespace,
                ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName).size();
        } catch (Exception e) {
            LOG.warnf("Could not count users for %s: %s", clusterName, e.getMessage());
            return null;
        }
    }

    private Integer countActiveRebalances(final String namespace, final String clusterName) {
        try {
            List<KafkaRebalanceResponse> rebalances = rebalanceService.listRebalances(
                namespace, clusterName);
            return (int) rebalances.stream()
                .filter(r -> KafkaRebalanceService.isActiveRebalanceState(r.state()))
                .count();
        } catch (Exception e) {
            LOG.warnf("Could not count rebalances for %s: %s", clusterName, e.getMessage());
            return null;
        }
    }

    // ---- Bulk-fetched infrastructure (one query total, matched per cluster) ----

    private List<KafkaConnectResponse> bulkListConnects(final String namespace) {
        try {
            return connectService.listConnects(namespace);
        } catch (Exception e) {
            LOG.warnf("Could not list KafkaConnects: %s", e.getMessage());
            return List.of();
        }
    }

    private List<KafkaBridgeResponse> bulkListBridges(final String namespace) {
        try {
            return bridgeService.listBridges(namespace);
        } catch (Exception e) {
            LOG.warnf("Could not list KafkaBridges: %s", e.getMessage());
            return List.of();
        }
    }

    private List<KafkaMirrorMaker2Response> bulkListMirrorMakers(final String namespace) {
        try {
            return mirrorMakerService.listMirrorMakers(namespace);
        } catch (Exception e) {
            LOG.warnf("Could not list KafkaMirrorMaker2 instances: %s", e.getMessage());
            return List.of();
        }
    }

    private Integer countMatching(final List<KafkaConnectResponse> connects,
                                  final Set<String> bootstrapAddresses) {
        return (int) connects.stream()
            .filter(c -> BootstrapMatcher.matchesBootstrap(c.bootstrapServers(), bootstrapAddresses))
            .count();
    }

    private Integer countMatchingBridges(final List<KafkaBridgeResponse> bridges,
                                         final Set<String> bootstrapAddresses) {
        return (int) bridges.stream()
            .filter(b -> BootstrapMatcher.matchesBootstrap(b.bootstrapServers(), bootstrapAddresses))
            .count();
    }

    private Integer countMatchingMirrorMakers(final List<KafkaMirrorMaker2Response> mirrorMakers,
                                              final Set<String> bootstrapAddresses) {
        return (int) mirrorMakers.stream()
            .filter(m -> BootstrapMatcher.mm2ConnectsToCluster(m, bootstrapAddresses))
            .count();
    }

    private void addWarning(final List<ClusterWarning> warnings,
                            final String clusterName, final String namespace,
                            final String warningType, final String message) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(new ClusterWarning(clusterName, namespace, warningType, message));
        }
    }
}
