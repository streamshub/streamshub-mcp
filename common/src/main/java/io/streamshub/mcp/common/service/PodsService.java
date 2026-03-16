/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.PodLogsResult;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import io.streamshub.mcp.common.util.InputUtils;
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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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
     * Get detailed description of a specific pod.
     * Defaults to summary-only when no sections specified.
     *
     * @param namespace the namespace to search in
     * @param podName   the name of the pod to describe
     * @return structured result containing pod description
     */
    public PodSummaryResponse describePod(String namespace, String podName) {
        return describePod(namespace, podName, null);
    }

    /**
     * Get description of a specific pod with section filtering.
     *
     * @param namespace the namespace to search in
     * @param podName   the name of the pod to describe
     * @param sections  comma-separated sections string (null/blank means summary only)
     * @return structured result containing pod description
     */
    public PodSummaryResponse describePod(String namespace, String podName, String sections) {
        Set<String> parsedSections = parseSections(sections);

        String normalizedNamespace = InputUtils.normalizeInput(namespace);

        if (normalizedNamespace == null) {
            throw new IllegalArgumentException("Namespace is required");
        }

        if (podName == null || podName.isBlank()) {
            throw new IllegalArgumentException("Pod name is required");
        }

        LOG.infof("Describing pod=%s in namespace=%s (sections=%s)",
            podName, normalizedNamespace, sections);

        Pod pod = kubernetesClient.pods()
            .inNamespace(normalizedNamespace)
            .withName(podName)
            .get();

        if (pod == null) {
            return PodSummaryResponse.notFound(normalizedNamespace, podName);
        }

        return extractPodDescribeResult(normalizedNamespace, pod, parsedSections);
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
     * <p>
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
        String phase = podStatus != null ? podStatus.getPhase() : KubernetesConstants.PodPhases.UNKNOWN;
        Map<String, String> podLabels = metadata.getLabels() != null ? metadata.getLabels() : Map.of();

        // Ready check
        boolean ready = false;
        if (podStatus != null && podStatus.getConditions() != null) {
            ready = podStatus.getConditions().stream()
                .anyMatch(c -> KubernetesConstants.Conditions.TYPE_READY.equals(c.getType()) &&
                    KubernetesConstants.Conditions.STATUS_TRUE.equals(c.getStatus()));
        }

        // Component - basic component detection from pod name and labels
        String component = determineComponentFromPodInfo(podName, podLabels);

        // Restarts
        int restarts = 0;
        if (podStatus != null && podStatus.getContainerStatuses() != null) {
            restarts = podStatus.getContainerStatuses().stream()
                .mapToInt(ContainerStatus::getRestartCount)
                .sum();
        }

        // Last termination info from container statuses
        String lastTerminationReason = extractLastTerminationReason(podStatus);
        Instant lastTerminationTime = extractLastTerminationTime(podStatus);

        // Pod-level resource summary from first container spec
        PodSummaryResponse.ResourceInfo podResources = extractPodResources(spec.getContainers());

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

        // Return enriched summary if no detail sections requested
        if (sections.isEmpty()) {
            return PodSummaryResponse.PodInfo.enrichedSummary(podName, phase, ready, component,
                restarts, ageMinutes, null, lastTerminationReason, lastTerminationTime, podResources);
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
            null, lastTerminationReason, lastTerminationTime, podResources,
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
        return PodSummaryResponse.of(namespace, List.of(podInfo));
    }

    /**
     * Extract the last termination reason from the most recently terminated container.
     * Checks {@code lastState} on all container statuses for terminated state reasons.
     *
     * @param podStatus the pod status
     * @return the termination reason, or null if no container was terminated
     */
    private String extractLastTerminationReason(PodStatus podStatus) {
        if (podStatus == null || podStatus.getContainerStatuses() == null) {
            return null;
        }
        return podStatus.getContainerStatuses().stream()
            .filter(cs -> cs.getLastState() != null && cs.getLastState().getTerminated() != null)
            .map(cs -> cs.getLastState().getTerminated().getReason())
            .filter(reason -> reason != null && !reason.isEmpty())
            .findFirst()
            .orElse(null);
    }

    /**
     * Extract the last termination time from the most recently terminated container.
     * Checks {@code lastState} on all container statuses for terminated state finish time.
     *
     * @param podStatus the pod status
     * @return the termination time, or null if no container was terminated
     */
    private Instant extractLastTerminationTime(PodStatus podStatus) {
        if (podStatus == null || podStatus.getContainerStatuses() == null) {
            return null;
        }
        return podStatus.getContainerStatuses().stream()
            .filter(cs -> cs.getLastState() != null && cs.getLastState().getTerminated() != null)
            .map(cs -> cs.getLastState().getTerminated())
            .filter(t -> t.getFinishedAt() != null)
            .map(ContainerStateTerminated::getFinishedAt)
            .map(Instant::parse)
            .findFirst()
            .orElse(null);
    }

    /**
     * Extract aggregated resource requests and limits from the pod's containers.
     * Returns a flat structure with {@code cpuRequest}, {@code cpuLimit},
     * {@code memoryRequest}, and {@code memoryLimit} fields.
     *
     * @param containers the list of containers from the pod spec
     * @return a ResourceInfo with flattened resource entries, or null if no resources defined
     */
    private PodSummaryResponse.ResourceInfo extractPodResources(List<Container> containers) {
        if (containers == null || containers.isEmpty()) {
            return null;
        }
        String cpuRequest = null;
        String cpuLimit = null;
        String memoryRequest = null;
        String memoryLimit = null;

        for (Container container : containers) {
            if (container.getResources() != null) {
                if (container.getResources().getRequests() != null) {
                    var reqs = container.getResources().getRequests();
                    if (reqs.get("cpu") != null) {
                        cpuRequest = reqs.get("cpu").toString();
                    }
                    if (reqs.get("memory") != null) {
                        memoryRequest = reqs.get("memory").toString();
                    }
                }
                if (container.getResources().getLimits() != null) {
                    var lims = container.getResources().getLimits();
                    if (lims.get("cpu") != null) {
                        cpuLimit = lims.get("cpu").toString();
                    }
                    if (lims.get("memory") != null) {
                        memoryLimit = lims.get("memory").toString();
                    }
                }
            }
        }

        if (cpuRequest == null && cpuLimit == null && memoryRequest == null && memoryLimit == null) {
            return null;
        }
        return new PodSummaryResponse.ResourceInfo(cpuRequest, cpuLimit, memoryRequest, memoryLimit);
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
            resources = extractContainerResources(container);
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

    private PodSummaryResponse.ResourceInfo extractContainerResources(Container container) {
        if (container.getResources() == null) {
            return null;
        }
        var res = container.getResources();
        String cpuReq = null;
        String cpuLim = null;
        String memReq = null;
        String memLim = null;

        if (res.getRequests() != null) {
            if (res.getRequests().get("cpu") != null) {
                cpuReq = res.getRequests().get("cpu").toString();
            }
            if (res.getRequests().get("memory") != null) {
                memReq = res.getRequests().get("memory").toString();
            }
        }
        if (res.getLimits() != null) {
            if (res.getLimits().get("cpu") != null) {
                cpuLim = res.getLimits().get("cpu").toString();
            }
            if (res.getLimits().get("memory") != null) {
                memLim = res.getLimits().get("memory").toString();
            }
        }

        if (cpuReq == null && cpuLim == null && memReq == null && memLim == null) {
            return null;
        }
        return new PodSummaryResponse.ResourceInfo(cpuReq, cpuLim, memReq, memLim);
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
                valueFrom = KubernetesConstants.ContainerStates.UNKNOWN;
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
        if (state == null) return KubernetesConstants.ContainerStates.UNKNOWN;
        if (state.getRunning() != null) return KubernetesConstants.ContainerStates.RUNNING;
        if (state.getWaiting() != null) {
            return KubernetesConstants.ContainerStates.WAITING + (state.getWaiting().getReason() != null
                ? ": " + state.getWaiting().getReason() : "");
        }
        if (state.getTerminated() != null) {
            return KubernetesConstants.ContainerStates.TERMINATED + (state.getTerminated().getReason() != null
                ? ": " + state.getTerminated().getReason() : "");
        }
        return KubernetesConstants.ContainerStates.UNKNOWN;
    }

    /**
     * Determine component type from pod name and labels using general patterns.
     * This provides basic component detection without technology-specific assumptions.
     *
     * @param name   the pod name
     * @param labels the pod labels
     * @return the component type string
     */
    private String determineComponentFromPodInfo(String name, Map<String, String> labels) {
        // Check common application labels first
        if (labels != null) {
            String appName = labels.get(KubernetesConstants.Labels.APP_NAME);
            if (appName != null) {
                return appName.toLowerCase(Locale.ENGLISH);
            }

            String app = labels.get(KubernetesConstants.Labels.APP);
            if (app != null) {
                return app.toLowerCase(Locale.ENGLISH);
            }
        }

        // Fallback to basic pod name pattern detection
        if (name != null) {
            String lowerName = name.toLowerCase(Locale.ENGLISH);
            if (lowerName.contains("operator")) {
                return "operator";
            }
            if (lowerName.contains("controller")) {
                return "controller";
            }
            if (lowerName.contains("manager")) {
                return "manager";
            }
            if (lowerName.contains("worker")) {
                return "worker";
            }
            if (lowerName.contains("server")) {
                return "server";
            }
        }

        return "app";
    }

    /**
     * Collect logs from a list of pods with optional filtering and time/tail parameters.
     * Requests {@code tailLines + 1} lines to detect whether more logs exist beyond
     * the requested limit, then trims to the requested count.
     *
     * <p>Supported filter values:</p>
     * <ul>
     *   <li>{@code "errors"} - only lines containing ERROR or EXCEPTION</li>
     *   <li>{@code "warnings"} - lines containing ERROR, EXCEPTION, or WARN</li>
     *   <li>Any other non-blank string is treated as a regex pattern</li>
     *   <li>{@code null} or blank - no filtering, return all lines</li>
     * </ul>
     *
     * @param namespace    the namespace of the pods
     * @param pods         the list of pods to collect logs from
     * @param filter       optional filter: "errors", "warnings", or a regex pattern
     * @param sinceSeconds optional time range in seconds to retrieve logs from
     * @param tailLines    number of lines to tail per pod
     * @param previous     optional flag to retrieve logs from previous container instance
     * @return the aggregated and optionally filtered log result
     */
    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    public PodLogsResult collectLogs(final String namespace, final List<Pod> pods, final String filter,
                                     final Integer sinceSeconds, final int tailLines,
                                     final Boolean previous) {
        List<String> podNames = pods.stream()
            .map(pod -> pod.getMetadata().getName())
            .toList();

        Pattern filterPattern = compileLogFilter(filter);

        StringBuilder allLogs = new StringBuilder();
        int errorCount = 0;
        int totalLines = 0;
        int filteredLines = 0;
        boolean hasMore = false;

        for (Pod pod : pods) {
            String podName = pod.getMetadata().getName();
            try {
                String podLog = fetchPodLog(namespace, podName, tailLines, sinceSeconds, previous);

                if (podLog != null && !podLog.isEmpty()) {
                    String[] lines = podLog.split("\n");

                    if (lines.length > tailLines) {
                        hasMore = true;
                        String[] trimmed = new String[tailLines];
                        System.arraycopy(lines, lines.length - tailLines, trimmed, 0, tailLines);
                        lines = trimmed;
                    }

                    totalLines += lines.length;

                    StringBuilder podOutput = new StringBuilder();
                    for (String line : lines) {
                        String upperLine = line.toUpperCase(Locale.ENGLISH);
                        if (upperLine.contains("ERROR") || upperLine.contains("EXCEPTION")) {
                            errorCount++;
                        }
                        if (filterPattern == null || filterPattern.matcher(line).find()) {
                            podOutput.append(line).append("\n");
                            filteredLines++;
                        }
                    }

                    if (!podOutput.isEmpty()) {
                        allLogs.append("=== Pod: ").append(podName).append(" ===\n");
                        allLogs.append(podOutput);
                    }
                }
            } catch (Exception e) {
                LOG.debugf("Could not retrieve logs from pod %s: %s", podName, e.getMessage());
                allLogs.append("=== Pod: ").append(podName).append(" === (logs unavailable)\n");
            }
        }

        return new PodLogsResult(podNames, allLogs.toString(), errorCount, totalLines, filteredLines, hasMore);
    }

    /**
     * Fetch logs from a single pod, applying tail, sinceSeconds, and previous options.
     *
     * @param namespace      the namespace of the pod
     * @param podName        the name of the pod
     * @param tailLines      the number of lines to tail (plus one for hasMore detection)
     * @param sinceSeconds   optional time range in seconds
     * @param previous       optional flag for previous container logs
     * @return the raw log string
     */
    private String fetchPodLog(final String namespace, final String podName,
                               final int tailLines, final Integer sinceSeconds,
                               final Boolean previous) {
        var podResource = kubernetesClient.pods()
            .inNamespace(namespace)
            .withName(podName);

        if (Boolean.TRUE.equals(previous)) {
            return podResource.terminated()
                .tailingLines(tailLines + 1)
                .getLog();
        }

        if (sinceSeconds != null) {
            return podResource.sinceSeconds(sinceSeconds)
                .tailingLines(tailLines + 1)
                .getLog();
        }

        return podResource.tailingLines(tailLines + 1).getLog();
    }

    private Pattern compileLogFilter(final String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        String normalized = filter.trim().toLowerCase(Locale.ENGLISH);
        if ("errors".equals(normalized)) {
            return Pattern.compile("(?i)(ERROR|EXCEPTION)");
        }
        if ("warnings".equals(normalized)) {
            return Pattern.compile("(?i)(ERROR|EXCEPTION|WARN)");
        }
        try {
            return Pattern.compile(filter.trim());
        } catch (PatternSyntaxException e) {
            LOG.warnf("Invalid log filter regex '%s', returning unfiltered logs: %s", filter, e.getMessage());
            return null;
        }
    }
}
