/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkaconnect;

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
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectLogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectPodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectResponse;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
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
 * Service for KafkaConnect cluster operations.
 */
@ApplicationScoped
public class KafkaConnectService {

    private static final Logger LOG = Logger.getLogger(KafkaConnectService.class);

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    PodsService podsService;

    @Inject
    LogCollectionService logCollectionService;

    KafkaConnectService() {
    }

    /**
     * List KafkaConnect clusters, optionally filtered by namespace.
     *
     * @param namespace the namespace to search in, or null for all namespaces
     * @return list of KafkaConnect responses
     */
    public List<KafkaConnectResponse> listConnects(final String namespace) {
        String ns = InputUtils.normalizeInput(namespace);
        LOG.infof("Listing KafkaConnect clusters (namespace=%s)", ns != null ? ns : "all");

        List<KafkaConnect> connects;
        if (ns != null) {
            connects = k8sService.queryResources(KafkaConnect.class, ns);
        } else {
            connects = k8sService.queryResourcesInAnyNamespace(KafkaConnect.class);
        }

        return connects.stream()
            .map(this::createConnectSummary)
            .toList();
    }

    /**
     * Get a specific KafkaConnect cluster by name.
     *
     * @param namespace   the namespace, or null for auto-discovery
     * @param connectName the KafkaConnect cluster name
     * @return the KafkaConnect response
     */
    public KafkaConnectResponse getConnect(final String namespace, final String connectName) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(connectName);

        if (normalizedName == null) {
            throw new ToolCallException("KafkaConnect cluster name is required");
        }

        LOG.infof("Getting KafkaConnect cluster name=%s (namespace=%s)", normalizedName, ns != null ? ns : "auto");

        KafkaConnect connect = findKafkaConnect(ns, normalizedName);
        return createConnectDetail(connect);
    }

    /**
     * Get pods for a specific KafkaConnect cluster.
     *
     * @param namespace   the namespace, or null for auto-discovery
     * @param connectName the KafkaConnect cluster name
     * @return the Connect pods response
     */
    public KafkaConnectPodsResponse getConnectPods(final String namespace, final String connectName) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(connectName);

        if (normalizedName == null) {
            throw new ToolCallException("KafkaConnect cluster name is required");
        }

        LOG.infof("Getting pods for KafkaConnect cluster=%s in namespace=%s",
            normalizedName, ns != null ? ns : "auto");

        if (ns == null) {
            ns = discoverConnectNamespace(normalizedName);
        }

        List<Pod> pods = queryConnectPods(ns, normalizedName);

        if (pods.isEmpty()) {
            return KafkaConnectPodsResponse.empty(normalizedName, ns);
        }

        final String finalNamespace = ns;
        List<PodSummaryResponse.PodInfo> podInfos = pods.stream()
            .map(pod -> podsService.extractPodSummary(finalNamespace, pod))
            .toList();

        PodSummaryResponse podSummary = PodSummaryResponse.of(ns, podInfos);
        return KafkaConnectPodsResponse.of(normalizedName, ns, podSummary);
    }

    /**
     * Get logs from KafkaConnect cluster pods.
     *
     * @param namespace   the namespace, or null for auto-discovery
     * @param connectName the KafkaConnect cluster name
     * @param options     log collection options
     * @return the Connect logs response
     */
    public KafkaConnectLogsResponse getConnectLogs(final String namespace, final String connectName,
                                                    final LogCollectionParams options) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(connectName);

        if (normalizedName == null) {
            throw new ToolCallException("KafkaConnect cluster name is required");
        }

        LOG.infof("Getting logs for KafkaConnect cluster=%s (namespace=%s, filter=%s, sinceSeconds=%s, "
                + "tailLines=%s, previous=%s)",
            normalizedName, ns != null ? ns : "auto",
            options.filter() != null ? options.filter() : "none",
            options.sinceSeconds(), options.tailLines(), options.previous());

        if (ns == null) {
            ns = discoverConnectNamespace(normalizedName);
        }

        List<Pod> pods = queryConnectPods(ns, normalizedName);

        if (pods.isEmpty()) {
            return KafkaConnectLogsResponse.empty(normalizedName, ns);
        }

        PodLogsResult result = logCollectionService.collectLogs(ns, pods, options);
        return KafkaConnectLogsResponse.of(normalizedName, ns, result.podNames(),
            result.hasErrors(), result.errorCount(), result.totalLines(), result.hasMore(), result.logs());
    }

    /**
     * Find a KafkaConnect cluster by name, with optional namespace.
     *
     * @param namespace   the namespace, or null for auto-discovery
     * @param connectName the normalized KafkaConnect cluster name
     * @return the KafkaConnect resource
     */
    public KafkaConnect findKafkaConnect(final String namespace, final String connectName) {
        KafkaConnect connect;
        if (namespace != null) {
            connect = k8sService.getResource(KafkaConnect.class, namespace, connectName);
        } else {
            connect = findConnectInAllNamespaces(connectName);
        }

        if (connect == null) {
            if (namespace != null) {
                throw new ToolCallException(
                    "KafkaConnect cluster '" + connectName + "' not found in namespace " + namespace);
            } else {
                throw new ToolCallException(
                    "KafkaConnect cluster '" + connectName + "' not found in any namespace");
            }
        }
        return connect;
    }

    private KafkaConnect findConnectInAllNamespaces(final String connectName) {
        List<KafkaConnect> all = k8sService.queryResourcesInAnyNamespace(KafkaConnect.class);
        List<KafkaConnect> matching = all.stream()
            .filter(c -> connectName.equals(c.getMetadata().getName()))
            .toList();

        if (matching.isEmpty()) {
            return null;
        }

        if (matching.size() > 1) {
            String namespaces = matching.stream()
                .map(c -> c.getMetadata().getNamespace())
                .distinct()
                .collect(Collectors.joining(", "));
            throw new ToolCallException("Multiple KafkaConnect clusters named '" + connectName
                + "' found in namespaces: " + namespaces + ". Please specify namespace.");
        }

        LOG.debugf("Discovered KafkaConnect cluster %s in namespace %s",
            connectName, matching.getFirst().getMetadata().getNamespace());
        return matching.getFirst();
    }

    private String discoverConnectNamespace(final String connectName) {
        KafkaConnect connect = findKafkaConnect(null, connectName);
        return connect.getMetadata().getNamespace();
    }

    private List<Pod> queryConnectPods(final String namespace, final String connectName) {
        Map<String, String> labels = Map.of(
            ResourceLabels.STRIMZI_CLUSTER_LABEL, connectName,
            ResourceLabels.STRIMZI_KIND_LABEL, StrimziConstants.KindValues.KAFKA_CONNECT);
        return k8sService.queryResourcesByLabels(Pod.class, namespace, labels);
    }

    private KafkaConnectResponse createConnectSummary(final KafkaConnect connect) {
        return KafkaConnectResponse.summary(
            connect.getMetadata().getName(),
            connect.getMetadata().getNamespace(),
            determineResourceStatus(connect),
            extractReplicas(connect),
            extractVersion(connect),
            extractBootstrapServers(connect),
            extractRestApiUrl(connect),
            extractConnectorPluginsCount(connect),
            extractConditions(connect));
    }

    private KafkaConnectResponse createConnectDetail(final KafkaConnect connect) {
        Instant creationTime = extractCreationTime(connect);
        Long ageMinutes = null;
        if (creationTime != null) {
            ageMinutes = Duration.between(creationTime, Instant.now()).toMinutes();
        }

        return KafkaConnectResponse.of(
            connect.getMetadata().getName(),
            connect.getMetadata().getNamespace(),
            determineResourceStatus(connect),
            extractReplicas(connect),
            extractVersion(connect),
            extractBootstrapServers(connect),
            extractRestApiUrl(connect),
            extractConnectorPluginsCount(connect),
            extractConnectorPlugins(connect),
            extractConditions(connect),
            creationTime, ageMinutes);
    }

    private String determineResourceStatus(final KafkaConnect connect) {
        if (connect.getStatus() == null || connect.getStatus().getConditions() == null
            || connect.getStatus().getConditions().isEmpty()) {
            return KubernetesConstants.ResourceStatus.UNKNOWN;
        }

        List<Condition> conditions = connect.getStatus().getConditions();
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

    private ReplicasInfo extractReplicas(final KafkaConnect connect) {
        Integer expected = connect.getSpec() != null ? connect.getSpec().getReplicas() : null;
        Integer ready = connect.getStatus() != null ? connect.getStatus().getReplicas() : null;
        return ReplicasInfo.of(expected, ready);
    }

    private String extractVersion(final KafkaConnect connect) {
        return connect.getSpec() != null ? connect.getSpec().getVersion() : null;
    }

    private String extractBootstrapServers(final KafkaConnect connect) {
        return connect.getSpec() != null ? connect.getSpec().getBootstrapServers() : null;
    }

    private String extractRestApiUrl(final KafkaConnect connect) {
        return connect.getStatus() != null ? connect.getStatus().getUrl() : null;
    }

    private Integer extractConnectorPluginsCount(final KafkaConnect connect) {
        if (connect.getStatus() != null && connect.getStatus().getConnectorPlugins() != null) {
            return connect.getStatus().getConnectorPlugins().size();
        }
        return null;
    }

    private List<KafkaConnectResponse.ConnectorPluginInfo> extractConnectorPlugins(final KafkaConnect connect) {
        if (connect.getStatus() == null || connect.getStatus().getConnectorPlugins() == null) {
            return null;
        }
        return connect.getStatus().getConnectorPlugins().stream()
            .map(p -> KafkaConnectResponse.ConnectorPluginInfo.of(
                p.getConnectorClass(), p.getType(), p.getVersion()))
            .toList();
    }

    private List<ConditionInfo> extractConditions(final KafkaConnect connect) {
        if (connect.getStatus() == null || connect.getStatus().getConditions() == null
            || connect.getStatus().getConditions().isEmpty()) {
            return null;
        }
        return connect.getStatus().getConditions().stream()
            .map(c -> ConditionInfo.of(
                c.getType(), c.getStatus(), c.getReason(),
                c.getMessage(), c.getLastTransitionTime()))
            .toList();
    }

    private Instant extractCreationTime(final KafkaConnect connect) {
        String ts = connect.getMetadata().getCreationTimestamp();
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
