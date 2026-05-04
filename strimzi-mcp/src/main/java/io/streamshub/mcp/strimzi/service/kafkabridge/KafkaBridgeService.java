/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkabridge;

import io.fabric8.kubernetes.api.model.Pod;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.dto.PodLogsResult;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.common.dto.ReplicasInfo;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.PodsService;
import io.streamshub.mcp.common.service.log.LogCollectionService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgeLogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgePodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkabridge.KafkaBridgeResponse;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.common.Condition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for KafkaBridge operations.
 */
@ApplicationScoped
public class KafkaBridgeService {

    private static final Logger LOG = Logger.getLogger(KafkaBridgeService.class);

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    PodsService podsService;

    @Inject
    LogCollectionService logCollectionService;

    KafkaBridgeService() {
    }

    /**
     * List KafkaBridge resources, optionally filtered by namespace.
     *
     * @param namespace the namespace to search in, or null for all namespaces
     * @return list of KafkaBridge responses
     */
    public List<KafkaBridgeResponse> listBridges(final String namespace) {
        String ns = InputUtils.normalizeInput(namespace);
        LOG.infof("Listing KafkaBridge resources (namespace=%s)", ns != null ? ns : "all");

        List<KafkaBridge> bridges;
        if (ns != null) {
            bridges = k8sService.queryResources(KafkaBridge.class, ns);
        } else {
            bridges = k8sService.queryResourcesInAnyNamespace(KafkaBridge.class);
        }

        return bridges.stream()
            .map(this::createBridgeSummary)
            .toList();
    }

    /**
     * Get a specific KafkaBridge by name.
     *
     * @param namespace  the namespace, or null for auto-discovery
     * @param bridgeName the KafkaBridge name
     * @return the KafkaBridge response
     */
    public KafkaBridgeResponse getBridge(final String namespace, final String bridgeName) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(bridgeName);

        if (normalizedName == null) {
            throw new ToolCallException("KafkaBridge name is required");
        }

        LOG.infof("Getting KafkaBridge name=%s (namespace=%s)", normalizedName, ns != null ? ns : "auto");

        KafkaBridge bridge = findKafkaBridge(ns, normalizedName);
        return createBridgeDetail(bridge);
    }

    /**
     * Get pods for a specific KafkaBridge.
     *
     * @param namespace  the namespace, or null for auto-discovery
     * @param bridgeName the KafkaBridge name
     * @return the bridge pods response
     */
    public KafkaBridgePodsResponse getBridgePods(final String namespace, final String bridgeName) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(bridgeName);

        if (normalizedName == null) {
            throw new ToolCallException("KafkaBridge name is required");
        }

        LOG.infof("Getting pods for KafkaBridge=%s in namespace=%s",
            normalizedName, ns != null ? ns : "auto");

        if (ns == null) {
            ns = discoverBridgeNamespace(normalizedName);
        }

        List<Pod> pods = queryBridgePods(ns, normalizedName);

        if (pods.isEmpty()) {
            return KafkaBridgePodsResponse.empty(normalizedName, ns);
        }

        final String finalNamespace = ns;
        List<PodSummaryResponse.PodInfo> podInfos = pods.stream()
            .map(pod -> podsService.extractPodSummary(finalNamespace, pod))
            .toList();

        PodSummaryResponse podSummary = PodSummaryResponse.of(ns, podInfos);
        return KafkaBridgePodsResponse.of(normalizedName, ns, podSummary);
    }

    /**
     * Get logs from KafkaBridge pods.
     *
     * @param namespace  the namespace, or null for auto-discovery
     * @param bridgeName the KafkaBridge name
     * @param options    log collection options
     * @return the bridge logs response
     */
    public KafkaBridgeLogsResponse getBridgeLogs(final String namespace, final String bridgeName,
                                                   final LogCollectionParams options) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(bridgeName);

        if (normalizedName == null) {
            throw new ToolCallException("KafkaBridge name is required");
        }

        LOG.infof("Getting logs for KafkaBridge=%s (namespace=%s, filter=%s, sinceSeconds=%s, "
                + "tailLines=%s, previous=%s)",
            normalizedName, ns != null ? ns : "auto",
            options.filter() != null ? options.filter() : "none",
            options.sinceSeconds(), options.tailLines(), options.previous());

        if (ns == null) {
            ns = discoverBridgeNamespace(normalizedName);
        }

        List<Pod> pods = queryBridgePods(ns, normalizedName);

        if (pods.isEmpty()) {
            return KafkaBridgeLogsResponse.empty(normalizedName, ns);
        }

        PodLogsResult result = logCollectionService.collectLogs(ns, pods, options);
        return KafkaBridgeLogsResponse.of(normalizedName, ns, result.podNames(),
            result.hasErrors(), result.errorCount(), result.totalLines(), result.hasMore(), result.logs());
    }

    /**
     * Find a KafkaBridge by name, with optional namespace.
     *
     * @param namespace  the namespace, or null for auto-discovery
     * @param bridgeName the normalized KafkaBridge name
     * @return the KafkaBridge resource
     */
    public KafkaBridge findKafkaBridge(final String namespace, final String bridgeName) {
        KafkaBridge bridge;
        if (namespace != null) {
            bridge = k8sService.getResource(KafkaBridge.class, namespace, bridgeName);
        } else {
            bridge = findBridgeInAllNamespaces(bridgeName);
        }

        if (bridge == null) {
            if (namespace != null) {
                throw new ToolCallException(
                    "KafkaBridge '" + bridgeName + "' not found in namespace " + namespace);
            } else {
                throw new ToolCallException(
                    "KafkaBridge '" + bridgeName + "' not found in any namespace");
            }
        }
        return bridge;
    }

    private KafkaBridge findBridgeInAllNamespaces(final String bridgeName) {
        List<KafkaBridge> all = k8sService.queryResourcesInAnyNamespace(KafkaBridge.class);
        List<KafkaBridge> matching = all.stream()
            .filter(b -> bridgeName.equals(b.getMetadata().getName()))
            .toList();

        if (matching.isEmpty()) {
            return null;
        }

        if (matching.size() > 1) {
            String namespaces = matching.stream()
                .map(b -> b.getMetadata().getNamespace())
                .distinct()
                .collect(Collectors.joining(", "));
            throw new ToolCallException("Multiple KafkaBridge resources named '" + bridgeName
                + "' found in namespaces: " + namespaces + ". Please specify namespace.");
        }

        LOG.debugf("Discovered KafkaBridge %s in namespace %s",
            bridgeName, matching.getFirst().getMetadata().getNamespace());
        return matching.getFirst();
    }

    private String discoverBridgeNamespace(final String bridgeName) {
        KafkaBridge bridge = findKafkaBridge(null, bridgeName);
        return bridge.getMetadata().getNamespace();
    }

    private List<Pod> queryBridgePods(final String namespace, final String bridgeName) {
        Map<String, String> labels = Map.of(
            ResourceLabels.STRIMZI_CLUSTER_LABEL, bridgeName,
            ResourceLabels.STRIMZI_KIND_LABEL, StrimziConstants.KindValues.KAFKA_BRIDGE);
        return k8sService.queryResourcesByLabels(Pod.class, namespace, labels);
    }

    private KafkaBridgeResponse createBridgeSummary(final KafkaBridge bridge) {
        return KafkaBridgeResponse.summary(
            bridge.getMetadata().getName(),
            bridge.getMetadata().getNamespace(),
            determineResourceStatus(bridge),
            extractReplicas(bridge),
            extractBootstrapServers(bridge),
            extractHttpUrl(bridge),
            extractConditions(bridge));
    }

    private KafkaBridgeResponse createBridgeDetail(final KafkaBridge bridge) {
        Instant creationTime = extractCreationTime(bridge);
        Long ageMinutes = null;
        if (creationTime != null) {
            ageMinutes = Duration.between(creationTime, Instant.now()).toMinutes();
        }

        return KafkaBridgeResponse.of(
            bridge.getMetadata().getName(),
            bridge.getMetadata().getNamespace(),
            determineResourceStatus(bridge),
            extractReplicas(bridge),
            extractBootstrapServers(bridge),
            extractHttpUrl(bridge),
            extractHttpPort(bridge),
            extractCorsAllowedOrigins(bridge),
            extractCorsAllowedMethods(bridge),
            extractProducerConfig(bridge),
            extractConsumerConfig(bridge),
            extractAdminClientConfig(bridge),
            extractAuthenticationType(bridge),
            extractTlsEnabled(bridge),
            extractLogging(bridge),
            extractConditions(bridge),
            creationTime, ageMinutes);
    }

    private String determineResourceStatus(final KafkaBridge bridge) {
        if (bridge.getStatus() == null || bridge.getStatus().getConditions() == null
            || bridge.getStatus().getConditions().isEmpty()) {
            return KubernetesConstants.ResourceStatus.UNKNOWN;
        }

        List<Condition> conditions = bridge.getStatus().getConditions();
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

    private ReplicasInfo extractReplicas(final KafkaBridge bridge) {
        Integer expected = bridge.getSpec() != null ? bridge.getSpec().getReplicas() : null;
        Integer ready = bridge.getStatus() != null ? bridge.getStatus().getReplicas() : null;
        return ReplicasInfo.of(expected, ready);
    }

    private String extractBootstrapServers(final KafkaBridge bridge) {
        return bridge.getSpec() != null ? bridge.getSpec().getBootstrapServers() : null;
    }

    private String extractHttpUrl(final KafkaBridge bridge) {
        return bridge.getStatus() != null ? bridge.getStatus().getUrl() : null;
    }

    private Integer extractHttpPort(final KafkaBridge bridge) {
        if (bridge.getSpec() != null && bridge.getSpec().getHttp() != null) {
            return bridge.getSpec().getHttp().getPort();
        }
        return null;
    }

    private List<String> extractCorsAllowedOrigins(final KafkaBridge bridge) {
        if (bridge.getSpec() != null && bridge.getSpec().getHttp() != null
            && bridge.getSpec().getHttp().getCors() != null) {
            return bridge.getSpec().getHttp().getCors().getAllowedOrigins();
        }
        return null;
    }

    private List<String> extractCorsAllowedMethods(final KafkaBridge bridge) {
        if (bridge.getSpec() != null && bridge.getSpec().getHttp() != null
            && bridge.getSpec().getHttp().getCors() != null) {
            return bridge.getSpec().getHttp().getCors().getAllowedMethods();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractProducerConfig(final KafkaBridge bridge) {
        if (bridge.getSpec() != null && bridge.getSpec().getProducer() != null
            && bridge.getSpec().getProducer().getConfig() != null) {
            return (Map<String, Object>) bridge.getSpec().getProducer().getConfig();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractConsumerConfig(final KafkaBridge bridge) {
        if (bridge.getSpec() != null && bridge.getSpec().getConsumer() != null
            && bridge.getSpec().getConsumer().getConfig() != null) {
            return (Map<String, Object>) bridge.getSpec().getConsumer().getConfig();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractAdminClientConfig(final KafkaBridge bridge) {
        if (bridge.getSpec() != null && bridge.getSpec().getAdminClient() != null
            && bridge.getSpec().getAdminClient().getConfig() != null) {
            return (Map<String, Object>) bridge.getSpec().getAdminClient().getConfig();
        }
        return null;
    }

    private String extractAuthenticationType(final KafkaBridge bridge) {
        if (bridge.getSpec() != null && bridge.getSpec().getAuthentication() != null) {
            return bridge.getSpec().getAuthentication().getType();
        }
        return null;
    }

    private Boolean extractTlsEnabled(final KafkaBridge bridge) {
        if (bridge.getSpec() != null) {
            return bridge.getSpec().getTls() != null;
        }
        return null;
    }

    private String extractLogging(final KafkaBridge bridge) {
        if (bridge.getSpec() != null && bridge.getSpec().getLogging() != null) {
            return bridge.getSpec().getLogging().getType();
        }
        return null;
    }

    private List<ConditionInfo> extractConditions(final KafkaBridge bridge) {
        if (bridge.getStatus() == null || bridge.getStatus().getConditions() == null
            || bridge.getStatus().getConditions().isEmpty()) {
            return null;
        }
        return bridge.getStatus().getConditions().stream()
            .map(c -> ConditionInfo.of(
                c.getType(), c.getStatus(), c.getReason(),
                c.getMessage(), c.getLastTransitionTime()))
            .toList();
    }

    private Instant extractCreationTime(final KafkaBridge bridge) {
        String ts = bridge.getMetadata().getCreationTimestamp();
        if (ts != null) {
            try {
                return Instant.parse(ts);
            } catch (DateTimeParseException e) {
                LOG.debugf("Could not parse creation timestamp: %s", e.getMessage());
            }
        }
        return null;
    }
}
