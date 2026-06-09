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
import java.util.function.Supplier;
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
    public KafkaFleetOverviewResponse getFleetOverview(final String namespace) {
        String ns = InputUtils.normalizeInput(namespace);
        LOG.infof("Getting fleet overview (namespace=%s)", ns != null ? ns : "all");

        List<KafkaClusterResponse> clusters = kafkaService.listClusters(ns);

        List<String> fleetResourceErrors = new ArrayList<>();
        List<KafkaConnectResponse> allConnects = bulkFetch(
            () -> connectService.listConnects(ns), "KafkaConnect", fleetResourceErrors);
        List<KafkaBridgeResponse> allBridges = bulkFetch(
            () -> bridgeService.listBridges(ns), "KafkaBridge", fleetResourceErrors);
        List<KafkaMirrorMaker2Response> allMirrorMakers = bulkFetch(
            () -> mirrorMakerService.listMirrorMakers(ns), "KafkaMirrorMaker2", fleetResourceErrors);

        int totalBrokers = 0;
        int[] statusCounts = new int[4];

        List<ClusterSummary> summaries = new ArrayList<>(clusters.size());
        List<ClusterWarning> warnings = new ArrayList<>();

        for (KafkaClusterResponse cluster : clusters) {
            int brokers = extractBrokerCount(cluster, true);
            int readyBrokers = extractBrokerCount(cluster, false);
            totalBrokers += brokers;

            summaries.add(buildClusterSummary(
                cluster, brokers, readyBrokers,
                allConnects, allBridges, allMirrorMakers, fleetResourceErrors));
            accumulateStatus(cluster, statusCounts, warnings);
            checkReplicaMismatch(cluster, brokers, readyBrokers, warnings);
        }

        return new KafkaFleetOverviewResponse(
            clusters.size(),
            totalBrokers,
            new StatusDistribution(statusCounts[0], statusCounts[1], statusCounts[2], statusCounts[3]),
            summaries,
            warnings,
            Instant.now(),
            ns,
            fleetResourceErrors.isEmpty() ? null : fleetResourceErrors);
    }

    private int extractBrokerCount(final KafkaClusterResponse cluster, final boolean expected) {
        if (cluster.replicas() == null) {
            return 0;
        }
        Integer value = expected ? cluster.replicas().expected() : cluster.replicas().ready();
        return value != null ? value : 0;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private ClusterSummary buildClusterSummary(
            final KafkaClusterResponse cluster,
            final int brokers, final int readyBrokers,
            final List<KafkaConnectResponse> allConnects,
            final List<KafkaBridgeResponse> allBridges,
            final List<KafkaMirrorMaker2Response> allMirrorMakers,
            final List<String> fleetErrors) {

        Set<String> bootstrapAddresses = BootstrapMatcher.extractBootstrapAddresses(
            cluster, cluster.name());

        List<String> clusterErrors = new ArrayList<>(fleetErrors);
        Integer topicCount = countTopics(cluster.namespace(), cluster.name());
        if (topicCount == null) {
            clusterErrors.add("KafkaTopic");
        }
        Integer userCount = countUsers(cluster.namespace(), cluster.name());
        if (userCount == null) {
            clusterErrors.add("KafkaUser");
        }
        Integer activeRebalances = countActiveRebalances(cluster.namespace(), cluster.name());
        if (activeRebalances == null) {
            clusterErrors.add("KafkaRebalance");
        }

        return new ClusterSummary(
            cluster.name(), cluster.namespace(), cluster.readiness(),
            cluster.kafkaVersion(), brokers, readyBrokers, cluster.ageMinutes(),
            topicCount, userCount, activeRebalances,
            fleetErrors.contains("KafkaConnect")
                ? null : countIfPresent(allConnects, bootstrapAddresses),
            fleetErrors.contains("KafkaBridge")
                ? null : countMatchingBridges(allBridges, bootstrapAddresses),
            fleetErrors.contains("KafkaMirrorMaker2")
                ? null : countMatchingMirrorMakers(allMirrorMakers, bootstrapAddresses),
            clusterErrors.isEmpty() ? null : clusterErrors);
    }

    private void accumulateStatus(final KafkaClusterResponse cluster,
                                  final int[] statusCounts,
                                  final List<ClusterWarning> warnings) {
        switch (cluster.readiness()) {
            case KubernetesConstants.ResourceStatus.READY -> statusCounts[0]++;
            case KubernetesConstants.ResourceStatus.NOT_READY -> {
                statusCounts[1]++;
                addWarning(warnings, cluster.name(), cluster.namespace(),
                    KubernetesConstants.ResourceStatus.NOT_READY,
                    "Cluster is not ready: condition Ready=False");
            }
            case KubernetesConstants.ResourceStatus.ERROR -> {
                statusCounts[2]++;
                addWarning(warnings, cluster.name(), cluster.namespace(),
                    KubernetesConstants.ResourceStatus.ERROR,
                    "Cluster is in error state");
            }
            default -> statusCounts[3]++;
        }
    }

    private void checkReplicaMismatch(final KafkaClusterResponse cluster,
                                      final int brokers, final int readyBrokers,
                                      final List<ClusterWarning> warnings) {
        if (brokers > 0 && readyBrokers < brokers) {
            addWarning(warnings, cluster.name(), cluster.namespace(),
                "ReplicaMismatch",
                String.format("Broker replica mismatch: %d/%d ready", readyBrokers, brokers));
        }
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

    private <T> List<T> bulkFetch(final Supplier<List<T>> fetcher,
                                   final String resourceType,
                                   final List<String> errors) {
        try {
            return fetcher.get();
        } catch (Exception e) {
            LOG.warnf("Could not list %s resources: %s", resourceType, e.getMessage());
            errors.add(resourceType);
            return List.of();
        }
    }

    private Integer countIfPresent(final List<KafkaConnectResponse> connects,
                                   final Set<String> bootstrapAddresses) {
        if (connects.isEmpty()) {
            return 0;
        }
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
