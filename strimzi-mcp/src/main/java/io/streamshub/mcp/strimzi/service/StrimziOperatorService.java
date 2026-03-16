/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.PodLogsResult;
import io.streamshub.mcp.common.service.DeploymentService;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.PodsService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorLogsResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorResponse;
import io.strimzi.api.ResourceLabels;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Service for Strimzi operator operations.
 */
@ApplicationScoped
public class StrimziOperatorService {

    private static final Logger LOG = Logger.getLogger(StrimziOperatorService.class);
    private static final double MINUTES_PER_HOUR = 60.0;
    private static final int SECONDS_PER_MINUTE = 60;

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    PodsService podsService;

    @Inject
    DeploymentService deploymentService;

    @ConfigProperty(name = "mcp.log.tail-lines", defaultValue = "200")
    int defaultTailLines;

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
                Deployment.class, ns, KubernetesConstants.Labels.APP, StrimziConstants.Operator.APP_LABEL_VALUE);
        } else {
            operators = k8sService.queryResourcesByLabelInAnyNamespace(
                Deployment.class, KubernetesConstants.Labels.APP, StrimziConstants.Operator.APP_LABEL_VALUE);
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
     * Get logs for Strimzi operator pods with optional filtering and log parameters.
     * Returns a StrimziOperatorLogsResponse (including notFound) rather than throwing,
     * since missing operator pods is a valid business response.
     *
     * @param namespace    the namespace, or null for auto-discovery
     * @param operatorName the operator name, or null for any operator
     * @param filter       optional log filter: "errors", "warnings", or a regex pattern
     * @param sinceMinutes optional time range in minutes to retrieve logs from
     * @param tailLines    optional number of lines to tail per pod (defaults to configured value)
     * @param previous     optional flag to retrieve logs from previous container instance
     * @return the operator logs response
     */
    public StrimziOperatorLogsResponse getOperatorLogs(final String namespace, final String operatorName,
                                                        final String filter, final Integer sinceMinutes,
                                                        final Integer tailLines, final Boolean previous) {
        String ns = InputUtils.normalizeInput(namespace);

        LOG.infof("Getting operator logs (namespace=%s, name=%s, filter=%s, sinceMinutes=%s, tailLines=%s, previous=%s)",
            ns, operatorName, filter != null ? filter : "none", sinceMinutes, tailLines, previous);

        if (ns == null) {
            ns = discoverOperatorNamespace(operatorName);
            if (ns == null) {
                return StrimziOperatorLogsResponse.notFound(KubernetesConstants.UNKNOWN);
            }
        }

        List<Pod> pods = k8sService.queryResourcesByLabel(
            Pod.class, ns, ResourceLabels.STRIMZI_KIND_LABEL, StrimziConstants.KindValues.CLUSTER_OPERATOR);

        if (pods.isEmpty()) {
            return StrimziOperatorLogsResponse.notFound(ns);
        }

        String normalizedFilter = InputUtils.normalizeInput(filter);
        Integer sinceSeconds = sinceMinutes != null ? sinceMinutes * SECONDS_PER_MINUTE : null;
        Integer effectiveTailLines = tailLines != null ? tailLines : defaultTailLines;

        PodLogsResult result = podsService.collectLogs(ns, pods, normalizedFilter,
            sinceSeconds, effectiveTailLines, previous);
        return StrimziOperatorLogsResponse.of(ns, result.logs(), result.podNames(),
            result.hasErrors(), result.errorCount(), result.totalLines(), result.hasMore());
    }

    private Deployment findOperatorInAllNamespaces(final String operatorName) {
        List<Deployment> allOperators = k8sService.queryResourcesByLabelInAnyNamespace(
            Deployment.class, KubernetesConstants.Labels.APP, StrimziConstants.Operator.APP_LABEL_VALUE);

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
            Deployment.class, KubernetesConstants.Labels.APP, StrimziConstants.Operator.APP_LABEL_VALUE);

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
            ? KubernetesConstants.HealthStatus.HEALTHY
            : KubernetesConstants.HealthStatus.DEGRADED;
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
