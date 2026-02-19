/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.service.infra;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.streamshub.mcp.config.StrimziConstants;
import io.streamshub.mcp.dto.StrimziOperatorLogsResponse;
import io.streamshub.mcp.dto.StrimziOperatorResponse;
import io.streamshub.mcp.dto.ToolError;
import io.streamshub.mcp.service.common.DeploymentService;
import io.streamshub.mcp.service.common.KubernetesResourceService;
import io.streamshub.mcp.service.common.PodsService;
import io.streamshub.mcp.util.InputUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Service for Strimzi Kafka operator operations.
 * Handles operator logs, status, and deployment information.
 */
@ApplicationScoped
public class StrimziOperatorService {

    private static final Logger LOG = Logger.getLogger(StrimziOperatorService.class);
    private static final int DEFAULT_LOG_LINES = 50;
    private static final int MAX_LOG_LINES = 200;

    /**
     * Ordered label strategies for finding operator deployments.
     */
    private static final List<Map.Entry<String, String>> OPERATOR_DEPLOYMENT_LABELS = List.of(
        new AbstractMap.SimpleImmutableEntry<>(
            StrimziConstants.StrimziLabels.KIND_LABEL, StrimziConstants.ComponentNames.CLUSTER_OPERATOR),
        new AbstractMap.SimpleImmutableEntry<>(
            StrimziConstants.KubernetesLabels.APP_LABEL, StrimziConstants.CommonValues.STRIMZI_CLUSTER_OPERATOR)
    );

    /**
     * Name-based predicate for identifying operator deployments as fallback.
     */
    private static final Predicate<String> OPERATOR_NAME_PATTERN = name ->
        name != null && name.contains(StrimziConstants.CommonValues.STRIMZI) && name.contains("operator");

    @Inject
    KubernetesClient kubernetesClient;
    @Inject
    StrimziDiscoveryService discoveryService;
    @Inject
    DeploymentService deploymentService;
    @Inject
    PodsService podsService;
    @Inject
    KubernetesResourceService resourceService;

    StrimziOperatorService() {
    }

    /**
     * Find Strimzi operator deployments using the generic deployment service.
     *
     * @param namespace the namespace to search in, or null for all namespaces
     * @return list of operator deployments found
     */
    public List<Deployment> findOperatorDeployments(String namespace) {
        for (Map.Entry<String, String> label : OPERATOR_DEPLOYMENT_LABELS) {
            List<Deployment> deployments = resourceService.queryResourcesByLabel(
                Deployment.class, namespace, label.getKey(), label.getValue());

            if (!deployments.isEmpty()) {
                String scopeDesc = namespace != null ? "namespace " + namespace : "all namespaces";
                LOG.debugf("Found operator deployments in %s via label %s=%s",
                    scopeDesc, label.getKey(), label.getValue());
                return deployments;
            }
        }

        try {
            List<Deployment> allDeployments = resourceService.queryResources(Deployment.class, namespace);

            List<Deployment> matchingDeployments = allDeployments.stream()
                .filter(d -> OPERATOR_NAME_PATTERN.test(d.getMetadata().getName()))
                .collect(Collectors.toList());

            if (!matchingDeployments.isEmpty()) {
                String scopeDesc = namespace != null ? "namespace " + namespace : "all namespaces";
                LOG.debugf("Found operator deployments in %s via name-based fallback", scopeDesc);
            }
            return matchingDeployments;

        } catch (Exception e) {
            String scopeDesc = namespace != null ? "namespace " + namespace : "all namespaces";
            LOG.debugf("Error finding operator deployments in %s: %s", scopeDesc, e.getMessage());
            return List.of();
        }
    }

    /**
     * Get operator logs with structured result.
     * If namespace is null, automatically discovers Strimzi operator across all namespaces.
     *
     * @param namespace the namespace to search in, or null for auto-discovery
     * @return structured result containing operator logs or ToolError for errors
     */
    public Object getOperatorLogs(String namespace) {
        return getOperatorLogs(namespace, DEFAULT_LOG_LINES);
    }

    /**
     * Get operator logs with custom line limit.
     *
     * @param namespace the namespace to search in, or null for auto-discovery
     * @param maxLines  the maximum number of log lines to retrieve
     * @return structured result containing operator logs or ToolError for errors
     */
    public Object getOperatorLogs(String namespace, int maxLines) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);

        // If no namespace specified, try to auto-discover Strimzi operator
        if (normalizedNamespace == null) {
            List<String> discoveredNamespaces = findOperatorDeployments(null)
                .stream()
                .map(deployment -> deployment.getMetadata().getNamespace())
                .distinct()
                .sorted()
                .toList();

            if (discoveredNamespaces.isEmpty()) {
                return ToolError.of("No Strimzi operator found in any namespace. Please ensure Strimzi is deployed. " +
                        "You can specify a namespace explicitly: 'Show me operator logs from the kafka namespace'");
            }

            if (discoveredNamespaces.size() == 1) {
                // Auto-use the single namespace found
                normalizedNamespace = discoveredNamespaces.getFirst();
                LOG.infof("Auto-discovered Strimzi operator in namespace: %s", normalizedNamespace);
            } else {
                // Multiple namespaces found, ask user to be specific
                String namespaceList = String.join(", ", discoveredNamespaces);
                return ToolError.of(String.format("Found Strimzi operator in multiple namespaces: %s. " +
                            "Please specify which one: 'Show me operator logs from the %s namespace'",
                        namespaceList, discoveredNamespaces.getFirst()));
            }
        }

        int limitedLines = Math.min(maxLines, MAX_LOG_LINES);

        LOG.infof("StrimziOperatorService: getOperatorLogs (namespace=%s, maxLines=%d)",
            normalizedNamespace, limitedLines);

        try {
            // Find Strimzi operator pods
            List<Pod> operatorPods = discoveryService.findOperatorPods(normalizedNamespace);

            if (operatorPods.isEmpty()) {
                return StrimziOperatorLogsResponse.notFound(normalizedNamespace);
            }

            StringBuilder allLogs = new StringBuilder();
            List<String> podNames = new ArrayList<>();
            boolean hasErrors = false;
            int errorCount = 0;
            int totalLogLines = 0;

            allLogs.append(String.format("=== Strimzi Operator Logs (namespace: %s) ===\n\n", normalizedNamespace));

            for (Pod pod : operatorPods) {
                String podName = pod.getMetadata().getName();
                String phase = pod.getStatus().getPhase();
                podNames.add(podName);

                allLogs.append(String.format("Pod: %s (Status: %s)\n", podName, phase));
                allLogs.append("----------------------------------------\n");

                try {
                    String podLogs = kubernetesClient.pods()
                        .inNamespace(normalizedNamespace)
                        .withName(podName)
                        .tailingLines(limitedLines)
                        .getLog();

                    if (podLogs == null || podLogs.trim().isEmpty()) {
                        allLogs.append("No logs available for this pod.\n\n");
                        continue;
                    }

                    // Analyze logs for errors and warnings
                    String[] logLines = podLogs.split("\n");
                    List<String> errorLines = new ArrayList<>();
                    List<String> recentLines = Arrays.asList(logLines);

                    for (String line : logLines) {
                        if (containsError(line)) {
                            errorLines.add(line);
                            errorCount++;
                            hasErrors = true;
                        }
                    }

                    totalLogLines += logLines.length;

                    // Show errors first if any
                    if (!errorLines.isEmpty()) {
                        allLogs.append("ERRORS/WARNINGS FOUND:\n");
                        for (String errorLine : errorLines) {
                            allLogs.append("  WARNING: ").append(errorLine).append("\n");
                        }
                        allLogs.append("\n");
                    }

                    // Show recent logs
                    allLogs.append("Recent logs:\n");
                    int startIndex = Math.max(0, recentLines.size() - 20);
                    for (int i = startIndex; i < recentLines.size(); i++) {
                        allLogs.append("  ").append(recentLines.get(i)).append("\n");
                    }
                    allLogs.append("\n");

                } catch (Exception e) {
                    allLogs.append(String.format("Error fetching logs for pod %s: %s\n\n", podName, e.getMessage()));
                    LOG.warnf("Error fetching logs for pod %s: %s", podName, e.getMessage());
                }
            }

            return StrimziOperatorLogsResponse.of(normalizedNamespace, allLogs.toString(), podNames,
                hasErrors, errorCount, totalLogLines);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving operator logs from namespace: %s", normalizedNamespace);
            return ToolError.of("Failed to retrieve operator logs from namespace " + normalizedNamespace, e);
        }
    }

    /**
     * Get operator status with structured result.
     *
     * @param namespace the namespace to search in, or null for auto-discovery
     * @return structured result containing operator status or ToolError for errors
     */
    public Object getOperatorStatus(String namespace) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);

        // If no namespace specified, try to auto-discover Strimzi operator
        if (normalizedNamespace == null) {
            List<String> discoveredNamespaces = findOperatorDeployments(null)
                .stream()
                .map(deployment -> deployment.getMetadata().getNamespace())
                .distinct()
                .sorted()
                .toList();

            if (discoveredNamespaces.isEmpty()) {
                LOG.warnf("No Strimzi operator found in any namespace");
                return ToolError.of("No Strimzi operator found in any namespace");
            }

            if (discoveredNamespaces.size() == 1) {
                // Auto-use the single namespace found
                normalizedNamespace = discoveredNamespaces.getFirst();
                LOG.infof("Auto-discovered Strimzi operator in namespace: %s", normalizedNamespace);
            } else {
                // Multiple namespaces found, cannot auto-select
                String namespaceList = String.join(", ", discoveredNamespaces);
                LOG.warnf("Found Strimzi operator in multiple namespaces: %s", namespaceList);
                return ToolError.of("Found Strimzi operator in multiple namespaces: " + namespaceList +
                    ". Please specify which one to use.");
            }
        }

        LOG.infof("StrimziOperatorService: getOperatorStatus (namespace=%s)", normalizedNamespace);

        try {
            List<Deployment> operatorDeployments = findOperatorDeployments(normalizedNamespace);

            if (operatorDeployments.isEmpty()) {
                LOG.warnf("No operator deployment found in namespace: %s", normalizedNamespace);
                return ToolError.of("No operator deployment found in namespace " + normalizedNamespace);
            }

            Deployment deployment = operatorDeployments.getFirst();
            String deploymentName = deployment.getMetadata().getName();
            int replicas = deployment.getSpec().getReplicas();
            int readyReplicas = deployment.getStatus().getReadyReplicas() != null ?
                deployment.getStatus().getReadyReplicas() : 0;
            boolean ready = readyReplicas > 0 && replicas == readyReplicas;

            String version = deploymentService.extractVersion(deployment);
            String image = deploymentService.extractImage(deployment);
            Long uptimeMinutes = deploymentService.calculateUptimeMinutes(deployment);
            String uptimeHours = uptimeMinutes != null ? String.format("%.1f", uptimeMinutes / 60.0) : "unknown";

            String status = ready ? "HEALTHY" : "DEGRADED";

            return StrimziOperatorResponse.forStatus(deploymentName, normalizedNamespace, ready,
                replicas, readyReplicas, version, image, uptimeHours, status);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving operator status from namespace: %s", normalizedNamespace);
            return ToolError.of("Failed to retrieve operator status from namespace " + normalizedNamespace, e);
        }
    }

    /**
     * Discover cluster operators across all namespaces or in a specific namespace.
     * When namespace is null, returns every operator on the cluster.
     * When namespace is specified, returns operators from that namespace only.
     *
     * @param namespace the namespace to search in, or null for all namespaces
     * @return list of operator information or ToolError for errors
     */
    public Object getClusterOperators(String namespace) {
        String normalizedNamespace = InputUtils.normalizeNamespace(namespace);

        LOG.infof("StrimziOperatorService: getClusterOperators (namespace=%s)", normalizedNamespace);

        try {
            List<Deployment> operatorDeployments;

            operatorDeployments = findOperatorDeployments(normalizedNamespace);

            return operatorDeployments.stream()
                .map(this::buildOperatorInfo)
                .toList();

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving cluster operators (namespace=%s)", normalizedNamespace);
            return ToolError.of("Failed to retrieve cluster operators from namespace " + normalizedNamespace, e);
        }
    }

    /**
     * Build operator information from deployment.
     */
    private StrimziOperatorResponse buildOperatorInfo(Deployment deployment) {
        String name = deployment.getMetadata().getName();
        String namespace = deployment.getMetadata().getNamespace();

        // Extract deployment status info
        Integer replicas = deployment.getSpec() != null ? deployment.getSpec().getReplicas() : null;
        Integer readyReplicas = deployment.getStatus() != null ? deployment.getStatus().getReadyReplicas() : null;

        boolean ready = replicas != null && replicas.equals(readyReplicas) && readyReplicas > 0;
        String status = ready ? "HEALTHY" : "DEGRADED";

        // Extract version from deployment
        String version = deploymentService.extractVersion(deployment);

        return StrimziOperatorResponse.forDiscovery(name, namespace, ready, replicas, readyReplicas, version, status);
    }

    private boolean containsError(String line) {
        String lowerLine = line.toLowerCase(Locale.ENGLISH);
        return lowerLine.contains("error") ||
            lowerLine.contains("exception") ||
            lowerLine.contains("failed") ||
            lowerLine.contains("warn");
    }
}
