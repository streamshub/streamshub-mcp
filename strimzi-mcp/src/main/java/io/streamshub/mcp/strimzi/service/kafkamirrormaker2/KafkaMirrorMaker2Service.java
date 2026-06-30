/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkamirrormaker2;

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
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2LogsResponse;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2PodsResponse;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response.ClusterInfo;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response.ConnectorInfo;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response.MirrorInfo;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2ClusterSpec;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2ConnectorSpec;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2MirrorSpec;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2Spec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
/**
 * Service for KafkaMirrorMaker2 operations.
 */
@ApplicationScoped
public class KafkaMirrorMaker2Service {

    private static final Logger LOG = Logger.getLogger(KafkaMirrorMaker2Service.class);

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    PodsService podsService;

    @Inject
    LogCollectionService logCollectionService;

    KafkaMirrorMaker2Service() {
    }

    /**
     * List KafkaMirrorMaker2 instances, optionally filtered by namespace.
     *
     * @param namespace the namespace, or null for all namespaces
     * @return list of MM2 summary responses
     */
    public List<KafkaMirrorMaker2Response> listMirrorMakers(final String namespace) {
        String ns = InputUtils.normalizeInput(namespace);
        LOG.infof("Listing KafkaMirrorMaker2 instances (namespace=%s)", ns != null ? ns : "all");

        List<KafkaMirrorMaker2> mirrorMakers;
        if (ns != null) {
            mirrorMakers = k8sService.queryResources(KafkaMirrorMaker2.class, ns);
        } else {
            mirrorMakers = k8sService.queryResourcesInAnyNamespace(KafkaMirrorMaker2.class);
        }

        return mirrorMakers.stream()
            .map(this::createSummary)
            .toList();
    }

    /**
     * Get a specific KafkaMirrorMaker2 by name.
     *
     * @param namespace       the namespace, or null for auto-discovery
     * @param mirrorMakerName the MM2 name
     * @return the detailed MM2 response
     */
    public KafkaMirrorMaker2Response getMirrorMaker(final String namespace, final String mirrorMakerName) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(mirrorMakerName);

        if (normalizedName == null) {
            throw new ToolCallException("MirrorMaker2 name is required");
        }
        InputUtils.validateK8sName(normalizedName, "mirror maker name");
        InputUtils.validateK8sName(ns, "namespace");

        LOG.infof("Getting KafkaMirrorMaker2 name=%s (namespace=%s)",
            normalizedName, ns != null ? ns : "auto");

        KafkaMirrorMaker2 mm2 = findMirrorMaker(ns, normalizedName);
        return createDetail(mm2);
    }

    /**
     * Get pods for a KafkaMirrorMaker2 instance.
     *
     * @param namespace       the namespace, or null for auto-discovery
     * @param mirrorMakerName the MM2 name
     * @return the pods response
     */
    public KafkaMirrorMaker2PodsResponse getMirrorMakerPods(final String namespace,
                                                             final String mirrorMakerName) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(mirrorMakerName);

        if (normalizedName == null) {
            throw new ToolCallException("MirrorMaker2 name is required");
        }
        InputUtils.validateK8sName(normalizedName, "mirror maker name");
        InputUtils.validateK8sName(ns, "namespace");

        LOG.infof("Getting pods for MirrorMaker2=%s in namespace=%s",
            normalizedName, ns != null ? ns : "auto");

        if (ns == null) {
            ns = discoverNamespace(normalizedName);
        }

        List<Pod> pods = queryMm2Pods(ns, normalizedName);
        if (pods.isEmpty()) {
            return KafkaMirrorMaker2PodsResponse.empty(normalizedName, ns);
        }

        final String finalNs = ns;
        List<PodSummaryResponse.PodInfo> podInfos = pods.stream()
            .map(pod -> podsService.extractPodSummary(finalNs, pod))
            .toList();

        return KafkaMirrorMaker2PodsResponse.of(normalizedName, ns,
            PodSummaryResponse.of(ns, podInfos));
    }

    /**
     * Get logs from KafkaMirrorMaker2 pods.
     *
     * @param namespace       the namespace, or null for auto-discovery
     * @param mirrorMakerName the MM2 name
     * @param options         log collection options
     * @return the logs response
     */
    public KafkaMirrorMaker2LogsResponse getMirrorMakerLogs(final String namespace,
                                                             final String mirrorMakerName,
                                                             final LogCollectionParams options) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(mirrorMakerName);

        if (normalizedName == null) {
            throw new ToolCallException("MirrorMaker2 name is required");
        }
        InputUtils.validateK8sName(normalizedName, "mirror maker name");
        InputUtils.validateK8sName(ns, "namespace");

        LOG.infof("Getting logs for MirrorMaker2=%s (namespace=%s, filter=%s)",
            normalizedName, ns != null ? ns : "auto",
            options.filter() != null ? options.filter() : "none");

        if (ns == null) {
            ns = discoverNamespace(normalizedName);
        }

        List<Pod> pods = queryMm2Pods(ns, normalizedName);
        if (pods.isEmpty()) {
            return KafkaMirrorMaker2LogsResponse.empty(normalizedName, ns);
        }

        PodLogsResult result = logCollectionService.collectLogs(ns, pods, options);
        return KafkaMirrorMaker2LogsResponse.of(normalizedName, ns, result.podNames(),
            result.hasErrors(), result.errorCount(), result.failedPods(),
            result.totalLines(), result.hasMore(), result.logs(), result.warnings());
    }

    // ---- Finders ----

    private KafkaMirrorMaker2 findMirrorMaker(final String namespace, final String name) {
        KafkaMirrorMaker2 mm2;
        if (namespace != null) {
            mm2 = k8sService.getResource(KafkaMirrorMaker2.class, namespace, name);
        } else {
            mm2 = findInAllNamespaces(name);
        }

        if (mm2 == null) {
            if (namespace != null) {
                throw new ToolCallException(
                    "KafkaMirrorMaker2 '" + name + "' not found in namespace " + namespace);
            } else {
                throw new ToolCallException(
                    "KafkaMirrorMaker2 '" + name + "' not found in any namespace");
            }
        }
        return mm2;
    }

    private KafkaMirrorMaker2 findInAllNamespaces(final String name) {
        List<KafkaMirrorMaker2> all = k8sService.queryResourcesInAnyNamespace(KafkaMirrorMaker2.class);
        List<KafkaMirrorMaker2> matching = all.stream()
            .filter(m -> name.equals(m.getMetadata().getName()))
            .toList();

        if (matching.isEmpty()) {
            return null;
        }
        if (matching.size() > 1) {
            String namespaces = matching.stream()
                .map(m -> m.getMetadata().getNamespace())
                .distinct()
                .collect(Collectors.joining(", "));
            throw new ToolCallException("Multiple KafkaMirrorMaker2 instances named '" + name
                + "' found in namespaces: " + namespaces + ". Please specify namespace.");
        }

        LOG.debugf("Discovered KafkaMirrorMaker2 %s in namespace %s",
            name, matching.getFirst().getMetadata().getNamespace());
        return matching.getFirst();
    }

    private String discoverNamespace(final String name) {
        KafkaMirrorMaker2 mm2 = findMirrorMaker(null, name);
        return mm2.getMetadata().getNamespace();
    }

    private List<Pod> queryMm2Pods(final String namespace, final String name) {
        Map<String, String> labels = Map.of(
            ResourceLabels.STRIMZI_CLUSTER_LABEL, name,
            ResourceLabels.STRIMZI_KIND_LABEL, StrimziConstants.KindValues.KAFKA_MIRROR_MAKER_2);
        return k8sService.queryResourcesByLabels(Pod.class, namespace, labels);
    }

    // ---- Response builders ----

    private KafkaMirrorMaker2Response createSummary(final KafkaMirrorMaker2 mm2) {
        return KafkaMirrorMaker2Response.summary(
            mm2.getMetadata().getName(),
            mm2.getMetadata().getNamespace(),
            determineResourceStatus(mm2),
            extractReplicas(mm2),
            extractTargetCluster(mm2),
            extractSourceClusterAliases(mm2),
            extractConditions(mm2));
    }

    private KafkaMirrorMaker2Response createDetail(final KafkaMirrorMaker2 mm2) {
        Instant creationTime = extractCreationTime(mm2);
        Long ageMinutes = null;
        if (creationTime != null) {
            ageMinutes = Math.max(0, Duration.between(creationTime, Instant.now()).toMinutes());
        }

        return KafkaMirrorMaker2Response.of(
            mm2.getMetadata().getName(),
            mm2.getMetadata().getNamespace(),
            determineResourceStatus(mm2),
            extractReplicas(mm2),
            extractTargetCluster(mm2),
            extractSourceClusterAliases(mm2),
            extractMirrors(mm2),
            extractClusters(mm2),
            extractConnectorStatuses(mm2),
            extractVersion(mm2),
            extractBootstrapServers(mm2),
            extractConditions(mm2),
            creationTime, ageMinutes);
    }

    // ---- Extractors ----

    private String determineResourceStatus(final KafkaMirrorMaker2 mm2) {
        if (mm2.getStatus() == null || mm2.getStatus().getConditions() == null
            || mm2.getStatus().getConditions().isEmpty()) {
            return KubernetesConstants.ResourceStatus.UNKNOWN;
        }

        List<Condition> conditions = mm2.getStatus().getConditions();
        boolean ready = conditions.stream().anyMatch(c ->
            KubernetesConstants.Conditions.TYPE_READY.equals(c.getType())
                && KubernetesConstants.Conditions.STATUS_TRUE.equals(c.getStatus()));
        if (ready) {
            return KubernetesConstants.ResourceStatus.READY;
        }

        boolean hasError = conditions.stream().anyMatch(c ->
            KubernetesConstants.Conditions.TYPE_READY.equals(c.getType())
                && KubernetesConstants.Conditions.STATUS_FALSE.equals(c.getStatus()));
        return hasError ? KubernetesConstants.ResourceStatus.ERROR
            : KubernetesConstants.ResourceStatus.NOT_READY;
    }

    private ReplicasInfo extractReplicas(final KafkaMirrorMaker2 mm2) {
        Integer expected = mm2.getSpec() != null ? mm2.getSpec().getReplicas() : null;
        Integer ready = mm2.getStatus() != null
            ? mm2.getStatus().getReplicas() : null;
        return ReplicasInfo.of(expected, ready);
    }

    private String extractVersion(final KafkaMirrorMaker2 mm2) {
        return mm2.getSpec() != null ? mm2.getSpec().getVersion() : null;
    }

    private String extractBootstrapServers(final KafkaMirrorMaker2 mm2) {
        if (mm2.getSpec() == null || mm2.getSpec().getTarget() == null) {
            return null;
        }
        return mm2.getSpec().getTarget().getBootstrapServers();
    }

    private String extractTargetCluster(final KafkaMirrorMaker2 mm2) {
        if (mm2.getSpec() == null || mm2.getSpec().getTarget() == null) {
            return null;
        }
        return mm2.getSpec().getTarget().getAlias();
    }

    private List<String> extractSourceClusterAliases(final KafkaMirrorMaker2 mm2) {
        if (mm2.getSpec() == null || mm2.getSpec().getMirrors() == null) {
            return null;
        }
        return mm2.getSpec().getMirrors().stream()
            .map(m -> m.getSource() != null ? m.getSource().getAlias() : null)
            .filter(alias -> alias != null)
            .distinct()
            .toList();
    }

    private List<ClusterInfo> extractClusters(final KafkaMirrorMaker2 mm2) {
        KafkaMirrorMaker2Spec spec = mm2.getSpec();
        if (spec == null) {
            return null;
        }

        List<ClusterInfo> result = new ArrayList<>();

        if (spec.getTarget() != null) {
            result.add(mapClusterSpec(spec.getTarget()));
        }

        if (spec.getMirrors() != null) {
            for (KafkaMirrorMaker2MirrorSpec mirror : spec.getMirrors()) {
                if (mirror.getSource() != null) {
                    result.add(mapClusterSpec(mirror.getSource()));
                }
            }
        }

        return result.isEmpty() ? null : result;
    }

    private ClusterInfo mapClusterSpec(final KafkaMirrorMaker2ClusterSpec cluster) {
        String authType = null;
        if (cluster.getAuthentication() != null) {
            authType = cluster.getAuthentication().getType();
        }
        Boolean tlsEnabled = cluster.getTls() != null ? Boolean.TRUE : null;
        return new ClusterInfo(cluster.getAlias(), cluster.getBootstrapServers(),
            authType, tlsEnabled);
    }

    private List<MirrorInfo> extractMirrors(final KafkaMirrorMaker2 mm2) {
        if (mm2.getSpec() == null || mm2.getSpec().getMirrors() == null) {
            return null;
        }
        return mm2.getSpec().getMirrors().stream()
            .map(this::mapMirrorSpec)
            .toList();
    }

    private MirrorInfo mapMirrorSpec(final KafkaMirrorMaker2MirrorSpec mirror) {
        String sourceCluster = mirror.getSource() != null
            ? mirror.getSource().getAlias() : null;

        return new MirrorInfo(
            sourceCluster,
            mirror.getTopicsPattern(),
            mirror.getTopicsExcludePattern(),
            mirror.getGroupsPattern(),
            mirror.getGroupsExcludePattern(),
            mapConnectorSpec(mirror.getSourceConnector()),
            mapConnectorSpec(mirror.getCheckpointConnector()));
    }

    private ConnectorInfo mapConnectorSpec(final KafkaMirrorMaker2ConnectorSpec spec) {
        if (spec == null) {
            return null;
        }
        Boolean autoRestart = spec.getAutoRestart() != null
            ? spec.getAutoRestart().isEnabled() : null;
        String state = spec.getState() != null ? spec.getState().toValue() : null;
        Boolean paused = "paused".equals(state) ? Boolean.TRUE : null;
        return new ConnectorInfo(spec.getTasksMax(), state, autoRestart, paused);
    }

    private List<Map<String, Object>> extractConnectorStatuses(final KafkaMirrorMaker2 mm2) {
        if (mm2.getStatus() == null || mm2.getStatus().getConnectors() == null
            || mm2.getStatus().getConnectors().isEmpty()) {
            return null;
        }
        return mm2.getStatus().getConnectors();
    }

    private List<ConditionInfo> extractConditions(final KafkaMirrorMaker2 mm2) {
        if (mm2.getStatus() == null || mm2.getStatus().getConditions() == null
            || mm2.getStatus().getConditions().isEmpty()) {
            return null;
        }
        return mm2.getStatus().getConditions().stream()
            .map(c -> ConditionInfo.of(
                c.getType(), c.getStatus(), c.getReason(),
                c.getMessage(), c.getLastTransitionTime()))
            .toList();
    }

    private Instant extractCreationTime(final KafkaMirrorMaker2 mm2) {
        String ts = mm2.getMetadata().getCreationTimestamp();
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
