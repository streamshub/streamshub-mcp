/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.config.KubernetesConstants;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Response containing pod summary and health information.
 * Generic pod summary that can be used for any set of pods.
 * Avoids naming conflicts with Kubernetes API classes.
 *
 * @param namespace          the Kubernetes namespace
 * @param totalPods          the total number of pods
 * @param readyPods          the number of ready pods
 * @param failedPods         the number of failed pods
 * @param componentBreakdown a map of component types to their pod counts
 * @param pods               the list of pod information
 * @param healthStatus       the overall health status
 * @param timestamp          the time this result was generated
 * @param message            a human-readable message describing the result
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PodSummaryResponse(
    @JsonProperty("namespace") String namespace,
    @JsonProperty("total_pods") int totalPods,
    @JsonProperty("ready_pods") int readyPods,
    @JsonProperty("failed_pods") int failedPods,
    @JsonProperty("component_breakdown") Map<String, Integer> componentBreakdown,
    @JsonProperty("pods") List<PodInfo> pods,
    @JsonProperty("health_status") String healthStatus,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a successful result with pod information.
     *
     * @param namespace the Kubernetes namespace
     * @param pods      the list of pod information
     * @return a PodSummaryResponse with the pod data
     */
    public static PodSummaryResponse of(String namespace, List<PodInfo> pods) {
        int totalPods = pods.size();
        int readyPods = (int) pods.stream().filter(PodInfo::ready).count();
        int failedPods = (int) pods.stream().filter(p -> KubernetesConstants.PodPhases.FAILED.equalsIgnoreCase(p.phase())).count();

        Map<String, Integer> componentBreakdown = pods.stream()
            .collect(Collectors.groupingBy(
                PodInfo::component,
                Collectors.collectingAndThen(Collectors.counting(), Math::toIntExact)
            ));

        String healthStatus = determineHealthStatus(totalPods, readyPods, failedPods);
        String message = generateMessage(namespace, totalPods, readyPods, failedPods, componentBreakdown);

        return new PodSummaryResponse(
            namespace, totalPods, readyPods, failedPods,
            componentBreakdown, pods, healthStatus, Instant.now(), message
        );
    }

    /**
     * Creates an empty result when no pods are found.
     *
     * @param namespace the Kubernetes namespace
     * @return an empty PodSummaryResponse
     */
    public static PodSummaryResponse empty(String namespace) {
        return new PodSummaryResponse(
            namespace, 0, 0, 0,
            Map.of(), List.of(), KubernetesConstants.HealthStatus.UNKNOWN, Instant.now(),
            String.format("No pods found in namespace '%s'", namespace)
        );
    }


    /**
     * Creates a not-found result when a specific pod does not exist.
     *
     * @param namespace the Kubernetes namespace
     * @param podName   the name of the pod that was not found
     * @return a not-found PodSummaryResponse
     */
    public static PodSummaryResponse notFound(String namespace, String podName) {
        return new PodSummaryResponse(
            namespace, 0, 0, 0,
            null, null, KubernetesConstants.HealthStatus.NOT_FOUND, Instant.now(),
            String.format("Pod '%s' not found in namespace '%s'", podName, namespace)
        );
    }

    private static String determineHealthStatus(int total, int ready, int failed) {
        if (total == 0) return KubernetesConstants.HealthStatus.UNKNOWN;
        if (failed > 0) return KubernetesConstants.HealthStatus.DEGRADED;
        if (ready == total) return KubernetesConstants.HealthStatus.HEALTHY;
        return KubernetesConstants.HealthStatus.PARTIAL;
    }

    private static String generateMessage(String namespace, int total, int ready, int failed,
                                          Map<String, Integer> breakdown) {
        StringBuilder msg = new StringBuilder();

        msg.append(String.format("Pods in namespace '%s': ", namespace));
        msg.append(String.format("%d total pods, %d ready, %d failed", total, ready, failed));

        if (!breakdown.isEmpty()) {
            msg.append(" (");
            breakdown.entrySet().stream()
                .map(e -> String.format("%d %s", e.getValue(), e.getKey()))
                .reduce((a, b) -> a + ", " + b)
                .ifPresent(msg::append);
            msg.append(")");
        }

        return msg.toString();
    }

    /**
     * Information about a single pod -- summary fields are always set,
     * detail fields are nullable and omitted from JSON when null.
     *
     * @param name           the pod name
     * @param phase          the pod phase
     * @param ready          whether the pod is ready
     * @param component      the component type
     * @param restarts       the number of restarts
     * @param ageMinutes     the pod age in minutes
     * @param nodeName       the node the pod is running on
     * @param hostIP         the host IP address
     * @param podIP          the pod IP address
     * @param serviceAccount the service account used by the pod
     * @param labels         the pod labels
     * @param annotations    the pod annotations
     * @param containers     the list of container details
     * @param volumes        the list of volume information
     * @param conditions     the list of pod conditions
     * @param startTime      the time the pod started
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PodInfo(
        // Summary fields (always present)
        @JsonProperty("name") String name,
        @JsonProperty("phase") String phase,
        @JsonProperty("ready") boolean ready,
        @JsonProperty("component") String component,
        @JsonProperty("restarts") int restarts,
        @JsonProperty("age_minutes") long ageMinutes,
        // Detail fields (nullable)
        @JsonProperty("node_name") String nodeName,
        @JsonProperty("host_ip") String hostIP,
        @JsonProperty("pod_ip") String podIP,
        @JsonProperty("service_account") String serviceAccount,
        @JsonProperty("labels") Map<String, String> labels,
        @JsonProperty("annotations") Map<String, String> annotations,
        @JsonProperty("containers") List<ContainerDetail> containers,
        @JsonProperty("volumes") List<VolumeInfo> volumes,
        @JsonProperty("conditions") List<ConditionInfo> conditions,
        @JsonProperty("start_time") Instant startTime
    ) {
        /**
         * Lightweight summary -- only the 6 core fields, detail fields are null.
         *
         * @param name       the pod name
         * @param phase      the pod phase
         * @param ready      whether the pod is ready
         * @param component  the component type
         * @param restarts   the number of restarts
         * @param ageMinutes the pod age in minutes
         * @return a summary PodInfo
         */
        public static PodInfo summary(String name, String phase, boolean ready,
                                      String component, int restarts, long ageMinutes) {
            return new PodInfo(name, phase, ready, component, restarts, ageMinutes,
                null, null, null, null, null, null, null, null, null, null);
        }

        /**
         * Full detail -- all fields populated.
         *
         * @param name           the pod name
         * @param phase          the pod phase
         * @param ready          whether the pod is ready
         * @param component      the component type
         * @param restarts       the number of restarts
         * @param ageMinutes     the pod age in minutes
         * @param nodeName       the node the pod is running on
         * @param hostIP         the host IP address
         * @param podIP          the pod IP address
         * @param serviceAccount the service account
         * @param labels         the pod labels
         * @param annotations    the pod annotations
         * @param containers     the container details
         * @param volumes        the volume information
         * @param conditions     the pod conditions
         * @param startTime      the pod start time
         * @return a detailed PodInfo
         */
        @SuppressWarnings("checkstyle:ParameterNumber")
        public static PodInfo detailed(String name, String phase, boolean ready,
                                       String component, int restarts, long ageMinutes,
                                       String nodeName, String hostIP, String podIP,
                                       String serviceAccount,
                                       Map<String, String> labels, Map<String, String> annotations,
                                       List<ContainerDetail> containers, List<VolumeInfo> volumes,
                                       List<ConditionInfo> conditions, Instant startTime) {
            return new PodInfo(name, phase, ready, component, restarts, ageMinutes,
                nodeName, hostIP, podIP, serviceAccount, labels, annotations,
                containers, volumes, conditions, startTime);
        }
    }

    /**
     * Detail information about a container within a pod.
     *
     * @param name         the container name
     * @param image        the container image
     * @param envVars      the environment variables
     * @param resources    the resource requests and limits
     * @param volumeMounts the volume mounts
     * @param restartCount the number of container restarts
     * @param state        the container state
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContainerDetail(
        @JsonProperty("name") String name,
        @JsonProperty("image") String image,
        @JsonProperty("env_vars") List<EnvVarInfo> envVars,
        @JsonProperty("resources") ResourceInfo resources,
        @JsonProperty("volume_mounts") List<VolumeMountInfo> volumeMounts,
        @JsonProperty("restart_count") Integer restartCount,
        @JsonProperty("state") String state
    ) {
    }

    /**
     * Information about an environment variable.
     *
     * @param name      the environment variable name
     * @param value     the environment variable value
     * @param valueFrom the source reference if the value comes from a reference
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnvVarInfo(
        @JsonProperty("name") String name,
        @JsonProperty("value") String value,
        @JsonProperty("value_from") String valueFrom
    ) {
    }

    /**
     * Information about container resource requests and limits.
     *
     * @param requests the resource requests
     * @param limits   the resource limits
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourceInfo(
        @JsonProperty("requests") Map<String, String> requests,
        @JsonProperty("limits") Map<String, String> limits
    ) {
    }

    /**
     * Information about a volume mount.
     *
     * @param name      the volume mount name
     * @param mountPath the mount path in the container
     * @param readOnly  whether the mount is read-only
     */
    public record VolumeMountInfo(
        @JsonProperty("name") String name,
        @JsonProperty("mount_path") String mountPath,
        @JsonProperty("read_only") boolean readOnly
    ) {
    }

    /**
     * Information about a pod volume.
     *
     * @param name the volume name
     * @param type the volume type
     */
    public record VolumeInfo(
        @JsonProperty("name") String name,
        @JsonProperty("type") String type
    ) {
    }

    /**
     * Information about a pod condition.
     *
     * @param type   the condition type
     * @param status the condition status
     * @param reason the reason for the condition
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConditionInfo(
        @JsonProperty("type") String type,
        @JsonProperty("status") String status,
        @JsonProperty("reason") String reason
    ) {
    }
}
