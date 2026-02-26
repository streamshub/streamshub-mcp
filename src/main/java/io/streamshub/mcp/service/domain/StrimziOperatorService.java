/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.service.domain;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.config.Constants;
import io.streamshub.mcp.dto.StrimziOperatorLogsResponse;
import io.streamshub.mcp.dto.StrimziOperatorResponse;
import io.streamshub.mcp.service.common.DeploymentService;
import io.streamshub.mcp.service.common.KubernetesResourceService;
import io.streamshub.mcp.util.InputUtils;
import io.strimzi.api.ResourceLabels;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;

/**
 * Service for Strimzi operator operations.
 */
@ApplicationScoped
public class StrimziOperatorService {

    private static final Logger LOG = Logger.getLogger(StrimziOperatorService.class);
    private static final int DEFAULT_LOG_TAIL_LINES = 100;
    private static final double MINUTES_PER_HOUR = 60.0;

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    DeploymentService deploymentService;

    StrimziOperatorService() {
    }

    /**
     * List Strimzi cluster operators, optionally filtered by namespace.
     *
     * @param namespace the namespace, or null for all namespaces
     * @return list of operator responses
     */
    public List<StrimziOperatorResponse> listOperators(final String namespace) {
        String ns = InputUtils.normalizeInput(namespace);
        LOG.infof("Listing Strimzi operators (namespace=%s)", ns != null ? ns : "all");

        List<Deployment> operators;
        if (ns != null) {
            operators = k8sService.queryResourcesByLabel(
                Deployment.class, ns, Constants.Kubernetes.Labels.APP, Constants.Strimzi.Operator.APP_LABEL_VALUE);
        } else {
            operators = k8sService.queryResourcesByLabelInAnyNamespace(
                Deployment.class, Constants.Kubernetes.Labels.APP, Constants.Strimzi.Operator.APP_LABEL_VALUE);
        }

        return operators.stream()
            .map(this::createOperatorResponse)
            .toList();
    }

    /**
     * Get specific Strimzi operator details.
     *
     * @param namespace    the namespace, or null for auto-discovery
     * @param operatorName the operator deployment name
     * @return the operator response
     */
    public StrimziOperatorResponse getOperator(final String namespace, final String operatorName) {
        String ns = InputUtils.normalizeInput(namespace);

        if (operatorName == null) {
            throw new ToolCallException("Operator name is required");
        }

        LOG.infof("Getting Strimzi operator name=%s in namespace=%s", operatorName, ns != null ? ns : "auto");

        Deployment operator;

        if (ns != null) {
            operator = k8sService.getResource(Deployment.class, ns, operatorName);
        } else {
            operator = findOperatorInAllNamespaces(operatorName);
        }

        if (operator == null) {
            throw new ToolCallException("Strimzi operator '" + operatorName + "' not found");
        }

        return createOperatorResponse(operator);
    }

    /**
     * Get logs for Strimzi operator pods.
     * Returns a StrimziOperatorLogsResponse (including notFound) rather than throwing,
     * since missing operator pods is a valid business response.
     *
     * @param namespace    the namespace, or null for auto-discovery
     * @param operatorName the operator name, or null for any operator
     * @return the operator logs response
     */
    public StrimziOperatorLogsResponse getOperatorLogs(final String namespace, final String operatorName) {
        String ns = InputUtils.normalizeInput(namespace);

        LOG.infof("Getting operator logs (namespace=%s, name=%s)", ns, operatorName);

        if (ns == null) {
            ns = discoverOperatorNamespace(operatorName);
            if (ns == null) {
                return StrimziOperatorLogsResponse.notFound(Constants.UNKNOWN);
            }
        }

        List<Pod> pods = k8sService.queryResourcesByLabel(
            Pod.class, ns, ResourceLabels.STRIMZI_KIND_LABEL, Constants.Strimzi.KindValues.CLUSTER_OPERATOR);

        if (pods.isEmpty()) {
            return StrimziOperatorLogsResponse.notFound(ns);
        }

        List<String> podNames = pods.stream()
            .map(pod -> pod.getMetadata().getName())
            .toList();

        StringBuilder allLogs = new StringBuilder();
        int errorCount = 0;
        int totalLines = 0;

        for (Pod pod : pods) {
            String podName = pod.getMetadata().getName();
            try {
                String podLog = kubernetesClient.pods()
                    .inNamespace(ns)
                    .withName(podName)
                    .tailingLines(DEFAULT_LOG_TAIL_LINES)
                    .getLog();

                if (podLog != null && !podLog.isEmpty()) {
                    allLogs.append("=== Pod: ").append(podName).append(" ===\n");
                    allLogs.append(podLog).append("\n");

                    String[] lines = podLog.split("\n");
                    totalLines += lines.length;
                    for (String line : lines) {
                        String upperLine = line.toUpperCase(Locale.ROOT);
                        if (upperLine.contains("ERROR") || upperLine.contains("EXCEPTION")) {
                            errorCount++;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.debugf("Could not retrieve logs from pod %s: %s", podName, e.getMessage());
                allLogs.append("=== Pod: ").append(podName).append(" === (logs unavailable)\n");
            }
        }

        return StrimziOperatorLogsResponse.of(ns, allLogs.toString(), podNames, errorCount > 0, errorCount, totalLines);
    }

    private Deployment findOperatorInAllNamespaces(final String operatorName) {
        List<Deployment> allOperators = k8sService.queryResourcesByLabelInAnyNamespace(
            Deployment.class, Constants.Kubernetes.Labels.APP, Constants.Strimzi.Operator.APP_LABEL_VALUE);

        for (Deployment operator : allOperators) {
            if (operatorName.equals(operator.getMetadata().getName())) {
                LOG.debugf("Discovered operator %s in namespace %s",
                    operatorName, operator.getMetadata().getNamespace());
                return operator;
            }
        }

        return null;
    }

    private String discoverOperatorNamespace(final String operatorName) {
        List<Deployment> allOperators = k8sService.queryResourcesByLabelInAnyNamespace(
            Deployment.class, Constants.Kubernetes.Labels.APP, Constants.Strimzi.Operator.APP_LABEL_VALUE);

        List<String> matchingNamespaces = allOperators.stream()
            .filter(op -> operatorName == null || operatorName.equals(op.getMetadata().getName()))
            .map(op -> op.getMetadata().getNamespace())
            .distinct()
            .toList();

        if (matchingNamespaces.isEmpty()) {
            return null;
        }

        if (matchingNamespaces.size() > 1) {
            throw new ToolCallException("Multiple Strimzi operators found in namespaces: "
                + String.join(", ", matchingNamespaces) + ". Please specify namespace.");
        }

        return matchingNamespaces.getFirst();
    }

    private StrimziOperatorResponse createOperatorResponse(final Deployment deployment) {
        String name = deployment.getMetadata().getName();
        String namespace = deployment.getMetadata().getNamespace();

        Integer replicas = null;
        if (deployment.getSpec() != null) {
            replicas = deployment.getSpec().getReplicas();
        }

        Integer readyReplicas = null;
        if (deployment.getStatus() != null) {
            readyReplicas = deployment.getStatus().getReadyReplicas();
        }

        boolean ready = replicas != null && replicas.equals(readyReplicas) && readyReplicas > 0;
        String status = ready
            ? Constants.Kubernetes.HealthStatus.HEALTHY
            : Constants.Kubernetes.HealthStatus.DEGRADED;
        String version = deploymentService.extractVersion(deployment);
        String image = deploymentService.extractImage(deployment);
        Long uptimeMinutes = deploymentService.calculateUptimeMinutes(deployment);

        String uptimeHours = null;
        if (uptimeMinutes != null) {
            uptimeHours = String.format("%.1f", uptimeMinutes / MINUTES_PER_HOUR);
        }

        return StrimziOperatorResponse.of(name, namespace, ready, replicas, readyReplicas,
            version, image, uptimeHours, status);
    }
}
