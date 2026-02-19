/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.service.common;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.streamshub.mcp.config.StrimziConstants;
import io.streamshub.mcp.dto.PodSummaryResponse;
import io.streamshub.mcp.dto.ToolError;
import io.streamshub.mcp.service.infra.StrimziDiscoveryService;
import io.streamshub.mcp.util.InputUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for cross-cutting Kubernetes resource inspection.
 * Handles generic pod description and other infrastructure-level queries
 * that are not specific to the Strimzi operator or Kafka cluster domain.
 */
@ApplicationScoped
public class PodsService {

    static final Set<String> FULL_SECTIONS = Set.of("full");
    static final Set<String> NO_SECTIONS = Set.of();
    private static final Logger LOG = Logger.getLogger(PodsService.class);

    @Inject
    KubernetesClient kubernetesClient;
    @Inject
    StrimziDiscoveryService discoveryService;

    PodsService() {
    }

    /**
     * Parse a comma-separated sections string into a normalized set.
     * Returns {@link #NO_SECTIONS} for null/blank input (summary by default).
     *
     * @param sections comma-separated sections string
     * @return normalized set of section names
     */
    public static Set<String> parseSections(String sections) {
        if (sections == null || sections.isBlank()) {
            return NO_SECTIONS;
        }
        return Arrays.stream(sections.split(","))
            .map(String::trim)
            .map(s -> s.toLowerCase(Locale.ENGLISH))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
    }

    /**
     * Get detailed description of a specific pod or the first operator pod (fallback).
     * Backward-compatible: defaults to summary-only when no sections specified.
     *
     * @param namespace the namespace to search in
     * @param podName   the name of the pod to describe
     * @return structured result containing pod description or ToolError for errors
     */
    public Object describePod(String namespace, String podName) {
        return describePod(namespace, podName, null);
    }

    /**
     * Get description of a specific pod with section filtering.
     * If podName is null/blank, delegates to the operator service to auto-discover operator pods.
     * If podName is provided, fetches and describes any pod by name.
     *
     * @param namespace the namespace to search in
     * @param podName   the name of the pod to describe
     * @param sections  comma-separated sections string (null/blank means summary only)
     * @return structured result containing pod description or ToolError for errors
     */
    public Object describePod(String namespace, String podName, String sections) {
        Set<String> parsedSections = parseSections(sections);

        // If no pod name specified, fall back to operator pod auto-discovery
        if (podName == null || podName.isBlank()) {
            return describePod(namespace, null, sections);
        }

        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);

        // If no namespace specified, try to auto-discover from Kafka clusters
        if (normalizedNamespace == null) {
            List<String> discoveredNamespaces = discoveryService.discoverKafkaClusters(null)
                .stream()
                .map(cluster -> cluster.namespace())
                .distinct()
                .sorted()
                .toList();

            if (discoveredNamespaces.isEmpty()) {
                return ToolError.of("No Strimzi resources found in any namespace. Please ensure Kafka is deployed. " +
                        "You can specify a namespace explicitly.");
            }

            if (discoveredNamespaces.size() == 1) {
                normalizedNamespace = discoveredNamespaces.getFirst();
                LOG.infof("Auto-discovered Strimzi in namespace: %s", normalizedNamespace);
            } else {
                String namespaceList = String.join(", ", discoveredNamespaces);
                return ToolError.of(String.format("Found Strimzi in multiple namespaces: %s. " +
                        "Please specify which one.", namespaceList));
            }
        }

        LOG.infof("ResourcesService: describePod (namespace=%s, podName=%s, sections=%s)",
            normalizedNamespace, podName, sections);

        try {
            Pod pod = kubernetesClient.pods()
                .inNamespace(normalizedNamespace)
                .withName(podName)
                .get();

            if (pod == null) {
                return PodSummaryResponse.notFound(normalizedNamespace, podName);
            }

            return extractPodDescribeResult(normalizedNamespace, pod, parsedSections);

        } catch (Exception e) {
            LOG.errorf(e, "Error describing pod '%s' in namespace: %s", podName, normalizedNamespace);
            return ToolError.of("Failed to describe pod '" + podName + "' in namespace " + normalizedNamespace, e);
        }
    }

    // Pod extraction helpers

    /**
     * Determine the Strimzi component type from pod name and labels.
     *
     * @param name   the pod name
     * @param labels the pod labels
     * @return the component type string
     */
    public String determineComponent(String name, Map<String, String> labels) {
        if (labels.containsKey(StrimziConstants.StrimziLabels.KIND_LABEL)) {
            return labels.get(StrimziConstants.StrimziLabels.KIND_LABEL).toLowerCase(Locale.ENGLISH);
        }
        if (name.contains("kafka-operator") || name.contains("strimzi-cluster-operator")) {
            return "operator";
        }
        if (name.contains("entity-operator")) {
            return "entity-operator";
        }
        if (name.contains("kafka") && !name.contains("zookeeper")) {
            return "kafka";
        }
        if (name.contains("zookeeper")) {
            return "zookeeper";
        }
        return "unknown";
    }

    /**
     * Extract a fully-detailed {@link PodSummaryResponse.PodInfo} from a Kubernetes Pod object.
     * Backward-compatible: includes all detail sections.
     *
     * @param namespace the namespace the pod belongs to
     * @param pod       the Kubernetes Pod object
     * @return detailed pod information
     */
    public PodSummaryResponse.PodInfo extractPodInfo(String namespace, Pod pod) {
        return extractPodInfo(namespace, pod, FULL_SECTIONS);
    }

    /**
     * Extract a summary-only {@link PodSummaryResponse.PodInfo} (6 core fields, no detail).
     *
     * @param namespace the namespace the pod belongs to
     * @param pod       the Kubernetes Pod object
     * @return summary pod information
     */
    public PodSummaryResponse.PodInfo extractPodSummary(String namespace, Pod pod) {
        return extractPodInfo(namespace, pod, NO_SECTIONS);
    }

    /**
     * Extract a {@link PodSummaryResponse.PodInfo} with detail controlled by the given sections set.
     *
     * Empty set means summary only.
     * "full" means everything.
     * Otherwise only requested sections: node, labels, env, resources, volumes, conditions.
     *
     * @param namespace the namespace the pod belongs to
     * @param pod       the Kubernetes Pod object
     * @param sections  the set of detail sections to include
     * @return pod information with requested detail level
     */
    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:NPathComplexity"})
    public PodSummaryResponse.PodInfo extractPodInfo(String namespace, Pod pod, Set<String> sections) {
        var metadata = pod.getMetadata();
        var spec = pod.getSpec();
        var podStatus = pod.getStatus();

        String podName = metadata.getName();
        String phase = podStatus != null ? podStatus.getPhase() : "Unknown";
        Map<String, String> podLabels = metadata.getLabels() != null ? metadata.getLabels() : Map.of();

        // Ready check
        boolean ready = false;
        if (podStatus != null && podStatus.getConditions() != null) {
            ready = podStatus.getConditions().stream()
                .anyMatch(c -> StrimziConstants.ConditionTypes.READY.equals(c.getType()) &&
                    StrimziConstants.ConditionStatuses.TRUE.equals(c.getStatus()));
        }

        // Component
        String component = determineComponent(podName, podLabels);

        // Restarts
        int restarts = 0;
        if (podStatus != null && podStatus.getContainerStatuses() != null) {
            restarts = podStatus.getContainerStatuses().stream()
                .mapToInt(ContainerStatus::getRestartCount)
                .sum();
        }

        // Calculate age
        long ageMinutes = 0;
        Instant startTime = null;
        if (podStatus != null && podStatus.getStartTime() != null) {
            startTime = Instant.parse(podStatus.getStartTime());
            ageMinutes = ChronoUnit.MINUTES.between(startTime, Instant.now());
        } else if (metadata.getCreationTimestamp() != null) {
            Instant created = Instant.parse(metadata.getCreationTimestamp());
            ageMinutes = ChronoUnit.MINUTES.between(created, Instant.now());
        }

        // Return summary only if no sections requested
        if (sections.isEmpty()) {
            return PodSummaryResponse.PodInfo.summary(podName, phase, ready, component, restarts, ageMinutes);
        }

        boolean full = sections.contains("full");

        // Node section
        String nodeName = null;
        String hostIP = null;
        String podIP = null;
        String serviceAccount = null;
        Instant startTimeDetail = null;
        if (full || sections.contains("node")) {
            nodeName = spec.getNodeName();
            hostIP = podStatus != null ? podStatus.getHostIP() : null;
            podIP = podStatus != null ? podStatus.getPodIP() : null;
            serviceAccount = spec.getServiceAccountName();
            startTimeDetail = startTime;
        }

        // Labels section
        Map<String, String> labels = null;
        Map<String, String> annotations = null;
        if (full || sections.contains("labels")) {
            labels = podLabels;
            annotations = metadata.getAnnotations() != null ? metadata.getAnnotations() : Map.of();
        }

        // Container sections: env, resources, volumes need containers
        boolean needContainers = full || sections.contains("env")
            || sections.contains("resources") || sections.contains("volumes");
        List<PodSummaryResponse.ContainerDetail> containers = null;
        if (needContainers) {
            containers = new ArrayList<>();
            if (spec.getContainers() != null) {
                for (Container container : spec.getContainers()) {
                    containers.add(extractContainerDetail(container, podStatus, sections));
                }
            }
            if (spec.getInitContainers() != null) {
                for (Container container : spec.getInitContainers()) {
                    containers.add(extractContainerDetail(container, podStatus, sections));
                }
            }
        }

        // Volumes section
        List<PodSummaryResponse.VolumeInfo> volumes = null;
        if (full || sections.contains("volumes")) {
            volumes = new ArrayList<>();
            if (spec.getVolumes() != null) {
                for (Volume volume : spec.getVolumes()) {
                    volumes.add(extractVolumeInfo(volume));
                }
            }
        }

        // Conditions section
        List<PodSummaryResponse.ConditionInfo> conditions = null;
        if (full || sections.contains("conditions")) {
            conditions = new ArrayList<>();
            if (podStatus != null && podStatus.getConditions() != null) {
                for (PodCondition condition : podStatus.getConditions()) {
                    conditions.add(new PodSummaryResponse.ConditionInfo(
                        condition.getType(),
                        condition.getStatus(),
                        condition.getReason()
                    ));
                }
            }
        }

        return PodSummaryResponse.PodInfo.detailed(
            podName, phase, ready, component, restarts, ageMinutes,
            nodeName, hostIP, podIP, serviceAccount, labels, annotations,
            containers, volumes, conditions, startTimeDetail
        );
    }

    /**
     * Extract a {@link PodSummaryResponse} wrapping a single pod's full details.
     * Backward-compatible: includes all detail sections.
     *
     * @param namespace the namespace the pod belongs to
     * @param pod       the Kubernetes Pod object
     * @return structured result containing pod details
     */
    public PodSummaryResponse extractPodDescribeResult(String namespace, Pod pod) {
        return extractPodDescribeResult(namespace, pod, FULL_SECTIONS);
    }

    /**
     * Extract a {@link PodSummaryResponse} wrapping a single pod's details filtered by sections.
     *
     * @param namespace the namespace the pod belongs to
     * @param pod       the Kubernetes Pod object
     * @param sections  the set of detail sections to include
     * @return structured result containing pod details
     */
    public PodSummaryResponse extractPodDescribeResult(String namespace, Pod pod, Set<String> sections) {
        PodSummaryResponse.PodInfo podInfo = extractPodInfo(namespace, pod, sections);
        return PodSummaryResponse.of(namespace, null, List.of(podInfo));
    }

    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    private PodSummaryResponse.ContainerDetail extractContainerDetail(Container container, PodStatus podStatus, Set<String> sections) {
        boolean full = sections.contains("full");

        // Extract env vars (only for env section)
        List<PodSummaryResponse.EnvVarInfo> envVars = null;
        if (full || sections.contains("env")) {
            envVars = new ArrayList<>();
            if (container.getEnv() != null) {
                for (EnvVar env : container.getEnv()) {
                    envVars.add(extractEnvVarInfo(env));
                }
            }
            if (envVars.isEmpty()) {
                envVars = null;
            }
        }

        // Extract resources (only for resources section)
        PodSummaryResponse.ResourceInfo resources = null;
        if (full || sections.contains("resources")) {
            if (container.getResources() != null) {
                var res = container.getResources();
                Map<String, String> requests = res.getRequests() != null
                    ? res.getRequests().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString()))
                    : null;
                Map<String, String> limits = res.getLimits() != null
                    ? res.getLimits().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString()))
                    : null;
                if (requests != null || limits != null) {
                    resources = new PodSummaryResponse.ResourceInfo(requests, limits);
                }
            }
        }

        // Extract volume mounts (only for volumes section)
        List<PodSummaryResponse.VolumeMountInfo> volumeMounts = null;
        if (full || sections.contains("volumes")) {
            volumeMounts = new ArrayList<>();
            if (container.getVolumeMounts() != null) {
                for (VolumeMount vm : container.getVolumeMounts()) {
                    volumeMounts.add(new PodSummaryResponse.VolumeMountInfo(
                        vm.getName(),
                        vm.getMountPath(),
                        Boolean.TRUE.equals(vm.getReadOnly())
                    ));
                }
            }
            if (volumeMounts.isEmpty()) {
                volumeMounts = null;
            }
        }

        // name, image, restartCount, state always included when containers are present
        Integer restartCount = null;
        String state = null;
        if (podStatus != null && podStatus.getContainerStatuses() != null) {
            for (ContainerStatus cs : podStatus.getContainerStatuses()) {
                if (cs.getName().equals(container.getName())) {
                    restartCount = cs.getRestartCount();
                    state = extractContainerState(cs.getState());
                    break;
                }
            }
        }

        return new PodSummaryResponse.ContainerDetail(
            container.getName(),
            container.getImage(),
            envVars,
            resources,
            volumeMounts,
            restartCount,
            state
        );
    }

    private PodSummaryResponse.EnvVarInfo extractEnvVarInfo(EnvVar env) {
        if (env.getValueFrom() != null) {
            EnvVarSource source = env.getValueFrom();
            String valueFrom;
            if (source.getFieldRef() != null) {
                valueFrom = "fieldRef:" + source.getFieldRef().getFieldPath();
            } else if (source.getSecretKeyRef() != null) {
                valueFrom = "secretKeyRef:" + source.getSecretKeyRef().getName() +
                    "/" + source.getSecretKeyRef().getKey();
            } else if (source.getConfigMapKeyRef() != null) {
                valueFrom = "configMapKeyRef:" + source.getConfigMapKeyRef().getName() +
                    "/" + source.getConfigMapKeyRef().getKey();
            } else if (source.getResourceFieldRef() != null) {
                valueFrom = "resourceFieldRef:" + source.getResourceFieldRef().getResource();
            } else {
                valueFrom = "unknown";
            }
            return new PodSummaryResponse.EnvVarInfo(env.getName(), null, valueFrom);
        }
        return new PodSummaryResponse.EnvVarInfo(env.getName(), env.getValue(), null);
    }

    private PodSummaryResponse.VolumeInfo extractVolumeInfo(Volume volume) {
        String type;
        if (volume.getConfigMap() != null) {
            type = "configMap: " + volume.getConfigMap().getName();
        } else if (volume.getSecret() != null) {
            type = "secret: " + volume.getSecret().getSecretName();
        } else if (volume.getEmptyDir() != null) {
            type = "emptyDir";
        } else if (volume.getHostPath() != null) {
            type = "hostPath: " + volume.getHostPath().getPath();
        } else if (volume.getPersistentVolumeClaim() != null) {
            type = "persistentVolumeClaim: " + volume.getPersistentVolumeClaim().getClaimName();
        } else if (volume.getDownwardAPI() != null) {
            type = "downwardAPI";
        } else if (volume.getProjected() != null) {
            type = "projected";
        } else {
            type = "other";
        }
        return new PodSummaryResponse.VolumeInfo(volume.getName(), type);
    }

    private String extractContainerState(ContainerState state) {
        if (state == null) return "unknown";
        if (state.getRunning() != null) return "running";
        if (state.getWaiting() != null) {
            return "waiting" + (state.getWaiting().getReason() != null
                ? ": " + state.getWaiting().getReason() : "");
        }
        if (state.getTerminated() != null) {
            return "terminated" + (state.getTerminated().getReason() != null
                ? ": " + state.getTerminated().getReason() : "");
        }
        return "unknown";
    }
}
