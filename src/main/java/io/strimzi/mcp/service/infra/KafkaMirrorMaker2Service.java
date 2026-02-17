/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.service.infra;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2MirrorSpec;
import io.strimzi.mcp.dto.KafkaClusterInfo;
import io.strimzi.mcp.dto.KafkaMirrorMaker2Info;
import io.strimzi.mcp.dto.KafkaMirrorMaker2Result;
import io.strimzi.mcp.util.InputUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for Kafka MirrorMaker2 operations.
 * Handles MirrorMaker2 discovery and provides information about replication configurations.
 */
@ApplicationScoped
public class KafkaMirrorMaker2Service {

    private KafkaMirrorMaker2Service() {

    }

    private static final Logger LOG = Logger.getLogger(KafkaMirrorMaker2Service.class);

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    StrimziDiscoveryService discoveryService;

    /**
     * Get Kafka MirrorMaker2 instances in namespace or auto-discover across namespaces.
     *
     * @param namespace the namespace to search in, or null for auto-discovery
     * @return structured result containing MirrorMaker2 information
     */
    public KafkaMirrorMaker2Result getKafkaMirrorMaker2(String namespace) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);

        LOG.infof("KafkaMirrorMaker2Service: getKafkaMirrorMaker2 (namespace=%s)", normalizedNamespace);

        // If no namespace specified, auto-discover from Kafka CRs
        if (normalizedNamespace == null) {
            List<KafkaClusterInfo> discoveredClusters = discoveryService.discoverKafkaClusters(null);

            if (discoveredClusters.isEmpty()) {
                return KafkaMirrorMaker2Result.error("not-found",
                    "No Kafka clusters found in any namespace. Please ensure Kafka is deployed. " +
                        "You can specify a namespace explicitly: 'Show me MirrorMaker2 in the kafka namespace'");
            }

            // Deduplicate namespaces
            List<String> distinctNamespaces = discoveredClusters.stream()
                .map(KafkaClusterInfo::namespace)
                .distinct()
                .toList();

            if (distinctNamespaces.size() == 1) {
                normalizedNamespace = distinctNamespaces.getFirst();
                LOG.infof("Auto-discovered Kafka cluster in namespace: %s", normalizedNamespace);
            } else {
                String clusterSuggestions = discoveredClusters.stream()
                    .limit(3)
                    .map(KafkaClusterInfo::getDisplayName)
                    .collect(Collectors.joining(", "));
                return KafkaMirrorMaker2Result.error("multiple-found",
                    String.format("Found Kafka clusters in multiple namespaces: %s. " +
                            "Please specify: 'Show me MirrorMaker2 for %s'",
                        clusterSuggestions, discoveredClusters.getFirst().getDisplayName()));
            }
        }

        final String effectiveNamespace = normalizedNamespace;

        try {
            List<KafkaMirrorMaker2> mm2Resources = kubernetesClient.resources(KafkaMirrorMaker2.class)
                .inNamespace(effectiveNamespace)
                .list()
                .getItems();

            if (mm2Resources.isEmpty()) {
                return KafkaMirrorMaker2Result.empty(effectiveNamespace);
            }

            List<KafkaMirrorMaker2Info> mm2Infos = mm2Resources.stream()
                .map(mm2 -> extractMirrorMaker2Info(effectiveNamespace, mm2))
                .sorted(Comparator.comparing(KafkaMirrorMaker2Info::name))
                .toList();

            return KafkaMirrorMaker2Result.of(mm2Infos);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving Kafka MirrorMaker2 instances from namespace: %s", effectiveNamespace);
            return KafkaMirrorMaker2Result.error(effectiveNamespace, e.getMessage());
        }
    }

    private KafkaMirrorMaker2Info extractMirrorMaker2Info(String namespace, KafkaMirrorMaker2 mm2) {
        String name = mm2.getMetadata().getName();
        String version = mm2.getSpec() != null ? mm2.getSpec().getVersion() : null;
        Integer replicas = mm2.getSpec() != null ? mm2.getSpec().getReplicas() : null;

        List<String> sourceClusters = new ArrayList<>();
        List<String> targetClusters = new ArrayList<>();

        if (mm2.getSpec() != null && mm2.getSpec().getClusters() != null) {
            sourceClusters = mm2.getSpec().getClusters().stream()
                .filter(cluster -> cluster.getAlias() != null)
                .map(cluster -> cluster.getAlias())
                .toList();
        }

        if (mm2.getSpec() != null && mm2.getSpec().getMirrors() != null) {
            targetClusters = mm2.getSpec().getMirrors().stream()
                .filter(mirror -> mirror.getTargetCluster() != null)
                .map(KafkaMirrorMaker2MirrorSpec::getTargetCluster)
                .distinct()
                .toList();
        }

        String status = "Unknown";
        if (mm2.getStatus() != null && mm2.getStatus().getConditions() != null) {
            status = mm2.getStatus().getConditions().stream()
                .filter(condition -> "Ready".equals(condition.getType()))
                .findFirst()
                .map(Condition::getStatus)
                .orElse("Unknown");
        }

        return new KafkaMirrorMaker2Info(
            name,
            namespace,
            version,
            replicas,
            sourceClusters,
            targetClusters,
            status
        );
    }
}