package io.strimzi.mcp.service;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.mcp.dto.OperatorLogsResult;
import io.strimzi.mcp.dto.OperatorStatusResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Service for Strimzi Kafka operator operations.
 * Handles operator logs, status, and deployment information.
 */
@ApplicationScoped
public class StrimziOperatorService {

    private static final Logger LOG = Logger.getLogger(StrimziOperatorService.class);

    // Default limits for result sets
    private static final int DEFAULT_LOG_LINES = 50;
    private static final int MAX_LOG_LINES = 200;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    StrimziDiscoveryService discoveryService;

    /**
     * Get operator logs with structured result.
     * If namespace is null, automatically discovers Strimzi operator across all namespaces.
     */
    public OperatorLogsResult getOperatorLogs(String namespace) {
        return getOperatorLogs(namespace, DEFAULT_LOG_LINES);
    }

    /**
     * Get operator logs with custom line limit.
     */
    public OperatorLogsResult getOperatorLogs(String namespace, int maxLines) {
        String normalizedNamespace = discoveryService.normalizeNamespace(namespace);

        // If no namespace specified, try to auto-discover Strimzi operator
        if (normalizedNamespace == null) {
            List<String> discoveredNamespaces = discoveryService.discoverStrimziNamespaces();

            if (discoveredNamespaces.isEmpty()) {
                return OperatorLogsResult.error("not-found",
                    "No Strimzi operator found in any namespace. Please ensure Strimzi is deployed. " +
                    "You can specify a namespace explicitly: 'Show me operator logs from the kafka namespace'");
            }

            if (discoveredNamespaces.size() == 1) {
                // Auto-use the single namespace found
                normalizedNamespace = discoveredNamespaces.get(0);
                LOG.infof("Auto-discovered Strimzi operator in namespace: %s", normalizedNamespace);
            } else {
                // Multiple namespaces found, ask user to be specific
                String namespaceList = String.join(", ", discoveredNamespaces);
                return OperatorLogsResult.error("multiple-found",
                    String.format("Found Strimzi operator in multiple namespaces: %s. " +
                        "Please specify which one: 'Show me operator logs from the %s namespace'",
                        namespaceList, discoveredNamespaces.get(0)));
            }
        }

        int limitedLines = Math.min(maxLines, MAX_LOG_LINES);

        LOG.infof("StrimziOperatorService: getOperatorLogs (namespace=%s, maxLines=%d)",
                 normalizedNamespace, limitedLines);

        try {
            // Find Strimzi operator pods
            List<Pod> operatorPods = findOperatorPods(normalizedNamespace);

            if (operatorPods.isEmpty()) {
                return OperatorLogsResult.notFound(normalizedNamespace);
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

            return OperatorLogsResult.of(normalizedNamespace, allLogs.toString(), podNames,
                                       hasErrors, errorCount, totalLogLines);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving operator logs from namespace: %s", normalizedNamespace);
            return OperatorLogsResult.error(normalizedNamespace, e.getMessage());
        }
    }

    /**
     * Get operator status with structured result.
     */
    public OperatorStatusResult getOperatorStatus(String namespace) {
        String normalizedNamespace = discoveryService.normalizeNamespace(namespace);

        // If no namespace specified, try to auto-discover Strimzi operator
        if (normalizedNamespace == null) {
            List<String> discoveredNamespaces = discoveryService.discoverStrimziNamespaces();

            if (discoveredNamespaces.isEmpty()) {
                return OperatorStatusResult.error("not-found",
                    "No Strimzi operator found in any namespace. Please ensure Strimzi is deployed. " +
                    "You can specify a namespace explicitly: 'Check operator status in the kafka namespace'");
            }

            if (discoveredNamespaces.size() == 1) {
                // Auto-use the single namespace found
                normalizedNamespace = discoveredNamespaces.get(0);
                LOG.infof("Auto-discovered Strimzi operator in namespace: %s", normalizedNamespace);
            } else {
                // Multiple namespaces found, ask user to be specific
                String namespaceList = String.join(", ", discoveredNamespaces);
                return OperatorStatusResult.error("multiple-found",
                    String.format("Found Strimzi operator in multiple namespaces: %s. " +
                        "Please specify which one: 'Check operator status in the %s namespace'",
                        namespaceList, discoveredNamespaces.get(0)));
            }
        }

        LOG.infof("StrimziOperatorService: getOperatorStatus (namespace=%s)", normalizedNamespace);

        try {
            List<Deployment> operatorDeployments = findOperatorDeployments(normalizedNamespace);

            if (operatorDeployments.isEmpty()) {
                return OperatorStatusResult.notFound(normalizedNamespace);
            }

            Deployment deployment = operatorDeployments.get(0);
            String deploymentName = deployment.getMetadata().getName();
            int replicas = deployment.getSpec().getReplicas();
            int readyReplicas = deployment.getStatus().getReadyReplicas() != null ?
                deployment.getStatus().getReadyReplicas() : 0;
            boolean ready = readyReplicas > 0 && replicas == readyReplicas;

            String version = extractVersionFromImage(deployment);
            String image = extractImageName(deployment);
            Long uptimeMinutes = calculateUptimeMinutes(deployment);

            return OperatorStatusResult.of(normalizedNamespace, deploymentName, ready,
                                         replicas, readyReplicas, version, image, uptimeMinutes);

        } catch (Exception e) {
            LOG.errorf(e, "Error retrieving operator status from namespace: %s", normalizedNamespace);
            return OperatorStatusResult.error(normalizedNamespace, e.getMessage());
        }
    }

    // Private helper methods

    private List<Pod> findOperatorPods(String namespace) {
        List<Pod> operatorPods = kubernetesClient.pods()
            .inNamespace(namespace)
            .withLabel("name", "strimzi-cluster-operator")
            .list()
            .getItems();

        if (operatorPods.isEmpty()) {
            // Try alternative selectors
            operatorPods = kubernetesClient.pods()
                .inNamespace(namespace)
                .list()
                .getItems()
                .stream()
                .filter(pod -> {
                    String name = pod.getMetadata().getName();
                    return name.contains("strimzi") && name.contains("operator");
                })
                .toList();
        }

        return operatorPods;
    }

    private List<Deployment> findOperatorDeployments(String namespace) {
        return kubernetesClient.apps().deployments()
            .inNamespace(namespace)
            .list()
            .getItems()
            .stream()
            .filter(deployment -> {
                String name = deployment.getMetadata().getName();
                return name.contains("strimzi") && name.contains("operator");
            })
            .toList();
    }

    private boolean containsError(String line) {
        String lowerLine = line.toLowerCase();
        return lowerLine.contains("error") ||
               lowerLine.contains("exception") ||
               lowerLine.contains("failed") ||
               lowerLine.contains("warn");
    }

    private String extractVersionFromImage(Deployment deployment) {
        if (deployment.getSpec().getTemplate().getSpec().getContainers() != null &&
            !deployment.getSpec().getTemplate().getSpec().getContainers().isEmpty()) {
            String image = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getImage();
            if (image.contains(":")) {
                return image.substring(image.lastIndexOf(":") + 1);
            }
        }
        return "unknown";
    }

    private String extractImageName(Deployment deployment) {
        if (deployment.getSpec().getTemplate().getSpec().getContainers() != null &&
            !deployment.getSpec().getTemplate().getSpec().getContainers().isEmpty()) {
            return deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getImage();
        }
        return "unknown";
    }

    private Long calculateUptimeMinutes(Deployment deployment) {
        try {
            if (deployment.getMetadata().getCreationTimestamp() != null) {
                Instant created = Instant.parse(deployment.getMetadata().getCreationTimestamp());
                return ChronoUnit.MINUTES.between(created, Instant.now());
            }
        } catch (Exception e) {
            LOG.debugf("Could not calculate uptime for deployment %s: %s",
                      deployment.getMetadata().getName(), e.getMessage());
        }
        return null;
    }
}