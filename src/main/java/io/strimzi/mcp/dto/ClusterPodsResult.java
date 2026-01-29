package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Structured result for cluster pods query.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClusterPodsResult(
    @JsonProperty("namespace") String namespace,
    @JsonProperty("cluster_name") String clusterName,
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
     * Information about a single pod.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PodInfo(
        @JsonProperty("name") String name,
        @JsonProperty("phase") String phase,
        @JsonProperty("ready") boolean ready,
        @JsonProperty("component") String component,
        @JsonProperty("restarts") int restarts,
        @JsonProperty("age_minutes") long ageMinutes
    ) {}

    public static ClusterPodsResult of(String namespace, String clusterName,
                                      List<PodInfo> pods, Map<String, Integer> componentBreakdown) {
        int totalPods = pods.size();
        int readyPods = (int) pods.stream().filter(PodInfo::ready).count();
        int failedPods = (int) pods.stream().filter(p -> "Failed".equalsIgnoreCase(p.phase())).count();

        String healthStatus = determineHealthStatus(totalPods, readyPods, failedPods);
        String message = generateMessage(namespace, clusterName, totalPods, readyPods, failedPods, componentBreakdown);

        return new ClusterPodsResult(
            namespace,
            clusterName,
            totalPods,
            readyPods,
            failedPods,
            componentBreakdown,
            pods,
            healthStatus,
            Instant.now(),
            message
        );
    }

    public static ClusterPodsResult empty(String namespace, String clusterName) {
        return new ClusterPodsResult(
            namespace,
            clusterName,
            0,
            0,
            0,
            Map.of(),
            List.of(),
            "UNKNOWN",
            Instant.now(),
            clusterName != null ?
                String.format("No Kafka pods found for cluster '%s' in namespace '%s'", clusterName, namespace) :
                String.format("No Kafka/Strimzi pods found in namespace '%s'", namespace)
        );
    }

    public static ClusterPodsResult error(String namespace, String clusterName, String errorMessage) {
        return new ClusterPodsResult(
            namespace,
            clusterName,
            0,
            0,
            0,
            null,
            null,
            "ERROR",
            Instant.now(),
            String.format("Error retrieving pods: %s", errorMessage)
        );
    }

    private static String determineHealthStatus(int total, int ready, int failed) {
        if (total == 0) return "UNKNOWN";
        if (failed > 0) return "DEGRADED";
        if (ready == total) return "HEALTHY";
        return "PARTIAL";
    }

    private static String generateMessage(String namespace, String clusterName,
                                        int total, int ready, int failed,
                                        Map<String, Integer> breakdown) {
        StringBuilder msg = new StringBuilder();

        if (clusterName != null) {
            msg.append(String.format("Cluster '%s' in namespace '%s': ", clusterName, namespace));
        } else {
            msg.append(String.format("All Kafka pods in namespace '%s': ", namespace));
        }

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
}